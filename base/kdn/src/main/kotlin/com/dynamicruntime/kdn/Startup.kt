package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.CommonComponent
import com.dynamicruntime.common.startup.InstanceRegistry

/**
 * Boot helper that assembles the base application. It lives in `base/kdn` because
 * that is the lowest module that can see both base components ([CommonComponent] in
 * `base/common` and [KdnComponent] here). It registers them and initializes the
 * instance -- gathering and compiling schema and creating/initializing services --
 * then hands back a context bound to the instance. Does not start any server.
 *
 * Mirrors dn's `StartupCommon.mkBootCxt`. The test variant [mkTestBootCxt] is kept
 * beside the production entry so every path that boots the application is visible
 * in one place.
 */
object Startup {
    fun mkBootCxt(cxtName: String, instanceName: String, overlay: Map<String, Any?> = emptyMap()): KdrCxt {
        InstanceRegistry.register(listOf(CommonComponent(), KdnComponent()))
        val config = InstanceRegistry.getOrCreateInstanceConfig(instanceName, overlay)
        return InstanceRegistry.createCxt(cxtName, config)
    }

    /**
     * Boots an instance for unit tests: forces the [ENV.unit] environment and defaults
     * [ACFG.inMemoryOnly] to true unless [overlay] already sets it, so a test runs
     * against in-memory state rather than any deployed resources. Otherwise, identical
     * to [mkBootCxt].
     */
    fun mkTestBootCxt(cxtName: String, instanceName: String, overlay: Map<String, Any?> = emptyMap()): KdrCxt {
        val testOverlay = LinkedHashMap(overlay)
        testOverlay[ACFG.env] = ENV.unit
        testOverlay.putIfAbsent(ACFG.inMemoryOnly, true)
        return mkBootCxt(cxtName, instanceName, testOverlay)
    }
}
