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
 * It is anonymous, and its first tenant -- the error-display policy `obfuscateSensitiveErrors` -- reflects the
 * deployment's resolved policy through the same resolver the error edge uses.
 */
class AppUiConfigEndpointTest : StringSpec({

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()
    fun features(resp: Map<String, Any?>): Map<String, Any?> = results(resp).getValue(UIC.features)!!.toJsonMap()

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
})
