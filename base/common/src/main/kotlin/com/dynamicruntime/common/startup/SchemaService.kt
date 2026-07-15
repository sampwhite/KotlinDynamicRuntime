package com.dynamicruntime.common.startup

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.collectDefs
import com.dynamicruntime.common.endpoint.defaultListLimit
import com.dynamicruntime.common.endpoint.renderEndpoint
import com.dynamicruntime.common.endpoint.resolveEndpointInputType
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.LogSchema
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.addDays
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.toJsonMap

/**
 * Startup service that compiles the collected schema into the read-only
 * [KdrSchemaStore]. In [onCreate] it captures the [SchemaCollector] (which
 * components have populated, and which other startup services may still add to);
 * in [checkInit] it parses the merged `$defs` into resolved [com.dynamicruntime.common.schema.SchType]s,
 * indexes the endpoints by path, and publishes the built store into the instance
 * config so [KdrSchemaStore.get] can find it.
 *
 * This is an initial port of dn's `DnSchemaService`: it does the collected-to-built
 * aggregation, without dn's builder-keyword resolution pass, because kd2's endpoint and
 * type schema are already realized eagerly by the `Sch*` builders.
 */
class SchemaService : ServiceInitializer {
    override val serviceName: String = SchemaService.serviceName

    @KdrPrivate
    var collector: SchemaCollector? = null

    @KdrPrivate
    var isInit: Boolean = false

    /** The compiled schema store; empty until [checkInit] runs. */
    var schemaStore: KdrSchemaStore = KdrSchemaStore()
        private set

    override fun onCreate(cxt: KdrCxt) {
        collector = SchemaCollector.get(cxt)
            ?: throw KdrException("Schema collector was not created for SchemaService.")
    }

