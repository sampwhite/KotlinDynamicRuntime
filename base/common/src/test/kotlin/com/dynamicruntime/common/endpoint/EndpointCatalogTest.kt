package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.refTargetName
import com.dynamicruntime.common.schema.typeRefPath
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/** Every fully qualified type name reached by a `$ref` anywhere within [node]. */
private fun refNames(node: Any?, acc: MutableSet<String>) {
    when (node) {
        is Map<*, *> -> {
            (node[SCH.dRef] as? String)?.let { acc.add(refTargetName(it)) }
            node.values.forEach { refNames(it, acc) }
        }
        is List<*> -> node.forEach { refNames(it, acc) }
    }
}

/**
 * Covers the `/schema/endpoints` catalog machinery (issue #42): flattening an endpoint's input into a JSON
 * schema with `$ref`s left intact, rendering an endpoint, and closing over the referenced types into a
 * shared `$defs` (dedup + cycle-safety).
 */
class EndpointCatalogTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    // An input type that references a nested type, an output type, and general/list/explicit-field endpoints.
    val module = schemaModule(cxt, "api") {
        type("Filter") { type = SCT.kObject; property("min", "Minimum") { type = SCT.integer } }
        type("Query") {
            type = SCT.kObject
            property("filter", "Filter criteria") { ref("Filter") }
            property("name", "Name filter")
        }
        type("Out") { type = SCT.kObject; property("id", "Id", required = true) { type = SCT.integer } }
        generalEndpoint("/things/get", "Get a thing", HttpMethod.POST, outputRef = "Out", inputRef = "Query") { _, _ ->
            emptyMap<String, Any?>()
        }
        listEndpoint("/things", "List things", outputRef = "Out", inputRef = "Query") { _, _ -> emptyList<Any?>() }
        generalEndpoint(
            "/calc", "Calc", HttpMethod.POST, outputRef = "Out",
            inputFields = { field("a", "First operand", required = true) { type = SCT.number } },
        ) { _, _ -> emptyMap<String, Any?>() }
    }
    val defs = module.defs
    fun ep(path: String): KdrEndpoint = module.endpoints.single { it.path == path }

    "buildEndpointInputSchema flattens a referenced type's fields, keeping field-level \$refs intact" {
        val input = buildEndpointInputSchema(ep("/things/get"), defs)
        input[SCH.type] shouldBe SCT.kObject
        input[SCH.additionalProperties] shouldBe false
        val props = input[SCH.properties]!!.toJsonMap()
        props.keys.toList() shouldContainExactly listOf("filter", "name")
        // The nested type stays a $ref (for the client to resolve), not inlined.
        props["filter"]!!.toJsonMap()[SCH.dRef] shouldBe typeRefPath("Filter", "api")
    }

    "buildEndpointInputSchema appends limit for a list endpoint" {
        val props = buildEndpointInputSchema(ep("/things"), defs)[SCH.properties]!!.toJsonMap()
        props.keys.toList() shouldContainExactly listOf("filter", "name", EP.limit)
        props[EP.limit]!!.toJsonMap()[SCH.default] shouldBe defaultListLimit
    }

    "buildEndpointInputSchema uses explicit fields directly, tracking required on the side" {
        val input = buildEndpointInputSchema(ep("/calc"), defs)
        (input[SCH.properties]!!.toJsonMap()).keys.toList() shouldContainExactly listOf("a")
        (input[SCH.required] as List<*>) shouldContainExactly listOf("a")
    }

    "buildEndpointInputSchema closes a no-parameter endpoint's input" {
        // No input type and no fields -> a closed empty object (no `properties`).
        val input = buildEndpointInputSchema(
            KdrEndpoint("/x", HttpMethod.GET, EndpointKind.general, "api", "X", null, null, false, emptyMap()) { _, _ -> null },
            defs,
        )
        input shouldBe mapOf(SCH.type to SCT.kObject, SCH.additionalProperties to false)
    }

    "renderEndpoint carries identity plus input and output schema" {
        val r = renderEndpoint(ep("/things/get"), defs)
        r[EI.path] shouldBe "/things/get"
        r[EI.method] shouldBe "POST"
        r[EI.kind] shouldBe EndpointKind.general.name
        r.keys shouldContain EI.inputSchema
        r.keys shouldContain EI.outputSchema
    }

    "collectDefs closes over every referenced type, once each" {
        val renderings = module.endpoints.map { renderEndpoint(it, defs) }
        val collected = collectDefs(renderings, defs)
        // Filter (via Query.filter, in the flattened input) and Out (output); Query itself is dissolved.
        collected.keys.toList() shouldContainExactlyInAnyOrder listOf("api.Filter", "api.Out")

        // Closure: every $ref reachable from the renderings or the collected defs resolves within the bag.
        val refs = linkedSetOf<String>()
        (renderings + collected.values).forEach { refNames(it, refs) }
        refs.forEach { collected.keys shouldContain it }
    }

    "collectDefs is safe against a self-referential type" {
        val recursive = schemaModule(cxt, "rec") {
            type("Node") {
                type = SCT.kObject
                property("value", "The value")
                property("next", "The next node") { ref("Node") } // self-reference
            }
            type("Out") { type = SCT.kObject; property("ok", "Ok") { type = SCT.boolean } }
            generalEndpoint("/tree", "Tree", HttpMethod.POST, outputRef = "Out", inputRef = "Node") { _, _ ->
                emptyMap<String, Any?>()
            }
        }
        val renderings = recursive.endpoints.map { renderEndpoint(it, recursive.defs) }
        val collected = collectDefs(renderings, recursive.defs)
        // The self-ref terminates: rec.Node is included once (kept as a $ref inside itself, not expanded).
        collected.keys.toList() shouldContainExactlyInAnyOrder listOf("rec.Node", "rec.Out")
    }
})
