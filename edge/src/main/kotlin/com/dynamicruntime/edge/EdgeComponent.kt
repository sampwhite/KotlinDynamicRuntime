package com.dynamicruntime.edge

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.startup.ComponentDefinition
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
     * The edge's own context roots, applied as instance config so the dispatcher binds them (issue #386).
     *
     * Supplied here rather than by a deployment's config object because they are what makes this node an edge,
     * not a deployment preference -- and `RequestService.checkInit` reads them from exactly these keys, so
     * nothing in `base/common` changes to accept them. A deployment can still override any of them, since an
     * explicit config entry set before boot wins.
     */
    override fun isLoaded(cxt: KdrCxt): Boolean {
        val config = cxt.instanceConfig
        config.put(ACFG.apiContextRoot, config.get(ACFG.apiContextRoot) ?: EdgeRoot.ea)
        config.put(ACFG.contentContextRoot, config.get(ACFG.contentContextRoot) ?: EdgeRoot.ec)
        config.put(ACFG.appContextRoot, config.get(ACFG.appContextRoot) ?: EdgeRoot.ew)
        config.put(ACFG.staticContextRoot, config.get(ACFG.staticContextRoot) ?: EdgeRoot.es)
        return true
    }

    override fun services(cxt: KdrCxt): List<() -> ServiceInitializer> = listOf(::EdgeService)

    @Suppress("ConstPropertyName")
    companion object {
        /** The name this component announces itself under, in logs and provider selection. */
        const val name = "KdrEdge"

        /**
         * Whether [cxt] is running as an edge -- i.e. this component is loaded.
         *
         * Asked of the *instance config*, never of a compile-time reference, because nothing outside this
         * module may depend on it.
         */
        fun isEdge(cxt: KdrCxt): Boolean = cxt.instanceConfig.bootRole == EdgeRole.name
    }
}
