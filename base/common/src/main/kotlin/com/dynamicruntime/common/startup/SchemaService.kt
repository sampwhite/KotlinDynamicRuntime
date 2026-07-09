package com.dynamicruntime.common.startup

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.LogSchema
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
        val store = KdrSchemaStore(types, endpoints, tables)
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
            type("EndpointQuery") {
                type = SCT.kObject
                property(EI.namespace, "Only endpoints declared in this namespace.")
                property(EI.method, "Only endpoints using this HTTP method (GET/POST/PUT).")
                property(SS.pathRegex, "Only endpoints whose path matches this regular expression.")
            }
            // The EndpointInfo type is owned by KdrEndpoint, alongside its serialization (toJsonMap).
            KdrEndpoint.defineInfoType(this)
            listEndpoint(
                "/schema/endpoints",
                "Lists the registered endpoints, optionally filtered by namespace, HTTP method, or a path regex.",
                outputRef = KdrEndpoint.infoTypeName,
                inputRef = "EndpointQuery",
            ) { c, request -> listEndpoints(c, request) }

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
        }

        /** Handler for `/schema/endpoints`: filter the registered endpoints and dump their attributes. */
        @KdrPrivate
        fun listEndpoints(cxt: KdrCxt, request: Map<String, Any?>): List<Map<String, Any?>> {
            val query = (request[EP.request] as? Map<*, *>)?.toJsonMap() ?: emptyMap()
            val namespace = query[EI.namespace] as? String
            val method = (query[EI.method] as? String)?.uppercase()
            val pathRegex = (query[SS.pathRegex] as? String)?.let { Regex(it) }
            return cxt.getSchema().endpoints.values
                .filter { ep ->
                    (namespace == null || ep.namespace == namespace) &&
                        (method == null || ep.method.name == method) &&
                        (pathRegex == null || pathRegex.containsMatchIn(ep.path))
                }
                // collationKey is "path:method", so this sorts by path then method (the same path may be
                // registered under two HTTP methods).
                .sortedBy { it.collationKey }
                .map { it.toJsonMap() } // KdrEndpoint owns its own serialization
        }

        /** Handler for `/schema/sample`: generate an interesting, schema-conforming set of items. */
        @KdrPrivate
        fun sampleItems(cxt: KdrCxt, request: Map<String, Any?>): List<Map<String, Any?>> {
            val query = (request[EP.request] as? Map<*, *>)?.toJsonMap() ?: emptyMap()
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
    }
}

/**
 * Field-name keys owned by the schema-service endpoints. The endpoint-dump attribute names live on
 * [com.dynamicruntime.common.endpoint.EI] (with [KdrEndpoint]); the `namespace`/`method` query filters
 * reuse those, so only `pathRegex` and the sample-endpoint fields are defined here.
 */
@Suppress("ConstPropertyName")
object SS {
    // Endpoint introspection query-only field.
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
