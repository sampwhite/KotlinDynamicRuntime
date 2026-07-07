// `sample` — an example app whose SampleComponent contributes demo Todo endpoints, built on the runtime's
// own endpoint framework (not an external web framework). It can run two ways: standalone via its own
// launcher (`./gradlew :sample:run`, which boots the full runtime plus this component on :7070), or folded
// into the main `launch` app, which registers SampleComponent only in developer environments (see
// shouldLoadSample in launch's Start.kt). The main build therefore depends on this module, but the demo
// never enters a real deployment's endpoint set.
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
