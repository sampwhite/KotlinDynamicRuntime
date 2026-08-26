package com.dynamicruntime.common.startup

import com.dynamicruntime.common.logging.LogStartup

/**
 * JVM shutdown hook installed once by [InstanceRegistry]. Logs the shutdown, then releases the resources
 * registered through [InstanceRegistry.registerForShutdown] -- today the outbound HTTP clients (issue #420),
 * which hold threads.
 */
class ShutdownThread : Thread("KdrShutdownThread") {
    override fun run() {
        LogStartup.info(null, "Shutting down KotlinDynamicRuntime application.")
        InstanceRegistry.runShutdownCloseables()
    }
}
