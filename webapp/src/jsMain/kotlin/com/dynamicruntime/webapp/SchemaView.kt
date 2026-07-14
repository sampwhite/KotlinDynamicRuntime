package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.refTargetName
import com.dynamicruntime.common.util.toJsonMap

/**
 * Client-side model of the endpoint catalog the display engine renders. It consumes the catalog form emitted
 * by the runtime's `/schema/endpoints` endpoint — a list of endpoint renderings plus a shared `$defs` bag —
 * and parses the schema with the SHARED multiplatform kernel (`:base:kernel`): the same `SchType` model,
 * `parseSchemaTypes`, and (in [SchemaForm] / [EndpointCatalog]) the same validator the JVM backend runs. So
 * the frontend and backend agree on schema semantics by construction, with no reimplementation.
 *
 * JSON keyword constants come from the kernel (`SCH`/`SCT`/`SFMT`); only the endpoint-catalog *wire* keys
 * ([EK]) are mirrored here, since those live in `:base:common` (JVM-only) rather than the kernel.
 */

/** Endpoint-catalog wire keys (mirror `:base:common`'s `EI`; the `$defs` key is the schema keyword `SCH.dDefs`). */
@Suppress("ConstPropertyName")
object EK {
    const val path = "path"
    const val method = "method"
    const val kind = "kind"
    const val namespace = "namespace"
    const val description = "description"
    const val inputSchema = "inputSchema"
    const val outputSchema = "outputSchema"
    const val endpoints = "endpoints"

    // Response-envelope payload keys, by endpoint kind (see :base:common's EP): general -> results,
    // item -> item, list -> items.
    const val results = "results"
    const val item = "item"
    const val items = "items"
}

/** Endpoint kinds (mirror `:base:common`'s `EndpointKind`), determining where the payload sits in a response. */
@Suppress("ConstPropertyName")
object EKind {
    const val general = "general"
    const val item = "item"
    const val list = "list"
}

/** One endpoint's catalog rendering. [inputSchema]/[outputSchema] are JSON-schema object maps with `$ref`s intact. */
class EndpointInfo(
    val path: String,
    val method: String,
    val kind: String,
    val namespace: String,
    val description: String?,
    val inputSchema: Map<String, Any?>,
    val outputSchema: Map<String, Any?>,
)

/** Wrapper names for parsing an endpoint's anonymous input/output schema against the shared `$defs`. */
private const val anonInputName = "#endpointInput"

/**
 * The whole `/schema/endpoints` result: the rendered endpoints and the shared `$defs` their `$ref`s resolve
 * in. The `$defs` are parsed once (lazily) into resolved [SchType]s — refs bound, self-referential types like
 * `TreeNode` forming a safe object-graph cycle rather than infinite expansion.
 */
class Catalog(val endpoints: List<EndpointInfo>, val defs: Map<String, Any?>) {
    /** The shared `$defs` parsed once into resolved kernel [SchType]s (refs bound). */
    val defTypes: Map<String, SchType> by lazy { parseSchemaTypes(defs) }

    /** Parses an endpoint's flat input schema into a resolved object [SchType]; its field `$ref`s resolve
     *  against [defTypes]. This is the type the form renders and the validator checks against. */
    fun inputType(ep: EndpointInfo): SchType =
        parseSchemaTypes(mapOf(anonInputName to ep.inputSchema), defTypes).getValue(anonInputName)

    /**
     * The resolved [SchType] of an endpoint's response payload — the type under the envelope's `results`/`item`
     * key, or the array element type under `items` — looked up in the shared [defTypes]. Resolving from
     * [defTypes] (rather than the envelope's parsed array item type) sidesteps the kernel parser not binding a
     * `$ref` that sits directly on an array's `items` node. Null when the output has no typed `$ref` payload.
     */
    fun payloadType(ep: EndpointInfo): SchType? {
        val props = ep.outputSchema[SCH.properties].asMap()
        val refNode = when (ep.kind) {
            EKind.list -> props[EK.items].asMap()[SCH.items]
            EKind.item -> props[EK.item]
            else -> props[EK.results]
        }
        val ref = refNode.asMap()[SCH.dRef] as? String ?: return null
        return defTypes[refTargetName(ref)]
    }
}

/** Null-tolerant view of a parsed-schema node as a `Map`, via the kernel's `toJsonMap` coercion. */
private fun Any?.asMap(): Map<String, Any?> = if (this is Map<*, *>) toJsonMap() else emptyMap()
