package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.qualifyTypeName
import com.dynamicruntime.common.schema.refTargetName

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
    val handler: KdrEndpointHandler,
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
                property(EI.kind, "The endpoint kind (general/item/list).", required = true)
                property(EI.namespace, "The namespace the endpoint was declared in.", required = true)
                property(EI.description, "Human description of the endpoint.", required = true)
                property(EI.inputSchema, $$"The endpoint's input JSON schema (with `$ref`s intact).", required = true) {
                    type = SCT.kObject
                }
                property(EI.outputSchema, $$"The endpoint's output JSON schema (with `$ref`s intact).", required = true) {
                    type = SCT.kObject
                }
            }
        }
    }
}

/** The types (`$defs` contents) and endpoints declared together for one namespace. */
class SchModule(val defs: Map<String, Any?>, val endpoints: List<KdrEndpoint>)

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
        handler: KdrEndpointHandler,
    ) {
        val output = scalarOutput(EP.results, "Result data (a map object) returned by the endpoint.", outputRef)
        val (fields, typeRef) = captureInput(inputRef, inputFields)
        endpoints.add(
            KdrEndpoint(path, method, EndpointKind.general, namespace, description, fields, typeRef, false, output, handler),
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
        handler: KdrEndpointHandler,
    ) {
        val output = scalarOutput(EP.item, "The single resource item returned by the endpoint.", outputRef)
        val (fields, typeRef) = captureInput(inputRef, inputFields)
        endpoints.add(
            KdrEndpoint(path, method, EndpointKind.item, namespace, description, fields, typeRef, false, output, handler),
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
        handler: KdrEndpointHandler,
    ) {
        val output = listOutput(outputRef, hasMore, hasNumAvailable)
        val (fields, typeRef) = captureInput(inputRef, inputFields)
        endpoints.add(
            KdrEndpoint(path, method, EndpointKind.list, namespace, description, fields, typeRef, !noLimit, output, handler),
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
    return SchModule(b.defs, b.endpoints)
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
        format = null,
        description = null,
        properties = properties,
        required = required,
        additionalProperties = false,
        itemType = null,
        options = null,
        default = null,
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
            format = null,
            description = "The maximum number of items to return.",
            properties = emptyMap(),
            required = emptySet(),
            additionalProperties = false,
            itemType = null,
            options = null,
            default = defaultListLimit,
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

/**
 * Builds the closed `$defs` bag for a set of endpoint [renderings]: every type reachable by `$ref` from them,
 * resolved against [allDefs] (the store's raw defs) and keyed by qualified name. The walk inserts each target
 * into the result BEFORE recursing into it, so a self- or mutually-referential type terminates instead of
 * looping. The outcome is closed: every `$ref` in the renderings (or in an included def) resolves within it,
 * and each shared type appears exactly once.
 */
fun collectDefs(renderings: List<Map<String, Any?>>, allDefs: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (rendering in renderings) {
        collectRefsInto(rendering, allDefs, out)
    }
    return out
}

/** Walks [node] for `$ref`s, adding each referenced def from [allDefs] to [out] (insert-before-recurse). */
@KdrPrivate
fun collectRefsInto(node: Any?, allDefs: Map<String, Any?>, out: MutableMap<String, Any?>) {
    when (node) {
        is Map<*, *> -> {
            val ref = node[SCH.dRef]
            if (ref is String) {
                val name = refTargetName(ref)
                if (name !in out) {
                    val target = allDefs[name]
                    if (target != null) {
                        out[name] = target // insert first, so a ref back to this type short-circuits
                        collectRefsInto(target, allDefs, out)
                    }
                }
            }
            for (value in node.values) {
                collectRefsInto(value, allDefs, out)
            }
        }
        is List<*> -> for (element in node) collectRefsInto(element, allDefs, out)
    }
}
