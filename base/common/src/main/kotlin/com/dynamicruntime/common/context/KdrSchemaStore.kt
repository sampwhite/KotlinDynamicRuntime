package com.dynamicruntime.common.context

import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.schema.SchType

/**
 * The read-only, compiled schema for an instance: resolved [types] (by fully
 * qualified name) and [endpoints] (keyed by [KdrEndpoint.collationKey], i.e.
 * "path:method"). It is built once at startup by the
 * schema service from the collected schema and published into the instance
 * config, from where [get] retrieves it. A context caches a reference to it (see
 * [KdrCxt.getSchema]) because it is fundamental to most processing.
 */
class KdrSchemaStore(
    val types: Map<String, SchType> = emptyMap(),
    val endpoints: Map<String, KdrEndpoint> = emptyMap(),
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
