// `sample` — a self-contained example app, isolated from the main launcher. It boots the full
// KotlinDynamicRuntime runtime plus its own component of Todo endpoints (built on the runtime's endpoint
// framework, not an external web framework) and serves them over the runtime's Jetty server on :7070.
// Nothing in the main build depends on it; it is a standalone demo you start with `./gradlew :sample:run`.
plugins {
    id("kdr.kotlin-conventions")
    application
}

dependencies {
    // `config` re-exports the base modules (common + kdn) via `api`, so this single dependency brings the
    // endpoint/schema DSL, the component + service model, the InstanceRegistry, and the HTTP server. It is
    // the same dependency the real `launch` module uses.
    implementation(project(":config"))
}

application {
    // Boots the base runtime plus the SampleComponent and starts the HTTP server (see Start.kt).
    mainClass.set("com.dynamicruntime.sample.StartKt")
}
