package com.dynamicruntime.common.endpoint

// The endpoint wire contract that BOTH sides share: the response-envelope keys, the endpoint-catalog keys, and
// the HTTP-method / endpoint-kind enums. In the KMP kernel (package preserved, so `base:common` references
// them unchanged) so the Kotlin/JS frontend reads a response by the same constants the backend writes it with,
// instead of re-hardcoding a mirror (issue #70 kernel dedup). The endpoint *builders* and `KdrEndpoint` stay
// backend-side; only the shared vocabulary lives here.

/** Attribute keys for an endpoint's `EndpointInfo` rendering in the schema catalog, plus the catalog wrapper. */
@Suppress("ConstPropertyName")
object EI {
    const val path = "path"
    const val method = "method"
    const val kind = "kind"
    const val namespace = "namespace"
    const val description = "description"
    const val inputSchema = "inputSchema"
    const val outputSchema = "outputSchema"

    /** The `/schema/endpoints` response's list of rendered endpoints (alongside the shared `$defs`). */
    const val endpoints = "endpoints"
}

/** The HTTP methods an endpoint may use. A closed, stable set, so an enum fits. */
enum class HttpMethod { GET, POST, PUT }

/**
 * The shape of an endpoint's result, which determines how the executor wraps it in the protocol envelope: a
 * [general] result goes under `results`, an [item] under `item`, and a [list] under `items` (with count/paging
 * metadata).
 *
 * [file] is the odd one and deliberately so: it marks an endpoint that trades in **file content** rather than
 * JSON. A download's response *is* the file — no envelope to put it in — and an upload's request arrives as
 * `multipart/form-data` rather than a JSON body. Both directions are one kind because `kind` exists to tell a
 * client how to deal with an endpoint, and both need the same answer: this one speaks files, not JSON. An
 * upload that returns metadata still returns it under `results`, as a [general] endpoint would.
 */
@Suppress("EnumEntryName")
enum class EndpointKind { general, item, list, file }

/**
 * Protocol field keys injected into endpoint input/output envelopes. The response/list keys keep the prior-art
 * `dn` spellings; `results`/`item`/`request` are new -- they lift the "real" data out from alongside the
 * protocol metadata (the change users and frontend parsers preferred).
 */
@Suppress("ConstPropertyName", "unused")
object EP {
    // Output metadata (present on every endpoint).
    const val requestUri = "requestUri"
    const val duration = "duration"

    // Output, list endpoints.
    const val numItems = "numItems"
    const val hasMore = "hasMore"
    const val numAvailable = "numAvailable"
    const val items = "items"

    // Output result wrappers, by endpoint kind.
    const val results = "results" // general endpoints: always a map object
    const val item = "item" // single-resource endpoints

    // Error envelope (a non-2xx response): the HTTP-style code and the human message.
    const val errorCode = "errorCode"
    const val errorMessage = "errorMessage"

    // Input, list endpoints.
    const val limit = "limit"

    // Off-contract keys (underscore-prefixed): allowed regardless of additionalProperties, kept in data.
    const val debug = "_debug" // request: comma-separated debug tags -> KdrCxt.debug
    const val meta = "_meta" // response: handler-injected extra structure (KdrRequest.responseMeta)
}

/** Default cap on the number of items a list endpoint returns. */
const val defaultListLimit = 100
