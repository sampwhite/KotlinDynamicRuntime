package kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.config.AppConfigApplier
import com.dynamicruntime.config.AppConfigBuilder

fun main() {
    // Name of the deployment config object to discover (default package, so a
    // bare class name). Overridable per deployment via an environment variable.
    val objectName = System.getenv("KDR_CUSTOM_CONFIG") ?: "KdrConfig"

    val curAppConfig = AppConfigBuilder(KdrCxt.mkSimpleCxt("start"), LinkedHashMap())

    // Reflection only LOCATES the object; the call goes through AppConfigApplier.
    val applier = runCatching { Class.forName(objectName).kotlin.objectInstance }
        .getOrNull() as? AppConfigApplier
    if (applier != null) {
        with(applier) { curAppConfig.applyAppConfig() }
    }

    println("app config: ${curAppConfig.data}")
}
