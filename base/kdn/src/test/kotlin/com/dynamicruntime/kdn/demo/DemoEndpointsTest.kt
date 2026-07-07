package com.dynamicruntime.kdn.demo

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * End-to-end coverage for the demo endpoints, driven through the in-process [TestHttpClient]: the
 * happy paths exercise selects, numbers, booleans, GET-query input, POST bodies, and the list
 * envelope; the failure cases prove schema validation (missing required / bad type) and a handler
 * error (divide-by-zero) surface as the right HTTP status.
 */
class DemoEndpointsTest : StringSpec({

    // The demo handlers are pure (no persistence), so these tests share one instance -- its component/schema
    // init is cached by instance name and runs once -- and vary only the cheap context name.
    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "demoEndpointsTest").instanceConfig)

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()

    "POST /demo/greeting composes a greeting from a select, an int, and a boolean" {
        val resp = client("greeting").sendJsonPostRequest(
            "/demo/greeting",
            mapOf(DMO.name to "Ada", DMO.style to DMO.formal, DMO.repeat to 2, DMO.shout to true),
        )
        results(resp)[DMO.message] shouldBe "GOOD DAY, ADA. GOOD DAY, ADA."
    }

    "POST /demo/greeting applies defaults when optional fields are omitted" {
        val resp = client("greetingDefault").sendJsonPostRequest("/demo/greeting", mapOf(DMO.name to "Sam"))
        // style defaults to casual, repeat defaults to 3, shout to false.
        results(resp)[DMO.message] shouldBe "Hi Sam. Hi Sam. Hi Sam."
    }

    "POST /demo/calc evaluates the selected operation" {
        val resp = client("calc").sendJsonPostRequest(
            "/demo/calc",
            mapOf(DMO.a to 6, DMO.b to 7, DMO.op to DMO.multiply),
        )
        results(resp)[DMO.value] shouldBe 42.0
    }

    "POST /demo/calc returns 400 when dividing by zero" {
        val handler = client("calcDiv").sendEditRequest(
            "/demo/calc", null, mapOf(DMO.a to 1, DMO.b to 0, DMO.op to DMO.divide), isPut = false,
        )
        handler.rptStatusCode shouldBe 400
    }

    "POST /demo/calc returns 400 when a required field is missing" {
        val handler = client("calcMissing").sendEditRequest(
            "/demo/calc", null, mapOf(DMO.a to 1, DMO.op to DMO.add), isPut = false, // no `b`
        )
        handler.rptStatusCode shouldBe 400
    }

    "GET /demo/fibonacci reads its integer from the query string" {
        val resp = client("fib").sendJsonGetRequest("/demo/fibonacci", mapOf(DMO.n to 10))
        results(resp)[DMO.value] shouldBe 55L
    }

    "GET /demo/fibonacci returns 400 for a non-integer query value" {
        val handler = client("fibBad").sendGetRequest("/demo/fibonacci", mapOf(DMO.n to "not-a-number"))
        handler.rptStatusCode shouldBe 400
    }

    "POST /demo/todos filters by the nested request and wraps results in the list envelope" {
        val resp = client("todos").sendJsonPostRequest(
            "/demo/todos",
            mapOf(EP.request to mapOf(DMO.status to DMO.open)),
        )
        @Suppress("UNCHECKED_CAST")
        val items = resp.getValue(EP.items) as List<Map<String, Any?>>
        items.size shouldBe 2 // two todos are not done
        items.all { it.getValue(DMO.done) == false } shouldBe true
    }

    "POST /demo/todos honors a text filter and the limit sibling" {
        val resp = client("todosFilter").sendJsonPostRequest(
            "/demo/todos",
            mapOf(EP.request to mapOf(DMO.contains to "port"), EP.limit to 1),
        )
        @Suppress("UNCHECKED_CAST")
        val items = resp.getValue(EP.items) as List<Map<String, Any?>>
        items.size shouldBe 1 // "Port the auth subsystem" matches, but limit caps it at 1
        resp[EP.numItems] shouldBe 1
    }
})
