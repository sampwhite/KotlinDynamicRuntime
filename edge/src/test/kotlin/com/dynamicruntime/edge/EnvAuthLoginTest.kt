package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.user.ADMR
import com.dynamicruntime.common.user.GOOG
import com.dynamicruntime.common.user.JwtKeySource
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * Drives the Env Auth login end to end (issue #386): a Google credential in, a session out, and the refusals
 * that keep everyone else out.
 *
 * Signs its own tokens against a test [JwtKeySource], so the **real** verification path runs -- RS256, the
 * `aud` check, expiry -- with no network. Modelled on `GoogleLoginTest`, which does the same for the
 * application's Google login.
 */
class EnvAuthLoginTest : StringSpec({

    val clientId = "kdr-edge-test.apps.googleusercontent.com"
    val testKid = "edge-key-1"
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val keySource = object : JwtKeySource {
        override fun rsaKey(cxt: com.dynamicruntime.common.context.KdrCxt, kid: String): RSAPublicKey? =
            if (kid == testKid) keyPair.public as RSAPublicKey else null
    }

    InstanceRegistry.register(listOf(EdgeComponent()))

    /** An edge instance with Google sign-in configured against this test's signing key. */
    fun bootEdge(name: String) = Startup.mkTestBootCxt(
        name, name,
        mapOf(
            ACFG.bootRole to BOOT.edge,
            GOOG.googleClientId to clientId,
            GOOG.googleKeySource to keySource,
            ADMR.adminEmailDomainEnvVar to "gyassa.com",
        ),
    )

    fun credential(email: String?, emailVerified: Boolean = true, aud: String = clientId): String {
        val header = mapOf(GOOG.alg to GOOG.rs256, GOOG.kid to testKid)
        val claims = buildMap<String, Any?> {
            put(GOOG.sub, "sub-${email ?: "none"}")
            put(GOOG.aud, aud)
            put(GOOG.iss, "https://accounts.google.com")
            put(GOOG.exp, (System.currentTimeMillis() / 1000) + 3600)
            if (email != null) put(GOOG.email, email)
            put(GOOG.emailVerified, emailVerified)
        }
        val h = header.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val c = claims.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update("$h.$c".toByteArray(Charsets.UTF_8))
        return "$h.$c.${signer.sign().base64Encode()}"
    }

    "a permitted address signs in, and the session carries who they are" {
        val cxt = bootEdge("envLoginOk")
        val client = TestHttpClient(cxt.instanceConfig)
        val resp = client.sendJsonPostRequest(
            EAEP.login, mapOf(EAEP.googleCredential to credential("sam@gyassa.com")),
        )
        resp.getValue(EP.results)!!.toJsonMap()[EAEP.email] shouldBe "sam@gyassa.com"
        // The cookie is what carries the session onward; the client's jar captured it.
        client.cookies[ENVAUTH.cookie] shouldNotBe null
    }

    /**
     * The whole point of the cookie: the *next* request is signed in without re-presenting anything, with the
     * identity restored by the edge's own extractor rather than the application's session path.
     */
    "the session is restored on the next request, as an operator acting for the house" {
        val cxt = bootEdge("envLoginRestore")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendJsonPostRequest(EAEP.login, mapOf(EAEP.googleCredential to credential("dana@gyassa.com")))

        val handler = client.sendGetRequest("/health")
        val profile = handler.createdCxt?.userProfile
        profile?.authId shouldBe "dana@gyassa.com"
        profile?.client shouldBe CL.house
        profile?.roles?.contains(ROLE.operator) shouldBe true
        // No row stands behind them, which is what stops refreshActingRoles going after CL.systemUserId.
        profile?.isRowBacked shouldBe false
        // And the address reaches the log line the same way it would on a backend told by header.
        handler.createdCxt?.envAuthEmail shouldBe "dana@gyassa.com"
    }

    "an address outside the permitted domain is refused" {
        val cxt = bootEdge("envLoginWrongDomain")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendEditRequest(
            EAEP.login, null, mapOf(EAEP.googleCredential to credential("someone@example.org")), isPut = false,
        ).rptStatusCode shouldBe EXC.notAuthorized
    }

    // Google not standing behind the address means it cannot open the gate, however well-formed the token.
    "an unverified address is refused" {
        val cxt = bootEdge("envLoginUnverified")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendEditRequest(
            EAEP.login, null,
            mapOf(EAEP.googleCredential to credential("sam@gyassa.com", emailVerified = false)),
            isPut = false,
        ).rptStatusCode shouldBe EXC.notAuthorized
    }

    /**
     * A token minted for a different application must not open this one -- the `aud` check is what stops
     * somebody replaying a credential issued elsewhere, and it is why the client id has no default.
     */
    "a token for another audience is refused" {
        val cxt = bootEdge("envLoginWrongAud")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendEditRequest(
            EAEP.login, null,
            mapOf(EAEP.googleCredential to credential("sam@gyassa.com", aud = "someone-else.apps.googleusercontent.com")),
            isPut = false,
        ).rptStatusCode shouldNotBe EXC.ok
    }

    "a forged cookie is not a session" {
        val cxt = bootEdge("envLoginForged")
        val client = TestHttpClient(cxt.instanceConfig)
        client.cookies[ENVAUTH.cookie] = "not-encrypted-by-this-node"
        client.sendGetRequest("/health").createdCxt?.userProfile?.isLoggedIn shouldBe false
    }
})
