package com.dynamicruntime.common.startup

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.LogSchema
import com.dynamicruntime.common.schema.parseSchemaTypes

/**
 * Startup service that compiles the collected schema into the read-only
 * [KdrSchemaStore]. In [onCreate] it captures the [SchemaCollector] (which
 * components have populated, and which other startup services may still add to);
 * in [checkInit] it parses the merged `$defs` into resolved [com.dynamicruntime.common.schema.SchType]s,
 * indexes the endpoints by path, and publishes the built store into the instance
 * config so [KdrSchemaStore.get] can find it.
 *
 * This is an initial port of dn's `DnSchemaService`: it does the collected-to-built
 * compile, without dn's builder-keyword resolution pass, because kd2's endpoint and
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
        val endpoints = collected.endpoints.associateBy { it.path }
        val store = KdrSchemaStore(types, endpoints)
        schemaStore = store
        cxt.instanceConfig.put(KdrSchemaStore.key, store)
        isInit = true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "SchemaService"

        /** Retrieves the schema service from the instance config, or null if absent. */
        fun get(cxt: KdrCxt): SchemaService? = cxt.instanceConfig.get(serviceName) as? SchemaService
    }
}
