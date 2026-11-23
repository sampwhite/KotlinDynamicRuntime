package com.dynamicruntime.common.node

// Constants for the node package. Health-report field keys whose names match their
// string values (per the code guide, schema keys are self-named).
@Suppress("ConstPropertyName")
object ND {
    const val nodeStartTime = "nodeStartTime"
    const val uptime = "uptime"
    const val currentTime = "currentTime"
    const val nodeName = "nodeName"
    const val nodeAddress = "nodeAddress"
    const val isClusterMember = "isClusterMember"
    const val version = "version"
}
