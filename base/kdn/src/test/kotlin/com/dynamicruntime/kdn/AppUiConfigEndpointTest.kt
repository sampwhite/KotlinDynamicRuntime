package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Exercises the app-level UI-config endpoint (issue #118): deployment-global config the whole frontend shares.
 * It is anonymous; it carries the error-display policy `obfuscateSensitiveErrors` (reflected through the same
 * resolver the error edge uses) and the idle-bump interval (issue #146) the frontend refreshes itself on.
 */
class AppUiConfigEndpointTest : StringSpec({

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()
    fun features(resp: Map<String, Any?>): Map<String, Any?> = results(resp).getValue(UIC.features)!!.toJsonMap()
    fun settings(resp: Map<String, Any?>): Map<String, Any?> = results(resp).getValue(UIC.settings)!!.toJsonMap()

    "app ui config is anonymous and reports obfuscation off by default (a non-prod deployment)" {
        val cxt = TestInstances.default("appCfg")
        val client = TestHttpClient(cxt.instanceConfig)
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.obfuscateSensitiveErrors] shouldBe false
    }

    "the obfuscation flag follows the deployment config" {
        val cxt = TestInstances.obfuscatedErrors("appCfgObf")
        val client = TestHttpClient(cxt.instanceConfig)
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.obfuscateSensitiveErrors] shouldBe true
    }

    // The error-detail flag (issue #223) tracks test access rather than a separate dev/prod notion, so every half is
    // asserted: on where the app is developed or tested, and off for a caller without env auth on a test instance
    // (issue #1149) and on a node shaped like a real one. The off halves are the ones that matter -- they are what
    // keep a stack trace off a user's screen.
    "the error-detail flag is on for a test instance and off for a real one" {
        val testCxt = TestInstances.default("appCfgDetail")
        features(TestHttpClient(testCxt.instanceConfig).alwaysWithTestAccess().sendJsonGetRequest(APP.uiConfig))[APP.showErrorDetail] shouldBe true
        features(TestHttpClient(testCxt.instanceConfig).sendJsonGetRequest(APP.uiConfig))[APP.showErrorDetail] shouldBe false

        val realCxt = TestInstances.notTestInstance("appCfgNoDetail")
        realCxt.instanceConfig.isTestInstance shouldBe false // guard the premise, or the next line proves nothing
        features(TestHttpClient(realCxt.instanceConfig).sendJsonGetRequest(APP.uiConfig))[APP.showErrorDetail] shouldBe false
    }

    // The debug-page flag (issue #227) rides the same test-instance fence as the error-detail one, but is a
    // separate flag: seeing internals and *manufacturing a failure* are different powers. The off half is the
    // one that matters -- it is what keeps a route that deliberately breaks the app off a real deployment.
    "the debug-page flag is on for a test instance and off for a real one" {
        val testCxt = TestInstances.default("appCfgDebug")
        features(TestHttpClient(testCxt.instanceConfig).alwaysWithTestAccess().sendJsonGetRequest(APP.uiConfig))[APP.allowDebugPages] shouldBe true
        // A caller without env auth on a test instance takes no advantage of it (issue #1149).
        features(TestHttpClient(testCxt.instanceConfig).sendJsonGetRequest(APP.uiConfig))[APP.allowDebugPages] shouldBe false

        val realCxt = TestInstances.notTestInstance("appCfgNoDebug")
        realCxt.instanceConfig.isTestInstance shouldBe false // guard the premise, or the next line proves nothing
        features(TestHttpClient(realCxt.instanceConfig).sendJsonGetRequest(APP.uiConfig))[APP.allowDebugPages] shouldBe false
    }

    // The test-access flag (issue #1149): what the Simulations page and the fixture-demo tools are offered by, so it
    // says whether the fixtures exist for this caller -- not merely that the node is a test instance.
    "the test-access flag is on only for an env-authed caller on a test instance" {
        val testCxt = TestInstances.default("appCfgTestAccess")
        features(TestHttpClient(testCxt.instanceConfig).alwaysWithTestAccess().sendJsonGetRequest(APP.uiConfig))[APP.hasTestAccess] shouldBe true
        features(TestHttpClient(testCxt.instanceConfig).sendJsonGetRequest(APP.uiConfig))[APP.hasTestAccess] shouldBe false
        val realCxt = TestInstances.envAuthNotTestInstance("appCfgNoTestAccess")
        features(TestHttpClient(realCxt.instanceConfig).sendJsonGetRequest(APP.uiConfig))[APP.hasTestAccess] shouldBe false
    }

    "the idle-bump interval defaults when the deployment does not tune it" {
        val cxt = TestInstances.default("appCfgIdle")
        val client = TestHttpClient(cxt.instanceConfig)
        // A JSON number round-trips through the parser as a Number; compare on the Int value.
        (settings(client.sendJsonGetRequest(APP.uiConfig))[APP.idleBumpIntervalMs] as Number).toInt() shouldBe
            APP.defaultIdleBumpIntervalMs
    }

    "the idle-bump interval follows the deployment config" {
        // Its own instance (issue #1075): a deployment-tuned idle-bump interval (`ACFG.idleBumpIntervalMs`), a setup no
        // shared entry has.
        val cxt = Startup.mkTestBootCxt("appCfgIdleSet", "appCfgIdleSetTest", mapOf(ACFG.idleBumpIntervalMs to 5000))
        val client = TestHttpClient(cxt.instanceConfig)
        (settings(client.sendJsonGetRequest(APP.uiConfig))[APP.idleBumpIntervalMs] as Number).toInt() shouldBe 5000
    }
})
