package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder

/** The HTTP methods an endpoint may use. A closed, stable set, so an enum fits. */
enum class HttpMethod { GET, POST, PUT }

/**
 * Protocol field keys injected into endpoint input/output envelopes. The response/list keys keep the
 * prior-art `dn` spellings; `results`/`item`/`request` are new — they lift the "real" data out from
 * alongside the protocol metadata (the change users and frontend parsers preferred).
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

    // Input, list endpoints.
    const val request = "request" // the caller's request, nested so limit/offset stay siblings
    const val limit = "limit"
}

/** Default cap on the number of items a list endpoint returns. */
const val defaultListLimit = 100

/**
 * The code executed by an endpoint. Given the acting [KdrCxt] (whose `request` also carries the original
 * request data) and the schema-validated request map, it returns the core result — the `results` map,
 * the `item`, or the `items` list — which the framework later wraps in the protocol envelope.
 *
 * Execution is not built yet; this fixes the shape so the builders can capture the handler now. There is
 * no code indirection: the handler is a plain lambda passed to the builder.
 */
typealias KdrEndpointHandler = (cxt: KdrCxt, request: Map<String, Any?>) -> Any?

/**
 * A fully realized endpoint: its [path] and [method], the input and output schema envelopes (JSON schema
 * maps built immediately, with `$ref`s into the module's `$defs`), and the [handler] to run. There is no
 * deferred construction pass — the envelope is complete the moment the builder returns.
 */
class KdrEndpoint(
    val path: String,
    val method: HttpMethod,
    val inputSchema: Map<String, Any?>,
    val outputSchema: Map<String, Any?>,
    val handler: KdrEndpointHandler,
)

/** The types (`$defs` contents) and endpoints declared together for one namespace. */
class SchModule(val defs: Map<String, Any?>, val endpoints: List<KdrEndpoint>)

/**
 * A [SchTypesBuilder] that also declares endpoints, so a namespace's types and endpoints are built in one
 * block. Each endpoint's input/output envelope is realized immediately from the protocol fields plus
 * `$ref`s to the named input/output types.
 */
class SchModuleBuilder(cxt: KdrCxt, namespace: String) : SchTypesBuilder(cxt, namespace) {
    val endpoints: MutableList<KdrEndpoint> = mutableListOf()

    /** A general endpoint: the result is returned under `results`, always a map object. */
    fun generalEndpoint(
        path: String,
        method: HttpMethod,
        outputRef: String,
        inputRef: String? = null,
        handler: KdrEndpointHandler,
    ) {
        val output = scalarOutput(EP.results, "Result data (a map object) returned by the endpoint.", outputRef)
        endpoints.add(KdrEndpoint(path, method, buildInput(inputRef), output, handler))
    }

    /**
     * An endpoint that retrieves a single resource, returned under `item`. Once execution exists this
     * implies a 404 when the item is not found; the request is effectively a GET.
     */
    fun itemEndpoint(
        path: String,
        method: HttpMethod,
        outputRef: String,
        inputRef: String? = null,
        handler: KdrEndpointHandler,
    ) {
        val output = scalarOutput(EP.item, "The single resource item returned by the endpoint.", outputRef)
        endpoints.add(KdrEndpoint(path, method, buildInput(inputRef), output, handler))
    }

    /**
     * An endpoint whose payload is a list under `items`, with the caller's request nested under `request`
     * and a `limit` sibling (so nothing is merged into the request type). Method-agnostic: a POST/PUT may
     * also be list-style.
     */
    fun listEndpoint(
        path: String,
        outputRef: String,
        method: HttpMethod = HttpMethod.GET, // list endpoints are rarely anything but GET
        inputRef: String? = null,
        hasMore: Boolean = false,
        hasNumAvailable: Boolean = false,
        noLimit: Boolean = false,
        handler: KdrEndpointHandler,
    ) {
        val input = listInput(inputRef, noLimit)
        val output = listOutput(outputRef, hasMore, hasNumAvailable)
        endpoints.add(KdrEndpoint(path, method, input, output, handler))
    }

    // --- envelope construction (all realized immediately) -------------------

    @KdrPrivate
    fun newObject(): SchTypeBuilder = SchTypeBuilder(cxt, namespace).also { it.type = SCT.kObject }

    /** Adds the metadata fields present on every endpoint's output. */
    @KdrPrivate
    fun SchTypeBuilder.addProtocolMeta() {
        property(EP.requestUri, "The request URI that made this request.", required = true)
        property(EP.duration, "The time taken to perform the request, in milliseconds.", required = true) {
            type = SCT.number
        }
    }

    /** Input for general/item endpoints: the caller's input type as-is (or an empty object if none). */
    @KdrPrivate
    fun buildInput(inputRef: String?): Map<String, Any?> =
        if (inputRef == null) newObject().data else SchTypeBuilder(cxt, namespace).also { it.ref(inputRef) }.data

    /** Output for general/item endpoints: protocol metadata plus the result under [resultKey]. */
    @KdrPrivate
    fun scalarOutput(resultKey: String, resultDesc: String, outputRef: String): Map<String, Any?> {
        val b = newObject()
        b.addProtocolMeta()
        b.property(resultKey, resultDesc, required = true) { ref(outputRef) }
        return b.data
    }

    /** Input for list endpoints: the caller's request under `request`, plus `limit` unless suppressed. */
    @KdrPrivate
    fun listInput(inputRef: String?, noLimit: Boolean): Map<String, Any?> {
        val b = newObject()
        if (inputRef != null) {
            b.property(EP.request, "The request parameters for the query.", required = true) { ref(inputRef) }
        }
        if (!noLimit) {
            b.property(EP.limit, "The maximum number of items to return.") {
                type = SCT.integer
                default = defaultListLimit
            }
        }
        return b.data
    }

    /** Output envelope for list endpoints: count, metadata, optional paging fields, then the `items` list. */
    @KdrPrivate
    fun listOutput(outputRef: String, hasMore: Boolean, hasNumAvailable: Boolean): Map<String, Any?> {
        val b = newObject()
        b.property(EP.numItems, "Number of items returned.", required = true) { type = SCT.integer }
        b.addProtocolMeta()
        if (hasMore) {
            b.property(EP.hasMore, "Whether there are more items that could be returned.", required = true) {
                type = SCT.boolean
            }
        }
        if (hasNumAvailable) {
            b.property(EP.numAvailable, "The total number of items available to be returned.", required = true) {
                type = SCT.integer
            }
        }
        b.property(EP.items, "Items returned by the endpoint.", required = true) {
            type = SCT.array
            items { ref(outputRef) }
        }
        return b.data
    }
}

/** Builds a namespace's types and endpoints together, realizing every endpoint schema immediately. */
fun schemaModule(cxt: KdrCxt, namespace: String, build: SchModuleBuilder.() -> Unit): SchModule {
    val b = SchModuleBuilder(cxt, namespace).apply(build)
    return SchModule(b.defs, b.endpoints)
}
