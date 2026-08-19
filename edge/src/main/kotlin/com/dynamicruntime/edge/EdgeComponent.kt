package com.dynamicruntime.edge

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
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
     * Loaded only on a node booted in the [BOOT.edge] role.
     *
     * This is what lets the component be **discovered** rather than referenced: `ServiceLoader` finds it in
     * every launcher, including the ordinary one, and it declines there. `InstanceRegistry` gates both schema
     * and services on this, so declining keeps the whole component out rather than half of it.
     *
     * A pure predicate, deliberately -- it is called more than once per boot (schema, then services), so
     * anything with an effect belongs in [EdgeService], not here.
     */
    override fun isLoaded(cxt: KdrCxt): Boolean = isEdge(cxt)

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

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(envAuthSchema(cxt))
    }

    override fun services(cxt: KdrCxt): List<() -> ServiceInitializer> = listOf(::EdgeService)

    @Suppress("ConstPropertyName")
    companion object {
        /** The name this component announces itself under, in logs and provider selection. */
        const val name = BOOT.edgeComponent

        /**
         * Whether [cxt] is running as an edge -- i.e., this component is loaded.
         *
         * Asked of the *instance config*, never of a compile-time reference, because nothing outside this
         * module may depend on it.
         */
        fun isEdge(cxt: KdrCxt): Boolean = cxt.instanceConfig.bootRole == BOOT.edge
    }
}
