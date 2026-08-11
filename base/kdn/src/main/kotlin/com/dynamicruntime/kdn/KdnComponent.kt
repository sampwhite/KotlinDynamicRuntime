package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector

/**
 * The `kdn` module's component. It contributes a small sample schema in its own namespace, proving that
 * schema from more than one component/module is assembled into a single compiled store at startup. Its
 * services are ported in later issues.
 *
 * It used to also contribute the `demo` endpoints -- greeting/calc/fibonacci/todos -- which existed to give
 * the endpoint portal forms with real input to render. They were removed once the runtime had endpoints of
 * its own worth rendering: `/schema/sample` and `/schema/complex` exercise choices, dates, numbers, booleans,
 * deep `$ref`s and recursion, and unlike the demo ones they are the actual subject of a test.
 */
class KdnComponent : ComponentDefinition {
    override val providerName: String = "kdn"

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
