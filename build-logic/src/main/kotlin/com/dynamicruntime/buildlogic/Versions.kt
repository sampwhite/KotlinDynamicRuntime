package com.dynamicruntime.buildlogic

/**
 * Versions of third-party dependency *families* -- one entry per project that publishes several artifacts we
 * use together.
 *
 * These exist to be paired with that project's BOM, which `kdr.kotlin-conventions` applies as a `platform`
 * to every module. A module then names the artifact and no version at all:
 *
 * ```
 * implementation("org.eclipse.jetty:jetty-server")
 * ```
 *
 * That is the point of the arrangement, and it is stronger than a shared constant on its own. A constant
 * still leaves the version at each call site, so a module can be added with a literal, or with the constant
 * from a stale copy, and the build succeeds -- artifacts of one project mixed across versions fail at
 * *runtime*, on a `NoSuchMethodError` far from the build file that caused it. With the BOM there is no
 * version to get wrong.
 *
 * The usual home for this is a `gradle/libs.versions.toml` version catalog, which does not fit here: Gradle
 * resolves the catalog against the **build root**, and this build's root is the workspace directory that
 * *contains* the repository -- not version-controlled. Putting a catalog under version control would mean
 * pointing every developer's `settings.gradle.kts` at a path inside the repo. `build-logic` is already
 * versioned, already on each module's build-script classpath (see [wireInjectedComponents], imported by
 * `launch/build.gradle.kts`), and needs no workspace change at all.
 *
 * Only families belong here. A lone artifact keeps its version inline, where it is read next to the comment
 * explaining why it is a dependency at all.
 */
@Suppress("ConstPropertyName")
object Versions {
    /** Jetty 12: the core HTTP server (`base/common`), the HTTP client, and the reverse proxy (`edge`). */
    const val jetty = "12.1.10"

    /** log4j2: the API, the core backend, and the slf4j binding that routes third-party logging into it. */
    const val log4j = "2.26.1"
}
