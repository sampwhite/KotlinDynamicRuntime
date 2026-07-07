package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.node.ND
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * End-to-end proof that endpoint execution works: boot an instance, then call the `/health` endpoint
 * through the in-process [TestHttpClient] (no socket) and check the protocol envelope + node health
 * fields. Exercises the whole path -- dispatcher, input validation, handler, envelope wrapping.
 */
class HealthEndpointTest : StringSpec({

    "GET /health returns a health envelope through the in-process client" {
        val cxt = Startup.mkTestBootCxt("healthCheck", "healthEndpointTest")
        val client = TestHttpClient(cxt.instanceConfig)

        val resp = client.sendJsonGetRequest("/health")

        // Protocol envelope metadata is present.
        resp.containsKey(EP.requestUri) shouldBe true
        resp.containsKey(EP.duration) shouldBe true

        // The handler's result is under `results` and carries the node health fields.
        val results = resp.getValue(EP.results)!!.toJsonMap()
        results[ND.version] shouldBe "0.2"
        results[ND.isClusterMember] shouldBe true
        results.containsKey(ND.nodeId) shouldBe true
        results.containsKey(ND.uptime) shouldBe true
    }

    "/health takes no parameters: a stray query param is rejected, but off-contract keys pass" {
        val cxt = Startup.mkTestBootCxt("healthCheck", "healthEndpointTest3")
        val client = TestHttpClient(cxt.instanceConfig)

        // A real, undeclared parameter is rejected (the input is a closed empty object -> 400).
        client.sendGetRequest("/health", mapOf("bogus" to "1")).rptStatusCode shouldBe 400

        // Off-contract `_`/`$` keys are still allowed even though the endpoint declares no parameters.
        client.sendGetRequest("/health", mapOf(EP.debug to "explainInput", $$"$note" to "hi")).rptStatusCode shouldBe 200
    }

    "an unknown path yields a not-found error response" {
        val cxt = Startup.mkTestBootCxt("healthCheck", "healthEndpointTest2")
        val client = TestHttpClient(cxt.instanceConfig)

        val handler = client.sendGetRequest("/nope/missing")

        handler.rptStatusCode shouldBe 404
    }

    "a request outside every context root is fast-failed with a short 404" {
        val cxt = Startup.mkTestBootCxt("healthCheck", "healthGateTest")
        val client = TestHttpClient(cxt.instanceConfig)

        // A bare endpoint path (no context root) is rejected...
        client.sendGetRequestRaw("/health").rptStatusCode shouldBe 404
        // ...as is an unknown context root, with the abbreviated body -- not the JSON error envelope.
        val probe = client.sendGetRequestRaw("/bogus/health")
        probe.rptStatusCode shouldBe 404
        probe.rptResponseData shouldBe "Not Found"
    }

    "the context root is configurable; the default is not served when overridden" {
        val cxt = Startup.mkTestBootCxt(
            "healthCheck",
            "healthCfgTest",
            mapOf(ACFG.apiContextRoot to "api2"),
        )
        val client = TestHttpClient(cxt.instanceConfig) // auto-routes under the configured "api2"

        client.sendGetRequest("/health").rptStatusCode shouldBe 200
        // The default kda root is no longer served on this instance.
        client.sendGetRequestRaw("/${ContextRoot.kda}/health").rptStatusCode shouldBe 404
    }
})