    override fun checkInit(cxt: KdrCxt) {
        if (isInit) {
            return
        }
        val collected = collector ?: throw KdrException("SchemaService.checkInit ran before onCreate.")
        LogSchema.debug(cxt, "Creating read only schema store from the collected schema.")

        val types = parseSchemaTypes(collected.defs)
        val endpoints = collected.endpoints.associateBy { it.collationKey }
        val tables = collected.tables.associateBy { it.tableName }
        // The raw defs ride along so the /schema/endpoints catalog can serve types with their `$ref`s intact.
        val store = KdrSchemaStore(types, endpoints, tables, collected.defs)

        // Fail fast: input resolution is deferred to request time (it needs the compiled types), so resolve
        // every endpoint's input once here. A missing referenced input type surfaces at boot instead of on
        // the first request. (Explicit-field parse errors already thrown from parseSchemaTypes.)
        for (endpoint in endpoints.values) {
            resolveEndpointInputType(endpoint, types)
                ?: throw KdrException(
                    "Endpoint '${endpoint.collationKey}' references an unknown input type '${endpoint.inputTypeRef}'.",
                )
        }

        schemaStore = store
        cxt.instanceConfig.put(KdrSchemaStore.key, store)
        isInit = true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "SchemaService"

        // Choice-value option sets, defined once and reused by both the schema and the sample data generator.
        private val categoryOptions = listOf("alpha", "beta", "gamma")
        private val tagOptions = listOf("red", "green", "blue")

        /** Retrieves the schema service from the instance config, or null if absent. */
        fun get(cxt: KdrCxt): SchemaService? = cxt.instanceConfig.get(serviceName) as? SchemaService

        /**
         * The schema-service endpoints (contributed by the `common` component): endpoint introspection
         * and a sample endpoint that exercises the schema surface. Follows the convention of defining a
         * service's endpoints with the service.
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "schema") {
            // ---- GET /schema/endpoints: introspect the registered endpoints ----
            // A general (not list) endpoint: its result carries two entries -- the endpoint renderings and a
            // shared `$defs` bag the renderings' `$ref`s bind to -- which the `items` envelope has no room for.
            type("EndpointQuery") {
                type = SCT.kObject
                property(EI.namespace, "Only endpoints declared in this namespace.")
                property(EI.method, "Only endpoints using this HTTP method (GET/POST/PUT).")
                property(SS.pathRegex, "Only endpoints whose path matches this regular expression.")
                property(EP.limit, "The maximum number of endpoints to return.") {
                    type = SCT.integer
                    default = defaultListLimit
                }
            }
            // The EndpointInfo type (one endpoint's rendering) is owned by KdrEndpoint.
            KdrEndpoint.defineInfoType(this)
            type("EndpointCatalog") {
                type = SCT.kObject
                property(EI.endpoints, "The matching endpoints, each rendered with its `\$ref`s intact.", required = true) {
                    type = SCT.array
                    items { ref(KdrEndpoint.infoTypeName) }
                }
                // The `$defs` bag: every type referenced by the endpoints, keyed by qualified name. A generic
                // object (no declared properties) so any type body is accepted.
                property(SCH.dDefs, "Every type referenced by the endpoints, keyed by name, for the client to resolve.", required = true) {
                    type = SCT.kObject
                }
            }
            generalEndpoint(
                "/schema/endpoints",
                "Lists the registered endpoints (with input/output schema and a shared \$defs), filtered by namespace, HTTP method, or a path regex.",
                HttpMethod.GET,
                outputRef = "EndpointCatalog",
                inputRef = "EndpointQuery",
            ) { c, request -> endpointCatalog(c, request) }

            // ---- GET /schema/endpoint: look up a single endpoint by exact method + path ----
            // Returns the SAME shape as /schema/endpoints (the reused EndpointCatalog): a one-element (or
            // empty, when unmatched) `endpoints` list plus the shared `$defs`, so a client consumes either
            // feed with identical code.
            type("EndpointLookup") {
                type = SCT.kObject
                property(EI.method, "The endpoint's HTTP method.", required = true) {
                    HttpMethod.entries.forEach { option(it.name) }
                }
                property(EI.path, "The exact endpoint path, as registered (e.g. `/schema/complex`).", required = true)
            }
            generalEndpoint(
                "/schema/endpoint",
                "Looks up a single registered endpoint by exact HTTP method and path, in the same shape as " +
                    "/schema/endpoints (a one-element `endpoints` list plus the shared \$defs).",
                HttpMethod.GET,
                outputRef = "EndpointCatalog",
                inputRef = "EndpointLookup",
            ) { c, request -> endpointLookup(c, request) }

            // ---- POST /schema/sample: a sample list endpoint exercising the schema surface ----
            type("SampleFilter") {
                type = SCT.kObject
                property(SS.minCount, "Minimum id for an item to be included.") { type = SCT.integer }
                property(SS.activeOnly, "Whether to include only active items.") { type = SCT.boolean }
            }
            type("SampleQuery") {
                type = SCT.kObject
                property(SS.filter, "Nested filter criteria.") { ref("SampleFilter") }
                property(SS.categories, "Category choices to include.") {
                    type = SCT.array
                    items { type = SCT.string; categoryOptions.forEach { option(it) } }
                }
                property(SS.sinceDate, "Only items on or after this day.") { dayOnlyDate() }
            }
            type("SampleDetails") {
                type = SCT.kObject
                property(SS.score, "A numeric score.", required = true) { type = SCT.number }
                property(SS.tags, "Choice tags for the item.", required = true) {
                    type = SCT.array
                    items { type = SCT.string; tagOptions.forEach { option(it) } }
                }
                property(SS.rank, "Integer rank.", required = true) { type = SCT.integer }
            }
            type("SampleItem") {
                type = SCT.kObject
                property(SS.id, "Item id.", required = true) { type = SCT.integer }
                property(SS.createdOn, "When the item was created.", required = true) { dateTime() }
                property(SS.active, "Whether the item is active.", required = true) { type = SCT.boolean }
                property(SS.details, "Nested detail object.", required = true) { ref("SampleDetails") }
            }
            listEndpoint(
                "/schema/sample",
                "A sample list endpoint exercising nested request/response schema, choices, dates, booleans, and integers.",
                outputRef = "SampleItem",
                method = HttpMethod.POST,
                inputRef = "SampleQuery",
            ) { c, request -> sampleItems(c, request) }

            // ---- PUT /schema/complex: a deliberately complex input that exercises, all at once, deep `$ref`
            //      validation (a chain of referenced object types), recursive validation + a cyclic `$defs`
            //      walk (a self-referential TreeNode), coercion, options, and dates. A list endpoint, so it
            //      also carries a `limit`. As the schema layer gains allOf / anyOf / if / else, extend
            //      `ComplexInput` (and the test) to cover them here -- this is the "everything at once" case.
            type("GeoPoint") {
                type = SCT.kObject
                property(CX.lat, "Latitude.", required = true) { type = SCT.number }
                property(CX.lon, "Longitude.", required = true) { type = SCT.number }
            }
            type("Address") {
                type = SCT.kObject
                property(CX.street, "Street address.", required = true)
                property(CX.zip, "Postal code.")
                property(CX.location, "Geographic location.") { ref("GeoPoint") } // nested $ref -> validated deep
            }
            type("TreeNode") {
                type = SCT.kObject
                property(CX.label, "Node label.", required = true)
                property(CX.weight, "Node weight.") { type = SCT.number }
                // Self-reference: exercises recursive validation AND the cyclic $defs walk (a $ref back to TreeNode).
                property(CX.parent, "Parent node; recursive and optional.") { ref("TreeNode") }
            }
            type("ComplexInput") {
                type = SCT.kObject
                property(CX.name, "Item name.", required = true)
                property(CX.priority, "Priority level.", required = true) {
                    option(CX.low); option(CX.medium); option(CX.high)
                }
                property(CX.createdOn, "Creation timestamp.", required = true) { dateTime() }
                property(CX.score, "Numeric score (numeric types coerce from a string by default).", required = true) {
                    type = SCT.number
                }
                // Booleans do not coerce by default; opt in with allowCoerce so "true"/"yes" strings are accepted.
                property(CX.active, "Active flag (string-coercible: allowCoerce is on).") {
                    type = SCT.boolean
                    allowCoerce = true
                }
                property(CX.aliases, "Alternate names.") { type = SCT.array; items { type = SCT.string } }
                property(CX.address, "Primary address.", required = true) { ref("Address") } // -> GeoPoint
                property(CX.tree, "A node hierarchy; its parent chain expands into the result items.") { ref("TreeNode") }
            }
            type("ComplexQuery") {
                type = SCT.kObject
                property(CX.input, "The complex object to process.", required = true) { ref("ComplexInput") }
                property(CX.mode, "Processing mode.") { option(CX.strict); option(CX.lenient) }
                property(CX.sinceDate, "Only consider items on or after this day.") { dayOnlyDate() }
            }
            type("ComplexResult") {
                type = SCT.kObject
                property(CX.name, "The node (or item) name.", required = true)
                property(CX.depth, "Index of this node in the parent chain (0 = the given node).", required = true) {
                    type = SCT.integer
                }
                property(CX.hasLocation, "Whether the address carried a geo location.", required = true) {
                    type = SCT.boolean
                }
                property(CX.priority, "Echoed priority.", required = true)
                property(CX.mode, "Echoed processing mode.", required = true)
            }
            listEndpoint(
                "/schema/complex",
                "Processes a deeply nested, recursive object, expanding its tree's parent chain into result " +
                    "items (capped by `limit`). Exercises deep \$ref validation and the recursive \$defs population.",
                outputRef = "ComplexResult",
                method = HttpMethod.PUT,
                inputRef = "ComplexQuery",
                // hasMore / hasNumAvailable are intentionally omitted: the executor does not populate paging
                // metadata yet (a TODO in buildEnvelope), so requiring them would fail output validation.
            ) { c, request -> complexItems(c, request) }
        }

        /**
         * Handler for `/schema/endpoints`: filter/sort/limit the registered endpoints, render each with its
         * `$ref`s intact, and pair the renderings with a shared `$defs` bag resolving every referenced type.
         * The client resolves the `$ref`s itself, so a type shared by many endpoints is returned once.
         */
        @KdrPrivate
        fun endpointCatalog(cxt: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
            // Input is flat: the filter fields and `limit` are top-level.
            val namespace = request[EI.namespace] as? String
            val method = (request[EI.method] as? String)?.uppercase()
            val pathRegex = (request[SS.pathRegex] as? String)?.let { Regex(it) }
            val limit = (request[EP.limit] as? Number)?.toInt() ?: defaultListLimit
            val schema = cxt.getSchema()
            val renderings = schema.endpoints.values
                .filter { ep ->
                    (namespace == null || ep.namespace == namespace) &&
                        (method == null || ep.method.name == method) &&
                        (pathRegex == null || pathRegex.containsMatchIn(ep.path))
                }
                // collationKey is "path:method", so this sorts by path then method (the same path may be
                // registered under two HTTP methods).
                .sortedBy { it.collationKey }
                .take(limit)
                .map { renderEndpoint(it, schema.defs) }
            return linkedMapOf(EI.endpoints to renderings, SCH.dDefs to collectDefs(renderings, schema.defs))
        }

        /**
         * Handler for `/schema/endpoint`: look up the single endpoint with the given method + path (by its
         * `path:method` collation key) and return it in the same shape as [endpointCatalog] -- a one-element
         * (or empty, when unmatched) `endpoints` list plus the shared `$defs`.
         */
        @KdrPrivate
        fun endpointLookup(cxt: KdrCxt, request: Map<String, Any?>): Map<String, Any?> {
            val method = (request[EI.method] as? String)?.uppercase()
            val path = request[EI.path] as? String
            val schema = cxt.getSchema()
            val endpoint = schema.endpoints["$path:$method"]
            val renderings = listOfNotNull(endpoint).map { renderEndpoint(it, schema.defs) }
            return linkedMapOf(EI.endpoints to renderings, SCH.dDefs to collectDefs(renderings, schema.defs))
        }

        /** Handler for `/schema/sample`: generate an interesting, schema-conforming set of items. */
        @KdrPrivate
        fun sampleItems(cxt: KdrCxt, request: Map<String, Any?>): List<Map<String, Any?>> {
            // Input is flat (issue #40): the query fields are top-level, alongside the framework `limit`.
            val query = request
            // Off-contract `$` annotations (e.g., $note) must have been dropped during coercion before we see them.
            if (query.keys.any { it.startsWith("$") }) {
                throw KdrException("An off-contract '$' annotation key leaked into the endpoint input.")
            }

            // With _debug=explainInput, echo the evaluated request parameters back under _meta.
            if (cxt.debug?.contains(SS.explainInput) == true) {
                cxt.request?.responseMeta?.put(SS.paramsEvaluated, query)
            }

            val filter = (query[SS.filter] as? Map<*, *>)?.toJsonMap() ?: emptyMap()
            val minCount = (filter[SS.minCount] as? Number)?.toInt() ?: 0
            val now = cxt.now()
            return (1..15)
                .filter { it >= minCount }
                .map { i ->
                    linkedMapOf<String, Any?>(
                        SS.id to i,
                        SS.createdOn to now.addDays(-i).formatDate(),
                        SS.active to (i % 2 == 0),
                        SS.details to linkedMapOf<String, Any?>(
                            SS.score to i * 1.5,
                            SS.tags to listOf(tagOptions[i % tagOptions.size], tagOptions[(i + 1) % tagOptions.size]),
                            SS.rank to i,
                        ),
                    )
                }
        }

        /**
         * Handler for `/schema/complex`: walk the validated input's recursive parent chain and echo one result
         * per node (root first). That the chain is navigable proves the deeply nested, recursive input passed
         * validation and coercion; `limit` then truncates the expanded items.
         */
        @KdrPrivate
        fun complexItems(@Suppress("UNUSED_PARAMETER") cxt: KdrCxt, request: Map<String, Any?>): List<Map<String, Any?>> {
            val input = (request[CX.input] as? Map<*, *>) ?: return emptyList()
            val itemName = input[CX.name] as? String ?: ""
            val priority = input[CX.priority] as? String ?: ""
            val mode = request[CX.mode] as? String ?: CX.strict
            val hasLocation = (input[CX.address] as? Map<*, *>)?.get(CX.location) != null

            // Follow the recursive `parent` chain. Finite data already terminates it; the bound is belt-and-braces.
            val chain = ArrayList<Map<*, *>>()
            var node = input[CX.tree] as? Map<*, *>
            while (node != null && chain.size < 1000) {
                chain.add(node)
                node = node[CX.parent] as? Map<*, *>
            }
            if (chain.isEmpty()) {
                return listOf(complexResult(itemName, 0, hasLocation, priority, mode))
            }
            return chain.mapIndexed { depth, n ->
                complexResult(n[CX.label] as? String ?: itemName, depth, hasLocation, priority, mode)
            }
        }

        private fun complexResult(name: String, depth: Int, hasLocation: Boolean, priority: String, mode: String): Map<String, Any?> =
            linkedMapOf(CX.name to name, CX.depth to depth, CX.hasLocation to hasLocation, CX.priority to priority, CX.mode to mode)
    }
}

