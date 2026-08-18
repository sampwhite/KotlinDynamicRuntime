package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.startup.ServiceInitializer

/** Topic logger for the edge, beside the code that owns the `"edge"` topic. */
object LogEdge : KdrLogger("edge")

/**
 * The KdrEdge service (issue #386): binds the edge's own context roots, and refuses to boot a node whose roots
 * would be mistaken for an application's.
 *
 * It will grow the route table and the upstream registry; today it is the boot-time guard and the place those
 * belong.
 */
class EdgeService : ServiceInitializer {
    override val serviceName: String = EdgeService.serviceName

    /**
     * Refuses to start when an edge root equals one of the application's well-known roots.
     *
     * An edge and the backends it fronts are reachable on the same host, so a shared root makes a path
     * ambiguous -- `/kda/user/profile` would name two different servers' endpoints. That failure is a
     * *mis-route*, which shows up as the wrong server answering rather than as an error, so it is worth
     * refusing at the one moment somebody is watching. The same reasoning as the unruled-section check in
     * `RequestService.checkInit`: a report that cannot be scrolled past.
     *
     * It compares against the application **defaults** rather than a live list of what the backends use, which
     * is route configuration and does not exist yet. That catches the mistake anybody would actually make --
     * configuring an edge with `kda` -- and tightens when the route table arrives.
     */
    override fun checkInit(cxt: KdrCxt) {
        val config = cxt.instanceConfig
        val configured = linkedMapOf(
            ACFG.apiContextRoot to (config.get(ACFG.apiContextRoot) as? String),
            ACFG.contentContextRoot to (config.get(ACFG.contentContextRoot) as? String),
            ACFG.appContextRoot to (config.get(ACFG.appContextRoot) as? String),
            ACFG.staticContextRoot to (config.get(ACFG.staticContextRoot) as? String),
        )
        val applicationRoots = setOf(ContextRoot.kda, ContextRoot.cp, ContextRoot.wa, ContextRoot.st)
        val clashes = configured.filterValues { it != null && it in applicationRoots }
        if (clashes.isNotEmpty()) {
            throw KdrException(
                "Refusing to start the edge: ${clashes.entries.joinToString(", ") { "${it.key}='${it.value}'" }} " +
                    "matches an application context root. An edge and the backends it fronts share a host, so a " +
                    "shared root makes a path ambiguous and the wrong server answers rather than failing.",
            )
        }
        LogEdge.info(cxt) { "KdrEdge serving context roots ${configured.values.filterNotNull()}." }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "EdgeService"

        fun get(cxt: KdrCxt): EdgeService? = cxt.instanceConfig.get(serviceName) as? EdgeService
    }
}
