package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.CommonComponent
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.startup.ComponentDefinition
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
    /**
     * Boots an instance from the two base components ([CommonComponent] + [KdnComponent]) plus any
     * [additionalComponents] the caller names (issue #524) -- the launcher passes its ServiceLoader-discovered
     * components here, and a test names the fixtures it needs, so the component set is per-boot rather than
     * VM-global. Initializes logging first (do-once), then builds the instance and returns a bound context.
     */
    fun mkBootCxt(
        cxtName: String,
        instanceName: String,
        overlay: Map<String, Any?> = emptyMap(),
        additionalComponents: List<ComponentDefinition> = emptyList(),
    ): KdrCxt {
        // Resolve env exactly as getOrCreateInstanceConfig does, so logging is initialized for the same
        // environment before that call emits its first "Initializing instance" line.
        val env = overlay[ACFG.env] as? String
            ?: System.getenv(KdrInstanceConfig.envName.name)
            ?: ENV.local
        LogSetup.ensureInit(env)
        val components = listOf(CommonComponent(), KdnComponent()) + additionalComponents
        val config = InstanceRegistry.getOrCreateInstanceConfig(instanceName, components, overlay)
        return InstanceRegistry.createCxt(cxtName, config)
    }

    /**
     * Boots an instance for unit tests: forces the [ENV.unit] environment and defaults
     * [ACFG.inMemoryOnly] to true unless [overlay] already sets it, so a test runs
     * against in-memory state rather than any deployed resources. Otherwise, identical
     * to [mkBootCxt], including the [additionalComponents] the test wants beyond the base two.
     */
    fun mkTestBootCxt(
        cxtName: String,
        instanceName: String,
        overlay: Map<String, Any?> = emptyMap(),
        additionalComponents: List<ComponentDefinition> = emptyList(),
    ): KdrCxt {
        val testOverlay = LinkedHashMap(overlay)
        testOverlay[ACFG.env] = ENV.unit
        testOverlay.putIfAbsent(ACFG.inMemoryOnly, true)
        // Validate endpoint responses against their output schema in tests, so a non-conforming response fails fast.
        testOverlay.putIfAbsent(ACFG.validateResponseSchema, true)
        return mkBootCxt(cxtName, instanceName, testOverlay, additionalComponents)
    }
}
