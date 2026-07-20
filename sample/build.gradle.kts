// `sample` — an example app whose SampleComponent contributes demo Todo endpoints, built on the runtime's
// own endpoint framework (not an external web framework). It has no launcher of its own: it is folded into
// the main `launch` app, which registers SampleComponent only in developer environments (see shouldLoadSample
// in launch's Start.kt). The main build therefore depends on this module, but the demo never enters a real
// deployment's endpoint set.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    // `config` re-exports the base modules (common + kdn) via `api`, so this single dependency brings the
    // endpoint/schema DSL, the component + service model, the InstanceRegistry, and the HTTP server. It is
    // the same dependency the real `launch` module uses.
    implementation(project(":config"))
}
