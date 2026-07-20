package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.typeRefPath
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
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

    // Resolves an endpoint's declared input into the flat consumption type (against the module's compiled defs),
    // the same thing the dispatcher validates against.
    fun resolvedInput(m: SchModule, path: String): SchType {
        val ep = m.endpoints.single { it.path == path }
        return resolveEndpointInputType(ep, parseSchemaTypes(m.defs))!!
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
        val m = sampleModule()
        val ep = m.endpoints.single { it.path == "/foo" }
        ep.outputSchema[SCH.type] shouldBe SCT.kObject
        props(ep.outputSchema).keys shouldContainExactlyInAnyOrder
            listOf(EP.requestUri, EP.duration, EP.contentHash, EP.webAppHash,EP.results)
        field(ep.outputSchema, EP.results)[SCH.dRef] shouldBe typeRefPath("FooOut", "api")
        field(ep.outputSchema, EP.duration)[SCH.type] shouldBe SCT.number
        (ep.outputSchema[SCH.required] as List<*>) shouldContainExactlyInAnyOrder
            listOf(EP.requestUri, EP.duration, EP.contentHash, EP.webAppHash,EP.results)
        // Reference declares input; it resolves to the referenced type's fields, closed to extras.
        ep.inputTypeRef shouldBe "api.FooIn"
        ep.inputFields shouldBe null
        val input = resolvedInput(m, "/foo")
        input.properties.keys shouldContainExactly listOf("q")
        input.additionalProperties shouldBe false
    }

    "an item endpoint wraps the output under item; no input ref yields an empty closed object" {
        val m = sampleModule()
        val ep = m.endpoints.single { it.path == "/foo/{id}" }
        props(ep.outputSchema).keys shouldContainExactlyInAnyOrder
            listOf(EP.requestUri, EP.duration, EP.contentHash, EP.webAppHash,EP.item)
        field(ep.outputSchema, EP.item)[SCH.dRef] shouldBe typeRefPath("FooOut", "api")
        // No input declared means "takes no parameters": resolves to a closed empty object, not a free-form map.
        ep.inputTypeRef shouldBe null
        ep.inputFields shouldBe null
        val input = resolvedInput(m, "/foo/{id}")
        input.jsonType shouldBe SCT.kObject
        input.properties.keys.shouldBeEmpty()
        input.additionalProperties shouldBe false
    }

    "a list endpoint flattens the referenced input fields and appends limit" {
        val m = sampleModule()
        val ep = m.endpoints.single { it.path == "/foos" }
        ep.inputTypeRef shouldBe "api.FooIn"
        ep.includeLimit shouldBe true
        // Input: the referenced type's fields, flat, plus the appended limit sibling; closed to extras.
        val input = resolvedInput(m, "/foos")
        input.properties.keys.toList() shouldContainExactly listOf("q", EP.limit)
        input.properties[EP.limit]!!.valueType.jsonType shouldBe SCT.integer
        input.properties[EP.limit]!!.valueType.default shouldBe defaultListLimit
        input.additionalProperties shouldBe false
        // Output: count, metadata, opted-in paging fields, then items last.
        props(ep.outputSchema).keys shouldContainExactlyInAnyOrder
            listOf(EP.numItems, EP.requestUri, EP.duration, EP.contentHash, EP.webAppHash,EP.hasMore, EP.numAvailable, EP.items)
        field(ep.outputSchema, EP.items)[SCH.type] shouldBe SCT.array
        field(ep.outputSchema, EP.items)[SCH.items]!!.toJsonMap()[SCH.dRef] shouldBe typeRefPath("FooOut", "api")
    }

    "a list endpoint omits limit and paging fields when not requested" {
        val m = schemaModule(cxt, "api") {
            type("Out") { type = SCT.kObject; property("n", "n") }
            listEndpoint("/xs", "Xs list endpoint", outputRef = "Out", noLimit = true) { _, _ -> emptyList<Any?>() }
        }
        val ep = m.endpoints.single()
        // No input and noLimit -> a closed no-parameters object.
        ep.includeLimit shouldBe false
        val input = resolvedInput(m, "/xs")
        input.properties.keys.shouldBeEmpty()
        input.additionalProperties shouldBe false
        props(ep.outputSchema).keys shouldContainExactlyInAnyOrder
            listOf(EP.numItems, EP.requestUri, EP.duration, EP.contentHash, EP.webAppHash,EP.items) // no hasMore / numAvailable
    }

    "an endpoint can declare explicit input fields instead of a named type" {
        val m = schemaModule(cxt, "api") {
            type("Out") { type = SCT.kObject; property("n", "n") }
            generalEndpoint(
                "/calc", "Calc", HttpMethod.POST, outputRef = "Out",
                inputFields = {
                    field("a", "First operand", required = true) { type = SCT.number }
                    field("b", "Second operand") { type = SCT.number }
                },
            ) { _, _ -> emptyMap<String, Any?>() }
        }
        val ep = m.endpoints.single()
        ep.inputTypeRef shouldBe null
        ep.inputFields!!.map { it.name } shouldContainExactly listOf("a", "b")
        // The explicit fields resolve to a closed object, with per-field required to be tracked on the side.
        val input = resolvedInput(m, "/calc")
        input.properties.keys.toList() shouldContainExactly listOf("a", "b")
        input.required shouldBe setOf("a")
        input.properties["a"]!!.valueType.jsonType shouldBe SCT.number
        input.additionalProperties shouldBe false
    }

    "declaring both an input ref and explicit input fields fails fast" {
        shouldThrow<KdrException> {
            schemaModule(cxt, "api") {
                type("In") { type = SCT.kObject; property("q", "q") }
                type("Out") { type = SCT.kObject; property("n", "n") }
                generalEndpoint(
                    "/x", "X", HttpMethod.POST, outputRef = "Out",
                    inputRef = "In", inputFields = { field("q", "q") },
                ) { _, _ -> emptyMap<String, Any?>() }
            }
        }
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

        validate(respType, mapOf(
            "requestUri" to "/foo", "duration" to 1.5, "contentHash" to "abc123", "webAppHash" to "",
            "results" to mapOf("name" to "x"),
        )).shouldBeEmpty()
        validate(respType, mapOf("results" to mapOf("name" to "x")))
            .map { it.code } shouldContain SchFailCode.missingRequired
    }
})
