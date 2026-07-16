package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Exercises the two SchemaService endpoints through the in-process client. Because these run under
 * [Startup.mkTestBootCxt] (which sets `validateResponseSchema`), every response is also validated against
 * the endpoint's output schema, so a non-conforming catalog or sample item would fail the call.
 */
class SchemaEndpointsTest : StringSpec({

    // /schema/endpoints is a general endpoint: its result carries the endpoint renderings and a shared $defs.
    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp[EP.results]!!.toJsonMap()
    fun catalogEndpoints(resp: Map<String, Any?>): List<Map<String, Any?>> =
        results(resp)[EI.endpoints].toJsonListOfMaps()

    // /schema/sample is a list endpoint: its payload is under `items`.
    fun items(resp: Map<String, Any?>): List<Map<String, Any?>> =
        resp[EP.items].toJsonListOfMaps()

    // These tests all boot the same (default) instance and only read, so they share one instance -- its
    // component/schema init is cached by instance name and runs once -- and vary only the inexpensive context name.
    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "schemaEndpointsTest").instanceConfig)

    $$"/schema/endpoints renders every endpoint and a shared $defs" {
        val client = client("schemaList")

        val resp = client.sendJsonGetRequest("/schema/endpoints")
        val eps = catalogEndpoints(resp)
        eps.map { it[EI.path] } shouldContainAll listOf("/health", "/schema/endpoints", "/schema/sample")

        val health = eps.first { it[EI.path] == "/health" }
        health.keys shouldContainAll
            listOf(EI.path, EI.method, EI.kind, EI.namespace, EI.description, EI.inputSchema, EI.outputSchema)
        health[EI.namespace] shouldBe "node"
        health[EI.method] shouldBe "GET"
        // /health takes no parameters: its rendered input schema is a closed, empty object.
        health[EI.inputSchema]!!.toJsonMap()[SCH.additionalProperties] shouldBe false

        // The shared $defs closes over the types the renderings reference (by $ref), returned once each:
        // /health's output refs node.Health; /schema/sample's input flattens SampleQuery, whose `filter` refs
        // SampleFilter, and its output refs SampleItem, which refs SampleDetails.
        val defs = results(resp)[SCH.dDefs]!!.toJsonMap()
        defs.keys shouldContainAll listOf("node.Health", "schema.SampleFilter", "schema.SampleItem", "schema.SampleDetails")
        // SampleQuery itself is dissolved into flat input fields, so it is NOT a returned def.
        defs.keys shouldNotContain "schema.SampleQuery"
    }

    "/schema/endpoints filters by namespace, method, and path regex" {
        val client = client("schemaFilters")

        fun paths(params: Map<String, Any?>): List<Any?> =
            catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", params)).map { it[EI.path] }

        paths(mapOf(EI.namespace to "node")) shouldBe listOf("/health")
        // The method filter returns only POST endpoints (which include /schema/sample and the demo POSTs).
        val posts = catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EI.method to "POST")))
        posts.map { it[EI.path] } shouldContain "/schema/sample"
        posts.map { it[EI.method] }.toSet() shouldBe setOf("POST")
        paths(mapOf(SS.pathRegex to "^/schema/")) shouldBe
            listOf("/schema/complex", "/schema/endpoint", "/schema/endpoints", "/schema/sample")
    }

    "/schema/endpoints caps the number of endpoints by limit" {
        val client = client("schemaLimit")
        catalogEndpoints(client.sendJsonGetRequest("/schema/endpoints", mapOf(EP.limit to 2))).size shouldBe 2
    }

    "/schema/endpoint looks up a single endpoint by exact method and path, in the catalog shape" {
        val client = client("schemaOne")
        val resp = client.sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "GET", EI.path to "/health"))
        // Same shape as /schema/endpoints: an `endpoints` list (here of one) plus a shared `$defs`.
        val eps = catalogEndpoints(resp)
        eps.map { it[EI.path] } shouldBe listOf("/health")
        eps.single()[EI.method] shouldBe "GET"
        (results(resp)[SCH.dDefs]!!.toJsonMap()).keys shouldContain "node.Health"
    }

    "/schema/endpoint returns an empty list (not a 404) when nothing matches" {
        val client = client("schemaOneMiss")
        val resp = client.sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "GET", EI.path to "/nope"))
        catalogEndpoints(resp).shouldBeEmpty()
    }

    "/schema/endpoint requires both method and path" {
        val client = client("schemaOneBad")
        client.sendGetRequest("/schema/endpoint", mapOf(EI.method to "GET")).rptStatusCode shouldBe 400
    }

    "/schema/sample returns a nested, schema-conforming list, with limit truncation" {
        val client = client("schemaSample")

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

    $$"/schema/sample drops an off-contract $note yet honors a _debug=explainInput echo in the same call" {
        val client = client("schemaOffContract")

        val resp = client.sendJsonPostRequest(
            "/schema/sample",
            mapOf(
                $$"$note" to "mimics standard query semantics", // off-contract annotation, dropped on coercing
                EP.debug to SS.explainInput, // "_debug" -> echo the evaluated params under _meta
                SS.filter to mapOf(SS.minCount to 1),
            ),
        )
        // The handler throws if any `$` key leaks into its input, so a normal item list proves $note was dropped.
        items(resp).size shouldBe 15
        // The _meta echo only appears because _debug rode onto the context; the echoed params show $note is gone.
        val evaluated = resp[EP.meta]!!.toJsonMap()[SS.paramsEvaluated]!!.toJsonMap()
        evaluated.keys shouldContainAll listOf(SS.filter)
    }
})
