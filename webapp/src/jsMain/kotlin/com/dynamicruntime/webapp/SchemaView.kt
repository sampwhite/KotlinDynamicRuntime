package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes

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
    const val results = "results"
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
}
