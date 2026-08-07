package com.dynamicruntime.common.startup

/**
 * Base marker for anything a deployment can inject and have discovered at startup through the JVM's
 * ServiceLoader mechanism (issue #171). It carries only the two universally meaningful traits -- a selection
 * identity and a load order -- so that a single `ServiceLoader.load(KdrProvider::class.java)` pass finds every
 * kind of provider, and each provider project lists itself in one
 * `META-INF/services/com.dynamicruntime.common.startup.KdrProvider` file regardless of how many roles it plays.
 *
 * The two current provider kinds extend this: [ComponentDefinition] (schema + services, loaded during boot) and
 * `AppConfigApplier` in `config` (pre-boot application config). Role-specific lifecycle -- notably a
 * component's [ComponentDefinition.isLoaded]/[ComponentDefinition.isActive] schema-vs-services split -- stays on
 * the subtype, because it has no meaning for a config applier.
 *
 * Discovery is not open-ended scanning: `ServiceLoader` only instantiates classes that *deliberately* implement
 * this first-party interface and declare themselves in `META-INF/services`, and the runtime classpath that is
 * scanned is controlled entirely by the deployment's `settings.gradle.kts`. So the injection is explicit and
 * auditable -- an unrelated third-party jar cannot implement an interface it has never heard of.
 */
interface KdrProvider {
    /**
     * Selection identity -- e.g., the target of the `KDR_CUSTOM_CONFIG` selector. Defaults to the simple class
     * name and is assumed globally unique across the providers on a given classpath.
     */
    val providerName: String get() = this::class.simpleName ?: this::class.java.name

    /** Load order when several providers apply or load together; lower loads earlier. */
    fun loadPriority(): Int = PRI.standard
}
