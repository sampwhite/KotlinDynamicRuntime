package com.dynamicruntime.common.node

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import java.net.InetAddress

/**
 * Node identity helpers. Ported from dn's `DnNodeUtil`. The environment variables below use the project's
 * `KDR_` prefix and are read through [KdrCxt.getEnvVar], so instance config (and tests) can override them
 * without touching the real process environment.
 */
@Suppress("ConstPropertyName")
object NodeUtil {
    /** The port the server binds to when [port] is not set. */
    const val defaultPort = 7070

    val port = EnvVarDef(
        "KDR_PORT", group = ENVGRP.node, defaultDoc = "$defaultPort (or the boot role's default)",
        description = "Overrides the HTTP port the server binds to. Set this to run a second instance " +
            "alongside another without a port collision -- usually together with `KDR_IN_MEMORY_ONLY=true`. " +
            "The one variable a boot role must NOT inherit from the unprefixed name (issue #377): under a " +
            "role only `KDR_EDGE_PORT` and the role's default are consulted, because two nodes sharing a port " +
            "collide, quietly.",
    )

    val bindAddress = EnvVarDef(
        "KDR_BIND_ADDRESS", group = ENVGRP.node, defaultDoc = "every interface",
        description = "The address the HTTP server listens on (issue #1245). Unset, it listens on every interface. " +
            "`127.0.0.1` makes it reachable from this machine alone, whatever a firewall says -- what a node behind " +
            "a proxy on the same machine (Caddy, on a lightweight node) wants, and what lets a public test " +
            "deployment assume env auth for a request that did not come through the proxy.",
    )

    val nodeIpAddress = EnvVarDef(
        "KDR_NODE_IP_ADDRESS", group = ENVGRP.node, defaultDoc = "`127.0.0.1`",
        description = "The node's IP address, in its `ip:port` address: where it is reached. What identifies it is `KDR_NODE_NAME`.",
    )

    val hostName = EnvVarDef(
        "KDR_HOSTNAME", group = ENVGRP.node, defaultDoc = "the OS `HOSTNAME`, else `localhost`",
        description = "Overrides the node's host name, which the startup log reports; falls back to the OS `HOSTNAME`.",
    )

    /** The prefix of a node's name when `KDR_NODE_NAME` is not set: `node-7070`. */
    const val defaultNamePrefix = "node-"

    /** What a node's name may be: letters, digits, `.`, `_`, and `-`, starting with a letter or digit, up to 64. */
    val namePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    val nodeName = EnvVarDef(
        "KDR_NODE_NAME", group = ENVGRP.node, defaultDoc = "`$defaultNamePrefix<port>`, such as `${defaultNamePrefix}$defaultPort`",
        description = "The node's name (issue #1871): what identifies it, where its address (`ip:port`) is where to " +
            "connect to it. It names the holder of a job claim, the node a table-cache report is about, and the node " +
            "in `/health` (`nodeName`) and the startup log. Letters, digits, `.`, `_`, and `-`, starting with a letter " +
            "or digit, up to 64 characters. The default is distinct for nodes on different ports of one machine, so a " +
            "deployment running several nodes on one port -- containers, or hosts that each serve 7070 -- must set it, " +
            "one name per node. Like `KDR_PORT`, a boot role does not inherit it from the unprefixed name.",
    )

    /** The address the HTTP server listens on ([bindAddress]), or null for every interface. */
    fun bindAddressOf(config: KdrInstanceConfig): String? = config.getEnvVar(bindAddress)?.trim()?.ifEmpty { null }

    /**
     * Whether the HTTP server listens on a loopback address alone (issue #1245), so that only this machine can reach
     * it directly. Judged on the configured address without a name lookup: an IP literal is read as written, and
     * `localhost` counts. Any other name is not trusted to be loopback, since what it resolves to can change.
     */
    fun bindsLoopbackOnly(config: KdrInstanceConfig): Boolean {
        val address = bindAddressOf(config) ?: return false
        if (address.equals("localhost", ignoreCase = true)) return true
        return isLoopbackLiteral(address)
    }

    /**
     * Whether [address] is an IP literal (bracketed or not, for IPv6) naming a loopback address. Never a name
     * lookup: anything that is not a literal is false, since what a name resolves to can change. Also how a
     * request's source address is judged (issue #1255), which Jetty reports as a literal.
     */
    fun isLoopbackLiteral(address: String): Boolean {
        val literal = address.trim().removePrefix("[").removeSuffix("]")
        val looksLiteral = literal.isNotEmpty() && (literal.all { it.isDigit() || it == '.' } || literal.contains(':'))
        if (!looksLiteral) return false
        return runCatching { InetAddress.getByName(literal).isLoopbackAddress }.getOrDefault(false)
    }

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
        return NodeId(ip, host, serverPort, resolveName(cxt, serverPort))
    }

    /** Resolves the HTTP port: the [port] env var (which must be an integer if set), else [defaultPort]. */
    private fun resolvePort(cxt: KdrCxt): Int {
        val config = cxt.instanceConfig
        val value = ownValue(cxt, port)
        if (value.isNullOrEmpty()) {
            return (config.get(ACFG.defaultPort) as? Int) ?: defaultPort
        }
        return value.toIntOrNull()
            ?: throw KdrException("Environment variable $port must be an integer port number, but was '$value'.")
    }

    /**
     * Resolves the node's name: the [nodeName] env var, which must be a name ([namePattern]) if set, else
     * `node-<port>`. A set-but-invalid name fails the start, as a mistyped port does, rather than falling back to
     * a default another node may hold.
     */
    private fun resolveName(cxt: KdrCxt, serverPort: Int): String {
        val value = ownValue(cxt, nodeName)?.trim()
        if (value.isNullOrEmpty()) return "$defaultNamePrefix$serverPort"
        if (!namePattern.matches(value)) {
            throw KdrException(
                "Environment variable $nodeName must be letters, digits, '.', '_', and '-', starting with a letter or " +
                    "digit, up to 64 characters, but was '$value'.",
            )
        }
        return value
    }

    /**
     * [def]'s value as this node's own: under a boot role, only the role's variable (`KDR_EDGE_PORT`), never the
     * unprefixed one. The port and the name are the variables a role must NOT inherit (issues #377, #1871). Everywhere
     * else `KDR_EDGE_X` falling back to `KDR_X` is exactly right -- a general value applies to every role. These
     * cannot: two nodes on one machine sharing a port is a collision by construction, and a *quiet* one, since a bind
     * failure followed by a health check answers from whichever server already owns the port; two sharing a name are
     * two nodes no claim or report can tell apart. So under a role only the role's own variable is consulted, and the
     * role's default stands behind it.
     *
     * The cost is that an edge-only deployment setting a bare KDR_PORT gets the role default instead. That surprise is
     * loud and immediately visible, and `KDR_EDGE_PORT` fixes it; the one it replaces is silent and lands on somebody
     * else's server.
     */
    private fun ownValue(cxt: KdrCxt, def: EnvVarDef): String? {
        val config = cxt.instanceConfig
        val role = config.bootRole
        if (role.isNullOrEmpty()) return cxt.getEnvVar(def)
        return KdrInstanceConfig.envVarNamesFor(def.name, role).first().let { (config.get(it) as? String) ?: System.getenv(it) }
    }
}
