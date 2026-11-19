package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.HttpUtil
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MAIL
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AUTHC
import com.dynamicruntime.common.user.EGATE
import com.dynamicruntime.common.user.EnvGate
import com.dynamicruntime.common.user.GOOG
import com.dynamicruntime.common.user.GoogleCodeExchange
import com.dynamicruntime.common.user.JwtKeySource
import com.dynamicruntime.common.user.LSRC
import com.dynamicruntime.common.user.PTD
import com.dynamicruntime.common.user.UserAuthCookie
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/**
 * The env gate (issue #1865): a node behind a Google sign-in. A visit to the web app without env access goes straight
 * to Google and back to the page asked for; that sign-in grants env access and signs the person in; any other request
 * without it is refused with the 401 the web app follows; and the gate is simulated for development on an `admin`
 * host under `.localhost`.
 *
 * Google is never called: the instance verifies tokens against this test's own signing key ([GOOG.googleKeySource]),
 * and its code exchange ([EGATE.codeExchange]) hands back the token a test signed for the code, so everything after the
 * trip to Google runs for real.
 */
class EnvGateTest : StringSpec({
    val domain = "gate1865.test"
    val clientId = "kdr-gate-test.apps.googleusercontent.com"
    val testKid = "gate-key-1"
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val keySource = object : JwtKeySource {
        override fun rsaKey(cxt: KdrCxt, kid: String): RSAPublicKey? = if (kid == testKid) keyPair.public as RSAPublicKey else null
    }

    /** The ID token Google would return for each code, as a test arranged it. */
    val tokensByCode = mutableMapOf<String, String>()
    val exchange = object : GoogleCodeExchange {
        override fun idTokenFor(cxt: KdrCxt, code: String, redirectUri: String): String =
            tokensByCode[code] ?: throw KdrException("Google does not know code '$code'.", code = EXC.authNeeded)
    }

    /** A gated node's settings: Google configured against this test's key and exchange, and header trust off. */
    fun gateSettings(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = buildMap {
        put(EGATE.envGate, "googleChallenge")
        put(ACFG.adminEmailDomain, domain)
        put(GOOG.googleClientId, clientId)
        put(GOOG.googleKeySource, keySource)
        put(EGATE.codeExchange, exchange)
        put(ACFG.trustEnvAuthHeader, false)
        putAll(extra)
    }

    /** The gated instance the file's tests share, each signing in its own address. */
    fun gated(cxtName: String): KdrCxt = Startup.mkTestBootCxt(cxtName, "envGateShared", gateSettings())

    /** A signed ID token, as Google's token endpoint would return it. */
    fun mkIdToken(
        sub: String, email: String, nonce: String?, hd: String? = domain, emailVerified: Boolean = true,
    ): String {
        val header = mapOf(GOOG.alg to GOOG.rs256, GOOG.kid to testKid)
        val claims = buildMap<String, Any?> {
            put(GOOG.sub, sub)
            put(GOOG.aud, clientId)
            put(GOOG.iss, "https://accounts.google.com")
            put(GOOG.exp, (System.currentTimeMillis() / 1000) + 3600)
            put(GOOG.email, email)
            put(GOOG.emailVerified, emailVerified)
            if (hd != null) put(GOOG.hd, hd)
            if (nonce != null) put(GOOG.nonce, nonce)
        }
        val h = header.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val c = claims.toJsonStr(compact = true).toByteArray(Charsets.UTF_8).base64Encode()
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update("$h.$c".toByteArray(Charsets.UTF_8))
        return "$h.$c.${signer.sign().base64Encode()}"
    }

    fun location(handler: RequestHandler): String = handler.rptResponseHeaders["location"]?.firstOrNull().shouldNotBeNull()

    /** The query of a redirect to Google's authorization endpoint. */
    fun googleQuery(handler: RequestHandler): Map<String, Any?> {
        handler.rptStatusCode shouldBe 303
        val to = location(handler)
        to shouldStartWith EGATE.authorizeUri
        return HttpUtil.parseQuery(URI(to).rawQuery)
    }

    /** Google's return to the callback with [code] for the sign-in [query] started. */
    fun callback(client: TestHttpClient, query: Map<String, Any?>, code: String): RequestHandler =
        client.sendGetRequest(EGATE.callbackPath, mapOf(EGATE.codeParam to code, EGATE.stateParam to query[EGATE.stateParam]))

    /** Signs [email] in through the gate on [client], returning where the callback sent the browser. */
    fun signIn(client: TestHttpClient, email: String, page: String = "/wa"): String {
        val query = googleQuery(client.sendGetRequestRaw(page))
        val code = "code-$email"
        tokensByCode[code] = mkIdToken("sub-$email", email, query[EGATE.nonceParam] as String)
        val back = callback(client, query, code)
        back.rptStatusCode shouldBe 303
        return location(back)
    }

    fun envAuthOf(client: TestHttpClient): String? = client.sendGetRequest(AEP.selfInfo).createdCxt?.envAuthEmail

    "a visit to the web app goes straight to Google, and the sign-in returns to the page asked for, with env access" {
        val cxt = gated("gateRoundTrip")
        val client = TestHttpClient(cxt.instanceConfig)
        val query = googleQuery(client.sendGetRequestRaw("/wa/reports", mapOf("tab" to "2")))
        query[EGATE.clientIdParam] shouldBe clientId
        query[EGATE.hdParam] shouldBe domain
        query[EGATE.responseTypeParam] shouldBe EGATE.codeResponse
        (query[EGATE.redirectUriParam] as String) shouldContain "/kda${EGATE.callbackPath}"
        tokensByCode["c-ada"] = mkIdToken("sub-ada", "ada@$domain", query[EGATE.nonceParam] as String)
        val back = callback(client, query, "c-ada")
        back.rptStatusCode shouldBe 303
        location(back) shouldBe "/wa/reports?tab=2"
        // Signed in, as a user of the deployment's own, with env access the gate's.
        client.cookies[AUTHC.authCookie].shouldNotBeNull()
        envAuthOf(client) shouldBe "ada@$domain"
        val user = UserService.get(cxt).queryLinkedIdentity(cxt, LSRC.google, "sub-ada").shouldNotBeNull()
        user.primaryId shouldBe "ada@$domain"
        // The web app is now served rather than sent to Google.
        client.sendGetRequestRaw("/wa/reports").rptResponseHeaders["location"].shouldBeNull()
    }

    "a request other than a web app visit is refused with the 401 the web app follows, and health still answers" {
        val cxt = gated("gateRefuses")
        val client = TestHttpClient(cxt.instanceConfig)
        val refused = client.sendGetRequest(AEP.selfInfo)
        refused.rptStatusCode shouldBe EXC.authNeeded
        val envelope = refused.rptResponseData?.jsonMap().shouldNotBeNull()
        envelope[EP.errorCode] shouldBe EP.envAuthRequiredCode
        envelope[EP.extraData].toJsonMapOrEmpty()[EP.envAuthLoginUrl] shouldBe "/kda${EGATE.startPath}"
        client.sendGetRequest("/health").rptStatusCode shouldBe EXC.ok
    }

    "the start path returns to the page the browser names, fragment included, and never to another site" {
        val cxt = gated("gateStart")
        val client = TestHttpClient(cxt.instanceConfig)
        val query = googleQuery(client.sendGetRequest(EGATE.startPath, mapOf(EP.envAuthNextParam to "/wa/forms?g=1#top")))
        tokensByCode["c-bo"] = mkIdToken("sub-bo", "bo@$domain", query[EGATE.nonceParam] as String)
        location(callback(client, query, "c-bo")) shouldBe "/wa/forms?g=1#top"
        // Somewhere else, or off the web app, is replaced by the web app's root.
        EnvGate.returnPath(cxt.instanceConfig, "https://evil.example/") shouldBe "/wa"
        EnvGate.returnPath(cxt.instanceConfig, "//evil.example/wa") shouldBe "/wa"
        EnvGate.returnPath(cxt.instanceConfig, "/kda/admin/user/list") shouldBe "/wa"
        EnvGate.returnPath(cxt.instanceConfig, "/wasteland") shouldBe "/wa"
    }

    "a bad state, a wrong nonce, and an account that is not the deployment's own are each refused before any user is made" {
        val cxt = gated("gateRefusals")
        val users = UserService.get(cxt)

        // A state that is not the one this browser was sent with.
        val stranger = TestHttpClient(cxt.instanceConfig)
        val query = googleQuery(stranger.sendGetRequestRaw("/wa"))
        tokensByCode["c-state"] = mkIdToken("sub-state", "state@$domain", query[EGATE.nonceParam] as String)
        stranger.sendGetRequest(EGATE.callbackPath, mapOf(EGATE.codeParam to "c-state", EGATE.stateParam to "forged"))
            .rptStatusCode shouldBe EXC.badInput
        // The state is spent by a return, good or not.
        callback(stranger, query, "c-state").rptStatusCode shouldBe EXC.badInput

        fun refusedWith(email: String, status: Int, token: (nonce: String) -> String) {
            val client = TestHttpClient(cxt.instanceConfig)
            val q = googleQuery(client.sendGetRequestRaw("/wa"))
            val code = "c-refuse-$email-${q[EGATE.nonceParam]}"
            tokensByCode[code] = token(q[EGATE.nonceParam] as String)
            val back = callback(client, q, code)
            back.rptStatusCode shouldBe status
            client.cookies[AUTHC.authCookie].shouldBeNull()
        }
        refusedWith("nonce@$domain", EXC.authNeeded) { mkIdToken("sub-nonce", "nonce@$domain", "not-the-nonce") }
        refusedWith("hd@$domain", EXC.notAuthorized) { mkIdToken("sub-hd", "hd@$domain", it, hd = "other.test") }
        refusedWith("nohd@$domain", EXC.notAuthorized) { mkIdToken("sub-nohd", "nohd@$domain", it, hd = null) }
        refusedWith("unverified@$domain", EXC.notAuthorized) { mkIdToken("sub-unv", "unverified@$domain", it, emailVerified = false) }
        refusedWith("outsider@other.test", EXC.notAuthorized) { mkIdToken("sub-out", "outsider@other.test", it, hd = "other.test") }
        for (address in listOf("state@$domain", "nonce@$domain", "hd@$domain", "nohd@$domain", "unverified@$domain", "outsider@other.test")) {
            users.queryIdentityByAddress(cxt, address).shouldBeNull()
        }
    }

    "an outside account is offered Google's chooser, to pick another" {
        val cxt = gated("gateChooser")
        val client = TestHttpClient(cxt.instanceConfig)
        val q = googleQuery(client.sendGetRequestRaw("/wa/home"))
        tokensByCode["c-chooser"] = mkIdToken("sub-chooser", "someone@other.test", q[EGATE.nonceParam] as String, hd = "other.test")
        val refused = callback(client, q, "c-chooser")
        refused.rptResponseData.shouldNotBeNull() shouldContain "Use another Google account"
        googleQuery(client.sendGetRequest(EGATE.startPath, mapOf(EP.envAuthNextParam to "/wa/home", EGATE.promptParam to EGATE.selectAccount)))[
            EGATE.promptParam,
        ] shouldBe EGATE.selectAccount
    }

    "only a session the gate's sign-in wrote has env access, and only until its stamp lapses" {
        val cxt = gated("gateStamp")
        val client = TestHttpClient(cxt.instanceConfig)
        signIn(client, "cy@$domain")
        envAuthOf(client) shouldBe "cy@$domain"
        val node = NodeService.get(cxt)
        val issued = UserAuthCookie.decode(node, client.cookies[AUTHC.authCookie]!!).shouldNotBeNull()
        issued.roles.contains(ROLE.allClients) shouldBe true
        fun withStamp(stamp: Long?): String = UserAuthCookie(
            issued.userId, issued.client, issued.roles, issued.expireEpochMs, issued.identityId, issued.persona,
            issued.signedInEpochMs, issued.issuedEpochMs, envGateUntilEpochMs = stamp,
        ).encode(node)

        // The same user's session written by any other sign-in -- a code sign-in to the same address -- has no stamp.
        val unstamped = TestHttpClient(cxt.instanceConfig).also { it.cookies[AUTHC.authCookie] = withStamp(null) }
        unstamped.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        // A lapsed stamp is no access either; the next visit goes back through Google.
        val lapsed = TestHttpClient(cxt.instanceConfig).also { it.cookies[AUTHC.authCookie] = withStamp(System.currentTimeMillis() - 1000) }
        lapsed.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        googleQuery(lapsed.sendGetRequestRaw("/wa"))
    }

    "a simulated sign-in on an admin host under localhost lands as the simulated user, with no trip to Google" {
        val cxt = Startup.mkTestBootCxt(
            "gateSimulated", "envGateSimulated",
            mapOf(
                ACFG.adminEmailDomain to domain, EGATE.simulate to true,
                EGATE.simulatedAddress to "dev@$domain", ACFG.trustEnvAuthHeader to false,
            ),
        )
        // The gate is off on this node: only the admin host is gated.
        val plain = TestHttpClient(cxt.instanceConfig).also { it.setHeader("Host", "kd9.localhost:7099") }
        plain.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.ok

        val admin = TestHttpClient(cxt.instanceConfig).also { it.setHeader("Host", "admin.kd9.localhost:7099") }
        admin.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        val visit = admin.sendGetRequestRaw("/wa/clients", mapOf("c" to "acme"))
        visit.rptStatusCode shouldBe 303
        location(visit) shouldBe "/wa/clients?c=acme"
        envAuthOf(admin) shouldBe "dev@$domain"

        // Through a proxy, the host is no trigger.
        val proxied = TestHttpClient(cxt.instanceConfig).also {
            it.setHeader("Host", "admin.kd9.localhost:7099")
            it.setHeader("X-Forwarded-For", "203.0.113.9")
        }
        proxied.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.ok
        // Nor is a host outside localhost.
        val outside = TestHttpClient(cxt.instanceConfig).also { it.setHeader("Host", "admin.example.com") }
        outside.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.ok
    }

    "the simulated address defaults to the machine's login name at the admin domain" {
        val cxt = Startup.mkTestBootCxt("gateSimDefault", "envGateSimDefault", mapOf(ACFG.adminEmailDomain to domain))
        val config = cxt.instanceConfig
        val expected = System.getProperty("user.name").lowercase().filter { it.isLetterOrDigit() || it in "._-" }
        EnvGate.simulatedAddress(config) shouldBe "$expected@$domain"
    }

    "the boot refuses a gate it cannot honor, and a simulation outside its fence" {
        fun refusal(name: String, settings: Map<String, Any?>): String =
            shouldThrow<KdrException> { Startup.mkTestBootCxt(name, "${name}Inst", settings) }.message.orEmpty()

        refusal("gateNoDomain", gateSettings(mapOf(ACFG.adminEmailDomain to ""))) shouldContain "no admin domain"
        refusal("gateNoClient", gateSettings(mapOf(GOOG.googleClientId to ""))) shouldContain "Google sign-in is not configured"
        refusal(
            "gateNoSecret",
            gateSettings(mapOf(EGATE.codeExchange to null, EGATE.clientSecretKey to "noSuchSecretForEnvGateTest")),
        ) shouldContain "client secret"
        refusal("gateTrusts", gateSettings(mapOf(ACFG.trustEnvAuthHeader to true))) shouldContain "must be off"
        refusal("gateBadMode", gateSettings(mapOf(EGATE.envGate to "always"))) shouldContain "takes one of"

        // On a public test deployment (a test instance the public can reach, in `dev`), which passes its own checks.
        fun publicRefusal(name: String, settings: Map<String, Any?>): String = shouldThrow<KdrException> {
            Startup.mkBootCxt(
                name, "${name}Inst",
                buildMap {
                    put(ACFG.env, ENV.dev)
                    put(ACFG.inMemoryOnly, true)
                    put(MAIL.mailgunApiKeySecretKey, "noSuchSecretForEnvGateTest")
                    put(PTD.publicTestDeploymentEnvVar.name, "true")
                    put(ACFG.trustEnvAuthHeader, false)
                    put(ACFG.adminEmailDomain, domain)
                    putAll(settings)
                },
            )
        }.message.orEmpty()
        publicRefusal("gateAdminLogin", gateSettings(mapOf(PTD.envAuthFromAdminLoginEnvVar.name to "true"))) shouldContain "must be off"
        // A simulation is never for a deployment the public can reach.
        publicRefusal("gateSimPublic", mapOf(EGATE.simulate to true)) shouldContain "simulated Google sign-in"
    }
})