/**
 * Field-name keys owned by the schema-service endpoints. The endpoint-dump attribute names live on
 * [com.dynamicruntime.common.endpoint.EI] (with [KdrEndpoint]); the `namespace`/`method` query filters
 * reuse those, so only `pathRegex` and the sample-endpoint fields are defined here.
 */
@Suppress("ConstPropertyName")
object SS {
    // Endpoint introspection: the path-regex query filter. The `endpoints` result key is now the shared
    // kernel EI.endpoints (the `$defs` result key is the JSON Schema keyword itself, SCH.dDefs).
    const val pathRegex = "pathRegex"

    // Debug behavior: the debug tag that triggers echoing input under _meta, and the key it is echoed under.
    const val explainInput = "explainInput"
    const val paramsEvaluated = "paramsEvaluated"

    // Sample endpoint request/response fields.
    const val filter = "filter"
    const val minCount = "minCount"
    const val activeOnly = "activeOnly"
    const val categories = "categories"
    const val sinceDate = "sinceDate"
    const val id = "id"
    const val createdOn = "createdOn"
    const val active = "active"
    const val details = "details"
    const val score = "score"
    const val tags = "tags"
    const val rank = "rank"
}

/**
 * Field-name keys and choice values for the `/schema/complex` endpoint (the "exercise everything" case). Kept
 * separate from [SS] because this is a self-contained showcase of nested / recursive schema; grows alongside
 * `ComplexInput` as the schema layer gains more constructs.
 */
@Suppress("ConstPropertyName")
object CX {
    // Query (input) fields.
    const val input = "input"
    const val mode = "mode"
    const val sinceDate = "sinceDate"

    // ComplexInput fields.
    const val name = "name"
    const val priority = "priority"
    const val createdOn = "createdOn"
    const val score = "score"
    const val active = "active"
    const val aliases = "aliases"
    const val address = "address"
    const val tree = "tree"

    // Address / GeoPoint fields.
    const val street = "street"
    const val zip = "zip"
    const val location = "location"
    const val lat = "lat"
    const val lon = "lon"

    // TreeNode fields (self-referential via `parent`).
    const val label = "label"
    const val weight = "weight"
    const val parent = "parent"

    // Result fields.
    const val depth = "depth"
    const val hasLocation = "hasLocation"

    // Choice values: priority levels and processing modes.
    const val low = "low"
    const val medium = "medium"
    const val high = "high"
    const val strict = "strict"
    const val lenient = "lenient"
}
