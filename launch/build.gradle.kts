// `launch` — the application entry point. Unlike the other modules, its Kotlin
// source lives directly in the `launch` directory rather than under a
// `src/main/kotlin` tree, and it carries no resources. Depends on `config`,
// which re-exports the base modules (common/kdn) via `api`, so this single
// dependency brings the whole configuration toolkit. This is the one allowed
// direction: `config` itself does not depend on `launch`.
import java.util.zip.ZipFile

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
    // The demo `sample` module, so the launcher can optionally register its Todo endpoints. Start.kt only
    // does so in developer environments (see shouldLoadSample there), so this dependency never puts the demo
    // into a real deployment's running endpoint set.
    implementation(project(":sample"))
    // The webapp host: its AppUiComponent serves the self-contained front end (embedded `:webapp` bundle)
    // under the `wa` context root. Registered unconditionally in Start.kt.
    implementation(project(":appui"))
    if (customConfig != null) {
        runtimeOnly(project(customConfig.path))
    }
}

application {
    // The full application entry point: boots the instance and starts the HTTP server.
    // (WiringCheck remains as a separate, server-free dependency-proof entry point.)
    mainClass.set("kdn.StartKt")
}

// A "fat" jar bundling this module's classes plus every runtime dependency into a single archive, so any
// main class in the project can be launched with `java -cp <jar> <ClassName>`. This is the compiled bundle
// behind running Kotlin from the command line like a shell/python script (see bin/kdr-run). It is distinct
// from the `run` task: its purpose is to launch *arbitrary* mains, not just `application.mainClass`.
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a fat jar with all runtime dependencies, for launching any main class (see bin/kdr-run)."
    archiveClassifier.set("all")
    manifest {
        // A default Main-Class for `java -jar`; kdr-run overrides it by naming a class via `-cp <jar> <Class>`.
        attributes["Main-Class"] = application.mainClass.get()
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        // Service declarations are merged separately (below) instead of being resolved first-wins.
        exclude("META-INF/services/**")
    }
    from(mergeServiceFiles)
    // Bundled dependencies collide on shared metadata: keep the first, drop the rest. Also strip jar
    // signatures (they would invalidate the repackaged archive) and duplicate module descriptors.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}

/**
 * Merges every `META-INF/services/...` file across the runtime classpath into one directory the fat jar takes
 * wholesale, concatenating the *lines* of same-named files rather than letting one jar's copy win.
 *
 * `DuplicatesStrategy.EXCLUDE` is right for the metadata that merely collides, but a service file is not a
 * collision: it is a list every provider appends itself to. Resolving it first-wins silently loses providers.
 * That is not hypothetical -- H2 and PostgreSQL both declare `java.sql.Driver`, and taking only H2's line left
 * the fat jar unable to reach a PostgreSQL database at all ("No suitable driver"), so every `kdr-run` script
 * that touches a real deployment's data would fail while the tests, on H2, stayed green.
 */
val mergeServiceFiles = tasks.register("mergeServiceFiles") {
    val runtimeJars = configurations.runtimeClasspath
    val outputDir = layout.buildDirectory.dir("fatJarServices")
    inputs.files(runtimeJars)
    outputs.dir(outputDir)
    doLast {
        val servicePrefix = "META-INF/services/"
        val merged = linkedMapOf<String, LinkedHashSet<String>>()
        runtimeJars.get().filter { it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(servicePrefix) }
                    .forEach { entry ->
                        val lines = zip.getInputStream(entry).bufferedReader().readLines()
                        merged.getOrPut(entry.name) { LinkedHashSet() }
                            .addAll(lines.map { it.trim() }.filter { it.isNotEmpty() })
                    }
            }
        }
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        merged.forEach { (name, providers) ->
            val file = File(dir, name)
            file.parentFile.mkdirs()
            file.writeText(providers.joinToString("\n", postfix = "\n"))
        }
    }
}
