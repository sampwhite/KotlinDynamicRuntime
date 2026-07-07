package com.dynamicruntime.sample

import com.dynamicruntime.common.http.server.HttpServer
import com.dynamicruntime.common.logging.LogSetup
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.kdn.Startup

/**
 * Standalone launcher for the sample app. It boots the full KotlinDynamicRuntime runtime (the base
 * `CommonComponent` + `KdnComponent`) plus this module's [SampleComponent], which contributes the Todo
 * endpoints, then serves them over the runtime's Jetty HTTP server on the default port (7070).
 *
 * Registering [SampleComponent] *before* [Startup.mkBootCxt] matters: `mkBootCxt` registers the base
 * components and then compiles the schema for all registered components, so the sample must be in the
 * registry by then. Registration is idempotent, so the extra base registration inside `mkBootCxt` is a
 * no-op. Nothing in the main build depends on this; run it with `./gradlew :sample:run`.
 */
fun main() {
    LogSetup.initFromEnv()

    InstanceRegistry.register(listOf(SampleComponent()))
    val cxt = Startup.mkBootCxt("start", "sample")

    val schema = cxt.getSchema()
    LogStartup.info(cxt, "Booted sample: ${schema.types.size} schema types, ${schema.endpoints.size} endpoints.")

    // Start serving HTTP. Blocks until the server stops.
    HttpServer.launch(cxt)
}
