// `sample` — an example app whose SampleComponent contributes demo file endpoints, built on the runtime's
// own endpoint framework (not an external web framework). It has no launcher of its own: it is folded into
// the main `launch` app, which discovers SampleComponent via ServiceLoader (issue #171). The component
// self-gates to developer environments (SampleComponent.isLoaded), so the demo never enters a real
// deployment's endpoint set. `launch` depends on this module `runtimeOnly` (it references it only through the
// discovery mechanism, never at compile time).
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    // `config` re-exports the base modules (common + kdn) via `api`, so this single dependency brings the
    // endpoint/schema DSL, the component + service model, the InstanceRegistry, and the HTTP server. It is
    // the same dependency the real `launch` module uses.
    implementation(project(":config"))
}
