package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.app.EnvAuthOp
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
        val cxt = TestInstances.default("envAuthOn")
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
        val cxt = TestInstances.default("envAuthNone")
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
        // Its own instance (issue #1075): a node that does not trust the env-auth header (`ACFG.trustEnvAuthHeader`), a
        // setup no shared entry has.
        val cxt = Startup.mkTestBootCxt(
            "envAuthOff", "envAuthOffTest", mapOf(ACFG.trustEnvAuthHeader to false),
        )
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader(ENVA.header, "envauth.mallory@gyassa.com")

        client.sendGetRequest(APP.uiConfig).createdCxt?.envAuthEmail shouldBe null
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.isEnvAuthed] shouldBe false
    }

    "a header this node would not repeat leaves the request simply not env-authed" {
        val cxt = TestInstances.default("envAuthBad")
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
        val cxt = TestInstances.default("envAuthWho")

        // Env-authed channel, anonymous caller.
        val anon = TestHttpClient(cxt.instanceConfig)
        anon.setHeader(ENVA.header, "envauth.chan@gyassa.com")
        val anonHandler = anon.sendGetRequest(APP.uiConfig)
        anonHandler.createdCxt?.envAuthEmail shouldBe "envauth.chan@gyassa.com"
        anonHandler.createdCxt?.userProfile?.isLoggedIn shouldBe false

        // Env-authed channel, logged-in caller: the address rides beside the session and grants no role.
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
        val cxt = TestInstances.default("envAuthSuppress")
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
        val cxt = TestInstances.default("envAuthSuppressNone")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.suppress.name))

        val f = features(client.sendJsonGetRequest(APP.uiConfig))
        f[APP.isEnvAuthed] shouldBe false
        f[APP.envAuthSuppressible] shouldBe false
    }

    // ---- the tri-state debug state (issue #517) ----------------------------

    "the env control cycles through off, on, and debug" {
        val cxt = TestInstances.default("envDebugCycle")
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader(ENVA.header, "envauth.dev@gyassa.com")

        // on -> debug: acting env-authed, and now with debug behaviors.
        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.debug.name))
        val dbg = features(client.sendJsonGetRequest(APP.uiConfig))
        dbg[APP.isEnvAuthed] shouldBe true
        dbg[APP.envAuthDebug] shouldBe true

        // debug -> off: suppression clears debug too.
        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.suppress.name))
        val off = features(client.sendJsonGetRequest(APP.uiConfig))
        off[APP.isEnvAuthed] shouldBe false
        off[APP.envAuthDebug] shouldBe false

        // off -> on: restore returns to plain-on, debug still off.
        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.restore.name))
        val on = features(client.sendJsonGetRequest(APP.uiConfig))
        on[APP.isEnvAuthed] shouldBe true
        on[APP.envAuthDebug] shouldBe false
    }

    "debug opens the debug behaviors on a node that is not a test instance" {
        // Not a test instance, so allowDebugPages/showErrorDetail are off by default -- but env auth is assumed
        // (the local-box path), so an env-authed operator can turn debug on for their own session (issue #517).
        val cxt = TestInstances.envAuthNotTestInstance("envDebugProd")
        val client = TestHttpClient(cxt.instanceConfig)

        val before = features(client.sendJsonGetRequest(APP.uiConfig))
        before[APP.isEnvAuthed] shouldBe true // assumed
        before[APP.envAuthDebug] shouldBe false
        before[APP.allowDebugPages] shouldBe false
        before[APP.showErrorDetail] shouldBe false

        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.debug.name))
        val after = features(client.sendJsonGetRequest(APP.uiConfig))
        after[APP.envAuthDebug] shouldBe true
        after[APP.allowDebugPages] shouldBe true
        after[APP.showErrorDetail] shouldBe true
    }

    "the debug cookie grants nothing without env auth" {
        // A plain unit node assumes no env auth, so a session that sets the debug cookie is still not env-authed
        // -- and debug requires effective env auth, the same rule the toggle's presence follows.
        val cxt = TestInstances.default("envDebugNoAuth")
        val client = TestHttpClient(cxt.instanceConfig)
        client.sendJsonPostRequest(APP.envAuthPath, mapOf(APP.envAuthOp to EnvAuthOp.debug.name))

        val f = features(client.sendJsonGetRequest(APP.uiConfig))
        f[APP.isEnvAuthed] shouldBe false
        f[APP.envAuthDebug] shouldBe false
    }

    /**
     * Nothing a caller sends grants env auth (issue #1149). A test fixture once set a cookie asserting it, which a
     * test instance honored; once the fixtures themselves needed env auth only a caller who already had it could
     * have used it, so it went. Pinned so the old cookie, typed into a browser, stays inert.
     */
    "the retired fixture cookie grants no env auth on a test instance" {
        val cxt = TestInstances.default("envAuthRetiredCookie")
        val client = TestHttpClient(cxt.instanceConfig)
        client.cookies["kdrEnvFixture"] = "attacker@gyassa.com"
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.isEnvAuthed] shouldBe false
        client.sendGetRequest(APP.uiConfig).createdCxt?.envAuthEmail shouldBe null
    }
})
