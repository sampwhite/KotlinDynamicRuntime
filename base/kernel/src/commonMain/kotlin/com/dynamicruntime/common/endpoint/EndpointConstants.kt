package com.dynamicruntime.common.endpoint

// The endpoint wire contract that BOTH sides share: the response-envelope keys, the endpoint-catalog keys, and
// the HTTP-method / endpoint-kind enums. In the KMP kernel (package preserved, so `base:common` references
// them unchanged) so the Kotlin/JS frontend reads a response by the same constants the backend writes it with,
// instead of re-hardcoding a mirror (issue #70 kernel dedup). The endpoint *builders* and `KdrEndpoint` stay
// backend-side; only the shared vocabulary lives here.

/**
 * The path a client's own copy of [path] is served under: `/gedra/formDoc/create` becomes
 * `/gedra/acme/formDoc/create` (issue #387).
 *
 * **The client goes after the section, not before it**, and that is forced rather than chosen. A path's
 * *section* is its first segment, it is what the access gate and the catalog filter both key on, and
 * `RequestService` refuses to boot on a section with no declared rules. Putting the client first would make
 * every client its own section, each needing rules written for it, and a new client would fail the boot until
 * somebody remembered. After the section, `gedra` stays the section and a new client inherits the access
 * policy by construction.
 *
 * In the kernel so that a frontend builds the path with the same rule the backend serves it under, rather
 * than concatenating one at each call site -- which is the form that rots when the shape changes.
 */
fun clientPath(path: String, client: String): String {
    val trimmed = path.removePrefix("/")
    val section = trimmed.substringBefore('/')
    val rest = trimmed.substringAfter('/', "")
    return if (rest.isEmpty()) "/$section/$client" else "/$section/$client/$rest"
}

/** Attribute keys for an endpoint's `EndpointInfo` rendering in the schema catalog, plus the catalog wrapper. */
@Suppress("ConstPropertyName")
object EI {
    const val path = "path"
    const val method = "method"
    const val kind = "kind"
    const val namespace = "namespace"

    /**
     * Catalog filter query param: whose surface to show, for a caller who may look at more than their own
     * (issues #387, #394). In the kernel beside [namespace] so a frontend selector can drive
     * `/schema/endpoints?client=<id>` from the same string the backend reads it under, rather than
     * hardcoding it -- the same reason the gedra paths moved in #393.
     */
    const val client = "client"
    const val description = "description"

    /**
     * Whether the endpoint is part of the published API (issue #433) -- what we document externally and take
     * support calls on. **Advertisement, not access**: an endpoint's absence from the published set protects
     * nothing, since the section gate is what refuses a caller.
     */
    const val publicApi = "publicApi"

    /** Free-form tags for slicing the catalog (issue #433). No runtime effect; navigation only. */
    const val tags = "tags"
    const val inputSchema = "inputSchema"
    const val outputSchema = "outputSchema"

    /** The `/schema/endpoints` response's list of rendered endpoints (alongside the shared `$defs`). */
    const val endpoints = "endpoints"

    /**
     * Whether this caller may slice the catalog at all (issue #489) -- true when they are env-authed. A caller
     * who is not is served **only** the [publicApi] endpoints and offered no filters, so the frontend reads
     * this to decide whether to draw the filter controls at all. It gates *display*; the restriction itself is
     * enforced server-side, so a frontend ignoring this still sees only the public set.
     */
    const val filtersAvailable = "filtersAvailable"
}

/**
 * The tag names this codebase applies to its own endpoints (issue #489), so a declaration and a test spell them
 * once. `tags` is an **open** set with no runtime effect -- a client or a later endpoint may coin its own -- so
 * these are the shared few, not an enum: the vocabulary a reader slices the catalog by.
 *
 * `publicApi` is deliberately **not** here: it is a boolean axis of its own ([EI.publicApi]), not a tag, because
 * it is the one the env-auth restriction keys on and a boolean says "published or not" without a spelling to
 * mistype.
 */
@Suppress("ConstPropertyName")
object ETAG {
    /** Operations and introspection -- health, system info, node identity, cache state. Not for an app to call. */
    const val internal = "internal"

    /** Consumed by a frontend widget-group rather than by an integrator -- the UI-config and auth-flow endpoints. */
    const val frontend = "frontend"
}

/**
 * The HTTP methods an endpoint may use. A closed, stable set, so an enum fits.
 *
 * [DELETE] carries its input as **query parameters**, like a [GET] rather than a [POST]: DELETE bodies are
 * legal but poorly handled by intermediaries, so nothing here sends one. `PATCH` is deliberately absent --
 * the gedra patch endpoint is a POST because it targets an arbitrary set of rows rather than the resource the
 * URI names, and PATCH advertises body formats (RFC 6902, RFC 7386) this design does not use.
 */
