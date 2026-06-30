// `config` — configuration builders for the runtime. This module sits above the
// base/library modules: it depends on (and so can use the types of) every other
// KotlinDynamicRuntime project EXCEPT `launch`. `launch` is the application
// entry point and is intentionally not visible here. Standard source tree; the
// Kotlin toolchain, repositories, and Kotest stack come from the
// `kdr.kotlin-conventions` plugin.
plugins {
    id("kdr.kotlin-conventions")
}

dependencies {
    // `api` (not `implementation`): config's public surface exposes base types
    // (e.g. AppConfigBuilder takes a KdrCxt, KdrConfigData exposes `cxt`), so the
    // base modules are re-exported. A module that depends on `config` then gets
    // the whole configuration toolkit transitively and can depend on config alone.
    api(project(":base:common"))
    api(project(":base:kdn"))
}
