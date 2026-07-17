package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.node.ND
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * End-to-end proof that endpoint execution works: boot an instance, then call the `/health` endpoint
 * through the in-process [TestHttpClient] (no socket) and check the protocol envelope + node health
 * fields. Exercises the whole path -- dispatcher, input validation, handler, envelope wrapping.
 */
class HealthEndpointTest : StringSpec({

    // The default-boot, read-only tests share one instance (its init is cached by instance name, so it runs
    // once) and vary only the inexpensive context name. The context-root override test at the end needs its own
    // instance -- it boots with a different apiContextRoot, and the instance cache is keyed on that name.
    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "healthEndpointTest").instanceConfig)

    "GET /health returns a health envelope through the in-process client" {
        val client = client("healthGet")

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
        val client = client("healthNoParams")

        // A real, undeclared parameter is rejected (the input is a closed empty object -> 400).
        client.sendGetRequest("/health", mapOf("bogus" to "1")).rptStatusCode shouldBe 400

        // Off-contract `_`/`$` keys are still allowed even though the endpoint declares no parameters.
        client.sendGetRequest("/health", mapOf(EP.debug to "explainInput", $$"$note" to "hi")).rptStatusCode shouldBe 200
    }

    "a real error response carries the standardized envelope (issue #103)" {
        val client = client("healthErrorEnvelope")

        val resp = client.sendGetRequest("/health", mapOf("bogus" to "1"))
        resp.rptStatusCode shouldBe 400
        val body = resp.rptResponseData!!.jsonMap()!!

        // The HTTP code is `status` (a number), not the old `errorCode`.
        body[EP.status] shouldBe 400L
        body.containsKey(EP.errorMessage) shouldBe true
        body.containsKey(EP.requestUri) shouldBe true
        // A schema-validation error carries no logical code and no bag, so neither key is present.
        body.containsKey(EP.errorCode) shouldBe false
        body.containsKey(EP.extraData) shouldBe false
    }

    "the appId and trace-id headers reach the request context and the log label (issue #105)" {
        val client = client("healthIdentity")
        client.setHeader("X-Kdr-App-Id", "kdr.en")
        client.setHeader("X-Kdr-Trace-Id", "2026071712000012307")

        val cxt = client.sendGetRequest("/health").createdCxt!!
        cxt.appId shouldBe "kdr.en"
        cxt.traceId shouldBe "2026071712000012307"
        // The whole point: the frontend's id is on the log line, so one grep spans browser and server.
        cxt.logInfo() shouldContain "2026071712000012307:"
    }

    "an unknown path yields a not-found error response" {
        val client = client("healthUnknownPath")

        val handler = client.sendGetRequest("/nope/missing")

        handler.rptStatusCode shouldBe 404
    }

    "a request outside every context root is fast-failed with a short 404" {
        val client = client("healthGate")

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
        // This instance no longer serves the default kda root.
        client.sendGetRequestRaw("/${ContextRoot.kda}/health").rptStatusCode shouldBe 404
    }
})
