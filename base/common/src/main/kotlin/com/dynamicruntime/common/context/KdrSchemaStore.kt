package com.dynamicruntime.common.context

import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.sql.KdrTable

/**
 * The read-only, compiled schema for an instance: resolved [types] (by fully
 * qualified name), the raw [defs] they were compiled from (the JSON `$defs`
 * bodies, keyed the same way), [endpoints] (keyed by [KdrEndpoint.collationKey],
 * i.e., "path:method"), and [tables] (keyed by [KdrTable.tableName]). It is built
 * once at startup by the schema service from the collected schema and published
 * into the instance config, from where [get] retrieves it. A context caches a
 * reference to it (see [KdrCxt.getSchema]) because it is fundamental to most
 * processing.
 *
 * The raw [defs] are retained alongside the compiled [types] because the
 * `/schema/endpoints` catalog serves types with their `$ref`s left intact (for the
 * client to resolve), which means walking and returning the raw JSON schema bodies
 * rather than the parsed [SchType]s.
 *
 * Tables are held here — beside types and endpoints — because a table definition is
 * "schema for data stored in a database"; the topic service reads its topic's tables
 * from here rather than owning the definitions itself.
 */
class KdrSchemaStore(
    val types: Map<String, SchType> = emptyMap(),
    val endpoints: Map<String, KdrEndpoint> = emptyMap(),
    val tables: Map<String, KdrTable> = emptyMap(),
    val defs: Map<String, Any?> = emptyMap(),
) {
    @Suppress("ConstPropertyName")
    companion object {
        /** Instance-config key under which the compiled store is published. */
        const val key = "KdrSchemaStore"

        /**
         * Returns the compiled schema store from the instance config, or an empty
         * store when none has been built (e.g., a simple, non-booted context).
         */
        fun get(cxt: KdrCxt): KdrSchemaStore = cxt.instanceConfig.get(key) as? KdrSchemaStore ?: KdrSchemaStore()
    }
}
