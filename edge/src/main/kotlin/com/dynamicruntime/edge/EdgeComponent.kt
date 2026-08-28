package com.dynamicruntime.edge

import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.PRI
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.Presence
import com.dynamicruntime.common.startup.ServiceEntry
import com.dynamicruntime.common.startup.service
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The **KdrEdge** component (issues #347, #386): what makes a node booted by `StartEdge` an edge rather than
 * an ordinary KDR node on a different port.
 *
 * The class is `EdgeComponent`, matching `CommonComponent` / `AppUiComponent` and carrying no `Kdr` prefix,
 * while [providerName] announces **KdrEdge** -- so the name a human meets in a log line or a selector carries
 * the weight, and the type names stay short.
 */
class EdgeComponent : ComponentDefinition {
    override val providerName: String = name

    /**
     * Present only on a node booted in the [BOOT.edge] role.
     *
     * This is what lets the component be **discovered** rather than referenced: `ServiceLoader` finds it in
     * every launcher, including the ordinary one, and it is absent there. `InstanceRegistry` gates both schema
     * and services on this, so being absent keeps the whole component out rather than half of it.
     *
     * Declared rather than decided in [isLoaded] (issue #433): the answer is a plain fact about which nodes
     * carry this, and a declaration can be read by anything asking what an edge contains. It was a predicate
     * only because there was nothing else to be.
     */
    override fun presence(cxt: KdrCxt): Presence = Presence(roles = setOf(BOOT.edge))

    /**
     * The instance config that makes this node an edge: its context roots, and the port its role binds.
     *
     * Defaults, not overrides -- each reads before it writes, so a deployment that chose its own keeps it.
     * These are what make the node an edge rather than preferences about it, which is why they are supplied by
     * the component rather than left to a deployment's config object to remember.
     */
    override fun applyInstanceConfig(cxt: KdrCxt) {
        val config = cxt.instanceConfig
        config.put(ACFG.apiContextRoot, config.get(ACFG.apiContextRoot) ?: EdgeRoot.ea)
        config.put(ACFG.contentContextRoot, config.get(ACFG.contentContextRoot) ?: EdgeRoot.ec)
        config.put(ACFG.appContextRoot, config.get(ACFG.appContextRoot) ?: EdgeRoot.ew)
        config.put(ACFG.staticContextRoot, config.get(ACFG.staticContextRoot) ?: EdgeRoot.es)
        config.put(ACFG.defaultPort, config.get(ACFG.defaultPort) ?: EdgeRole.defaultPort)
    }

    /*
     * No load-priority override any more (issue #433).
     *
     * This used to return `PRI.early` so `EdgeService` registered its content server before `PortalService`
     * did -- content servers answer in registration order, and without it `/ec` reached the application's
     * portal instead of the sign-in page. Its own comment called it "a stopgap for as long as an edge loads
     * the portal at all", and an edge no longer does: `PortalService` is declared application-only, so there
     * is nothing to lose the race to. The stopgap retiring is what shows the declaration did the real work
     * rather than merely moving the ordering around.
     */

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(envAuthSchema(cxt))
    }

    /**
     * The shell's wordmark, marked so an edge is recognizable as one (issue #446).
     *
     * An overlay of the `home` fragment rather than a frontend conditional: the shell renders the brand it is
     * handed and still does not know edges exist. It needs no cfact either -- this component loads only on an
     * edge, so its overlay simply is not present anywhere else.
     *
     * Only the **edge** is marked, not the application. The application is the ordinary case, and labelling it
     * would tell nearly every viewer something they never needed told; more to the point, a deployment's
     * application will carry the *customer's* brand, where a marker would be wrong exactly where it matters.
     * An edge is ours in every deployment, so a marker on it stays true.
     *
     * The value is literal rather than composed from the base brand, which is the limitation to know: a
     * deployment that renames the product renames this too. Composing it would need the base split into a
     * separate key, which is not worth doing before a second deployment brand exists.
     */
    override fun fragments(cxt: KdrCxt): List<FragmentSource> = listOf(
        fragmentInline(HFRAG.home, origin = name) {
            namespace(HFRAG.home) { key(EDGEUI.brandKey, EDGEUI.brand) }
        },
    )

    override fun services(cxt: KdrCxt): List<ServiceEntry> = listOf(service(::EdgeService))

    @Suppress("ConstPropertyName")
    companion object {
        /** The name this component announces itself under, in logs and provider selection. */
        const val name = BOOT.edgeComponent

    }
}
