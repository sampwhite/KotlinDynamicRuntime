package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.sql.KdrTable

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

    /** Every contributed table definition, in contribution order. */
    val tables: MutableList<KdrTable> = mutableListOf()

    /**
     * The Gedra config bundles components contributed, and the checks over them (issue #299).
     *
     * Its own collector rather than three more fields here: taking a config involves checking it against
     * every config already taken, which is logic rather than accumulation, and this class is deliberately the
     * latter.
     */
    val gedraConfigs: GedraConfigCollector = GedraConfigCollector()

    /** Folds a module's types and endpoints into the collector. */
    fun addModule(module: SchModule) {
        defs.putAll(module.defs)
        endpoints.addAll(module.endpoints)
    }

    /**
     * Takes a Gedra config bundle, checking it against the ones already taken, and folds the entry types its
     * traits generated into [defs] so they compile with everything else. A config that fails a check is
     * dropped rather than folded in -- outside production the check throws before reaching here.
     */
    fun addGedraConfig(cxt: KdrCxt, config: GedraConfig) {
        if (gedraConfigs.add(cxt, config)) {
            defs.putAll(config.defs)
        }
    }

    /** Adds contributed table definitions (from a `tableModule`) into the collector. */
    fun addTables(tables: List<KdrTable>) {
        this.tables.addAll(tables)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Instance-config key under which the collector is published during startup. */
        const val key = "SchemaCollector"

        /** Retrieves the collector from the instance config, or null if not present. */
        fun get(cxt: KdrCxt): SchemaCollector? = cxt.instanceConfig.get(key) as? SchemaCollector
    }
}
