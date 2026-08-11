package kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.server.HttpServer
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

fun main() {
    // Pre-boot: build the instance config (from KDR_ENV and the default-environment-variables file) and a
    // context to read it, so the deployment configuration can be loaded BEFORE the application boots and can
    // therefore influence how it starts up. Env-var lookups from here on go through the context, so the
    // default-environment-variables file acts as a fallback for anything not set in the real environment.
    val preBootConfig = KdrInstanceConfig.preBootLoadConfig()
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

    // Boot with the loaded config as the overlay. At startup the instance name aligns with the environment.
    val instanceName = appConfig.data[ACFG.env] as? String ?: ENV.local
    val cxt = Startup.mkBootCxt("start", instanceName, appConfig.data)

    LogStartup.info(cxt, "Booted instance '$instanceName' with app config: ${appConfig.data}")
    val schema = cxt.getSchema()
    LogStartup.info(cxt, "Booted instance: ${schema.types.size} schema types, ${schema.endpoints.size} endpoints.")

    // Start serving HTTP. Blocks until the server stops.
    HttpServer.launch(cxt)
}
