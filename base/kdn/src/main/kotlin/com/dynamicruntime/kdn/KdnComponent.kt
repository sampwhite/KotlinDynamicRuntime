package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.kdn.demo.DemoEndpoints

/**
 * The `kdn` module's component. It contributes a small sample schema in its own namespace
 * (proving schema from more than one component/module is assembled into a single compiled
 * store at startup) and the [DemoEndpoints] -- illustrative endpoints with real input, so
 * the endpoint portal has forms to render. Its services are ported in later issues.
 */
class KdnComponent : ComponentDefinition {
    override val componentName: String = "kdn"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(
            schemaModule(cxt, "kdn") {
                type("RuntimeInfo") {
                    type = SCT.kObject
                    property("ready", "Whether the runtime is ready.", required = true) { type = SCT.boolean }
                }
            }
        )
        collector.addModule(DemoEndpoints.schema(cxt))
    }
}
