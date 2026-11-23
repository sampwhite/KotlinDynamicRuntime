package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.flavor.FlavorDef
import com.dynamicruntime.common.flavor.flavor
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.HttpUtil
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MAIL
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.AUTHC
import com.dynamicruntime.common.user.EGATE
import com.dynamicruntime.common.user.INVITE
import com.dynamicruntime.common.user.RL
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.user.EnvAuthRules
import com.dynamicruntime.common.user.EnvGate
import com.dynamicruntime.common.user.GOOG
import com.dynamicruntime.common.user.GoogleCodeExchange
import com.dynamicruntime.common.user.JwtKeySource
import com.dynamicruntime.common.user.LSRC
import com.dynamicruntime.common.user.PTD
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

    /**
     * A gated node's settings: Google configured against this test's key and exchange. Header trust is left unset,
     * which behind the gate is off even on a test instance.
     */
    fun gateSettings(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = buildMap {
        put(EGATE.envGate, "googleChallenge")
        put(ACFG.adminEmailDomain, domain)
        put(GOOG.googleClientId, clientId)
        put(GOOG.googleKeySource, keySource)
        put(EGATE.codeExchange, exchange)
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
        // A test instance does not trust the env-auth header behind the gate: the header is no way past it.
        EnvAuthRules.isTrusted(cxt.instanceConfig) shouldBe false
        client.setHeader(ENVA.header, "header@$domain")
        client.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
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
        // The portal's pages are visits too.
        EnvGate.returnPath(cxt.instanceConfig, "/cp/portal?x=1") shouldBe "/cp/portal?x=1"
    }

    "sign-ins started together in one browser each return to their own page" {
        val cxt = gated("gateTabs")
        val client = TestHttpClient(cxt.instanceConfig)
        // Two tabs whose env access lapsed together each start a sign-in before either comes back.
        val first = googleQuery(client.sendGetRequestRaw("/wa/one"))
        val second = googleQuery(client.sendGetRequestRaw("/wa/two"))
        tokensByCode["c-tab1"] = mkIdToken("sub-tabs", "tabs@$domain", first[EGATE.nonceParam] as String)
        tokensByCode["c-tab2"] = mkIdToken("sub-tabs", "tabs@$domain", second[EGATE.nonceParam] as String)
        location(callback(client, second, "c-tab2")) shouldBe "/wa/two"
        location(callback(client, first, "c-tab1")) shouldBe "/wa/one"
        envAuthOf(client) shouldBe "tabs@$domain"
    }

    "a portal page and a HEAD request go to Google as a web app visit does; a static resource is refused" {
        val cxt = gated("gatePages")
        val client = TestHttpClient(cxt.instanceConfig)
        val query = googleQuery(client.sendGetRequestRaw("/cp/portal"))
        tokensByCode["c-portal"] = mkIdToken("sub-portal", "portal@$domain", query[EGATE.nonceParam] as String)
        location(callback(client, query, "c-portal")) shouldBe "/cp/portal"
        val head = RequestHandler(cxt.instanceConfig, "HEAD", "/wa/forms", emptyMap(), mutableMapOf())
        head.handleRequest()
        googleQuery(head)
        TestHttpClient(cxt.instanceConfig).sendGetRequestRaw("/st/anything.js").rptStatusCode shouldBe EXC.authNeeded
    }

    "the callback is held to the Google sign-in's per-IP ceiling" {
        val cxt = Startup.mkTestBootCxt("gateLimited", "envGateLimited", gateSettings())
        val client = TestHttpClient(cxt.instanceConfig).also { it.peerAddress = "198.51.100.7" }
        val statuses = (1..RL.pwPerIpMax + 1).map {
            val query = googleQuery(client.sendGetRequestRaw("/wa"))
            callback(client, query, "no-such-code-$it").rptStatusCode
        }
        // Google refuses every made-up code; the last attempt is not sent to Google at all.
        statuses.dropLast(1).toSet() shouldBe setOf(EXC.authNeeded)
        statuses.last() shouldBe EXC.tooManyRequests
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
        // The state is spent by a return, good or not: one that failed at Google cannot be used again.
        callback(stranger, query, "c-no-such-code").rptStatusCode shouldBe EXC.authNeeded
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
        val refused = listOf("state@$domain", "nonce@$domain", "hd@$domain", "nohd@$domain", "unverified@$domain", "outsider@other.test")
        for (address in refused) {
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
        val another = client.sendGetRequest(
            EGATE.startPath, mapOf(EP.envAuthNextParam to "/wa/home", EGATE.promptParam to EGATE.selectAccount),
        )
        googleQuery(another)[EGATE.promptParam] shouldBe EGATE.selectAccount
    }

    "env access is the gate's own cookie, whoever the session acts as, and lasts only its lifetime" {
        val cxt = gated("gateAccess")
        val client = TestHttpClient(cxt.instanceConfig)
        signIn(client, "cy@$domain")
        envAuthOf(client) shouldBe "cy@$domain"
        val session = client.cookies[AUTHC.authCookie].shouldNotBeNull()
        val access = client.cookies[EGATE.accessCookie].shouldNotBeNull()

        // The session alone -- as a code sign-in to the same address would leave -- has no env access.
        val sessionOnly = TestHttpClient(cxt.instanceConfig).also { it.cookies[AUTHC.authCookie] = session }
        sessionOnly.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        // The env access alone gets through, with nobody signed in.
        val accessOnly = TestHttpClient(cxt.instanceConfig).also { it.cookies[EGATE.accessCookie] = access }
        val anonymous = accessOnly.sendGetRequest(AEP.selfInfo)
        anonymous.rptStatusCode shouldBe EXC.ok
        anonymous.createdCxt?.envAuthEmail shouldBe "cy@$domain"
        anonymous.createdCxt?.userProfile?.isLoggedIn shouldBe false
        // A lapsed one is no access; the next visit goes back through Google.
        val node = NodeService.get(cxt)
        val lapsed = TestHttpClient(cxt.instanceConfig).also {
            it.cookies[EGATE.accessCookie] = EnvGate.EnvGateAccess("cy@$domain", System.currentTimeMillis() - 1000).encode(node)
        }
        lapsed.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        googleQuery(lapsed.sendGetRequestRaw("/wa"))
    }

    "a person keeps env access as another user, and a sign-in keeps whoever is signed in" {
        val cxt = gated("gateApart")
        val ada = TestHttpClient(cxt.instanceConfig)
        signIn(ada, "ada.apart@$domain")
        val adaId = ada.sendGetRequest(AEP.selfInfo).createdCxt?.userProfile?.userId.shouldNotBeNull()
        val bo = TestHttpClient(cxt.instanceConfig)
        signIn(bo, "bo.apart@$domain")

        // Bo's browser comes to act as Ada (a switch, or a sign-in as her): Bo's env access stays, and nothing loops.
        bo.cookies[AUTHC.authCookie] = ada.cookies[AUTHC.authCookie]!!
        val asAda = bo.sendGetRequest(AEP.selfInfo)
        asAda.rptStatusCode shouldBe EXC.ok
        asAda.createdCxt?.userProfile?.userId shouldBe adaId
        asAda.createdCxt?.envAuthEmail shouldBe "bo.apart@$domain"
        bo.sendGetRequestRaw("/wa").rptResponseHeaders["location"].shouldBeNull()

        // Signed in as Ada with no env access, Bo signs in to Google: the session stays Ada's.
        val adaSession = TestHttpClient(cxt.instanceConfig).also { it.cookies[AUTHC.authCookie] = ada.cookies[AUTHC.authCookie]!! }
        signIn(adaSession, "bo.apart@$domain")
        val after = adaSession.sendGetRequest(AEP.selfInfo)
        after.createdCxt?.userProfile?.userId shouldBe adaId
        after.createdCxt?.envAuthEmail shouldBe "bo.apart@$domain"
    }

    // --- The allow list and the anonymous surface (issue #1866) ---

    "the allow list opens the endpoints it names to a caller without env access, and nothing beside them" {
        val cxt = Startup.mkTestBootCxt(
            "gateAllow", "envGateAllow",
            gateSettings(mapOf(EGATE.allowList to listOf("GET /auth/ui/config", "get  /gedra/acme/formDoc"))),
        )
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendGetRequest(AEP.authUiConfig).rptStatusCode shouldBe EXC.ok
        // Its neighbor in the same section is not on the list.
        client.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        // An entry is normalized: the method upper-cased, one space.
        EnvGate.allowList(cxt.instanceConfig) shouldBe setOf("GET /auth/ui/config", "GET /gedra/acme/formDoc")
    }

    "the anonymous surface is the gate's paths, health, and the allow list, and a test can hold a node to it" {
        val cxt = Startup.mkTestBootCxt(
            "gateSurface", "envGateSurface",
            gateSettings(mapOf(EGATE.allowList to "GET /auth/ui/config, POST /auth/newContact/sendVerify")),
        )
        val expected = listOf(
            "GET /kda/auth/envGate/callback", "GET /kda/auth/envGate/start", "POST /kda/auth/newContact/sendVerify",
            "GET /kda/auth/ui/config", "GET /kda/health",
        )
        val surface = EnvGate.anonymousSurface(cxt)
        surface.map { it.toString() } shouldBe expected
        surface.first { it.path == "/kda/health" }.source shouldBe EGATE.sourceHealth
        surface.first { it.path == "/kda/auth/ui/config" }.source shouldBe EGATE.sourceAllowList
        EnvGate.requireAnonymousSurface(cxt, expected)
        shouldThrow<IllegalStateException> { EnvGate.requireAnonymousSurface(cxt, expected - "GET /kda/auth/ui/config") }
            .message.orEmpty() shouldContain "unexpectedly: GET /kda/auth/ui/config"
    }

    "components add their own entries to a flavor's allow list, and the surface names who added each" {
        class SiteComponent(
            override val providerName: String,
            private val defines: Boolean,
            private val entry: String,
        ) : ComponentDefinition {
            override fun flavorDefs(cxt: KdrCxt) =
                if (defines) listOf(FlavorDef("gateSite", "A site beside a gated node.")) else emptyList()
            override fun flavors(cxt: KdrCxt) = listOf(flavor("gateSite") { add(EGATE.allowList, entry) })
        }
        val cxt = Startup.mkTestBootCxt(
            "gateAdded", "envGateAdded", gateSettings(mapOf(FLV.deployFlavorsEnvVar.name to "gateSite")),
            additionalComponents = listOf(
                SiteComponent("siteLeads", defines = true, entry = "POST /auth/newContact/sendVerify"),
                SiteComponent("siteVideos", defines = false, entry = "GET /auth/ui/config"),
            ),
        )
        EnvGate.allowList(cxt.instanceConfig) shouldBe setOf("POST /auth/newContact/sendVerify", "GET /auth/ui/config")
        val byPath = EnvGate.anonymousSurface(cxt).associateBy { it.path }
        byPath.getValue("/kda/auth/newContact/sendVerify").component shouldBe "siteLeads"
        byPath.getValue("/kda/auth/ui/config").component shouldBe "siteVideos"
        byPath.getValue("/kda/health").component.shouldBeNull()
        TestHttpClient(cxt.instanceConfig).sendGetRequest(AEP.authUiConfig).rptStatusCode shouldBe EXC.ok
    }

    "the operator view shows the anonymous surface, and no gate means no surface" {
        val cxt = TestInstances.default("gateSurfaceView")
        val admin = TestUser.createFullAdmin(cxt, "surface.admin@example.com")
        val view = admin.getData(EGATE.surfacePath)
        view[EGATE.gate] shouldBe "none"
        view[EGATE.entries] shouldBe emptyList<Any?>()
    }

    "the boot refuses an allow list with the gate off, one that does not parse, and one that names no endpoint" {
        fun refusal(name: String, settings: Map<String, Any?>): String =
            shouldThrow<KdrException> { Startup.mkTestBootCxt(name, "${name}Inst", settings) }.message.orEmpty()

        refusal("allowGateOff", mapOf(EGATE.allowList to listOf("GET /auth/ui/config"))) shouldContain "gate is off"
        refusal("allowShape", gateSettings(mapOf(EGATE.allowList to listOf("/auth/ui/config")))) shouldContain "is not '<METHOD> <path>'"
        refusal("allowUnknown", gateSettings(mapOf(EGATE.allowList to listOf("GET /auth/no/such")))) shouldContain "'GET /auth/no/such'"
        // A wrong method is no endpoint either, nor a client path over a section that has no copies.
        refusal("allowMethod", gateSettings(mapOf(EGATE.allowList to listOf("POST /auth/ui/config")))) shouldContain "names no endpoint"
        refusal("allowNotCopied", gateSettings(mapOf(EGATE.allowList to listOf("GET /auth/acme/ui/config")))) shouldContain
            "names no endpoint"
    }

    "a simulated sign-in on an admin host under localhost lands as the simulated user, with no trip to Google" {
        val cxt = Startup.mkTestBootCxt(
            "gateSimulated", "envGateSimulated",
            mapOf(
                ACFG.adminEmailDomain to domain, EGATE.simulate to true, EGATE.simulatedAddress to "dev@$domain",
            ),
        )
        // The gate is off on this node: only the admin host is gated.
        val plain = TestHttpClient(cxt.instanceConfig).also { it.setHeader("Host", "kd9.localhost:7099") }
        plain.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.ok

        val admin = TestHttpClient(cxt.instanceConfig).also { it.setHeader("Host", "admin.kd9.localhost:7099") }
        admin.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        // This test instance trusts the env-auth header, being ungated, but on a gated request the header does not count.
        EnvAuthRules.isTrusted(cxt.instanceConfig) shouldBe true
        val headered = TestHttpClient(cxt.instanceConfig).also {
            it.setHeader("Host", "admin.kd9.localhost:7099")
            it.setHeader(ENVA.header, "header@$domain")
        }
        headered.sendGetRequest(AEP.selfInfo).rptStatusCode shouldBe EXC.authNeeded
        val visit = admin.sendGetRequestRaw("/wa/clients", mapOf("c" to "acme"))
        visit.rptStatusCode shouldBe 303
        location(visit) shouldBe "/wa/clients?c=acme"
        envAuthOf(admin) shouldBe "dev@$domain"
        // Signed in by the address alone: no Google account is linked for a made-up subject.
        val users = UserService.get(cxt)
        users.queryIdentityByAddress(cxt, "dev@$domain").shouldNotBeNull()
        users.queryLinkedIdentity(cxt, LSRC.google, "simulated:dev@$domain").shouldBeNull()

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
        refusal("gateTrusts", gateSettings(mapOf(ACFG.trustEnvAuthHeader to true))) shouldContain "is set on"
        refusal("gateBadMode", gateSettings(mapOf(EGATE.envGate to "always"))) shouldContain "takes one of"

        // A real deployment must say where Google returns; a test instance may build it from the request.
        fun realNode(publicUrl: String?) = KdrInstanceConfig("gateReal", ENV.prod, ENV.liveSource).apply {
            put(ACFG.isTestInstance, false)
            for ((k, v) in gateSettings()) put(k, v)
            if (publicUrl != null) put(INVITE.publicUrl.name, publicUrl)
        }
        shouldThrow<KdrException> { EnvGate.checkBoot(realNode(null), emptyList()) }.message.orEmpty() shouldContain INVITE.publicUrl.name
        EnvGate.checkBoot(realNode("https://gate.example"), emptyList())

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
