package com.dynamicruntime.common.node

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException

/**
 * Node identity helpers. Ported from dn's `DnNodeUtil`. The environment variables below use the project's
 * `KDR_` prefix and are read through [KdrCxt.getEnvVar], so instance config (and tests) can override them
 * without touching the real process environment.
 */
@Suppress("ConstPropertyName")
object NodeUtil {
    /** The port the server binds to when [port] is not set. */
    const val defaultPort = 7070

    /**
     * Overrides the HTTP port the server binds to (default [defaultPort]). Set this to run a second instance
     * alongside another (e.g. an automated agent's server beside a developer's) without a port collision --
     * usually together with `KDR_IN_MEMORY_ONLY=true` so the two do not contend on a database.
     */
    const val port = "KDR_PORT"

    /** Overrides the node's IP identity (used in the node label). */
    const val nodeIpAddress = "KDR_NODE_IP_ADDRESS"

    /** Overrides the node's host name (used in the node label); falls back to the OS `HOSTNAME`. */
    const val hostName = "KDR_HOSTNAME"

    /**
     * Determines this node's identity from the environment, falling back to a localhost identity. The HTTP
     * [port] defaults to [defaultPort]; a set-but-non-integer value is a startup (configuration) error rather
     * than a silent fallback, so a mistyped port fails loudly instead of colliding on the default.
     */
    fun extractNodeId(cxt: KdrCxt): NodeId {
        val ip = cxt.getEnvVar(nodeIpAddress).takeUnless { it.isNullOrEmpty() } ?: "127.0.0.1"
        val host = (cxt.getEnvVar(hostName) ?: System.getenv("HOSTNAME")).let {
            if (it.isNullOrEmpty() || it == "ubuntu") "localhost" else it
        }
        val serverPort = resolvePort(cxt)
        return NodeId(ip, host, serverPort)
    }

    /** Resolves the HTTP port: the [port] env var (which must be an integer if set), else [defaultPort]. */
    private fun resolvePort(cxt: KdrCxt): Int {
        val config = cxt.instanceConfig
        val role = config.bootRole
        // The port is the one variable a boot role must NOT inherit from the unprefixed name (issue #377).
        // Everywhere else `KDR_EDGE_X` falling back to `KDR_X` is exactly right -- a general value applies to
        // every role. A port cannot: two nodes on one machine sharing one is a collision by construction, and
        // it is a *quiet* one, since a bind failure followed by a health check answers from whichever server
        // already owns the port. So under a role only the role's own variable is consulted, and the role's
        // default stands behind it.
        //
        // The cost is that an edge-only deployment setting a bare KDR_PORT gets the role default instead. That
        // surprise is loud and immediately visible, and `KDR_EDGE_PORT` fixes it; the one it replaces is
        // silent and lands on somebody else's server.
        val value = if (role.isNullOrEmpty()) {
            cxt.getEnvVar(port)
        } else {
            KdrInstanceConfig.envVarNamesFor(port, role).first()
                .let { (config.get(it) as? String) ?: System.getenv(it) }
        }
        if (value.isNullOrEmpty()) {
            return (config.get(ACFG.defaultPort) as? Int) ?: defaultPort
        }
        return value.toIntOrNull()
            ?: throw KdrException("Environment variable $port must be an integer port number, but was '$value'.")
    }
}
