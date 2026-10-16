package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.node.NodeUtil

/** Where a request that reached this node without a proxy may come from and still count as **direct** (issue #1255). */
enum class DirectAccessMode {
    /** Nowhere: no request is direct. For a machine shared with other users or services. */
    off,

    /** This machine: a loopback source address. The default. */
    loopback,

    /**
     * Any address. Only for a node that nothing but its own network can reach -- a private subnet behind an edge
     * -- which the node cannot check, so a deployment asserts it.
     */
    network,
}

/**
 * Whether a request reached this node **directly** (issue #1255): not through a proxy, from where the deployment
 * allows. A fact about the channel, kept apart from env auth, which is about a person -- a perimeter that vouched
 * for someone. A direct request says nothing about who is calling, only how the request arrived.
 *
 * What it admits is declared per section ([SectionRules.directReads]): the operator surface's reads, without a
 * login. Those are reasonably innocuous -- they name infrastructure (`KDR_DB_HOST`), never a secret -- and whoever
 * can make a direct request already reaches the node from inside: a shell on it, or the private network around it.
 */
object DirectAccess {
    /**
     * RFC 7239's header naming the original client, beside the older `X-Forwarded-For` that Caddy, nginx and most
     * others write. Jetty's own proxy writes only this one.
     */
    const val forwardedHeader = "Forwarded"

    /** Env var choosing where a direct request may come from: a [DirectAccessMode] name. */
    val directAccessEnvVar = EnvVarDef(
        "KDR_DIRECT_ACCESS", group = ENVGRP.application, defaultDoc = DirectAccessMode.loopback.name,
        description = "Where a request that reached this node without a proxy -- carrying neither `X-Forwarded-For` " +
            "nor `Forwarded` -- may come from and count as direct, which lets it read the `operator` section " +
            "without a login (its GETs, not its POSTs; issue #1255). `${DirectAccessMode.loopback}` (the default): " +
            "from this machine, so a command run on the node over SSH can read it. " +
            "`${DirectAccessMode.network}`: from any address, for a node only its own private network can reach, " +
            "such as one behind an edge. `${DirectAccessMode.off}`: never, for a machine shared with other users or " +
            "services. Any proxy in front of the node must set one of the two headers, or every request through " +
            "it would count as direct. Another value refuses the start.",
    )

    /** The mode [config] sets, or [DirectAccessMode.loopback]; refuses a value that names no mode. */
    fun mode(config: KdrInstanceConfig): DirectAccessMode {
        val value = config.getEnvVar(directAccessEnvVar)?.trim()?.ifEmpty { null } ?: return DirectAccessMode.loopback
        return DirectAccessMode.entries.firstOrNull { it.name == value }
            ?: throw KdrException(
                "Refusing to start: $directAccessEnvVar is '$value', which is none of " +
                    "${DirectAccessMode.entries.joinToString(", ")}.",
            )
    }

    /**
     * Whether a request is direct under [mode]: it carries neither proxy header ([forwardedFor], [forwarded]), and
     * its [peerAddress] -- the connection's source, as the server reports it -- is one [mode] allows. An absent
     * address (an in-process test request that set none) is never direct.
     */
    fun isDirect(mode: DirectAccessMode, forwardedFor: String?, forwarded: String?, peerAddress: String?): Boolean {
        if (!forwardedFor.isNullOrBlank() || !forwarded.isNullOrBlank() || peerAddress == null) return false
        return when (mode) {
            DirectAccessMode.off -> false
            DirectAccessMode.loopback -> NodeUtil.isLoopbackLiteral(peerAddress)
            DirectAccessMode.network -> true
        }
    }
}
