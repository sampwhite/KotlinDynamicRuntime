package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.typeRefPath
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

private fun props(schema: Map<String, Any?>): Map<String, Any?> = schema[SCH.properties]!!.toJsonMap()
private fun field(schema: Map<String, Any?>, name: String): Map<String, Any?> = props(schema)[name]!!.toJsonMap()

class EndpointBuilderTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    fun sampleModule(): SchModule = schemaModule(cxt, "api") {
        type("FooIn") { type = SCT.kObject; property("q", "A query filter") }
        type("FooOut") { type = SCT.kObject; property("name", "The name") }
        generalEndpoint("/foo", "Foo general endpoint", HttpMethod.POST, outputRef = "FooOut", inputRef = "FooIn") { _, _ ->
            mapOf("name" to "x")
        }
        itemEndpoint("/foo/{id}", "Foo item endpoint", HttpMethod.GET, outputRef = "FooOut") { _, _ -> mapOf("name" to "y") }
        listEndpoint(
            "/foos", "Foo list endpoint", outputRef = "FooOut", inputRef = "FooIn", // method defaults to GET
            hasMore = true, hasNumAvailable = true,
        ) { _, _ -> emptyList<Any?>() }
    }

    "a module collects types and endpoints together" {
        val m = sampleModule()
        m.defs.keys shouldBe setOf("api.FooIn", "api.FooOut") // endpoint envelopes are inline, not in defs
        m.endpoints.map { it.path } shouldBe listOf("/foo", "/foo/{id}", "/foos")
        m.endpoints.map { it.method } shouldBe listOf(HttpMethod.POST, HttpMethod.GET, HttpMethod.GET)
    }

    "endpoints carry their declaring namespace and required description" {
        val m = sampleModule()
        m.endpoints.map { it.namespace } shouldBe listOf("api", "api", "api")
        m.endpoints.single { it.path == "/foo" }.description shouldBe "Foo general endpoint"
        m.endpoints.single { it.path == "/foos" }.description shouldBe "Foo list endpoint"
    }

    "a general endpoint wraps the output under results, with protocol metadata" {
        val ep = sampleModule().endpoints.single { it.path == "/foo" }
        ep.outputSchema[SCH.type] shouldBe SCT.kObject
        props(ep.outputSchema).keys shouldContainExactlyInAnyOrder listOf(EP.requestUri, EP.duration, EP.results)
        field(ep.outputSchema, EP.results)[SCH.dRef] shouldBe typeRefPath("FooOut", "api")
        field(ep.outputSchema, EP.duration)[SCH.type] shouldBe SCT.number
        (ep.outputSchema[SCH.required] as List<*>) shouldContainExactlyInAnyOrder
            listOf(EP.requestUri, EP.duration, EP.results)
        // Input is the caller's type, referenced as-is.
        ep.inputSchema[SCH.dRef] shouldBe typeRefPath("FooIn", "api")
    }

    "an item endpoint wraps the output under item; no input ref yields an empty object" {
        val ep = sampleModule().endpoints.single { it.path == "/foo/{id}" }
        props(ep.outputSchema).keys shouldContainExactlyInAnyOrder listOf(EP.requestUri, EP.duration, EP.item)
        field(ep.outputSchema, EP.item)[SCH.dRef] shouldBe typeRefPath("FooOut", "api")
        // No input ref means "takes no parameters": a closed empty object, not a free-form map.
        ep.inputSchema shouldBe mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to false)
    }

    "a list endpoint nests the request, adds limit, and builds the items envelope" {
        val ep = sampleModule().endpoints.single { it.path == "/foos" }
        // Input: request (the caller's type) + limit sibling, nothing merged.
        props(ep.inputSchema).keys shouldContainExactlyInAnyOrder listOf(EP.request, EP.limit)
        field(ep.inputSchema, EP.request)[SCH.dRef] shouldBe typeRefPath("FooIn", "api")
        field(ep.inputSchema, EP.limit)[SCH.type] shouldBe SCT.integer
        field(ep.inputSchema, EP.limit)[SCH.default] shouldBe defaultListLimit
        // Output: count, metadata, opted-in paging fields, then items last.
        props(ep.outputSchema).keys shouldContainExactlyInAnyOrder
            listOf(EP.numItems, EP.requestUri, EP.duration, EP.hasMore, EP.numAvailable, EP.items)
        field(ep.outputSchema, EP.items)[SCH.type] shouldBe SCT.array
        field(ep.outputSchema, EP.items)[SCH.items]!!.toJsonMap()[SCH.dRef] shouldBe typeRefPath("FooOut", "api")
    }

    "a list endpoint omits limit and paging fields when not requested" {
        val m = schemaModule(cxt, "api") {
            type("Out") { type = SCT.kObject; property("n", "n") }
            listEndpoint("/xs", "Xs list endpoint", outputRef = "Out", noLimit = true) { _, _ -> emptyList<Any?>() }
        }
        val ep = m.endpoints.single()
        // No inputRef -> no request; noLimit -> no limit; so the wrapper is a closed no-parameters object.
        ep.inputSchema shouldBe mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to false)
        props(ep.outputSchema).keys shouldContainExactlyInAnyOrder
            listOf(EP.numItems, EP.requestUri, EP.duration, EP.items) // no hasMore / numAvailable
    }

    "the handler is captured as a plain callable lambda (no indirection)" {
        val ep = sampleModule().endpoints.single { it.path == "/foo" }
        ep.handler(cxt, mapOf("q" to "hi")) shouldBe mapOf("name" to "x")
    }

    "a built endpoint output schema is real: it parses and validates against its defs" {
        val m = sampleModule()
        val ep = m.endpoints.single { it.path == "/foo" }
        // Register the built envelope as a named type alongside the module's defs, then parse it.
        val defs = LinkedHashMap(m.defs)
        defs["api.FooResponse"] = ep.outputSchema
        val respType = parseSchemaTypes(defs)["api.FooResponse"]!!

        validate(respType, mapOf("requestUri" to "/foo", "duration" to 1.5, "results" to mapOf("name" to "x")))
            .shouldBeEmpty()
        validate(respType, mapOf("results" to mapOf("name" to "x")))
            .map { it.code } shouldContain SchFailCode.missingRequired
    }
})
