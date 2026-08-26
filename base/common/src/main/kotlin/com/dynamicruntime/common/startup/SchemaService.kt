package com.dynamicruntime.common.startup

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.clientAttribute
import com.dynamicruntime.common.gedra.entryEditUnionDefs
import com.dynamicruntime.common.gedra.entryUnionDefs
import com.dynamicruntime.common.schema.collectDefs
import com.dynamicruntime.common.endpoint.defaultListLimit
import com.dynamicruntime.common.endpoint.renderEndpoint
import com.dynamicruntime.common.endpoint.resolveEndpointInputType
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.sectionOf
import com.dynamicruntime.common.schema.LogSchema
import com.dynamicruntime.common.user.refreshActingRoles
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.SchOptionsProvider
import com.dynamicruntime.common.schema.optionsSourceProblems
import com.dynamicruntime.common.schema.resolveOptionsSources
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

    /**
     * The options providers components contributed, keyed by the id a `g-optionsSource` names (issue #413).
     * Empty until [checkInit] runs, after which it never changes -- a provider is startup wiring, and the
     * per-caller part of it is the callback's own answer rather than the set of callbacks.
     */
    var optionsProviders: Map<String, SchOptionsProvider> = emptyMap()
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

        // Test-only endpoints (issue #125) are dropped from the store -- neither dispatchable nor in the
        // catalog -- unless this is a test instance. And a test instance outside a local/unit environment is a
        // misconfiguration we refuse to run with, so nothing test-only can ever reach a real environment.
        val isTestInstance = cxt.instanceConfig.isTestInstance
        if (isTestInstance) {
            val env = cxt.instanceConfig.env
            if (env != ENV.local && env != ENV.unit) {
                throw KdrException(
                    "Refusing to start: this is a test instance in the '$env' environment. Test-only affordances " +
                        "must NEVER be exposed outside 'local' or 'unit' -- unset ${KdrInstanceConfig.testInstanceEnvVar} " +
                        "and turn off inMemoryOnly, or run in a local/unit environment.",
                )
            }
        }
        val availableEndpoints =
            if (isTestInstance) collected.endpoints else collected.endpoints.filterNot { it.forTestingOnly }

        // Manufactured last and compiled with everything else: the union's branches are not known until
        // every component has contributed, and by the time anything reads the store, it is an ordinary type.
        // Called once with the global scope; per-client views call the same function with a different one.
        val globalTraits = collected.gedraConfigs.traitsFor(GID.globalClient)
        // The kinds that carry validated entries -- see `GU.entryKinds`, which the per-client pass reads too.
        for (kind in GU.entryKinds) {
            collected.defs.putAll(entryUnionDefs(cxt, GCFG.globalNamespace, kind, globalTraits))
            // The edit union beside it, from the same traits (issue #337): one source, two renderings.
            collected.defs.putAll(entryEditUnionDefs(cxt, GCFG.globalNamespace, kind, globalTraits))
        }

        val types = parseSchemaTypes(collected.defs)
        val endpoints = availableEndpoints.associateBy { it.collationKey }
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

        // (The endpoints' access rules are checked in RequestService.checkInit, which owns them and runs in
        // the later service tier -- see the note there on why it cannot live here.)

        schemaStore = store
        cxt.instanceConfig.put(KdrSchemaStore.key, store)
        // Built after the global store, from it (issue #356). A variant is the same document with one
        // client's overlays applied and re-parsed, so it cannot exist until the document is complete.
        val variants = buildClientVariants(cxt, collected, store)
        // Each client that has a variant gets its own copy of the client-shaped endpoints (issue #387). After
        // the variants, because a client with no variant needs none -- its endpoints would be the global ones
        // under a longer name.
        val clientEndpoints = buildClientEndpoints(cxt, availableEndpoints, variants.keys)
        if (clientEndpoints.isEmpty()) {
            clientStores = variants
        } else {
            // Every store carries the **same** endpoint map, the final one. A variant built before the copies
            // existed would hold the map from before them, so anything resolving an endpoint through a
            // variant would not find the very endpoints the variant is for. The types and defs are reused as
            // parsed -- only the endpoint map changes -- so this costs a map merge and no re-parsing.
            val allEndpoints = endpoints + clientEndpoints.associateBy { it.collationKey }
            val withClients = KdrSchemaStore(types, allEndpoints, tables, collected.defs)
            clientStores = variants.mapValues { (_, v) ->
                KdrSchemaStore(v.types, allEndpoints, v.tables, v.defs)
            }
            schemaStore = withClients
            cxt.instanceConfig.put(KdrSchemaStore.key, withClients)
            cxt.schemaStore = withClients
        }
        optionsProviders = collected.optionsProviders.toMap()
        checkOptionsSources(optionsProviders)
        isInit = true
    }

    /**
     * Refuses the boot when a `g-optionsSource` names no registered provider, or sits beside a declared
     * options list (issue #413).
     *
     * Here because this is the one moment holding both halves: the compiled document (global and every
     * client's) and the complete registration set. Deferring either check to request time would turn a typo
     * into an empty choice list on a page -- silent, and possibly only for one client, which is the hardest
     * shape of all to notice.
     *
     * The endpoints carry schema of their own (inline input fields, and the realized output envelope) that
     * lives in no `$defs`, so they are walked as well. A client's endpoint copies share those very objects
     * with the shared endpoint they were copied from, so checking the shared map covers them.
     *
     * Takes [providers] rather than reading [optionsProviders], so a test can ask what this node's real,
     * compiled document would say when checked against a registry that is missing something -- which is the
     * only way to see that the scan reaches the document at all rather than quietly finding nothing.
     */
    @KdrPrivate
    fun checkOptionsSources(providers: Map<String, SchOptionsProvider>) {
        // A set: the same shared def is reachable from the global document and from every client variant
        // that did not alter it, and one problem reported once is the useful form of it.
        val problems = LinkedHashSet<String>()
        fun check(where: String, node: Any?) {
            problems.addAll(optionsSourceProblems(where, node, providers))
        }
        for ((name, body) in schemaStore.defs) check("Type '$name'", body)
        for ((client, store) in clientStores) {
            for ((name, body) in store.defs) check("Type '$name' (client '$client')", body)
        }
        for (endpoint in schemaStore.endpoints.values) {
            endpoint.inputFields?.forEach { check("Endpoint '${endpoint.collationKey}' field '${it.name}'", it.schema) }
            check("Endpoint '${endpoint.collationKey}' output", endpoint.outputSchema)
        }
        if (problems.isNotEmpty()) {
            throw KdrException(
                "Refusing to start: ${problems.size} problem(s) with sourced choice lists.\n" +
                    problems.joinToString("\n"),
            )
        }
    }

    /**
     * The schema each client sees, for the clients that vary something. Absent from this map means the global
     * store; see [storeFor].
     */
    @KdrPrivate
    var clientStores: Map<String, KdrSchemaStore> = emptyMap()

    /**
     * The compiled schema [client] sees: their variant, or the global store when they have none (issue #356).
     *
     * **Not what `KdrCxt.getSchema` returns, and deliberately.** That stays global, because `RequestService`
     * resolves each endpoint's input and output types through it and caches them **keyed by path** -- so an
     * endpoint has to mean one type for every caller or the cache is unsound. `client-definition.md` settles
     * the split as case (a): permissive at the edge, strict where it is stored. The published type stays
     * global; this is what the *storage* side validates against.
     *
     * A client with no variant, and a caller with no client at all, both get the global store -- the same
     * answer, which is what lets `SqlTopicService` read the table catalog at boot before any client exists
     * and anonymous callers be served without a special case.
     */
    fun storeFor(client: String?): KdrSchemaStore {
        val store = schemaStore
        return if (client == null) store else clientStores[client] ?: store
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "SchemaService"

        // Choice-value option sets, defined once and reused by both the schema and the sample data generator.
        private val categoryOptions = listOf("alpha", "beta", "gamma")
        private val tagOptions = listOf("red", "green", "blue")

        /** Suggested query labels -- an **open** list, so anything else is accepted too (issue #418). */
        private val labelOptions = listOf("daily", "weekly", "ad hoc")

        /** The service; throws naming it on a node that does not run it. */
        fun get(cxt: KdrCxt): SchemaService = cxt.instanceConfig.get(serviceName) as? SchemaService
            ?: throw KdrException("The $serviceName is not available on this node.")

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
                property(
                    EI.client,
                    "Show the surface of this client instead of your own -- its endpoints, and its schema. " +
                        "Requires the '" + ROLE.allClients + "' capability unless it names your own client.",
                ) { clientAttribute() }
                property(EI.tags, "Only endpoints carrying this tag.")
                property(
                    EI.publicApi,
                    "Only endpoints in the published API -- the documented, supported set. This filters what " +
                        "is *listed*; it grants nothing, and omitting it lists everything you may already see.",
                ) {
                    type = SCT.boolean
                }
                property(EP.limit, "The maximum number of endpoints to return.") {
                    type = SCT.integer
                    default = defaultListLimit
                }
            }
            // The EndpointInfo type (one endpoint's rendering) is owned by KdrEndpoint.
            KdrEndpoint.defineInfoType(this)
            type("EndpointCatalog") {
                type = SCT.kObject
                property(EI.endpoints, $$"The matching endpoints, each rendered with its `$ref`s intact.", required = true) {
                    type = SCT.array
                    items { ref(KdrEndpoint.infoTypeName) }
                }
                // The `$defs` bag: every type referenced by the endpoints, keyed by qualified name. A generic
                // object (no declared properties), so any type body is accepted.
                property(SCH.dDefs, "Every type referenced by the endpoints, keyed by name, for the client to resolve.", required = true) {
                    type = SCT.kObject
                }
            }
            generalEndpoint(
                "/schema/endpoints",
                $$"Lists the registered endpoints (with input/output schema and a shared $defs), filtered by namespace, HTTP method, or a path regex.",
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
                        $$"/schema/endpoints (a one-element `endpoints` list plus the shared $defs).",
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
                // An **open** choice list (issue #418), beside the closed `categories` above it. The two
                // together are what this endpoint is for: it declares more input than its handler reads
                // precisely so that every construct the schema layer supports has somewhere to be seen and
                // driven, and a list that suggests rather than bounds is now one of them.
                property(SS.label, "A label for this query; the suggestions are not the whole list.") {
                    labelOptions.forEach { option(it) }
                    openOptions()
                }
                // The array counterpart, so both shapes of an open list have somewhere to be driven: this one
                // renders as a multi-select that also accepts a value nobody offered.
                property(SS.labels, "Any number of labels; the suggestions are not the whole list.") {
                    type = SCT.array
                    items { type = SCT.string; labelOptions.forEach { option(it) }; openOptions() }
                }
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
                "/demo/schema/sample",
                "A sample list endpoint exercising nested request/response schema, choices, dates, booleans, and integers.",
                outputRef = "SampleItem",
                method = HttpMethod.POST,
                inputRef = "SampleQuery",
                // The worked example of search tags (issue #433). This endpoint already exists to demonstrate
                // the schema layer, so it demonstrates this too -- rather than tagging a production endpoint,
                // which would be guessing at a vocabulary nobody has designed yet. Tags are an open set with
                // no runtime effect: a wrong one yields a slightly wrong filter, not a wrong decision.
                tags = setOf(SS.demoTag, SS.schemaTag),
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
            type("Contact") {
                type = SCT.kObject
                property(CX.kind, "How to reach this contact.", required = true) {
                    option(CX.email); option(CX.phone)
                }
                property(CX.handle, "The address or number itself.", required = true)
                // A ref inside an array's element type: the parser has to resolve an item ref, and the
                // catalog's defs walk has to reach through an array to find GeoPoint.
                property(CX.location, "Where this contact is based.") { ref("GeoPoint") }
            }
            type("ComplexInput") {
                type = SCT.kObject
                property(CX.name, "Item name.", required = true)
                property(CX.priority, "Priority level.", required = true) {
                    option(CX.low); option(CX.medium); option(CX.high)
                    // Only the one code, so an invalidOption here still falls through to the built-in
                    // wording -- which is the fallback chain being exercised rather than an oversight.
                    errors { missingRequired("Choose how urgent this is.") }
                }
                property(CX.createdOn, "Creation timestamp.", required = true) { dateTime() }
                // Carries `g-errors` (issue #202), so the endpoint form has something real to show: the
                // messages below replace the validator's own wording under the field, while the failure
                // listing keeps both -- that surface documents the wire, and "not a valid number" is what an
                // API caller needs to read.
                property(CX.score, "Numeric score (numeric types coerce from a string by default).", required = true) {
                    type = SCT.number
                    // A bound on the numeric pair (issue #203); `aliases` below carries the array pair, so the
                    // sample exercises two of the four with the same two failure codes.
                    minimum = 0
                    maximum = 100
                    errors {
                        missingRequired("A score is needed before this can be processed.")
                        belowMinimum("A score cannot be negative.")
                        aboveMaximum("A score cannot be more than 100.")
                        default("A score has to be a number, such as 42 or 3.5.")
                    }
                }
                // A boolean coerces from a string by default (issue #439), so "true" / "yes" / "on" are all
                // accepted here without the field saying anything -- which is the construct being shown.
                property(CX.active, "Active flag (string-coercible, as a boolean is by default).") {
                    type = SCT.boolean
                }
                property(CX.aliases, "Alternate names (at most three).") {
                    type = SCT.array; items { type = SCT.string }
                    maxItems = 3
                }
                // The list-of-objects case: an array whose items are a referenced object type, so each element
                // is validated property-wise (and the frontend has to offer a way to add one).
                property(CX.contacts, "Contact methods; a list of objects.") {
                    type = SCT.array; items { ref("Contact") }
                }
                property(CX.address, "Primary address.", required = true) { ref("Address") } // -> GeoPoint
                property(CX.tree, "A node hierarchy; its parent chain expands into the result items.") { ref("TreeNode") }
                // The free-form map case (issue #251): an object declaring no properties, which the parser
                // reads as open (`additionalProperties` defaults to `properties.isEmpty()`), so any keys are
                // accepted and none are described. There is nothing to lay out as fields, so the form edits it
                // as raw JSON. `gedra-entry.md` leans on this shape for an entry's `origin`, which stays
                // free-form until integrations land.
                property(CX.extras, "Free-form JSON object; any keys are accepted, none are declared.") {
                    type = SCT.kObject
                    // The validator's own wording for this is "This must be of type 'object'", which is true
                    // and unhelpful to someone looking at a text box: what they need to hear is that the text
                    // did not parse. The field editor names the line and column; this is what the failure
                    // listing and any API caller see.
                    errors { wrongType("This has to be a JSON object, such as {\"a\": 1}.") }
                }
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
                property(CX.contactCount, "How many contacts the input carried.", required = true) {
                    type = SCT.integer
                }
                property(CX.primaryContact, "The first contact's handle, or empty when there were none.",
                    required = true)
                property(CX.extraKeys, "How many keys the free-form map carried.", required = true) {
                    type = SCT.integer
                }
            }
            listEndpoint(
                "/fixture/schema/complex",
                "Processes a deeply nested, recursive object, expanding its tree's parent chain into result " +
                        $$"items (capped by `limit`). Exercises deep $ref validation and the recursive $defs population.",
                outputRef = "ComplexResult",
                method = HttpMethod.PUT,
                inputRef = "ComplexQuery",
                // A fixture, so it is absent outside a test instance (issue #270). It exists for
                // SchemaComplexEndpointTest to assert against, and a client has no business seeing it -- which
                // is the whole distinction between the `fixture` and `demo` roots. The portal loses its
                // richest form on a non-test node as a result; `/demo/schema/sample` is the groomed one that
                // is meant to be seen.
                forTestingOnly = true,
                // hasMore / hasNumAvailable are intentionally omitted: the executor does not populate paging
                // metadata yet (a TODO in buildEnvelope), so requiring them would fail output validation.
            ) { c, request -> complexItems(c, request) }
        }

        /**
         * Whose surface the catalog answers with, or null for the shared one (issue #387).
         *
         * A caller whose client has endpoints of its own is shown those; naming a **different** client takes
         * [ROLE.allClients], which is the picker this was designed around -- an admin says which client they
         * are looking at rather than having it inferred from what they happened to ask for.
         *
         * Naming your own client is always allowed, since it is what you would have been shown anyway.
         */
        @KdrPrivate
        fun catalogClient(cxt: KdrCxt, requested: String?): String? {
            val own = cxt.userProfile.client
            val client = requested?.trim()?.ifEmpty { null } ?: return own.takeIf { hasEndpoints(cxt, it) }
            if (client != own && !cxt.userProfile.roles.contains(ROLE.allClients)) {
                throw KdrException.mkInput(
                    "Looking at another client's endpoints takes the '${ROLE.allClients}' capability. " +
                        "Yours is '$own'.",
                )
            }
            return client.takeIf { hasEndpoints(cxt, it) }
        }

        /**
         * Whose surface a catalog call answers with (issue #387).
         *
         * Shared by the listing and the single lookup rather than written in each. It decides *whose schema a
         * caller is shown*, and the lookup's own note says it is "filtered exactly as the listing is" -- a
         * property two copies would hold only until somebody changed one. #390 was that exact shape, days
         * ago: two lists of the same thing with a comment saying they had to agree, and they stopped.
         */
        @KdrPrivate
        fun catalogSurface(cxt: KdrCxt, request: Map<String, Any?>): CatalogSurface {
            // A caller sees their own client's endpoints in place of the shared ones they replace; an
            // `allClients` holder may name another. The `$defs` bag comes from the same client's store, so the
            // advertised types are that client's too -- which is the whole reason a client endpoint exists
            // rather than a differently-named shared one.
            val named = (request[EI.client] as? String)?.trim()?.ifEmpty { null }
            val client = catalogClient(cxt, named)
            return CatalogSurface(
                named = named != null,
                client = client,
                // Read optionally, not through the throwing get(): falling back to the shared store is the
                // deliberate answer when there is no compiled client store to consult -- and this runs in the
                // same handler as isVisibleTo below, which tolerates a missing RequestService for the same
                // reason (a store built by hand, outside a running dispatcher).
                schema = client?.let {
                    (cxt.instanceConfig.get(serviceName) as? SchemaService)?.storeFor(it)
                } ?: cxt.getSchema(),
            )
        }

        private fun hasEndpoints(cxt: KdrCxt, client: String): Boolean =
            // Read optionally, not through the throwing get(): "this node advertises no endpoints for that
            // client" is the right answer when no store has been compiled, and keeps catalogClient on the
            // shared surface rather than faulting the catalog.
            (cxt.instanceConfig.get(serviceName) as? SchemaService)?.clientStores?.containsKey(client) == true

        /**
         * Whether an endpoint belongs on the surface being shown.
         *
         * Three answers, and the middle one is the reason there are three:
         *
         * - **No client** -- the shared surface and nothing else. A client's endpoints are not advertised to
         *   somebody who cannot use them.
         * - **A client, [named] explicitly** -- *only* that client's endpoints. Somebody who asks to see
         *   `acme` is looking at what `acme` has of its own, and answering with the whole application beside
         *   it means reaching for a regex to get back to the question. This is the picker's shape.
         * - **A client, inferred from the caller** -- that client's endpoints **in place of** the shared ones
         *   they copy, with the rest of the shared surface intact. `auth`, `profile` and everything with no
         *   client version are not client-shaped, and a client's people need them exactly as anybody does.
         *
         * Replacing rather than adding, in that last case, is what keeps one `$defs` bag honest. Showing both
         * would need `gedra.FormDoc` to mean the global type for one endpoint and the client's for another, in
         * one map, and whichever way it were filled one of the two would be advertised wrongly.
         */
        @KdrPrivate
        fun surfaceOf(
            endpoint: KdrEndpoint,
            forClient: String?,
            named: Boolean,
            all: Collection<KdrEndpoint>,
        ): Boolean {
            if (forClient == null) {
                return endpoint.client == null
            }
            if (named) {
                return endpoint.client == forClient
            }
            if (endpoint.client == forClient) {
                return true
            }
            if (endpoint.client != null) {
                return false
            }
            val replacement = clientPath(endpoint.path, forClient)
            return all.none { it.client == forClient && it.path == replacement }
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
            // Catalog slicing (issue #433). Both narrow what is *listed* and neither grants anything: the
            // access decision below is unchanged, so a filter can only ever hide endpoints the caller could
            // already have seen.
            val tag = (request[EI.tags] as? String)?.trim()?.ifEmpty { null }
            val publishedOnly = request[EI.publicApi] as? Boolean
            val limit = (request[EP.limit] as? Number)?.toInt() ?: defaultListLimit
            refreshCallerRoles(cxt)
            val surface = catalogSurface(cxt, request)
            // One access decision per endpoint, consumed twice: what survives is rendered, what does not is
            // what `explainAccess` reports. Deriving the explanation from a second, independent pass is how an
            // explanation comes to disagree with the filter it claims to describe -- the same drift issue #211
            // closed between the catalog and the gate.
            val (visible, withheld) = surface.schema.endpoints.values
                .filter { surfaceOf(it, surface.client, surface.named, surface.schema.endpoints.values) }
                .partition { ep -> isVisibleTo(cxt, ep.path) }
            explainAccess(cxt, withheld)
            val renderings = visible
                .asSequence()
                .filter { ep ->
                    (namespace == null || ep.namespace == namespace) &&
                            (method == null || ep.method.name == method) &&
                            (pathRegex == null || pathRegex.containsMatchIn(ep.path)) &&
                            (tag == null || tag in ep.tags) &&
                            (publishedOnly == null || ep.publicApi == publishedOnly)
                }
                // collationKey is "path:method", so this sorts by path then method (the same path may be
                // registered under two HTTP methods).
                .sortedBy { it.collationKey }
                .take(limit)
                .map { renderEndpoint(it, surface.schema.defs) }
                .toList()
            return catalogResult(cxt, renderings, surface)
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
            refreshCallerRoles(cxt)
            val surface = catalogSurface(cxt, request)
            // Filtered exactly as the listing is: a lookup that answered for an endpoint the listing hides
            // would be a one-call way around the hiding, and this endpoint exists to return the same shape.
            // Explained the same way too, so "it came back empty" can be told apart from "you may not see it",
            // which from the outside look identical.
            val found = surface.schema.endpoints["$path:$method"]
            val endpoint = found?.takeIf { isVisibleTo(cxt, it.path) }
            explainAccess(cxt, if (found != null && endpoint == null) listOf(found) else emptyList())
            val renderings = listOfNotNull(endpoint).map { renderEndpoint(it, surface.schema.defs) }
            return catalogResult(cxt, renderings, surface)
        }

        /**
         * The catalog's answer: the [renderings] paired with a `$defs` bag closed over what they reference,
         * with every sourced choice list resolved for this caller (issue #413).
         *
         * Resolved **once over the assembled result**, rather than per endpoint as each is rendered. A type
         * shared by twenty endpoints appears once in the closed bag, so it is resolved once; doing it inside
         * `renderEndpoint` would call the same provider twenty times to produce twenty equal answers.
         *
         * Shared by the listing and the single lookup for the reason [catalogSurface] is: the two return the
         * same shape by contract, and a client consuming either feed with identical code is entitled to have
         * them stay identical.
         */
        private fun catalogResult(
            cxt: KdrCxt,
            renderings: List<Map<String, Any?>>,
            surface: CatalogSurface,
        ): Map<String, Any?> {
            val result = linkedMapOf(
                EI.endpoints to renderings,
                SCH.dDefs to collectDefs(renderings, surface.schema.defs),
            )
            // Read optionally, like the surface -- a store built by hand, outside a running dispatcher, has
            // no service to consult. But an absent service resolves against an **empty** registry rather than
            // skipping resolution: a document with nothing sourced comes back untouched either way, and one
            // with a sourced list faults instead of quietly shipping the id to a client that has no idea what
            // to do with it.
            val providers = (cxt.instanceConfig.get(serviceName) as? SchemaService)?.optionsProviders.orEmpty()
            return resolveOptionsSources(cxt, result, providers)
        }

        /**
         * Reports, under `_meta`, what the catalog's access filter just withheld from this caller and why --
         * the `_debug=explainAccess` tag (issue #215).
         *
         * It exists because a wrong filtering decision is otherwise invisible: it shows up only as a count one
         * lower than expected, with nothing to inspect. The stale-roles defect in #211 was found exactly that
         * way, by reasoning backwards from `36` when `37` was due. So the two things reported are the ones that
         * were unavailable then: the roles the filter actually compared -- **after** [refreshCallerRoles], since
         * the difference between those and the session cookie's *was* the bug -- and each withheld endpoint
         * beside the role its section demands.
         *
         * **Fenced to a test instance, and silently.** `_debug` is accepted from any caller in any environment
         * (`RequestHandler` validates its shape and nothing else), so an unfenced version of this would hand an
         * anonymous caller in production a map of the privileged surface -- undoing, as a debugging aid, the
         * very hiding #211 added. The fence is the one test-only affordances already sit behind, which the
         * runtime refuses to start with outside `local`/`unit`. Off a test instance the key is simply absent
         * rather than empty or refused: an error would confirm the tag exists.
         *
         * [withheld] is what privilege removed, and is deliberately *not* narrowed by the request's
         * namespace/method/regex filters. The question being answered is "what is being kept from me", which is
         * a property of the caller; what a query excluded is already evident from the query.
         */
        @KdrPrivate
        fun explainAccess(cxt: KdrCxt, withheld: List<KdrEndpoint>) {
            if (!cxt.hasDebug(SS.explainAccess) || !cxt.instanceConfig.isTestInstance) {
                return
            }
            // Read optionally, not through the throwing get(): explainAccess also runs while a store is built
            // by hand in a unit test, where no dispatcher and so no RequestService exists.
            val service = cxt.instanceConfig.get(RequestService.serviceName) as? RequestService
            val bySection = withheld.groupBy { sectionOf(it.path) }.entries.sortedBy { it.key }.map { (section, eps) ->
                val rules = service?.sectionRulesMap?.get(section)
                linkedMapOf(
                    SS.section to section,
                    SS.requiredRole to rules?.requiredRole,
                    // Reported beside the level, not folded into it: a section can withhold itself for either
                    // reason, and "requires admin" would be a puzzling explanation for an administrator.
                    SS.requiredCapability to rules?.requiredCapability,
                    EI.endpoints to eps.map { it.path }.sorted(),
                )
            }
            cxt.request?.responseMeta?.put(
                SS.accessExplained,
                linkedMapOf<String, Any?>(
                    SS.actingRoles to cxt.userProfile.roles.sorted(),
                    SS.withheld to bySection,
                ),
            )
        }

        /**
         * Brings the acting caller's "roles" up to date before the catalog decides what to show them, once per
         * request rather than once per endpoint.
         *
         * The dispatcher only refreshes roles for a section that requires one, and the catalog's own section
         * is anonymous -- so without this the filter would run against whatever the session cookie carried at
         * login. A role granted since then would not appear, and would keep not appearing for the cookie's
         * whole life, precisely contradicting the grant taking effect on the next request (issue #212). Costs
         * one row read on a call that is already assembling every endpoint's schema; no-ops when nobody is
         * logged in.
         */
        @KdrPrivate
        fun refreshCallerRoles(cxt: KdrCxt) = refreshActingRoles(cxt)

        /**
         * Whether the acting caller may be *shown* the endpoint at [appPath] -- the catalog's filter (issue
         * #211), answered by the same [RequestService.canAccess] the dispatcher enforces with, so the catalog
         * cannot advertise a door that will not open.
         *
         * Visible when the request service is absent, which happens only outside a running dispatcher (a unit
         * test building a store by hand). Nothing is being served there, so there is nothing to protect.
         */
        @KdrPrivate
        fun isVisibleTo(cxt: KdrCxt, appPath: String): Boolean =
            // Read optionally, not through the throwing get(): absent only outside a running dispatcher (a unit
            // test building a store by hand), where nothing is served and so nothing needs protecting.
            (cxt.instanceConfig.get(RequestService.serviceName) as? RequestService)
                ?.canAccess(cxt.userProfile, appPath) ?: true

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
            if (cxt.hasDebug(SS.explainInput)) {
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

            // Echo the list-of-objects back through the result, so a test can see that the array arrived AND
            // that an individual element's fields survived validation.
            val contacts = (input[CX.contacts] as? List<*>).orEmpty()
            val primary = (contacts.firstOrNull() as? Map<*, *>)?.get(CX.handle) as? String ?: ""

            // The same reason as the contacts echo, for the free-form map (issue #251): the parent type is closed,
            // so the question a test needs answered is whether coercion pruned the map's undeclared keys on
            // the way down into a property that is deliberately open. Without a count coming back, a request
            // that silently arrived empty is indistinguishable from one that arrived whole.
            val extraKeys = (input[CX.extras] as? Map<*, *>)?.size ?: 0

            // Follow the recursive `parent` chain. Finite data already terminates it; the bound is belt-and-braces.
            val chain = ArrayList<Map<*, *>>()
            var node = input[CX.tree] as? Map<*, *>
            while (node != null && chain.size < 1000) {
                chain.add(node)
                node = node[CX.parent] as? Map<*, *>
            }
            if (chain.isEmpty()) {
                return listOf(complexResult(itemName, 0, hasLocation, priority, mode, contacts.size, primary, extraKeys))
            }
            return chain.mapIndexed { depth, n ->
                complexResult(
                    n[CX.label] as? String ?: itemName, depth, hasLocation, priority, mode, contacts.size, primary,
                    extraKeys,
                )
            }
        }

        private fun complexResult(
            name: String, depth: Int, hasLocation: Boolean, priority: String, mode: String,
            contactCount: Int, primaryContact: String, extraKeys: Int,
        ): Map<String, Any?> = linkedMapOf(
            CX.name to name, CX.depth to depth, CX.hasLocation to hasLocation, CX.priority to priority,
            CX.mode to mode, CX.contactCount to contactCount, CX.primaryContact to primaryContact,
            CX.extraKeys to extraKeys,
        )
    }
}

