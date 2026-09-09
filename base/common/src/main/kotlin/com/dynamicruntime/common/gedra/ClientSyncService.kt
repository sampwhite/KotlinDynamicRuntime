package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.startup.ServiceInitializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/**
 * Keeps this node's client configuration current with its peers (issue #618): the multi-node fallback to the
 * reload endpoint (#616). One node's reload targets one node; the others learn they are behind by reading the
 * shared [ClientSyncTracking] row and reloading a client whose marker has moved past what this node last ran.
 *
 * ### Request-driven, like the cache
 *
 * There is no timer in this codebase; the table caches stay coherent by checking on request traffic, and this
 * follows them exactly. `RequestService` calls [checkSync] once per request; a node-global throttle
 * ([checkThrottleMs]) collapses that to at most one shared-row read every quarter second, and the comparison
 * that follows is in memory. An idle node with no traffic does not sync until a request arrives -- which is
 * harmless, because an idle node is serving no one, and the request that does arrive syncs before it is handled.
 * The trade is the one the caches already make.
 *
 * ### The baseline, and not reloading your own load
 *
 * At startup the node has just loaded the current configuration (#614), so [checkReady] seeds [lastSynced] from
 * what it loaded and announces those markers -- announcing is a monotonic max, so it moves the shared row only
 * when this restarted node loaded something newer than peers (the case #611 is careful about). A node therefore
 * begins caught up, and [checkSync] reloads only a client a peer later advances.
 *
 * Off exactly when the boot load is off ([GedraConfigLoadService.loadEnabled]): a node that does not load stored
 * configuration has nothing to sync, and an in-memory test shares one database across specs, so syncing there
 * would drag one spec's reload into another's node -- the same reason the load itself is gated.
 */
class ClientSyncService : ServiceInitializer {
    override val serviceName: String = ClientSyncService.serviceName

    /** Per client, the marker this node has caught up to. Read on the request path, so a concurrent map. */
    private val lastSynced = ConcurrentHashMap<String, Instant>()

    /** Node-global throttle on the shared-row read, the twin of the cache's `stateReadThrottleMs`. */
    @Volatile
    private var lastCheckMs: Long = 0L

    private var enabled: Boolean = false

    override fun checkReady(cxt: KdrCxt) {
        enabled = GedraConfigLoadService.get(cxt).loadEnabled(cxt)
        if (!enabled) return
        // Announce what this node loaded (so a peer behind it catches up), then take that as the baseline this
        // node is already current with. Both read the boot loader's per-client markers.
        val loaded = GedraConfigLoadService.get(cxt).loadedMarkers()
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, clientSyncTopic)
        ClientSyncTracking.announce(cxt, sqlCxt, loaded)
        val shared = ClientSyncTracking.readMarkers(cxt, sqlCxt)
        // Baseline: every client the shared row knows, at its current marker -- this node just loaded the
        // current configuration for all of them, so it is caught up to all of them and reloads none at first.
        lastSynced.putAll(shared)
    }

    /**
     * Reloads any client this node is behind on (issue #618), throttled to one shared-row read per window. A
     * client whose shared marker is newer than [lastSynced] is reloaded through the endpoint's own coordinator
     * (#616) and its marker recorded, so the next check leaves it alone. Never throws: a sync failure is logged
     * and retried on the next window, exactly as the cache's publish is best-effort -- a request must not fail
     * because a peer's change could not be pulled.
     */
    fun checkSync(cxt: KdrCxt) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < checkThrottleMs) return
        lastCheckMs = now
        try {
            val sqlCxt = SqlTopicService.mkSqlCxt(cxt, clientSyncTopic)
            val shared = ClientSyncTracking.readMarkers(cxt, sqlCxt)
            for ((client, marker) in shared) {
                val seen = lastSynced[client]
                if (seen == null || marker > seen) {
                    GedraConfigReload.reloadClient(cxt, client)
                    lastSynced[client] = marker
                }
            }
        } catch (e: Exception) {
            LogStartup.error(cxt, "Client-config sync check failed; retrying on the next request.", e)
        }
    }

    /**
     * Records that this node has produced [marker] for [client] (issue #618): announced to peers and taken as
     * this node's own baseline, so its own reload does not bounce back through [checkSync]. Called by the reload
     * endpoint after it applies a change on this node.
     */
    fun announceAndMark(cxt: KdrCxt, client: String, marker: Instant?) {
        if (!enabled || marker == null) return
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, clientSyncTopic)
        ClientSyncTracking.announce(cxt, sqlCxt, mapOf(client to marker))
        val seen = lastSynced[client]
        if (seen == null || marker > seen) lastSynced[client] = marker
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "ClientSyncService"

        /** At most one shared-row read this often per node; the twin of the cache's 250ms state-read throttle. */
        const val checkThrottleMs = 250L

        fun get(cxt: KdrCxt): ClientSyncService = cxt.instanceConfig.get(serviceName) as? ClientSyncService
            ?: throw KdrException("The $serviceName is not available on this node.")

        /** The service, or null on a node that does not run it -- an edge carries no config surface to sync. */
        fun getOrNull(cxt: KdrCxt): ClientSyncService? = cxt.instanceConfig.get(serviceName) as? ClientSyncService
    }
}
