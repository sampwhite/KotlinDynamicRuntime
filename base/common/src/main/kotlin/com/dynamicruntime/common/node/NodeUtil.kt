package com.dynamicruntime.common.node

import com.dynamicruntime.common.context.KdrCxt

/** Node identity helpers. Ported from dn's `DnNodeUtil`. */
@Suppress("ConstPropertyName")
object NodeUtil {
    /** Default port the HTTP server binds to (hardwired for now). */
    const val defaultPort = 7070

    /**
     * Determines this node's identity from environment variables, falling back to a
     * localhost identity. The port is hardwired to [defaultPort] for now.
     */
    fun extractNodeId(@Suppress("UNUSED_PARAMETER") cxt: KdrCxt): NodeId {
        val ipAddress = System.getenv("NODE_IP_ADDRESS").takeUnless { it.isNullOrEmpty() } ?: "127.0.0.1"
        val hostName = System.getenv("HOSTNAME").let {
            if (it.isNullOrEmpty() || it == "ubuntu") "localhost" else it
        }
        return NodeId(ipAddress, hostName, defaultPort)
    }
}
