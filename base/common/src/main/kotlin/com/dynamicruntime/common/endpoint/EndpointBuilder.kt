package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchOptionsProvider
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.qualifyTypeName

// EI / HttpMethod / EndpointKind / EP / defaultListLimit moved to the kernel (endpoint/EndpointConstants.kt),
// so the frontend shares this wire vocabulary; still referenced as `com.dynamicruntime.common.endpoint.*`.

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
 * What a **list** handler returns when it pages the answer itself: the page's [items], the total [numAvailable]
 * across the whole scoped set, and whether more remain past this page ([hasMore]) (issue #408).
 *
 * A list handler may instead return a plain `List`, which the executor caps at `limit` and reports only
 * `numItems` for -- the ordinary, unpaged case. Returning this is how a handler that applied its own
 * `limit`/`offset` fills the `hasMore` / `numAvailable` fields its `listEndpoint(hasMore = true, ...)`
 * declared; the executor takes [items] as the page **without re-capping** (the handler already did), and sets
 * the two paging fields from here. An endpoint returning this must declare both fields, and one returning a
 * plain list must declare neither -- the response validator holds it to exactly what its output type says.
 */
class ListPage(val items: List<Any?>, val numAvailable: Int, val hasMore: Boolean)

/**
 * One declared input field of an endpoint -- the explicit-fields alternative to referencing a named input
 * type. Carries a [name], whether it is [required], and its JSON-schema node [schema] (type / description /
 * format / options / `$ref` / ... exactly as the `property` DSL builds it). Collected by [InputFieldsBuilder],
 * flattened into the endpoint's input type by [resolveEndpointInputType] (for validation) and into its
 * rendered input schema by [buildEndpointInputSchema] (for the `/schema/endpoints` catalog).
 */
class EndpointField(val name: String, val required: Boolean, val schema: Map<String, Any?>)

/**
 * A fully realized endpoint: its [path] and [method], the [namespace] it was declared in, a required
 * [description] (endpoints are our documented API, so a description is mandatory), its input declaration,
 * the output schema envelope (a JSON schema map built immediately, with `$ref`s into the module's `$defs`),
 * and the [handler] to run. The [namespace] comes from the enclosing `schemaModule` when built via the DSL,
 * or is supplied explicitly when a `KdrEndpoint` is constructed directly.
 *
 * Input is declared one of two mutually exclusive ways: [inputTypeRef], the fully qualified name of a named
 * input type whose top-level properties become the input fields; or [inputFields], an explicit list of
 * fields. Either may be null (both nulls mean the endpoint takes no parameters). Unlike the output envelope,
 * the input type is NOT realized here -- a type ref cannot be flattened until its target is bound -- so it is
 * resolved on demand by [resolveEndpointInputType] against the compiled types (always closed to undeclared
 * properties). For a `list` endpoint with [includeLimit], resolution appends the `limit` field.
 */
class KdrEndpoint(
    val path: String,
    val method: HttpMethod,
    val kind: EndpointKind,
    val namespace: String,
    val description: String,
    /** Explicit input fields, or null when the input is a [inputTypeRef] or absent. Mutually exclusive with it. */
    val inputFields: List<EndpointField>?,
    /** Fully qualified name of the input type, or null when the input is [inputFields] or absent. */
    val inputTypeRef: String?,
    /** Whether input resolution appends the `limit` field (list endpoints that did not opt out). */
    val includeLimit: Boolean,
    val outputSchema: Map<String, Any?>,
    /**
     * A **test-only** endpoint (issue #125): removed from the compiled endpoint store -- so neither dispatchable
     * nor listed in the catalog -- unless the deployment allows test endpoints (see
     * [com.dynamicruntime.common.startup.SchemaService]). Kept before [handler] so the handler stays last
     * (trailing-lambda construction) and this defaults off.
     */
    val forTestingOnly: Boolean = false,
    val handler: KdrEndpointHandler,
    /**
     * The client this endpoint belongs to, or null for the shared surface (issue #387).
     *
     * What it decides is where the endpoint's types **resolve**: a client endpoint resolves against that
     * client's schema variant, which is sound precisely because the path names one client -- so the
     * path-keyed type caches stay correct without the published type having to be global.
     *
     * Declared **after** `handler` so that a call ending in a trailing lambda still binds that lambda to the
     * handler, which is how every builder here constructs one.
     */
    val client: String? = null,
    /**
     * Whether this endpoint is part of the **published API** (issue #433): the set we document for people
     * outside the team and take support calls on.
     *
     * **This is not a security control, and must never be used as one.** It decides what the catalog
     * *advertises*, never what may be invoked. Anyone watching a browser talk to this server can find
     * endpoints that are not published and call them, and a penetration tester is handed the full catalog
     * anyway -- so absence from the published set buys no protection whatsoever. What enforces access is the
     * section model ([com.dynamicruntime.common.http.request.RequestService]), which runs on every request
     * regardless of what any catalog says.
     *
     * A consequence worth knowing, because it is what makes this axis cheap: an over-broad `publicApi` cannot
     * expose anything. Publish an admin endpoint by mistake and an anonymous caller still meets the section
     * gate. The failure is a documentation lie, not a breach.
     *
     * Restricted to sections in `RequestService.userSections`, refused at boot. See that check for why.
     */
    val publicApi: Boolean = false,
    /**
     * Free-form tags for slicing the endpoint catalog (issue #433) -- navigation, with no runtime effect at
     * all.
     *
     * **Open vocabulary, deliberately**, unlike the axes that decide something. A typo here yields a slightly
     * wrong filter that somebody notices and fixes; forcing these through a closed set of constants would
     * make people stop adding them, and being cheap to add is their entire value. Cedar reached close to a
     * thousand endpoints, at which point a catalog stops being a list anybody reads and becomes something you
     * query.
     */
    val tags: Set<String> = emptySet(),
    /**
     * Whether this endpoint gets a **per-client copy** at a path naming the client (issue #455) --
     * `/userAdmin/cfacts` alongside `/userAdmin/<client>/cfacts`.
     *
     * `buildClientEndpoints` copies the whole `gedra` section without being asked, because everything there
     * reads or writes one client's data. Its own note says the property is really "what this endpoint answers
     * belongs to a client", which is a fact about the endpoint rather than about its path -- and the cfact
     * registry is the first case where that is true outside `gedra`, since a client's config may add names.
     * So this is that note's flag, declared where the endpoint is.
     *
     * **It says the answer differs, not that the caller is confined.** A gedra copy also refuses a target
     * belonging to another client; there is nothing to refuse in a listing of what a client declared, and the
     * section gate is what decides who may read it either way.
     */
    val clientShaped: Boolean = false,
) {
    init {
        if (inputFields != null && inputTypeRef != null) {
            throw KdrException(
                "Endpoint '$path' declares both explicit inputFields and an inputTypeRef; only one is allowed.",
            )
        }
    }

    /**
     * Derived key uniquely identifying this endpoint as `path:method` (e.g. `/health:GET`). Used both as
     * the registry key (so the same path may be registered under two HTTP methods) and to collate/sort
     * endpoints. It is *not* included in the `/schema/endpoints` catalog rendering.
     */
    val collationKey: String = "$path:${method.name}"

    @Suppress("ConstPropertyName")
    companion object {
        /** Schema type name for one endpoint's rendering in the catalog (the shape of [renderEndpoint]). */
        const val infoTypeName = "EndpointInfo"

        /**
         * Defines the `EndpointInfo` schema type -- the shape of a single endpoint's [renderEndpoint] output --
         * on [builder], naming it [infoTypeName]. Kept with the class so the type and the rendering cannot
         * drift apart. The input/output schemas are generic objects: they carry arbitrary JSON schema (with
         * `$ref`s left intact for the client to resolve), so they are not further constrained here.
         */
        fun defineInfoType(builder: SchModuleBuilder) {
            builder.type(infoTypeName) {
                type = SCT.kObject
                property(EI.path, "The endpoint's request path.", required = true)
                property(EI.method, "The HTTP method.", required = true)
                property(EI.kind, "The endpoint kind (general/item/list/file).", required = true)
                property(EI.namespace, "The namespace the endpoint was declared in.", required = true)
                property(EI.description, "Human description of the endpoint.", required = true)
                property(EI.inputSchema, $$"The endpoint's input JSON schema (with `$ref`s intact).", required = true) {
                    type = SCT.kObject
                }
                property(EI.outputSchema, $$"The endpoint's output JSON schema (with `$ref`s intact).", required = true) {
                    type = SCT.kObject
                }
                property(
                    EI.publicApi,
                    "Whether this endpoint is part of the published API -- documented externally and " +
                        "supported. Advertisement only: it is not an access control.",
                    required = true,
                ) { type = SCT.boolean }
                property(EI.tags, "Free-form tags for slicing the catalog.", required = true) {
                    type = SCT.array
                    items { type = SCT.string }
                }
            }
        }
    }
}

/** The types (`$defs` contents) and endpoints declared together for one namespace. */
class SchModule(
    val defs: Map<String, Any?>,
    val endpoints: List<KdrEndpoint>,
    /** Options providers declared in the same block, keyed by id (issue #413). Usually empty. */
    val optionsProviders: Map<String, SchOptionsProvider> = emptyMap(),
)

/**
 * Collects an endpoint's explicit input fields. [field] mirrors [SchTypeBuilder.property] (a description is
 * mandatory; the type defaults to string unless [build] sets a `type` or a `$ref`), so declaring fields
 * inline reads the same as declaring a named type's properties.
 */
class InputFieldsBuilder(private val cxt: KdrCxtBase, private val namespace: String) {
    val fields: MutableList<EndpointField> = mutableListOf()

    fun field(name: String, description: String, required: Boolean = false, build: SchTypeBuilder.() -> Unit = {}) {
        val sub = SchTypeBuilder(cxt, namespace)
        sub.description = description
        sub.apply(build)
        if (SCH.type !in sub.data && SCH.dRef !in sub.data) {
            sub.type = SCT.string
        }
        fields.add(EndpointField(name, required, sub.data))
    }
}

/**
 * A [SchTypesBuilder] that also declares endpoints, so a namespace's types and endpoints are built in one
 * block. Each endpoint's input/output envelope is realized immediately from the protocol fields plus
 * `$ref`s to the named input/output types.
 */
class SchModuleBuilder(cxt: KdrCxt, namespace: String) : SchTypesBuilder(cxt, namespace) {
    val endpoints: MutableList<KdrEndpoint> = mutableListOf()

    /** Options providers declared in this block, keyed by id (issue #413). */
    val optionsProviders: MutableMap<String, SchOptionsProvider> = LinkedHashMap()

    /**
     * Registers the callback that answers for `optionsSource(`[id]`)`, in the same block as the schema naming
     * it (issue #413).
     *
     * Here rather than in a registration list elsewhere so that the id has one obvious home: an attribute
     * pointing at a provider and the provider itself are read together, and a rename that misses one of them
     * fails the boot rather than emptying a choice list.
     *
     * Named `optionsProvider` and not `optionsSource` on purpose -- the identically named call inside a
     * property block *consumes* an id, and two DSL calls a line apart that differ only in what they take
     * would be a poor thing to read.
     */
    fun optionsProvider(id: String, provider: SchOptionsProvider) {
        optionsProviders[id] = provider
    }

    /**
     * A general endpoint: the result is returned under `results`, always a map object. Input is declared
     * either by [inputRef] (a named type) or [inputFields] (declared inline via the [InputFieldsBuilder]
     * DSL), never both; omit both for a no-parameter endpoint.
     */
    fun generalEndpoint(
        path: String,
        description: String,
        method: HttpMethod,
        outputRef: String,
        inputRef: String? = null,
        inputFields: (InputFieldsBuilder.() -> Unit)? = null,
        forTestingOnly: Boolean = false,
        /** Part of the published API (issue #433). Advertisement, never access -- see [KdrEndpoint.publicApi]. */
        publicApi: Boolean = false,
        /** Free-form tags for slicing the catalog (issue #433); no runtime effect. */
        tags: Set<String> = emptySet(),
        handler: KdrEndpointHandler,
    ) {
        val output = scalarOutput(EP.results, "Result data (a map object) returned by the endpoint.", outputRef)
        val (fields, typeRef) = captureInput(inputRef, inputFields)
        endpoints.add(
            KdrEndpoint(path, method, EndpointKind.general, namespace, description, fields, typeRef, false, output,
                forTestingOnly, handler, publicApi = publicApi, tags = tags),
        )
    }

    /**
     * An endpoint that retrieves a single resource, returned under `item`. Once execution exists, this
     * implies a 404 when the item is not found; the request is effectively a GET. Input is declared by
     * [inputRef] or [inputFields] (never both).
     */
    fun itemEndpoint(
        path: String,
        description: String,
        method: HttpMethod,
        outputRef: String,
        inputRef: String? = null,
        inputFields: (InputFieldsBuilder.() -> Unit)? = null,
        forTestingOnly: Boolean = false,
        /** Part of the published API (issue #433). Advertisement, never access -- see [KdrEndpoint.publicApi]. */
        publicApi: Boolean = false,
        /** Free-form tags for slicing the catalog (issue #433); no runtime effect. */
        tags: Set<String> = emptySet(),
        handler: KdrEndpointHandler,
    ) {
        val output = scalarOutput(EP.item, "The single resource item returned by the endpoint.", outputRef)
        val (fields, typeRef) = captureInput(inputRef, inputFields)
        endpoints.add(
            KdrEndpoint(path, method, EndpointKind.item, namespace, description, fields, typeRef, false, output,
                forTestingOnly, handler, publicApi = publicApi, tags = tags),
        )
    }

    /**
     * An endpoint whose payload is a list under `items`. The caller's input is a flat set of top-level
     * fields (declared by [inputRef] or [inputFields], never both), to which resolution appends a `limit`
     * field unless [noLimit] is set. Method-agnostic: a POST/PUT may also be list-style.
     */
    fun listEndpoint(
        path: String,
        description: String,
        outputRef: String,
        method: HttpMethod = HttpMethod.GET, // list endpoints are rarely anything but GET
        inputRef: String? = null,
        inputFields: (InputFieldsBuilder.() -> Unit)? = null,
        hasMore: Boolean = false,
        hasNumAvailable: Boolean = false,
        noLimit: Boolean = false,
        forTestingOnly: Boolean = false,
        /** Part of the published API (issue #433). Advertisement, never access -- see [KdrEndpoint.publicApi]. */
        publicApi: Boolean = false,
        /** Free-form tags for slicing the catalog (issue #433); no runtime effect. */
        tags: Set<String> = emptySet(),
        /**
         * Gets a per-client copy at a path naming the client (issue #455); see [KdrEndpoint.clientShaped].
         *
         * On this builder alone because a listing is what has wanted it so far. It belongs on the others the
         * day one of them answers differently per client -- adding it to all four now would be three
         * parameters nothing passes, which read as options rather than as the record of a decision.
         */
        clientShaped: Boolean = false,
        handler: KdrEndpointHandler,
    ) {
        val output = listOutput(outputRef, hasMore, hasNumAvailable)
        val (fields, typeRef) = captureInput(inputRef, inputFields)
        endpoints.add(
            KdrEndpoint(path, method, EndpointKind.list, namespace, description, fields, typeRef, !noLimit, output,
                forTestingOnly, handler, publicApi = publicApi, tags = tags, clientShaped = clientShaped),
        )
    }

    /**
     * An endpoint whose response **is a file** rather than a JSON envelope. Its handler returns a
     * [com.dynamicruntime.common.http.request.ContentData], which the executor sends as the body; there is no
     * `results`/`item`/`items` wrapper, because there is nowhere in a file to put one.
     *
     * The output schema is OpenAPI's declaration of a downloaded body — `{"type": "string", "format":
     * "binary"}` (see [com.dynamicruntime.common.schema.SFMT.binary]) — so the catalog says "this returns a file",
     * and the display engine offers a download rather than trying to parse the bytes as JSON.
     *
     * Input is ordinary: [inputRef] or [inputFields] name the file to fetch, and travel in the query string
     * for a GET like any other endpoint's input.
     */
    fun fileDownloadEndpoint(
        path: String,
        description: String,
        method: HttpMethod = HttpMethod.GET, // fetching a file is a GET unless there is a reason otherwise
        inputRef: String? = null,
        inputFields: (InputFieldsBuilder.() -> Unit)? = null,
        forTestingOnly: Boolean = false,
        /** Part of the published API (issue #433). Advertisement, never access -- see [KdrEndpoint.publicApi]. */
        publicApi: Boolean = false,
        /** Free-form tags for slicing the catalog (issue #433); no runtime effect. */
        tags: Set<String> = emptySet(),
        handler: KdrEndpointHandler,
    ) {
        val output = SchTypeBuilder(cxt, namespace).also {
            it.binaryContent()
            it.description = "The file's content, returned as the response body."
        }.data
        val (fields, typeRef) = captureInput(inputRef, inputFields)
        endpoints.add(
            KdrEndpoint(path, method, EndpointKind.file, namespace, description, fields, typeRef, false, output,
                forTestingOnly, handler, publicApi = publicApi, tags = tags),
        )
    }

    /**
     * An endpoint that **receives a file**: the request arrives as `multipart/form-data`, and the input field
     * declared [com.dynamicruntime.common.schema.SFMT.binary] carries a
     * [com.dynamicruntime.common.http.request.ContentData] rather than a
     * string. Its *response* is ordinary JSON under `results` — an upload's answer is metadata (an id, a size),
     * not a file — so only the request half is special.
     *
     * It is still [EndpointKind.file], because `kind` tells a client how to deal with an endpoint and the
     * answer here is the same as a download's: this one speaks files, so send multipart and offer a file
     * picker.
     *
     * Declare the file field with `binaryContent()` in [inputFields] (or on the named [inputRef] type):
     * ```
     * fileUploadEndpoint("/file/upload", "Upload a file.", outputRef = "FileInfo",
     *     inputFields = { field("file", "The file to upload", required = true) { binaryContent() } }) { ... }
     * ```
     */
    fun fileUploadEndpoint(
        path: String,
        description: String,
        outputRef: String,
        method: HttpMethod = HttpMethod.POST, // a body-carrying request; POST unless the caller says otherwise
        inputRef: String? = null,
        inputFields: (InputFieldsBuilder.() -> Unit)? = null,
        forTestingOnly: Boolean = false,
        /** Part of the published API (issue #433). Advertisement, never access -- see [KdrEndpoint.publicApi]. */
        publicApi: Boolean = false,
        /** Free-form tags for slicing the catalog (issue #433); no runtime effect. */
        tags: Set<String> = emptySet(),
        handler: KdrEndpointHandler,
    ) {
        val output = scalarOutput(EP.results, "Result data (a map object) describing the uploaded file.", outputRef)
        val (fields, typeRef) = captureInput(inputRef, inputFields)
        endpoints.add(
            KdrEndpoint(path, method, EndpointKind.file, namespace, description, fields, typeRef, false, output,
                forTestingOnly, handler, publicApi = publicApi, tags = tags),
        )
    }

    /**
     * Captures an endpoint's input declaration: at most one of a named-type [inputRef] (kept as a fully
     * qualified inputTypeRef or an inline [inputFields] block collected into [EndpointField]s). Fails
     * fast if both are given, so the mutually exclusive contract is enforced at construction.
     */
    @KdrPrivate
    fun captureInput(
        inputRef: String?,
        inputFields: (InputFieldsBuilder.() -> Unit)?,
    ): Pair<List<EndpointField>?, String?> {
        if (inputRef != null && inputFields != null) {
            throw KdrException("Endpoint input may be declared with inputRef or inputFields, not both.")
        }
        val fields = inputFields?.let { InputFieldsBuilder(cxt, namespace).apply(it).fields }
        val typeRef = inputRef?.let { qualifyTypeName(it, namespace) }
        return fields to typeRef
    }

    // --- envelope construction (output realized immediately; input resolved on demand) ------------------

    @KdrPrivate
    fun newObject(): SchTypeBuilder = SchTypeBuilder(cxt, namespace).also { it.type = SCT.kObject }

    /** Adds the metadata fields present on every endpoint's output. */
    @KdrPrivate
    fun SchTypeBuilder.addProtocolMeta() {
        property(EP.requestUri, "The request URI that made this request.", required = true)
        property(EP.duration, "The time taken to perform the request, in milliseconds.", required = true) {
            type = SCT.number
        }
        property(EP.contentHash, "A content hash of the result payload; changes only when that content changes.",
            required = true)
        property(EP.webAppHash, "Content hash of the served web-app bundle (empty when none); the frontend " +
            "compares it against its own to detect a new deployment.", required = true) {
            // One of the fields whose empty value IS the value: "" means "no bundle is being served", which a
            // deployment without a frontend (and every unit test) reports on every response. Left on the
            // default, an empty hash would read as absent and then fail its own required check.
            emptyIsAbsent = false
        }
    }

    /** Output for general/item endpoints: protocol metadata plus the result under [resultKey]. */
    @KdrPrivate
    fun scalarOutput(resultKey: String, resultDesc: String, outputRef: String): Map<String, Any?> {
        val b = newObject()
        b.addProtocolMeta()
        b.property(resultKey, resultDesc, required = true) { ref(outputRef) }
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
    return SchModule(b.defs, b.endpoints, b.optionsProviders)
}

/**
 * Resolves an endpoint's declared input into the flat consumption [SchType] the dispatcher validates
 * against (and the portal renders), computed against the compiled [types] (a store's `types` map). The two
 * declaration forms converge here: an [KdrEndpoint.inputTypeRef] contributes the referenced type's top-level
 * properties; explicit [KdrEndpoint.inputFields] are parsed into properties (so any `$ref` inside a field
 * resolves against [types]); no declaration contributes nothing. To that base, a `limit` field is appended
 * for a list endpoint with [KdrEndpoint.includeLimit], and the result is always closed to undeclared
 * properties (`additionalProperties = false`) -- off-contract `_`/`$` keys stay exempt (see the validator).
 *
 * The referenced type in [types] is never mutated: a fresh object type is built wrapping its (shared,
 * already-resolved) property objects. Returns null if a referenced input type is absent (a fail-fast signal
 * for a bad `inputRef`).
 */
fun resolveEndpointInputType(endpoint: KdrEndpoint, types: Map<String, SchType>): SchType? {
    val base: SchType = when {
        endpoint.inputTypeRef != null -> types[endpoint.inputTypeRef] ?: return null
        endpoint.inputFields != null -> parseInputFieldsType(endpoint, types) ?: return null
        else -> emptyInputType
    }
    val props = LinkedHashMap(base.properties)
    if (endpoint.includeLimit) {
        props[EP.limit] = limitInputProperty
    }
    return inputObjectType("${endpoint.path}#input", props, base.required)
}

/** Parses an endpoint's explicit [KdrEndpoint.inputFields] into an object [SchType] (resolving nested `$ref`s
 *  against [types]), so its properties/required can seed the flat input type. */
@KdrPrivate
fun parseInputFieldsType(endpoint: KdrEndpoint, types: Map<String, SchType>): SchType? {
    val fields = endpoint.inputFields ?: return emptyInputType
    val properties = LinkedHashMap<String, Any?>()
    val required = ArrayList<String>()
    for (field in fields) {
        properties[field.name] = field.schema
        if (field.required) required.add(field.name)
    }
    val schema = linkedMapOf<String, Any?>(SCH.type to SCT.kObject, SCH.properties to properties)
    if (required.isNotEmpty()) schema[SCH.required] = required
    val name = "${endpoint.path}#fields"
    return parseSchemaTypes(mapOf(name to schema), types)[name]
}

/** Builds the closed object [SchType] for an endpoint's resolved input, wrapping the given [properties]. */
@KdrPrivate
fun inputObjectType(name: String, properties: Map<String, SchProperty>, required: Set<String>): SchType =
    SchType(
        name = name,
        jsonType = SCT.kObject,
        allowCoerce = false,
        emptyIsAbsent = false, // the object default: an endpoint's input envelope is never "absent"
        format = null,
        title = null,
        description = null,
        properties = properties,
        required = required,
        additionalProperties = false,
        itemType = null,
        options = null,
        openOptions = false,
        // An envelope is not a branch of anything and admits no single value, so neither construct applies.
        constValue = null,
        derived = false,
        variants = null,
        condition = null,
        default = null,
        // An endpoint's input envelope is machinery, not a field anyone fills in, so there is nothing here
        // for custom error copy to be about, and no bound on how many fields it may carry.
        errorMessages = emptyMap(),
        minBound = null,
        maxBound = null,
    )

/** The empty (no-parameters) input base: a closed object with no properties. */
@KdrPrivate
val emptyInputType: SchType = inputObjectType("#emptyInput", emptyMap(), emptySet())

/** The `limit` field appended to list-endpoint input during resolution (a single shared, immutable property). */
@KdrPrivate
val limitInputProperty: SchProperty =
    SchProperty(EP.limit, "The maximum number of items to return.", refName = null).also {
        it.valueType = SchType(
            name = null,
            jsonType = SCT.integer,
            allowCoerce = true,
            // The scalar default, and it matters on the wire: `?limit=` is an empty string, which used to be a
            // 400 and now reads as no limit given.
            emptyIsAbsent = true,
            format = null,
            title = null,
            description = "The maximum number of items to return.",
            properties = emptyMap(),
            required = emptySet(),
            additionalProperties = false,
            itemType = null,
            options = null,
            openOptions = false,
            constValue = null,
            derived = false,
            variants = null,
            condition = null,
            default = defaultListLimit,
            errorMessages = emptyMap(),
            // Deliberately unbounded rather than `minimum = 1`: a bound here would start rejecting `?limit=0`,
            // which is a behavior change no caller has asked for. Worth revisiting on its own.
            minBound = null,
            maxBound = null,
        )
    }

// --- /schema/endpoints catalog: render endpoints with $refs intact, plus a shared $defs bag -------------

/**
 * Renders one endpoint for the `/schema/endpoints` catalog: its identity plus input and output JSON schema
 * with `$ref`s left intact (bound to the catalog's `$defs`, which the client resolves). This is the fuller
 * counterpart to the pre-catalog attribute dump; unlike [resolveEndpointInputType] (which resolves refs for
 * server-side validation), the rendering preserves refs so shared types are returned once, in `$defs`.
 */
fun renderEndpoint(endpoint: KdrEndpoint, defs: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
    EI.path to endpoint.path,
    EI.method to endpoint.method.name,
    EI.kind to endpoint.kind.name,
    EI.namespace to endpoint.namespace,
    EI.description to endpoint.description,
    EI.inputSchema to buildEndpointInputSchema(endpoint, defs),
    EI.outputSchema to endpoint.outputSchema,
    EI.publicApi to endpoint.publicApi,
    // Sorted so a catalog rendering is stable between boots; a Set has no order of its own to promise.
    EI.tags to endpoint.tags.sorted(),
)

/**
 * Builds an endpoint's flat input schema as a JSON map with `$ref`s left intact: the declared fields (for an
 * [KdrEndpoint.inputFields] endpoint) or the referenced type's top-level property nodes copied verbatim (for
 * an [KdrEndpoint.inputTypeRef] endpoint), plus an appended `limit` for a list endpoint, closed to undeclared
 * properties. A field whose value is a `$ref` keeps it; the ref binds to the catalog's `$defs`. The referenced
 * type's own nodes are read-only (shared into the result), never mutated.
 */
fun buildEndpointInputSchema(endpoint: KdrEndpoint, defs: Map<String, Any?>): Map<String, Any?> {
    val properties = LinkedHashMap<String, Any?>()
    val required = ArrayList<String>()
    when {
        endpoint.inputTypeRef != null -> {
            val type = defs[endpoint.inputTypeRef] as? Map<*, *>
            (type?.get(SCH.properties) as? Map<*, *>)?.forEach { (k, v) -> if (k is String) properties[k] = v }
            (type?.get(SCH.required) as? List<*>)?.forEach { if (it is String) required.add(it) }
        }
        endpoint.inputFields != null -> {
            for (field in endpoint.inputFields) {
                properties[field.name] = field.schema
                if (field.required) required.add(field.name)
            }
        }
    }
    // The input projection (issue #254): a `g-derived` field is produced by something other than the caller,
    // so the schema they are shown does not offer it -- and `required` goes with it, or a field they may not
    // send becomes a field they are told they must.
    //
    // Top level only, and the limit is structural rather than an omission: a derived field *inside* a shared
    // type lives in the catalog's one `$defs` bag, which the input and the output both resolve against, so
    // removing it there would take it out of the response schema too. Closing that needs a separately
    // projected defs bag, which belongs with the export contract rather than here. Until then the keyword
    // still travels, and every surface that reads schema honors it -- which is what the form does.
    val derivedFields = properties.filterValues { (it as? Map<*, *>)?.get(SCH.derived).let { d -> d == true || d is Map<*, *> } }.keys
    properties.keys.removeAll(derivedFields)
    required.removeAll(derivedFields)
    if (endpoint.includeLimit) {
        properties[EP.limit] = linkedMapOf(
            SCH.description to "The maximum number of items to return.",
            SCH.type to SCT.integer,
            SCH.default to defaultListLimit,
        )
    }
    val schema = linkedMapOf<String, Any?>(SCH.type to SCT.kObject, SCH.additionalProperties to false)
    if (properties.isNotEmpty()) schema[SCH.properties] = properties
    if (required.isNotEmpty()) schema[SCH.required] = required
    return schema
}
