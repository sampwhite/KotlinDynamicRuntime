package com.dynamicruntime.common.node

/**
 * Identity of the running node: its [name], and where it is reached -- its IP address, hostname, and the port it serves
 * on. The name identifies the node (a job claim's holder, the node a report is about); the [label] is where to connect
 * to it, which nodes sharing an address and port share too (issue #1871).
 */
class NodeId(
    val nodeIpAddress: String,
    val hostname: String,
    val port: Int,
    /** What the node is called, `KDR_NODE_NAME`, else `node-<port>` (`NodeUtil.nodeName`). */
    val name: String,
) {
    /** The node's "ip:port" address, for connecting to it and in the health report's `nodeAddress`. */
    val label: String = "$nodeIpAddress:$port"
}
