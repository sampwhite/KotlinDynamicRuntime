package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.content.fragmentFiles
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.flavor.FlavorContribution
import com.dynamicruntime.common.flavor.flavor
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.naming.OWNR
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceEntry
import com.dynamicruntime.common.startup.service
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.confdemo.ConfDemoBootService
import com.dynamicruntime.kdn.confdemo.confDemoDocs
import com.dynamicruntime.kdn.confdemo.confDemoSimulationSchema

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

    /** Core's root (issue #950). */
    override val ownerRoot: String = OWNR.kdrRoot

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(
            schemaModule(cxt, "kdr.kdn") {
                type("RuntimeInfo") {
                    type = SCT.kObject
                    property("ready", "Whether the runtime is ready.", required = true) { type = SCT.boolean }
                }
            }
        )
        // The conference demo's simulation (issue #1137): test-only, so it exists on a test instance alone.
        collector.addModule(confDemoSimulationSchema(cxt))
    }

    /** The conference demo's documentation (issue #1137), the developer docs `docs/demo-client/` holds. */
    override fun docs(cxt: KdrCxt): List<MarkdownDocDef> = confDemoDocs()

    /**
     * The conference demo's copy (issue #1139): a fragment file its logistics layout pulls a heading and a description
     * from, so the docs can show copy that lives in a file and a client's overlay can reword.
     */
    override fun fragments(cxt: KdrCxt): List<FragmentSource> =
        fragmentFiles(ConfDemo.fragmentFile, shownOn = "the conference demo's workshop logistics form") +
            // The help line under each demo workflow's title (issue #1221), empty as shipped, so none shows: the
            // keys an overlay fills -- the `reference` flavor's links to the walkthrough -- and the check holds it to.
            fragmentInline(
                WVF.helpFile, origin = "KdnComponent", isOverlay = false, audience = FragmentAudience.backend,
                shownOn = "the conference demo's workflow pages, under the title", shownFor = ConfDemo.client,
            ) {
                namespace(ConfDemo.client) { ConfDemo.workflows.forEach { key(it, "") } }
            }

    /**
     * What kdn changes under a reference deployment (issue #1221): the conference demo is provisioned as the node
     * starts, and each of its workflow pages links to the walkthrough page that explains it.
     */
    override fun flavors(cxt: KdrCxt): List<FlavorContribution> = listOf(
        flavor(FLV.reference) {
            set(ConfDemo.provisionAtBootKey, true)
            omnibusOverlay("kdrConfDemoReference")
        },
    )

    override fun services(cxt: KdrCxt): List<ServiceEntry> = listOf(service(::ConfDemoBootService))
}
