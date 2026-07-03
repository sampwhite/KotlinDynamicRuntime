// `launch` — the application entry point. Unlike the other modules, its Kotlin
// source lives directly in the `launch` directory rather than under a
// `src/main/kotlin` tree, and it carries no resources. Depends on `config`,
// which re-exports the base modules (common/kdn) via `api`, so this single
// dependency brings the whole configuration toolkit. This is the one allowed
// direction: `config` itself does not depend on `launch`.
plugins {
    id("kdr.kotlin-conventions")
    application
}

sourceSets {
    main {
        // Source code root is the `apps` subdirectory, rather than a
        // `src/main/kotlin` tree. This keeps package paths aligned with the
        // directory structure Java-style: e.g. `apps/kdn/Main.kt` -> package
        // `kdn`. Keeping the root at `apps` (rather than the whole launch
        // project dir) also keeps `build.gradle.kts` and the `build` output
        // outside the source root, so the IDE does not mistake the build
        // script for a compilable module source.
        kotlin.setSrcDirs(listOf("apps"))
        // No resources for the launch module.
        resources.setSrcDirs(emptyList<Any>())
    }
    test {
        kotlin.setSrcDirs(emptyList<Any>())
        resources.setSrcDirs(emptyList<Any>())
    }
}

// Deployment projects living outside the source tree may follow a known naming
// convention; this versioned build opts into them when a deployment has actually
// defined them in settings.gradle.kts. `:customConfig`, when present, supplies
// configuration code that the launcher discovers by reflection, so it is added to
// the RUNTIME classpath only (never compile) and its absence is not an error.
val customConfig = findProject(":customConfig")

dependencies {
    implementation(project(":config"))
    if (customConfig != null) {
        runtimeOnly(project(customConfig.path))
    }
}

application {
    mainClass.set("kdn.WiringCheckKt")
}
