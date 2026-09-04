package com.dynamicruntime.common.context

import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.withoutLayouts
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
 *
 * [layouts] and [servedDefs] are both **derived** from [defs] (issue #584), never supplied, so a store cannot be
 * built with one and not the other. [layouts] are the per-type `g-layout` presentation models, keyed by the
 * same qualified name — a friendly form joins a type to its layout by that name — and each is pruned to the
 * properties its type actually declares, so a client that narrowed a type inherits a layout that fits it.
 * They live here rather than on [SchType] because a layout varies by surface, not by validity. [servedDefs]
 * is [defs] with every `g-layout` stripped: what the catalog and the workflow view hand out, so the wire schema
 * stays documentation-grade while the layout travels out-of-band. Both are lazy — computed once, on first use,
 * from a store that never changes after boot.
 */
class KdrSchemaStore(
    val types: Map<String, SchType> = emptyMap(),
    val endpoints: Map<String, KdrEndpoint> = emptyMap(),
    val tables: Map<String, KdrTable> = emptyMap(),
    val defs: Map<String, Any?> = emptyMap(),
) {
    val layouts: Map<String, SchLayout> by lazy {
        collectLayouts(defs).mapValues { (name, layout) ->
            types[name]?.let { layout.prunedTo(it.properties.keys) } ?: layout
        }
    }

    val servedDefs: Map<String, Any?> by lazy { withoutLayouts(defs) }

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
