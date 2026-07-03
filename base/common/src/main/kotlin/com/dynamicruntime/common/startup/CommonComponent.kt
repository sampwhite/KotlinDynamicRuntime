package com.dynamicruntime.common.startup

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT

/**
 * The `common` module's component. It owns the foundational schema and the
 * [SchemaService] that compiles all contributed schemas at startup. Dn's separate
 * `core` component is folded in here (see [ComponentDefinition]).
 *
 * The schema below is an initial sample that exercises the pipeline (a type plus an
 * endpoint); the full set of common types/endpoints is ported in later issues.
 */
class CommonComponent : ComponentDefinition {
    override val componentName: String = "common"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(
            schemaModule(cxt, "core") {
                type("HealthStatus") {
                    type = SCT.kObject
                    property("status", "Health status of the instance.", required = true)
                    property("count", "A sample count value.") { type = SCT.integer }
                }
                generalEndpoint("/health", HttpMethod.GET, outputRef = "HealthStatus") { _, _ ->
                    mapOf("status" to "ok", "count" to 0)
                }
            }
        )
    }

    /** Schema compilation must be ready before regular services, so it is a startup service. */
    override fun startupServices(cxt: KdrCxt): List<() -> ServiceInitializer> = listOf(::SchemaService)

    /** Load just ahead of the standard components (demonstrates relative priority). */
    override fun loadPriority(): Int = PRI.standard - 1
}
