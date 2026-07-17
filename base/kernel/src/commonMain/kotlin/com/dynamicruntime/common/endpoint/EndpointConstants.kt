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

    // Error envelope (a non-2xx response). Four fields, each a different job:
    //  - `status`    the HTTP-style code (an Int), for transport and retry. Was named `errorCode`.
    //  - `errorCode` the *logical* code (a String), the thing a frontend branches on -- how to present a parse
    //                error, how to follow up a failed purchase. Promoted here from the exception's extraData
    //                (see `KdrException.errorCodeKey`, the same key one layer down); absent when there is none.
    //  - `errorMessage` the human sentence to show.
    //  - `errorFromFragment` (Bool) whether `errorMessage` was rendered from a Markdown fragment (issue #108)
    //                -- true means designed, user-facing copy with sanitized params, which the frontend may
    //                show (or Markdown-render) freely; false means a raw/internal message, to show cautiously
    //                (error-highlighted, plain, or a cryptic stand-in in prod). Always present on an error.
    //  - `extraData` an area-specific bag (e.g., a parser's offset/line/lineCol), nested so it can never shadow
    //                a protocol field; absent when empty.
    const val status = "status"
    const val errorCode = "errorCode"
    const val errorMessage = "errorMessage"
    const val errorFromFragment = "errorFromFragment"
    const val extraData = "extraData"

    // Input, list endpoints.
    const val limit = "limit"

    // Off-contract keys (underscore-prefixed): allowed regardless of additionalProperties, kept in data.
    const val debug = "_debug" // request: comma-separated debug tags -> KdrCxt.debug
    const val meta = "_meta" // response: handler-injected extra structure (KdrRequest.responseMeta)
}

/**
 * Client-supplied request identity (issue #105). The frontend attaches both to every call so the backend can
 * act on them:
 *  - **appId** selects content — the application, plus the locale suffix an anonymous visitor's backend cannot
 *    know (see `Http.appId`). Consumed once the backend honours it (fragment resolution, a later phase).
 *  - **traceId** correlates a call across tiers: the frontend mints it, the backend stamps it onto the
 *    request context so every log line for that call carries it, and a troubleshooter greps one id from
 *    browser to server.
 *
 * Each travels two ways: a **header** (what the frontend sends) or an off-contract **`_` param** (the
 * alternate schema validation already lets through, handy for a link or a bare `curl`). Shared here, in the
 * kernel, so the frontend sets exactly the names the backend reads. Header casing is canonical; HTTP header
 * lookup is case-insensitive regardless.
 */
@Suppress("ConstPropertyName")
object RID {
    const val appIdHeader = "X-Kdr-App-Id"
    const val traceIdHeader = "X-Kdr-Trace-Id"
    const val appIdParam = "_appId"
    const val traceIdParam = "_traceId"
}

/** Default cap on the number of items a list endpoint returns. */
const val defaultListLimit = 100
