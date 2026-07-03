package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector

/**
 * The `kdn` module's component. For now it only contributes a small sample schema
 * in its own namespace, which is enough to prove that schema from more than one
 * component (and more than one module) is assembled into a single compiled store at
 * startup. Its services are ported in later issues.
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
    }
}
