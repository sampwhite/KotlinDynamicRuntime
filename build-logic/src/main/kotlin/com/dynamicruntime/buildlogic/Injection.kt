package com.dynamicruntime.buildlogic

import org.gradle.api.Project

/**
 * Wires every deployment-injected provider project onto this project's RUNTIME classpath (issue #171).
 *
 * The versioned build names no specific provider: it reads the list of project paths the deployment declared in
 * its (non-versioned) `settings.gradle.kts` -- published under the `kdrInjectedComponents` extra property on the
 * root project -- and adds each as a `runtimeOnly` dependency. Runtime-only is deliberate and sufficient: the
 * launcher reaches provider classes purely through `ServiceLoader` and the versioned `KdrProvider` interface, so
 * `launch` never compiles against provider code; the classes need only be present at runtime for discovery.
 *
 * Called once from `launch/build.gradle.kts`. A no-op when the deployment declared nothing.
 *
 * Package note: this lives in `com.dynamicruntime.buildlogic`, not `...build` -- a package directory named
 * `build` would be swallowed by the `build/` line in `.gitignore` (which targets Gradle output dirs) and never
 * get committed.
 */
fun Project.wireInjectedComponents(configurationName: String = "runtimeOnly") {
    val extra = rootProject.extensions.extraProperties
    @Suppress("UNCHECKED_CAST")
    val paths = (if (extra.has("kdrInjectedComponents")) extra.get("kdrInjectedComponents") else null) as? List<String>
    paths.orEmpty().forEach { path ->
        dependencies.add(configurationName, dependencies.project(mapOf("path" to path)))
    }
}