/**
 * Whose endpoints and whose schema a catalog call answers with (issue #387).
 *
 * [named] is kept apart from [client] because the two are different questions: whether the caller *asked* for
 * a client decides how much they are shown, while which client decides what it means. Naming one asks what
 * that client has of its own; having one inferred asks what this caller may use.
 */
@KdrPrivate
class CatalogSurface(val named: Boolean, val client: String?, val schema: KdrSchemaStore)

/**
 * Field-name keys owned by the schema-service endpoints. The endpoint-dump attribute names live on
 * [com.dynamicruntime.common.endpoint.EI] (with [KdrEndpoint]); the `namespace`/`method` query filters
 * reuse those, so only `pathRegex` and the sample-endpoint fields are defined here.
 */
@Suppress("ConstPropertyName")
object SS {
    /**
     * Catalog search tags used by the demonstration endpoints (issue #433).
     *
     * Constants here only because these two are referenced from a test; the axis itself is deliberately an
     * **open vocabulary** and a tag is just a string. Nothing requires a declaration to use a constant, and
     * requiring one would defeat the point -- these are for finding things in a catalog that will one day
     * have hundreds of entries, and cheapness is what makes them get written.
     */
    const val demoTag = "demo"

    /** Companion to [demoTag]; see it for why these are constants at all. */
    const val schemaTag = "schema"

