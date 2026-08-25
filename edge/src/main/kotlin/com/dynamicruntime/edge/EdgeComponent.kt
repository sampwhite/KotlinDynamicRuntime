package com.dynamicruntime.edge

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

    /**
     * Loaded ahead of `CommonComponent`, so `EdgeService` registers its content server before the portal
     * does: content servers are offered a request in registration order, and the bare content root is claimed
     * by whichever answers first. Without this, `/ec` reaches the application's portal instead of the sign-in
     * page. A stopgap for as long as an edge loads the portal at all.
     */
    override fun loadPriority(): Int = PRI.early

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(envAuthSchema(cxt))
    }

    override fun services(cxt: KdrCxt): List<ServiceEntry> = listOf(service(::EdgeService))

    @Suppress("ConstPropertyName")
    companion object {
        /** The name this component announces itself under, in logs and provider selection. */
        const val name = BOOT.edgeComponent

    }
}
