package kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.server.HttpServer
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.appui.AppUiComponent
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.config.AppConfigApplier
import com.dynamicruntime.config.AppConfigBuilder
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent

fun main() {
    // Pre-boot: build the instance config (from KDR_ENV and the default-environment-variables file) and a
    // context to read it, so the deployment configuration can be loaded BEFORE the application boots and can
    // therefore influence how it starts up. Env-var lookups from here on go through the context, so the
    // default-environment-variables file acts as a fallback for anything not set in the real environment.
    val preBootConfig = KdrInstanceConfig.preBootLoadConfig()
    val preBootCxt = KdrCxt.mkSimpleCxt("preBoot", preBootConfig)

    LogSetup.initFromEnv(getEnv = preBootCxt::getEnvVar)

    // The `sample` module's Todo endpoints are a demo, not part of a real deployment. Register its component
    // only in developer environments (see [shouldLoadSample]) and BEFORE booting: schema is compiled and
    // services are created during boot, so a later registration would be ignored.
    val loadSample = shouldLoadSample(preBootCxt)
    if (loadSample) {
        InstanceRegistry.register(listOf(SampleComponent()))
    }

    // The webapp host serves the self-contained front end under its own context root (e.g. /wa). Register it
    // unconditionally before booting (schema/services are wired during boot) -- it is a real feature, not a
    // demo; the shell serves even when the sample Todo endpoints it exercises are absent.
    InstanceRegistry.register(listOf(AppUiComponent()))

    // Load the deployment's app config. Reflection only LOCATES the config object (its name is overridable via
    // an environment variable); the call goes through AppConfigApplier.
    val objectName = preBootCxt.getEnvVar("KDR_CUSTOM_CONFIG") ?: "KdrConfig"
    val appConfig = AppConfigBuilder(preBootCxt, LinkedHashMap())
    val applier = runCatching { Class.forName(objectName).kotlin.objectInstance }
        .getOrNull() as? AppConfigApplier
    if (applier != null) {
        with(applier) { appConfig.applyAppConfig() }
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
    if (loadSample) {
        LogStartup.info(cxt, "Sample component loaded: demo Todo endpoints are served under /todo.")
    }

    // Start serving HTTP. Blocks until the server stops.
    HttpServer.launch(cxt)
}

/**
 * Whether to load the `sample` module's demo Todo endpoints. Being a demo, they load only in developer
 * environments (`local`/`dev`) and never in `prod`/`integration`; an explicit `KDR_LOAD_SAMPLE=true|false`
 * overrides that. The environment is read through [cxt] (so the default-environment-variables file applies),
 * from `KDR_ENV`, defaulting to [ENV.local].
 */
private fun shouldLoadSample(cxt: KdrCxt): Boolean {
    cxt.getEnvVar("KDR_LOAD_SAMPLE")?.let { return it.equals("true", ignoreCase = true) }
    val env = cxt.getEnvVar("KDR_ENV") ?: ENV.local
    return env == ENV.local || env == ENV.dev
}
