package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.collectDefs
import com.dynamicruntime.common.schema.isBinaryFormat
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.refTargetName
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * Client-side model of the endpoint catalog the display engine renders. It consumes the catalog form emitted
 * by the runtime's `/schema/endpoints` endpoint — a list of endpoint renderings plus a shared `$defs` bag —
 * and parses the schema with the SHARED multiplatform kernel (`:base:kernel`): the same `SchType` model,
 * `parseSchemaTypes`, and (in [SchemaForm] / [EndpointCatalog]) the same validator the JVM backend runs. So
 * the frontend and backend agree on schema semantics by construction, with no reimplementation.
 *
 * The wire vocabulary is shared from the kernel too: JSON keywords (`SCH`/`SCT`/`SFMT`), the endpoint-catalog
 * keys (`EI`), the envelope keys (`EP`), and the `EndpointKind` enum -- no frontend mirror.
 */

/** One endpoint's catalog rendering. [inputSchema]/[outputSchema] are JSON-schema object maps with `$ref`s intact. */
class EndpointInfo(
    val path: String,
    val method: String,
    val kind: String,
    val namespace: String,
    val description: String?,
    val inputSchema: Map<String, Any?>,
    val outputSchema: Map<String, Any?>,
    /** Whether the endpoint is part of the published API (issue #489); a filterable axis in the catalog. */
    val publicApi: Boolean = false,
    /** The endpoint's catalog tags (issue #489), e.g. `internal`/`frontend`; the multi-select filter's options. */
    val tags: List<String> = emptyList(),
) {
    /** Stable identity for a `method:path` endpoint (used as a table row key). */
    val key: String get() = "$method:$path"
}

/** Wrapper names for parsing an endpoint's anonymous input/output schema against the shared `$defs`. */
private const val anonInputName = "#endpointInput"
private const val anonOutputName = "#endpointOutput"

/**
 * The whole `/schema/endpoints` result: the rendered endpoints and the shared `$defs` their `$ref`s resolve
 * in. The `$defs` are parsed once (lazily) into resolved [SchType]s — refs bound, self-referential types like
 * `TreeNode` forming a safe object-graph cycle rather than infinite expansion.
 */
class Catalog(
    val endpoints: List<EndpointInfo>,
    val defs: Map<String, Any?>,
    /**
     * Whether this caller may slice the catalog (issue #489): true when env-authed. When false the backend has
     * already limited the response to the published endpoints, so the page offers no filters -- there is nothing
     * to toggle, and re-offering a publicApi filter that is forced on would only mislead.
     */
    val filtersAvailable: Boolean = true,
) {
    /** The shared `$defs` parsed once into resolved kernel [SchType]s (refs bound). */
    val defTypes: Map<String, SchType> by lazy { parseSchemaTypes(defs) }

    /** Parses an endpoint's flat input schema into a resolved object [SchType]; its field `$ref`s resolve
     *  against [defTypes]. This is the type the form renders and the validator checks against. */
    fun inputType(ep: EndpointInfo): SchType =
        parseSchemaTypes(mapOf(anonInputName to ep.inputSchema), defTypes).getValue(anonInputName)

    /** Parses an endpoint's output envelope schema into a resolved object [SchType] (its `$ref`s — including
     *  a list endpoint's `items` element ref — resolve against [defTypes]). Rendered by the output-schema view. */
    fun outputType(ep: EndpointInfo): SchType =
        parseSchemaTypes(mapOf(anonOutputName to ep.outputSchema), defTypes).getValue(anonOutputName)

    /**
     * True when this endpoint's response **is a file** rather than a JSON envelope: its output schema is
     * OpenAPI's `{"type": "string", "format": "binary"}` (see the kernel's `SFMT.binary`). Such a response must
     * be downloaded rather than parsed — reading bytes as JSON would fail, and reading them as text would
     * corrupt them.
     */
    fun isFileDownload(ep: EndpointInfo): Boolean =
        ep.kind == EndpointKind.file.name && isBinaryFormat(ep.outputSchema[SCH.format] as? String)

    /**
     * True when this endpoint **takes a file**: one of its input fields is binary content. Such a request goes
     * as `multipart/form-data` rather than JSON, since a JSON body has nowhere to put a file.
     */
    fun hasFileInput(ep: EndpointInfo): Boolean =
        inputType(ep).properties.values.any { isBinaryFormat(it.valueType.format) }

    /**
     * [schema] as a **standalone JSON Schema document**: the node itself, plus a `$defs` bag holding exactly
     * the types it references (issue #262).
     *
     * The `$defs` are what make it standalone. Shown without them, every `$ref` dangles, and what is on screen
     * is a fragment that no tool can resolve and no reader can follow — which is most of the reason a raw view
     * is worth having at all. Reachability comes from the kernel's own [collectDefs], the same walk the backend
     * uses to close the catalog, rather than a second one written here: it already knows the awkward cases,
     * notably that a discriminator's `defaultMapping` is a bare ref string that a keyword search steps past.
     *
     * Deliberately **unmodified** otherwise. No `$schema`, no stripping of `g-` keywords, no synthesized
     * `discriminator.mapping` — those belong to the export contract, which is not decided yet, and a view that
     * quietly rewrote the schema would be worse than none while that is true.
     */
    fun rawDocument(schema: Map<String, Any?>): Map<String, Any?> {
        val reachable = collectDefs(listOf(schema), defs)
        return if (reachable.isEmpty()) schema else schema + (SCH.dDefs to reachable)
    }

    /**
     * The resolved [SchType] of an endpoint's response payload — the type under the envelope's `results`/`item`
     * key, or the array element type under `items` — looked up by name in the shared [defTypes]. Reading the
     * `$ref` name straight from the output schema keeps this independent of the envelope's own parse. Null when
     * the output has no typed `$ref` payload.
     */
    fun payloadType(ep: EndpointInfo): SchType? {
        val props = ep.outputSchema[SCH.properties].toJsonMapOrEmpty()
        val refNode = when (ep.kind) {
            EndpointKind.list.name -> props[EP.items].toJsonMapOrEmpty()[SCH.items]
            EndpointKind.item.name -> props[EP.item]
            else -> props[EP.results]
        }
        val ref = refNode.toJsonMapOrEmpty()[SCH.dRef] as? String ?: return null
        return defTypes[refTargetName(ref)]
    }
}

