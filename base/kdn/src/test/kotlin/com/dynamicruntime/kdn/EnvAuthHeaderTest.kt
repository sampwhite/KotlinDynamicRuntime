package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.app.EnvAuthOp
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.test.EnvAuthFixtureOp
import com.dynamicruntime.common.test.TENV
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Drives the env-auth header through the real request pipeline (issue #348): an edge server's
 * `X-Kdr-Env-Email` reaching a backend, landing on the request context, and being reported to the frontend.
 *
 * No edge is involved -- the header is just a header, which is the whole point of landing this half first:
 * the application can be built against it before any edge exists.
 */
class EnvAuthHeaderTest : StringSpec({

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()
    fun features(resp: Map<String, Any?>): Map<String, Any?> = results(resp).getValue(UIC.features)!!.toJsonMap()

    "the header lands on the request context and is reported to the frontend" {
        val cxt = Startup.mkTestBootCxt("envAuthOn", "envAuthOnTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader(ENVA.header, "envauth.alice@gyassa.com")

        // The context is the contract; the feature flag is its projection. Assert both, since a later change
        // could satisfy one without the other.
        val handler = client.sendGetRequest(APP.uiConfig)
        handler.createdCxt?.envAuthEmail shouldBe "envauth.alice@gyassa.com"
        val f = features(client.sendJsonGetRequest(APP.uiConfig))
        f[APP.isEnvAuthed] shouldBe true
        f[APP.envAuthSuppressible] shouldBe true
    }

    "a request that did not come through an edge reports nothing" {
        val cxt = Startup.mkTestBootCxt("envAuthNone", "envAuthNoneTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendGetRequest(APP.uiConfig).createdCxt?.envAuthEmail shouldBe null
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.isEnvAuthed] shouldBe false
    }

    /**
     * The security-relevant direction, and the one no test reaches by accident: the default is *on* under
     * `unit`, so a node that refuses the header has to be asked for deliberately. Without this, the branch
     * that protects every node not behind an edge would be untested.
     */
    "a node that does not trust the header ignores it entirely" {
        val cxt = Startup.mkTestBootCxt(
            "envAuthOff", "envAuthOffTest", mapOf(ACFG.trustEnvAuthHeader to false),
        )
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader(ENVA.header, "envauth.mallory@gyassa.com")

        client.sendGetRequest(APP.uiConfig).createdCxt?.envAuthEmail shouldBe null
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.isEnvAuthed] shouldBe false
    }

    "a header this node would not repeat leaves the request simply not env-authed" {
        val cxt = Startup.mkTestBootCxt("envAuthBad", "envAuthBadTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader(ENVA.header, "envauth.eve@gyassa.com\r\nINFO fabricated log line")

        // Dropped, not fatal: this is an assertion about the channel, and a malformed one is no assertion --
        // not an error the caller could act on.
        val resp = client.sendJsonGetRequest(APP.uiConfig)
        features(resp)[APP.isEnvAuthed] shouldBe false
    }

    /**
     * How the request arrived and who is acting are independent -- env auth never dictates a KDR login, and the
     * common deployment case is an env-authed channel carrying an ordinarily logged-in user. Both combinations
     * are asserted, because collapsing the two axes into one is exactly the modeling mistake to guard against.
     */
    "env auth is a property of the channel, not of the user" {
        val cxt = Startup.mkTestBootCxt("envAuthWho", "envAuthWhoTest")

        // Env-authed channel, anonymous caller.
        val anon = TestHttpClient(cxt.instanceConfig)
        anon.setHeader(ENVA.header, "envauth.chan@gyassa.com")
        val anonHandler = anon.sendGetRequest(APP.uiConfig)
        anonHandler.createdCxt?.envAuthEmail shouldBe "envauth.chan@gyassa.com"
        anonHandler.createdCxt?.userProfile?.isLoggedIn shouldBe false

        // Env-authed channel, logged-in caller: the address rides beside the session, and grants no role.
        val user = TestUser.create(cxt, "envauth.dana@gyassa.com", level = ROLE.user)
        user.client.setHeader(ENVA.header, "envauth.chan@gyassa.com")
        val userHandler = user.client.sendGetRequest(APP.uiConfig)
        userHandler.createdCxt?.envAuthEmail shouldBe "envauth.chan@gyassa.com"
        userHandler.createdCxt?.userProfile?.isLoggedIn shouldBe true
        userHandler.createdCxt?.userProfile?.roles?.contains(ROLE.admin) shouldBe false
    }

    /**
     * The round trip the whole slice exists to prove: suppress, and the session stops *acting* env-authed
     * while still knowing that it is -- which is what keeps the control that restores it on screen.
     */
    "suppressing turns the effective flag off and leaves availability on" {
        val cxt = Startup.mkTestBootCxt("envAuthSuppress", "envAuthSuppressTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader(ENVA.header, "envauth.sara@gyassa.com")

        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.suppress.name))

        val off = features(client.sendJsonGetRequest(APP.uiConfig))
        off[APP.isEnvAuthed] shouldBe false
        off[APP.envAuthSuppressible] shouldBe true

        // And the truth survives on the context, because that is what the log line records. Suppression is a
        // display choice, never a way to act unattributed.
        client.sendGetRequest(APP.uiConfig).createdCxt?.envAuthEmail shouldBe "envauth.sara@gyassa.com"

        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.restore.name))
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.isEnvAuthed] shouldBe true
    }

    "suppressing when there is no env auth to suppress changes nothing" {
        val cxt = Startup.mkTestBootCxt("envAuthSuppressNone", "envAuthSuppressNoneTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.suppress.name))

        val f = features(client.sendJsonGetRequest(APP.uiConfig))
        f[APP.isEnvAuthed] shouldBe false
        f[APP.envAuthSuppressible] shouldBe false
    }

    /**
     * The fixture is how a browser reaches the env-authed view at all, since it cannot attach a request
     * header. Note `clear` is not `suppress`: it stops pretending and returns the session to the truth, which
     * here is no env auth.
     */
    "the test fixture asserts env auth for a session no edge vouched for" {
        val cxt = Startup.mkTestBootCxt("envAuthFixture", "envAuthFixtureTest")
        val client = TestHttpClient(cxt.instanceConfig)

        features(client.sendJsonGetRequest(APP.uiConfig))[APP.envAuthSuppressible] shouldBe false

        client.sendJsonPostRequest(
            TENV.path,
            mapOf(TENV.op to EnvAuthFixtureOp.assert.name, TENV.email to "envauth.fix@gyassa.com"),
        )
        val on = features(client.sendJsonGetRequest(APP.uiConfig))
        on[APP.isEnvAuthed] shouldBe true
        on[APP.envAuthSuppressible] shouldBe true
        client.sendGetRequest(APP.uiConfig).createdCxt?.envAuthEmail shouldBe "envauth.fix@gyassa.com"

        client.sendJsonPostRequest(TENV.path, mapOf(TENV.op to EnvAuthFixtureOp.clear.name))
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.envAuthSuppressible] shouldBe false
    }

    /**
     * The security-relevant half, and the one a `forTestingOnly` marking does **not** provide: the endpoint
     * gate stops the cookie being issued, and nothing stops one being typed into a browser. So a node shaped
     * like a real one must refuse the cookie itself.
     *
     * The endpoint is also absent from such a node's store, which this asserts second -- both halves matter,
     * because either alone leaves a way in.
     */
    "a real-shaped node refuses a forged fixture cookie, and does not serve the fixture at all" {
        val cxt = Startup.mkTestBootCxt(
            "envAuthReal", "envAuthRealTest",
            mapOf(ACFG.isTestInstance to false, ACFG.trustEnvAuthHeader to true),
        )
        cxt.instanceConfig.isTestInstance shouldBe false // guard the premise
        val client = TestHttpClient(cxt.instanceConfig)
        client.cookies[ENVA.assertCookie] = "attacker@gyassa.com"

        features(client.sendJsonGetRequest(APP.uiConfig))[APP.envAuthSuppressible] shouldBe false

        // And the fixture endpoint itself is not in the store on a node like this.
        client.sendEditRequest(
            TENV.path, null, mapOf(TENV.op to EnvAuthFixtureOp.clear.name), HttpMethod.POST,
        ).rptStatusCode shouldBe EXC.notFound
    }
})
