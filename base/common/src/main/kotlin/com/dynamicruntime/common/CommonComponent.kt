package com.dynamicruntime.common

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.portal.PortalService
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.PRI
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * The `common` module's component. It owns the foundational schema and services: the [SchemaService]
 * that compiles all contributed schemas at startup, the [NodeService] (which also contributes the
 * `/health` endpoint), and the [RequestService] dispatcher. Dn's separate `core` component is folded
 * in here (see [ComponentDefinition]).
 *
 * Lives at the module's root package, beside [Common], matching how `KdnComponent` sits at the root of
 * the `kdn` module.
 */
class CommonComponent : ComponentDefinition {
    override val componentName: String = "common"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        // Endpoints/types live with the services that own them; the component just wires them in.
        collector.addModule(NodeService.schema(cxt))
        collector.addModule(SchemaService.schema(cxt))
    }

    /**
     * Startup services -- fully initialized before regular services. Schema compilation must be ready
     * first; [NodeService] is here too so the node's identity and basic facts about itself are known
     * early, since regular services (and future startup services) may need them during their own init.
     */
    override fun startupServices(cxt: KdrCxt): List<() -> ServiceInitializer> = listOf(::SchemaService, ::NodeService)

    /** The request dispatcher, then the portal (which registers itself with the dispatcher as a content server). */
    override fun services(cxt: KdrCxt): List<() -> ServiceInitializer> = listOf(::RequestService, ::PortalService)

    /** Load just ahead of the standard components (demonstrates relative priority). */
    override fun loadPriority(): Int = PRI.standard - 1
}