    // Endpoint introspection: the path-regex query filter. The `endpoints` result key is now the shared
    // kernel EI.endpoints (the `$defs` result key is the JSON Schema keyword itself, SCH.dDefs).
    const val pathRegex = "pathRegex"


    // Debug behavior: the debug tag that triggers echoing input under _meta, and the key it is echoed under.
    const val explainInput = "explainInput"
    const val paramsEvaluated = "paramsEvaluated"

    // The debug tag that reports what the catalog's access filter withheld (issue #215), the "_meta" key it is
    // reported under, and that report's own fields. Test instances only -- see `explainAccess`.
    const val explainAccess = "explainAccess"
    const val accessExplained = "accessExplained"
    const val actingRoles = "actingRoles"
    const val withheld = "withheld"
    const val section = "section"
    const val requiredRole = "requiredRole"
    const val requiredCapability = "requiredCapability"

    // Sample endpoint request/response fields.
    const val filter = "filter"
    const val minCount = "minCount"
    const val activeOnly = "activeOnly"
    const val categories = "categories"
    const val sinceDate = "sinceDate"
    const val label = "label"
    const val labels = "labels"
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
    const val extras = "extras"

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

    // Contact fields: ComplexInput.contacts is an array whose element type is the Contact object.
    const val contacts = "contacts"
    const val kind = "kind"
    const val handle = "handle"

    // Result fields.
    const val depth = "depth"
    const val hasLocation = "hasLocation"
    const val contactCount = "contactCount"
    const val extraKeys = "extraKeys"
    const val primaryContact = "primaryContact"

    // Choice values: priority levels and processing modes.
    const val low = "low"
    const val medium = "medium"
    const val high = "high"
    const val strict = "strict"
    const val lenient = "lenient"

    // Choice values: contact kinds.
    const val email = "email"
    const val phone = "phone"
}
