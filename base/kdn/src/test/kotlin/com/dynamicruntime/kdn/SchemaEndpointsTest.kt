package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * Exercises the two SchemaService endpoints through the in-process client. Because these run under
 * [Startup.mkTestBootCxt] (which sets `validateResponseSchema`), every response is also validated against
 * the endpoint's output schema, so a non-conforming dump or sample item would fail the call.
 */
class SchemaEndpointsTest : StringSpec({

    fun items(resp: Map<String, Any?>): List<Map<String, Any?>> =
        (resp[EP.items] as List<*>).map { it!!.toJsonMap() }

    "/schema/endpoints lists every endpoint and dumps its attributes" {
        val cxt = Startup.mkTestBootCxt("schemaEp", "schemaEndpointsTest")
        val client = TestHttpClient(cxt.instanceConfig)

        val all = items(client.sendJsonGetRequest("/schema/endpoints"))
        all.map { it[EI.path] } shouldContainAll listOf("/health", "/schema/endpoints", "/schema/sample")

        val health = all.first { it[EI.path] == "/health" }
        health.keys shouldContainAll
            listOf(EI.path, EI.method, EI.kind, EI.namespace, EI.description, EI.inputSchema, EI.outputSchema)
        health[EI.namespace] shouldBe "node"
        health[EI.method] shouldBe "GET"
    }

    "/schema/endpoints filters by namespace, method, and path regex" {
        val cxt = Startup.mkTestBootCxt("schemaEp2", "schemaEndpointsTest2")
        val client = TestHttpClient(cxt.instanceConfig)

        items(client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.namespace to "node")))
            .map { it[EI.path] } shouldBe listOf("/health")
        items(client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.method to "POST")))
            .map { it[EI.path] } shouldBe listOf("/schema/sample")
        items(client.sendJsonGetRequest("/schema/endpoints", mapOf(SS.pathRegex to "^/schema/")))
            .map { it[EI.path] } shouldBe listOf("/schema/endpoints", "/schema/sample")
    }

    "/schema/sample returns a nested, schema-conforming list, with limit truncation" {
        val cxt = Startup.mkTestBootCxt("schemaSample", "schemaSampleTest")
        val client = TestHttpClient(cxt.instanceConfig)

        // A nested request exercising a choice list, a date, and a nested filter object.
        val full = client.sendJsonPostRequest(
            "/schema/sample",
            mapOf(
                SS.filter to mapOf(SS.minCount to 1, SS.activeOnly to false),
                SS.categories to listOf("alpha", "beta"),
                SS.sinceDate to "2020-01-01",
            ),
        )
        val allItems = items(full)
        allItems.size shouldBe 15
        // The item is nested and carries choice/date/bool/int values.
        val details = allItems.first()[SS.details]!!.toJsonMap()
        details.keys shouldContainAll listOf(SS.score, SS.tags, SS.rank)

        // `limit` truncates the returned items.
        items(client.sendJsonPostRequest("/schema/sample", mapOf(EP.limit to 5))).size shouldBe 5
    }
})
