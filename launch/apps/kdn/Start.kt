package kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.http.server.HttpServer
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.config.AppConfigApplier
import com.dynamicruntime.config.AppConfigBuilder
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent

fun main() {
    LogSetup.initFromEnv()

    // The `sample` module's Todo endpoints are a demo, not part of a real deployment. Register its component
    // only in developer environments (see [shouldLoadSample]) and BEFORE booting: schema is compiled and
    // services are created during boot, so a later registration would be ignored.
    val loadSample = shouldLoadSample()
    if (loadSample) {
        InstanceRegistry.register(listOf(SampleComponent()))
    }

    // Boot the application instance: register components, gather and compile schema,
    // and create and initialize services. Creating this boot context is the core of
    // full application initialization.
    val cxt = Startup.mkBootCxt("start", "local")

    // Name of the deployment config object to discover (default package, so a
    // bare class name). Overridable per deployment via an environment variable.
    val objectName = System.getenv("KDR_CUSTOM_CONFIG") ?: "KdrConfig"

    val curAppConfig = AppConfigBuilder(cxt, LinkedHashMap())

    // Reflection only LOCATES the object; the call goes through AppConfigApplier.
    val applier = runCatching { Class.forName(objectName).kotlin.objectInstance }
        .getOrNull() as? AppConfigApplier
    if (applier != null) {
        with(applier) { curAppConfig.applyAppConfig() }
    }

    LogStartup.info(cxt, "app config: ${curAppConfig.data}")

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
 * overrides that. The environment is read the same way the instance boot resolves it -- from `KDR_ENV`,
 * defaulting to [ENV.local].
 */
private fun shouldLoadSample(): Boolean {
    System.getenv("KDR_LOAD_SAMPLE")?.let { return it.equals("true", ignoreCase = true) }
    val env = System.getenv("KDR_ENV") ?: ENV.local
    return env == ENV.local || env == ENV.dev
}
