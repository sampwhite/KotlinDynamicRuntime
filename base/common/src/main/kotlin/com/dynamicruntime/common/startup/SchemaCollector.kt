package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModule

/**
 * Gathers schema during startup, before it is compiled. Each
 * [ComponentDefinition.addSchema] (and, later, a startup service's `onCreate`)
 * contributes [SchModule]s here; [SchemaService] then compiles the accumulated
 * [defs] into resolved types and indexes the [endpoints] into the read-only
 * [com.dynamicruntime.common.context.KdrSchemaStore].
 *
 * Created early by the [InstanceRegistry] and stashed in the instance config under
 * [key] so any contributor reached during startup can add to it. This is kd2's
 * take on dn's `DnRawSchemaStore`; named for its job (collecting contributions)
 * rather than for the "raw" state of the data it holds.
 */
class SchemaCollector {
    /** Merged `$defs` contents across all contributed modules, keyed by qualified type name. */
    val defs: MutableMap<String, Any?> = LinkedHashMap()

    /** Every contributed endpoint, in contribution order. */
    val endpoints: MutableList<KdrEndpoint> = mutableListOf()

    /** Folds a module's types and endpoints into the collector. */
    fun addModule(module: SchModule) {
        defs.putAll(module.defs)
        endpoints.addAll(module.endpoints)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Instance-config key under which the collector is published during startup. */
        const val key = "SchemaCollector"

        /** Retrieves the collector from the instance config, or null if not present. */
        fun get(cxt: KdrCxt): SchemaCollector? = cxt.instanceConfig.get(key) as? SchemaCollector
    }
}
