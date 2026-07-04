package kdn

import com.dynamicruntime.common.http.server.HttpServer
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.config.AppConfigApplier
import com.dynamicruntime.config.AppConfigBuilder
import com.dynamicruntime.kdn.Startup

fun main() {
    LogSetup.initFromEnv()

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

    // Start serving HTTP. Blocks until the server stops.
    HttpServer.launch(cxt)
}
