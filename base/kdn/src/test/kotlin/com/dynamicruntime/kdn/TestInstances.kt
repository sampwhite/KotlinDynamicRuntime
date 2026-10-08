package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.startup.BootCheckMode

/**
 * The instances `base:kdn`'s tests share (issue #1075). Booting an instance per test made the suite's cost grow with
 * both the number of tests and the size of every instance -- the registry keeps each one for the life of the JVM --
 * while separate clients and users already give most tests the isolation a fresh instance was providing.
 *
 * **Shared by default, a private instance justified.** A test takes the entry whose setup it needs; each call returns
 * a **fresh context** on the one shared instance, so a test may re-bind its own context's client or user freely. A
 * test isolates itself on a shared instance with clients and user addresses of its own -- `TestUser.create` finds an
 * existing address and keeps its level, so a reused address is a different test's user -- and never counts or lists
 * everything on the instance.
 *
 * A test boots an instance of its own (`Startup.mkTestBootCxt` with a name of its own) only when it changes something
 * instance-wide, and says which in a comment: the instance clock (time travel is instance-wide), the job scheduler,
 * instance configuration it sets while running, a restart against its own database, or a setup no entry here has. A
 * new setup that other tests could share is added here instead, with who it is for. Within one test file, share one
 * instance across its tests rather than booting one per test.
 *
 * An entry is the whole setup, not just a name: the registry refuses a cached name asked for with other components
 * or settings, so a test cannot drift from the setup it shares.
 */
object TestInstances {
    /** The base components with the standard test settings: any test isolated by its own clients and users. */
    fun default(cxtName: String): KdrCxt = Startup.mkTestBootCxt(cxtName, "kdnSharedDefault")

    /**
     * Stored-configuration problems **reported rather than refused** (`KDR_STORED_CONFIG_CHECK=warn`): for tests that
     * write a faulty configuration on purpose and read the issues it leaves, each under a client of its own.
     */
    fun storedConfigWarn(cxtName: String): KdrCxt =
        Startup.mkTestBootCxt(cxtName, "kdnSharedStoredConfigWarn", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name))

    /** Callers named by the env-auth header (`assumeEnvAuth`): for tests that sign callers in by address alone. */
    fun envAuth(cxtName: String): KdrCxt = Startup.mkTestBootCxt(cxtName, "kdnSharedEnvAuth", mapOf(ACFG.assumeEnvAuth to true))

    /**
     * Not a test instance (`isTestInstance=false`): for tests of what a real deployment withholds -- error detail,
     * debug pages, test-only configuration. Its test endpoints do not exist, so callers are made by other means.
     */
    fun notTestInstance(cxtName: String): KdrCxt = Startup.mkTestBootCxt(cxtName, "kdnSharedNotTestInstance", mapOf(ACFG.isTestInstance to false))

    /** As [notTestInstance], with callers named by the env-auth header: for the fences a real deployment keeps. */
    fun envAuthNotTestInstance(cxtName: String): KdrCxt = Startup.mkTestBootCxt(
        cxtName, "kdnSharedEnvAuthNotTestInstance", mapOf(ACFG.isTestInstance to false, ACFG.assumeEnvAuth to true),
    )

    /** Sensitive error detail obfuscated (`obfuscateSensitiveErrors`): for tests of what a caller is not told. */
    fun obfuscatedErrors(cxtName: String): KdrCxt =
        Startup.mkTestBootCxt(cxtName, "kdnSharedObfuscatedErrors", mapOf(ACFG.obfuscateSensitiveErrors to true))

    /** With [ExtensionTemplateComponent]'s source template: for tests of clients defined on a template (issue #945). */
    fun extensionTemplate(cxtName: String): KdrCxt =
        Startup.mkTestBootCxt(cxtName, "kdnSharedExtensionTemplate", emptyMap(), listOf(ExtensionTemplateComponent()))
}
