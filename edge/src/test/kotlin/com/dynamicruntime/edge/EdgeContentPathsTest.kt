package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.ADMR
import com.dynamicruntime.common.user.GOOG
import com.dynamicruntime.common.user.JwtKeySource
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * What the edge's two content paths do, signed in and signed out (issue #386).
 *
 * A regression test for a **loop**: the bare content root always redirected to the sign-in page and the page
 * always rendered, so signing in without a `next` went to `/`, which redirects to the content root, which sent
 * you back to sign in. Either half alone leaves it -- the root still had nothing to offer a signed-in caller,
 * and the page still re-asked somebody who had just answered.
 *
 * It was found by a person signing in from the bare root rather than from a deep link, which is a path the
 * unit tests did not walk. Hence this one.
 */
class EdgeContentPathsTest : StringSpec({

    val clientId = "kdr-edge-paths.apps.googleusercontent.com"
    val testKid = "edge-paths-key"
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val keySource = object : JwtKeySource {
        override fun rsaKey(cxt: com.dynamicruntime.common.context.KdrCxt, kid: String): RSAPublicKey? =
            if (kid == testKid) keyPair.public as RSAPublicKey else null
    }


    fun bootEdge(name: String) = Startup.mkTestBootCxt(
        name, name,
        mapOf(
            ACFG.bootRole to BOOT.edge,
            GOOG.googleClientId to clientId,
            GOOG.googleKeySource to keySource,
            ADMR.adminEmailDomainEnvVar.name to "gyassa.com",
        ),
        additionalComponents = listOf(EdgeComponent()),
    )

    fun credential(email: String): String {
        val header = mapOf(GOOG.alg to GOOG.rs256, GOOG.kid to testKid)
        val claims = mapOf(
            GOOG.sub to "sub-$email", GOOG.aud to clientId, GOOG.iss to "https://accounts.google.com",
            GOOG.exp to (System.currentTimeMillis() / 1000) + 3600,
            // A real Workspace token names its hosted domain; the gate now requires it to match (issue #429).
            GOOG.email to email, GOOG.emailVerified to true, GOOG.hd to "gyassa.com",
        )
        val h = header.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val c = claims.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update("$h.$c".toByteArray(Charsets.UTF_8))
        return "$h.$c.${signer.sign().base64Encode()}"
    }

    /**
     * Signed out, the chain must **end** at the sign-in page rather than coming back around. An edge does not
     * assume env auth (there is no edge in front of it), so a fresh client here is genuinely anonymous.
     */
    "signed out: the content root sends you to sign in, and the page renders" {
        val cxt = bootEdge("edgePathsOut")
        val client = TestHttpClient(cxt.instanceConfig)

        val root = client.sendGetRequestRaw("/" + EdgeRoot.ec)
        root.rptStatusCode shouldBe 303
        root.rptResponseHeaders["location"]?.first() shouldContain EDGEP.loginPage

        val page = client.sendGetRequestRaw("/" + EdgeRoot.ec + EDGEP.loginPage)
        page.rptStatusCode shouldBe EXC.ok
        page.rptResponseData shouldContain "Sign in to continue"
    }

    /**
     * Signed in, both paths must stop asking. The root offers a landing rather than a redirect, and the page
     * sends the caller onward -- which together is what breaks the loop.
     */
    "signed in: the root shows who you are, and the sign-in page steps aside" {
        val cxt = bootEdge("edgePathsIn")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendJsonPostRequest(EAEP.login, mapOf(EAEP.googleCredential to credential("sam@gyassa.com")))

        val root = client.sendGetRequestRaw("/" + EdgeRoot.ec)
        root.rptStatusCode shouldBe EXC.ok
        root.rptResponseData shouldContain "sam@gyassa.com"

        val page = client.sendGetRequestRaw("/" + EdgeRoot.ec + EDGEP.loginPage)
        page.rptStatusCode shouldBe 303
    }

    /** And an honored `next` survives the page stepping aside, rather than being dropped for the default. */
    "signed in: the sign-in page still returns you where you were headed" {
        val cxt = bootEdge("edgePathsNext")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendJsonPostRequest(EAEP.login, mapOf(EAEP.googleCredential to credential("dana@gyassa.com")))

        val page = client.sendGetRequestRaw(
            "/" + EdgeRoot.ec + EDGEP.loginPage, mapOf(EnvAuthReturn.param to "/ea/schema/endpoints"),
        )
        page.rptStatusCode shouldBe 303
        page.rptResponseHeaders["location"]?.first() shouldBe "/ea/schema/endpoints"
    }
})
