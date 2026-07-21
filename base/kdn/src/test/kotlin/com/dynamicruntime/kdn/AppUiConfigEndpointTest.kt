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
        val cxt = Startup.mkTestBootCxt("appCfg", "appCfgTest")
        val client = TestHttpClient(cxt.instanceConfig)
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.obfuscateSensitiveErrors] shouldBe false
    }

    "the obfuscation flag follows the deployment config" {
        val cxt = Startup.mkTestBootCxt("appCfgObf", "appCfgObfTest", mapOf(ACFG.obfuscateSensitiveErrors to true))
        val client = TestHttpClient(cxt.instanceConfig)
        features(client.sendJsonGetRequest(APP.uiConfig))[APP.obfuscateSensitiveErrors] shouldBe true
    }

    "the idle-bump interval defaults when the deployment does not tune it" {
        val cxt = Startup.mkTestBootCxt("appCfgIdle", "appCfgIdleTest")
        val client = TestHttpClient(cxt.instanceConfig)
        // A JSON number round-trips through the parser as a Number; compare on the Int value.
        (settings(client.sendJsonGetRequest(APP.uiConfig))[APP.idleBumpIntervalMs] as Number).toInt() shouldBe
            APP.defaultIdleBumpIntervalMs
    }

    "the idle-bump interval follows the deployment config" {
        val cxt = Startup.mkTestBootCxt("appCfgIdleSet", "appCfgIdleSetTest", mapOf(ACFG.idleBumpIntervalMs to 5000))
        val client = TestHttpClient(cxt.instanceConfig)
        (settings(client.sendJsonGetRequest(APP.uiConfig))[APP.idleBumpIntervalMs] as Number).toInt() shouldBe 5000
    }
})
