package com.dynamicruntime.common.startup

import com.dynamicruntime.common.logging.LogStartup

/**
 * JVM shutdown hook installed once by [InstanceRegistry]. For now it just logs that
 * the application is shutting down; later it will give services a chance to release
 * resources cleanly.
 */
class ShutdownThread : Thread("KdrShutdownThread") {
    override fun run() {
        LogStartup.info(null, "Shutting down KotlinDynamicRuntime application.")
    }
}