enum class HttpMethod { GET, POST, PUT, DELETE }

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

    // A content hash (CRC32, hex) of the result payload alone -- the value under `results`/`item`/`items`, never
    // the volatile envelope siblings (`duration`, `requestUri`). It changes iff that content changes, so a
    // client can re-fetch an inexpensive config freely and act only when the hash moves (issue #113/#114). The same
    // mechanism as a content file's `buildId` (`ContentResources.buildId`), applied to a response payload.
    const val contentHash = "contentHash"

    // A content hash (CRC32, hex) of the served web-app bundle, or empty when this backend serves no bundle
    // (issue #134). Deployment-global, so it is the same on every response. The frontend compares it against the
    // hash injected into its own bootstrap (`window.kdrCfg.webAppHash`, the same key): a divergence means a
    // newer app has been deployed than the one this tab is running, and the tab offers a (non-forced) reload.
    const val webAppHash = "webAppHash"

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
    //                -- true means a designed, user-facing copy with sanitized params, which the frontend may
    //                show (or Markdown-render) freely; false means a raw/internal message, to show cautiously
    //                (error-highlighted, plain, or a cryptic stand-in in prod). Always present on an error.
    //  - `extraData` an area-specific bag (e.g., a parser's offset/line/lineCol), nested so it can never shadow
    //                a protocol field; absent when empty.
    const val status = "status"
    const val errorCode = "errorCode"
    const val errorMessage = "errorMessage"
    const val errorFromFragment = "errorFromFragment"
    const val extraData = "extraData"

    /**
     * The `errorCode` an **edge server** returns when a request carried no environment session, and the
     * `extraData` key naming where to sign in (issue #419).
     *
     * Here rather than in the edge's own module because it is a contract between a server and a browser, and
     * the browser's half is the web app -- which is served by the *application*, cannot depend on the edge,
     * and does not otherwise know an edge exists. Which is also why the address has to travel: only the edge
     * knows where its sign-in page is.
     *
     * The code is what distinguishes this from the application's own 401s. Those mean "log in to this app";
     * this one means "the perimeter no longer knows you", and only the second is answered by leaving the app.
     * The URL is nested under [extraData] like every other area-specific field, so it cannot shadow a
     * protocol one.
     */
    const val envAuthRequiredCode = "envAuthRequired"

    /**
     * Where to sign in, under [extraData], when [errorCode] is [envAuthRequiredCode].
     *
     * Deliberately **bare** -- no return path attached. The edge builds one for a navigation, where the
     * request it refused *is* the page the caller wanted; on a background call the refused request is an API
     * path, and returning somebody there after sign-in lands them on raw JSON. Only the browser knows which
     * page it is on, so the browser supplies [envAuthNextParam].
     */
    const val envAuthLoginUrl = "envAuthLoginUrl"

    /**
     * Query parameter naming where to go after an environment sign-in.
     *
     * Shared because both ends write it: an edge when it redirects a navigation, and the web app when it
     * follows [envAuthLoginUrl] -- the only party that can see the fragment it needs to include.
     */
    const val envAuthNextParam = "next"

    // Schema validation failures, under `extraData` when a request fails validation (issue #198). A list of
    // objects rather than a sentence, so a client can say which field was wrong instead of parsing English:
    //
    //  - `path`        where in the request data, in the validator's own spelling (`input.contacts[1].handle`)
    //  - `code`        which kind of failure -- a `SchFailCode` name
    //  - `message`     the framework's wording, which describes the wire problem
    //  - `userMessage` the schema's own wording from its `g-errors` block; absent when it declares none
    //  - `options`     for an invalid choice, the values that would have been valid; absent otherwise
    //
    // The exception's `cause` is deliberately not carried: it is the one part most likely to hold internal
    // detail, and redacting it belongs with issue #97 rather than here.
    const val failures = "failures"
    const val failurePath = "path"
    const val failureCode = "code"
    const val failureMessage = "message"
    const val failureUserMessage = "userMessage"
    const val failureOptions = "options"

    // Input, list endpoints.
    const val limit = "limit"

    /** Input, list endpoints: how many items to skip before the page (paging). Defaults to 0. */
    const val offset = "offset"

    // Off-contract keys (underscore-prefixed): allowed regardless of additionalProperties, kept in data.
    const val debug = "_debug" // request: comma-separated debug tags -> KdrCxt.debug
    const val meta = "_meta" // response: handler-injected extra structure (KdrRequest.responseMeta)
}

/**
 * Client-supplied request identity (issue #105). The frontend attaches both to every call so the backend can
 * act on them:
 *  - **appId** selects content — the application, plus the locale suffix an anonymous visitor's backend cannot
 *    know (see `Http.appId`). Consumed once the backend honors it (fragment resolution, a later phase).
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
