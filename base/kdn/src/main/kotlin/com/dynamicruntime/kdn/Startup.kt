package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.CommonComponent
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.mail.MAIL
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.BootStage
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
        /** How far the boot goes (issue #1289); see [BootStage]. A serving node's default. */
        stage: BootStage = BootStage.ready,
        /** The services a [BootStage.collected] boot runs, by name. */
        services: Set<String> = emptySet(),
    ): KdrCxt {
        // Resolve env exactly as getOrCreateInstanceConfig does, so logging is initialized for the same
        // environment before that call emits its first "Initializing instance" line.
        val env = overlay[ACFG.env] as? String
            ?: System.getenv(KdrInstanceConfig.envName.name)
            ?: ENV.local
        LogSetup.ensureInit(env)
        val components = listOf(CommonComponent(), KdnComponent()) + additionalComponents
        val config = InstanceRegistry.getOrCreateInstanceConfig(instanceName, components, overlay, stage, services)
        return InstanceRegistry.createCxt(cxtName, config)
    }

    /** The secret a test node's Mailgun key is looked up under: one no secrets file holds (issue #1250). */
    const val noTestMailKeySecret = "noMailgunKeyInUnitTests"

    /**
     * The overlay for a test's node that is **not** a test instance -- a boot in [env] (production, development) as a
     * real deployment makes it, for a test of what such a deployment does, booted with [mkBootCxt]. Kept in memory,
     * and given no Mailgun key (issue #1250): a real deployment loads its key, and the developer's secrets file may
     * hold a real one.
     */
    fun realNodeOverlay(env: String): Map<String, Any?> = mapOf(
        ACFG.env to env, ACFG.isTestInstance to false, ACFG.inMemoryOnly to true,
        MAIL.mailgunApiKeySecretKey to noTestMailKeySecret,
    )

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
        stage: BootStage = BootStage.ready,
        services: Set<String> = emptySet(),
    ): KdrCxt {
        val testOverlay = LinkedHashMap(overlay)
        testOverlay[ACFG.env] = ENV.unit
        testOverlay.putIfAbsent(ACFG.inMemoryOnly, true)
        // Validate endpoint responses against their output schema in tests, so a non-conforming response fails fast.
        testOverlay.putIfAbsent(ACFG.validateResponseSchema, true)
        // No test finds a real Mailgun key (issue #1250). A developer's secrets file may hold one, and a node that
        // is not a test instance loads it -- so a test of such a node could otherwise send real mail. A test that
        // needs a key names its own secret.
        testOverlay.putIfAbsent(MAIL.mailgunApiKeySecretKey, noTestMailKeySecret)
        return mkBootCxt(cxtName, instanceName, testOverlay, additionalComponents, stage, services)
    }
}
