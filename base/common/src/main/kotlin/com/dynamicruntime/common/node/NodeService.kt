package com.dynamicruntime.common.node

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.formatDate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Node-level service: identity, a health report, and an internal-IP filter. Ported from dn's
 * `DnCoreNodeService`; the `Core` qualifier is dropped since kd2 has a single component per module.
 *
 * Registered as a **startup service** (see CommonComponent), so the node knows its own identity and
 * basic facts about itself before any regular service initializes -- other services may need those
 * during their own init. (dn intended `DnCoreNodeService` to be a `StartupServiceInitializer` but a bug
 * left it a regular service; kd2 fixes that by placing it in the component's startup list.)
 *
 * Following the newer convention, this service also *defines its own endpoints* ([schema]) rather than
 * a separate `NodeEndpoints`, because the service file is small. The `common` component wires them in.
 *
 * Encryption is intentionally stubbed here -- real encryption/hashing (and the auth-config/key storage
 * that dn kept in its separate `DnNodeService`, to become a future `AuthConfigService`) is deferred.
 */
class NodeService : ServiceInitializer {
    override val serviceName: String = NodeService.serviceName

    @KdrPrivate
    lateinit var nodeId: NodeId

    @KdrPrivate
    lateinit var internalIpAddresses: Regex

    /** Whether this node is acting as part of the cluster (toggled by operator endpoints). */
    @Volatile
    var isInCluster: Boolean = true

    var loggingHealthChecks: Boolean = false

    override fun onCreate(cxt: KdrCxt) {
        nodeId = NodeUtil.extractNodeId(cxt)
        loggingHealthChecks = cxt.instanceConfig.get("loggingHealthChecks") as? Boolean ?: false
        // Trusted/internal addresses -- used to relax limits or gate expensive APIs. NOT an auth bypass.
        val addressFilter = cxt.instanceConfig.get("node.internalIpAddressFilter") as? String ?: "127.0.0.1|206.*"
        internalIpAddresses = Regex(addressFilter)
    }

    val nodeLabel: String get() = nodeId.label

    /** Basic health/status report returned by the `/health` endpoint. */
    fun getHealth(cxt: KdrCxt): Map<String, Any?> {
        val now = cxt.now()
        val uptimeDays = (now - vmStartTime).inWholeMilliseconds / (1000.0 * 24 * 3600)
        return linkedMapOf(
            ND.nodeStartTime to vmStartTime.formatDate(),
            ND.uptime to "%.4f days".format(uptimeDays),
            ND.currentTime to now.formatDate(),
            ND.nodeId to nodeId.label,
            ND.isClusterMember to isInCluster,
            ND.version to "0.2",
        )
    }

    /** Whether [ipAddress] matches the trusted/internal filter. A null address is treated as internal. */
    fun checkIsInternalAddress(ipAddress: String?): Boolean =
        ipAddress == null || internalIpAddresses.matches(ipAddress)

    // Encryption is deferred to a dedicated issue; these are stubs so callers can be wired now.
    @Suppress("UNUSED_PARAMETER")
    fun encryptString(plainText: String): String =
        throw KdrException("Encryption is not yet implemented (deferred to a future issue).")

    @Suppress("UNUSED_PARAMETER")
    fun decryptString(encryptedText: String): String =
        throw KdrException("Encryption is not yet implemented (deferred to a future issue).")

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "NodeService"

        /** When this VM started, used to compute uptime. */
        val vmStartTime: Instant = Clock.System.now()

        fun get(cxt: KdrCxt): NodeService? = cxt.instanceConfig.get(serviceName) as? NodeService

        /**
         * The node's endpoints (currently just `/health`), contributed by the `common` component. Defined
         * here with the service (the newer convention for small service files) rather than in a separate
         * endpoints file. The handler resolves the live [NodeService] at request time via [get].
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "node") {
            type("Health") {
                type = SCT.kObject
                property(ND.nodeStartTime, "When this node's VM started.", required = true)
                property(ND.uptime, "How long this node has been up.", required = true)
                property(ND.currentTime, "Current server time.", required = true)
                property(ND.nodeId, "The node's ip:port identity.", required = true)
                property(ND.isClusterMember, "Whether the node is acting as part of the cluster.", required = true) {
                    type = SCT.boolean
                }
                property(ND.version, "Runtime version string.", required = true)
            }
            generalEndpoint(
                "/health",
                "Basic health and identity of this node (uptime, node id, version, cluster membership).",
                HttpMethod.GET,
                outputRef = "Health",
            ) { c, _ ->
                (get(c) ?: throw KdrException("NodeService is not available.")).getHealth(c)
            }
        }
    }
}
