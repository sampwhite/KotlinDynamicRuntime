// `launch` — the application entry point. Unlike the other modules, its Kotlin
// source lives directly in the `launch` directory rather than under a
// `src/main/kotlin` tree, and it carries no resources. Depends on `config`,
// which re-exports the base modules (common/kdn) via `api`, so this single
// dependency brings the whole configuration toolkit. This is the one allowed
// direction: `config` itself does not depend on `launch`.
import com.dynamicruntime.buildlogic.wireInjectedComponents

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

dependencies {
    implementation(project(":config"))
    // The demo `sample` module. Its SampleComponent is discovered at startup via ServiceLoader (issue #171)
    // and self-gates to developer environments (SampleComponent.isLoaded), so it never enters a real
    // deployment's endpoint set. `runtimeOnly` because the launcher no longer references it at compile time --
    // it reaches it only through the versioned KdrProvider/ComponentDefinition interfaces.
    runtimeOnly(project(":sample"))
    // The webapp host: its AppUiComponent serves the self-contained front end (embedded `:webapp` bundle)
    // under the `wa` context root. Registered unconditionally in Start.kt.
    implementation(project(":appui"))
}

// Deployment-injected providers (issue #171): custom config now, custom components later. The deployment
// declares which projects to bring in via its non-versioned settings.gradle.kts; this call adds each to the
// RUNTIME classpath (never compile) so the launcher's ServiceLoader can discover them. The versioned build
// names none of them, and declaring nothing is not an error.
wireInjectedComponents()

application {
    // The full application entry point: boots the instance and starts the HTTP server.
    // (WiringCheck remains as a separate, server-free dependency-proof entry point.)
    //
    // Selectable with `-PmainClass=` (issue #377), so `:launch:run` can start the edge launcher
    // (`kdn.StartEdgeKt`, via `bin/kdr-edge`) as well as the application one. The `run` task is the right
    // entry for both because it rebuilds and embeds the current `:webapp` bundle -- an edge serves the front
    // end too, so reaching for `bin/kdr-run` and the pathing jar instead would skip that.
    mainClass.set(providers.gradleProperty("mainClass").orElse("kdn.StartKt"))
}

// A "pathing" jar (issue #175): a manifest-only jar whose `Class-Path` lists this module's own jar plus every
// runtime dependency jar, each at its build/cache location as a `file:` URI. It is the classpath behind
// `bin/kdr-run`, which launches an arbitrary main with `java -cp <jar> <ClassName>` -- so unlike the `run`
// task, it is not tied to `application.mainClass`.
//
// Chosen over a fat jar because the dependency jars stay *separate*: there is no `META-INF/services` collision
// to reconcile -- `ServiceLoader` reads each jar's own provider file directly (H2/PostgreSQL `java.sql.Driver`,
// and our own `KdrProvider` files, all just work) -- and rebuilding after a small change only recompiles the
// affected project's jar, never a repacked archive whose manifest is otherwise identical. Because it references
// jars by absolute path, it is a local developer artifact, not a portable one; a formal, relocatable
// distribution comes from the `application` plugin's `distZip`/`installDist` (a `bin/` script plus a `lib/` of
// these same separate jars).
tasks.register<Jar>("pathingJar") {
    group = "build"
    description = "Builds a manifest-only 'pathing' jar referencing every runtime jar by path (see bin/kdr-run)."
    archiveClassifier.set("path")
    // The referenced jars must exist and be current: this module's own jar, plus (via the runtime classpath as
    // an input) every project and external dependency jar.
    dependsOn(tasks.jar)
    val runtimeClasspath = configurations.runtimeClasspath
    val ownJar = tasks.jar.flatMap { it.archiveFile }
    inputs.files(runtimeClasspath)
    manifest {
        // A default Main-Class for `java -jar`; kdr-run overrides it by naming a class via `-cp <jar> <Class>`.
        attributes["Main-Class"] = application.mainClass.get()
    }
    doFirst {
        // Resolve in the task action (not at configuration time) so an unrelated build does not pay for
        // dependency resolution. Class-Path entries are space-separated `file:` URIs, so paths with spaces are
        // encoded, and the JDK handles the manifest line-wrapping of the long value.
        val jars = listOf(ownJar.get().asFile) + runtimeClasspath.get().files
        manifest.attributes["Class-Path"] = jars.joinToString(" ") { it.toURI().toString() }
    }
}
