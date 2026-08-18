package kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.appui.AppUiComponent
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.startup.KdrProvider
import com.dynamicruntime.config.AppConfigApplier
import com.dynamicruntime.config.AppConfigBuilder
import com.dynamicruntime.kdn.Startup
import java.util.ServiceLoader

/**
 * The boot sequence every KDR launcher runs: pre-boot configuration, provider discovery, component
 * registration, config-applier selection, and the instance boot itself. Returns the booted [KdrCxt], ready for
 * a launcher to start serving from.
 *
 * **Shared rather than copied, and that is the point.** `Start` and `StartEdge` need all of this identically --
 * an edge is a KDR node that additionally proxies, not a different kind of program -- and two near-identical
 * `main`s would drift, silently, because nothing compares them. What legitimately differs between launchers is
 * expressed as an argument here.
 *
 * [bootRole] names what this process is running as (issue #377): `edge` for a `StartEdge` node, null for an
 * ordinary one. Under a role every environment variable gains a per-role override, so an edge and an
 * application can run side by side on one machine wanting different values for the same variable -- see
 * `KdrInstanceConfig.envVarNamesFor`. [defaultPort] is the port the role binds when no variable names one.
 *
 * The role is a **literal supplied by the launcher**, never configuration: `KDR_CUSTOM_CONFIG` is read off the
 * pre-boot context before any `AppConfigApplier` runs, so the role has to be settled earlier than application
 * config exists. It is also the honest place for it -- the launcher *is* the role.
 */
fun bootInstance(cxtName: String, bootRole: String? = null, defaultPort: Int? = null): KdrCxt {
    // Pre-boot: build the instance config (from KDR_ENV and the default-environment-variables file) and a
    // context to read it, so the deployment configuration can be loaded BEFORE the application boots and can
    // therefore influence how it starts up. Env-var lookups from here on go through the context, so the
    // default-environment-variables file acts as a fallback for anything not set in the real environment.
    val preBootConfig = KdrInstanceConfig.preBootLoadConfig(bootRole)
    val preBootCxt = KdrCxt.mkSimpleCxt("preBoot", preBootConfig)

    LogSetup.initFromEnv(getEnv = preBootCxt::getEnvVar)

    // The webapp host serves the self-contained front end under its own context root (e.g. /wa). Register it
    // unconditionally before booting (schema/services are wired during boot) -- it is a real feature, not a
    // demo, and the shell serves regardless of which optional components a deployment loads.
    InstanceRegistry.register(listOf(AppUiComponent()))

    // Discover deployment-injected providers via the JVM ServiceLoader (issue #171). A single pass over the
    // KdrProvider base finds every provider kind the deployment put on the runtime classpath; each subset is
    // routed to its own phase. What is discoverable is controlled entirely by the deployment's
    // settings.gradle.kts (which projects reach launch's runtime classpath), so this is explicit, auditable
    // injection -- logged below by name and code source -- not open-ended scanning.
    val providers = ServiceLoader.load(KdrProvider::class.java, KdrProvider::class.java.classLoader).toList()
    for (p in providers) {
        LogStartup.info(preBootCxt) {
            "Discovered provider '${p.providerName}' (${p::class.java.name}) from " +
                "${p::class.java.protectionDomain?.codeSource?.location}"
        }
    }

    // Register discovered components (schema + services) before booting; each self-gates at boot via its
    // isLoaded/isActive. The `sample` module is the first component discovered this way -- its demo file
    // endpoints load only in developer environments (see SampleComponent.isLoaded), so no explicit
    // shouldLoadSample gate is needed here anymore.
    InstanceRegistry.register(providers.filterIsInstance<ComponentDefinition>().sortedBy { it.loadPriority() })

    // App config appliers run pre-boot so they can shape how the instance starts. They are SELECTED, not
    // composed: competing full profiles (e.g., the developer's KdrConfig and Claude's ClaudeConfig) would
    // conflict, so KDR_CUSTOM_CONFIG names the one to apply by its providerName, defaulting to "KdrConfig".
    val selector = preBootCxt.getEnvVar("KDR_CUSTOM_CONFIG") ?: "KdrConfig"
    val appConfig = AppConfigBuilder(preBootCxt, LinkedHashMap())
    val appliers = providers.filterIsInstance<AppConfigApplier>()
        .filter { it.providerName == selector }
        .sortedBy { it.loadPriority() }
    for (applier in appliers) {
        with(applier) { appConfig.applyAppConfig() }
    }
    if (appliers.isEmpty() && providers.any { it is AppConfigApplier }) {
        LogStartup.warn(preBootCxt, "KDR_CUSTOM_CONFIG='$selector' matched no discovered AppConfigApplier; none applied.")
    } else {
        LogStartup.info(preBootCxt, "Applied ${appliers.size} config applier(s): ${appliers.map { it.providerName }}")
    }

    // Fold the pre-boot config's entries into the loaded app config, adding keys it did not set (so the
    // default-environment-variables values reach the booted instance) without replacing explicit choices.
    for ((k, v) in preBootConfig.entries()) {
        appConfig.data.putIfAbsent(k, v)
    }

    // Carry the role and its default port into the overlay, so the instance config every request later
    // reads its environment through knows them too -- resolved here rather than looked up again, since the
    // launcher is the only thing that knows them.
    bootRole?.let { appConfig.data[ACFG.bootRole] = it }
    defaultPort?.let { appConfig.data.putIfAbsent(ACFG.defaultPort, it) }

    // Boot with the loaded config as the overlay. At startup the instance name aligns with the environment.
    val instanceName = appConfig.data[ACFG.env] as? String ?: ENV.local
    val cxt = Startup.mkBootCxt(cxtName, instanceName, appConfig.data)

    LogStartup.info(cxt, "Booted instance '$instanceName' with app config: ${appConfig.data}")
    val schema = cxt.getSchema()
    LogStartup.info(cxt, "Booted instance: ${schema.types.size} schema types, ${schema.endpoints.size} endpoints.")

    return cxt
}
