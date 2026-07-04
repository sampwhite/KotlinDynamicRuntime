package com.dynamicruntime.common.node

/** Identity of the running node: its IP address, hostname, and the port it serves on. */
class NodeId(
    val nodeIpAddress: String,
    val hostname: String,
    val port: Int,
) {
    /** Short "ip:port" label used in logging and the health report. */
    val label: String = "$nodeIpAddress:$port"
}
