package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.GOOG
import com.dynamicruntime.common.user.JwtKeySource
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptLong
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * End-to-end coverage for Google sign-in (issue #157), driven through the real request pipeline: the endpoint,
 * the `LinkedUsers` link, user provisioning, and the session cookie.
 *
 * The instance is booted with a test [JwtKeySource] holding a key this test signs its own tokens with, so the
 * genuine verification path runs with no network. The behaviors that matter here are the *linking* rules --
 * that a returning identity is recognized by Google's `sub` and not by its email, and that an unverified email
 * cannot reach an existing account.
 */
class GoogleLoginTest : StringSpec({

    val clientId = "kdr-test.apps.googleusercontent.com"
    val testKid = "test-key-1"
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val keySource = object : JwtKeySource {
        override fun rsaKey(cxt: KdrCxt, kid: String): RSAPublicKey? =
            if (kid == testKid) keyPair.public as RSAPublicKey else null
    }

    /** Boots an instance with Google sign-in configured against this test's signing key. */
    fun bootGoogle(cxtName: String, instanceName: String) = Startup.mkTestBootCxt(
        cxtName, instanceName,
        mapOf(GOOG.googleClientId to clientId, GOOG.googleKeySource to keySource),
    )

    /** A signed ID token for [sub]/[email], as Google's sign-in would hand the browser. */
    fun mkCredential(
        sub: String,
        email: String?,
        emailVerified: Boolean = true,
        aud: String = clientId,
    ): String {
        val header = mapOf(GOOG.alg to GOOG.rs256, GOOG.kid to testKid)
        val claims = buildMap<String, Any?> {
            put(GOOG.sub, sub)
            put(GOOG.aud, aud)
            put(GOOG.iss, "https://accounts.google.com")
            put(GOOG.exp, (System.currentTimeMillis() / 1000) + 3600)
            if (email != null) put(GOOG.email, email)
            put(GOOG.emailVerified, emailVerified)
            put(GOOG.name, "Test Person")
        }
        val h = header.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val c = claims.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update("$h.$c".toByteArray(Charsets.UTF_8))
        return "$h.$c.${signer.sign().base64Encode()}"
    }

    fun login(client: TestHttpClient, credential: String): Map<String, Any?> =
        client.sendJsonPostRequest(AEP.loginByGoogle, mapOf(AFLD.googleCredential to credential))
            .getValue("results")!!.toJsonMap()

    "a first Google sign-in provisions a user and logs them in" {
        val cxt = bootGoogle("googNew", "googNewTest")
        val client = TestHttpClient(cxt.instanceConfig)
        val info = login(client, mkCredential("sub-alice", "alice@example.com"))
        info[UPF.userId].toOptLong()!! shouldNotBe 0L
        // The session cookie stuck: a follow-up call on the same client is made as that user.
        client.sendJsonGetRequest(AEP.selfInfo).getValue("results")!!.toJsonMap()[UPF.userId].toOptLong() shouldBe
            info[UPF.userId].toOptLong()
    }

    "signing in again with the same Google identity returns the same user" {
        val cxt = bootGoogle("googRepeat", "googRepeatTest")
        val first = login(TestHttpClient(cxt.instanceConfig), mkCredential("sub-bob", "bob@example.com"))
        val second = login(TestHttpClient(cxt.instanceConfig), mkCredential("sub-bob", "bob@example.com"))
        second[UPF.userId].toOptLong() shouldBe first[UPF.userId].toOptLong()
    }

    // The reason the link is keyed on `sub`: Google can change the email on an account, and a Workspace domain
    // can reassign one outright. Neither may re-point an established link.
    "an established link follows the Google subject, not the email on the token" {
        val cxt = bootGoogle("googEmailChange", "googEmailChangeTest")
        val first = login(TestHttpClient(cxt.instanceConfig), mkCredential("sub-carol", "carol@example.com"))
        // Same Google account, new email address. It must still be the same local user.
        val renamed = login(TestHttpClient(cxt.instanceConfig), mkCredential("sub-carol", "carol.new@example.com"))
        renamed[UPF.userId].toOptLong() shouldBe first[UPF.userId].toOptLong()
    }

    "a different Google identity at the same email does not take over the linked account" {
        val cxt = bootGoogle("googSquat", "googSquatTest")
        val owner = login(TestHttpClient(cxt.instanceConfig), mkCredential("sub-dave", "dave@example.com"))
        // A *different* Google account presenting the same (Google-verified) address. The existing local user
        // was matched by email on its own first link, so this one links to it too -- but as the same user, not
        // by displacing the first link.
        val other = login(TestHttpClient(cxt.instanceConfig), mkCredential("sub-dave-2", "dave@example.com"))
        other[UPF.userId].toOptLong() shouldBe owner[UPF.userId].toOptLong()
        // And the original identity still resolves to that same user.
        login(TestHttpClient(cxt.instanceConfig), mkCredential("sub-dave", "dave@example.com"))[UPF.userId]
            .toOptLong() shouldBe owner[UPF.userId].toOptLong()
    }

    // The takeover vector: an unverified Google address must never match an existing local account.
    "an unverified Google email is refused" {
        val cxt = bootGoogle("googUnverified", "googUnverifiedTest")
        val client = TestHttpClient(cxt.instanceConfig)
        val handler = client.sendEditRequest(
            AEP.loginByGoogle, null,
            mapOf(AFLD.googleCredential to mkCredential("sub-evil", "victim@example.com", emailVerified = false)),
            isPut = false,
        )
        handler.rptStatusCode shouldBe 400 // mkMsg's default: bad input, with the auth fragment's copy
        // Nothing was created or linked: the address is still free to register normally.
        login(client, mkCredential("sub-victim", "victim@example.com")).let { real ->
            real[UPF.userId].toOptLong()!! shouldNotBe 0L
        }
    }

    "a token minted for another application is refused end to end" {
        val cxt = bootGoogle("googAud", "googAudTest")
        val client = TestHttpClient(cxt.instanceConfig)
        val handler = client.sendEditRequest(
            AEP.loginByGoogle, null,
            mapOf(AFLD.googleCredential to mkCredential("sub-x", "x@example.com", aud = "other-app.apps.googleusercontent.com")),
            isPut = false,
        )
        handler.rptStatusCode shouldNotBe 200
    }

    "the auto-admin domain rule reaches a Google-provisioned user" {
        val cxt = Startup.mkTestBootCxt(
            "googAdmin", "googAdminTest",
            mapOf(
                GOOG.googleClientId to clientId, GOOG.googleKeySource to keySource,
                "adminEmailDomain" to "acme.com",
            ),
        )
        val info = login(TestHttpClient(cxt.instanceConfig), mkCredential("sub-boss", "boss@acme.com"))
        @Suppress("UNCHECKED_CAST")
        val roles = (info[UPF.roles] as? List<*>)?.map { it.toString() } ?: emptyList()
        roles.contains(ROLE.admin) shouldBe true
    }

    "the auth UI config advertises Google sign-in and carries the client id when configured" {
        val cxt = bootGoogle("googUiOn", "googUiOnTest")
        val results = TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(AEP.authUiConfig)
            .getValue("results")!!.toJsonMap()
        results.getValue("features")!!.toJsonMap()["googleLogin"] shouldBe true
        results.getValue("state")!!.toJsonMap().getOptStr(AFLD.googleClientId) shouldBe clientId
    }

    "with no client id configured the feature is off and the client id is empty" {
        val cxt = Startup.mkTestBootCxt("googUiOff", "googUiOffTest")
        val results = TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(AEP.authUiConfig)
            .getValue("results")!!.toJsonMap()
        results.getValue("features")!!.toJsonMap()["googleLogin"] shouldBe false
        results.getValue("state")!!.toJsonMap().getOptStr(AFLD.googleClientId) shouldBe ""
    }
})
