package com.dynamicruntime.script

import java.io.File
import kotlin.system.exitProcess

/**
 * The clever half of the `kdr-create-config` kdr command (the dumb half is `bin/kdr-create-config`, which just
 * hands off here via `kdr-run`). It scaffolds a deployment's **custom-config provider project** so a developer
 * does not have to assemble the pieces by hand -- the multistep setup `examples/custom-config.md` documents:
 * the `customConfig/` Gradle project (resources enabled), a placeholder `KdrConfig` in the reserved
 * `com.dynamicruntime.deploy` package, the `META-INF/services` registration ServiceLoader reads, and the
 * `settings.gradle.kts` wiring (`injectComponent(":customConfig", ...)` plus the injection prologue).
 *
 * It assumes `kdr-install` has already run (it does none of the boot/sniff bootstrap). Everything is
 * non-destructive and idempotent: it never overwrites an existing source file, treats a commented-out construct
 * as present, and prompts before altering the contents of a file that already exists.
 */

/** The service file is named after the `KdrProvider` base type; its lines are provider class FQNs. */
private const val serviceFileRelPath = "resources/META-INF/services/com.dynamicruntime.common.startup.KdrProvider"
private const val kdrConfigRelPath = "apps/com/dynamicruntime/deploy/KdrConfig.kt"

private val buildFileContent =
    """
    // Deployment-supplied custom configuration project (issue #171), living in this (non-versioned) parent
    // directory rather than the KotlinDynamicRuntime source tree. It ships an AppConfigApplier the launcher
    // discovers at startup via ServiceLoader. Created by `kdr-create-config`.
    plugins {
        id("kdr.kotlin-conventions")
    }

    sourceSets {
        main {
            // Source root is `apps` directly (like `launch`), keeping the build script and output out of it.
            kotlin.setSrcDirs(listOf("apps"))
            // Resources enabled so this project can ship its META-INF/services/...KdrProvider registration.
            resources.setSrcDirs(listOf("resources"))
        }
        test {
            kotlin.setSrcDirs(emptyList<Any>())
            resources.setSrcDirs(emptyList<Any>())
        }
    }

    dependencies {
        // `config` re-exports the base modules, bringing AppConfigApplier / AppConfigBuilder / KdrCxt.
        implementation(project(":config"))
    }
    """.trimIndent() + "\n"

private val kdrConfigContent =
    """
    package com.dynamicruntime.deploy

    import com.dynamicruntime.config.AppConfigApplier
    import com.dynamicruntime.config.AppConfigBuilder

    // Deployment configuration, discovered at startup via ServiceLoader (issue #171) and selected by its
    // providerName -- the simple class name "KdrConfig" -- the default when KDR_CUSTOM_CONFIG is unset. A class,
    // not an object, so ServiceLoader can instantiate it via its public no-arg constructor.
    @Suppress("unused")
    class KdrConfig : AppConfigApplier {
        override fun AppConfigBuilder.applyAppConfig() {
            // The AppConfigBuilder is the implicit receiver, so this reads as builder DSL. Set config here, e.g.:
            //   env = com.dynamicruntime.common.context.ENV.prod
            //   inMemoryOnly = false
            //   data["featureX"] = true
        }
    }
    """.trimIndent() + "\n"

private val serviceFileContent =
    """
    # Deployment providers discovered at boot by ServiceLoader.load(KdrProvider). One fully-qualified class per line.
    com.dynamicruntime.deploy.KdrConfig
    """.trimIndent() + "\n"

fun main(args: Array<String>) {
    // Launched via kdr-run, so the working directory is the KDR workspace (the Gradle build root). The repo path
    // is passed as the one argument (for examples/settings.gradle.kts.example, the source of the prologue block).
    val workDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
    val repoDir = File(args.firstOrNull() ?: File(workDir, "KotlinDynamicRuntime").path).absoluteFile
    val examples = File(repoDir, "examples")

    val settings = File(workDir, "settings.gradle.kts")
    if (!settings.isFile) {
        System.err.println("kdr-create-config: no settings.gradle.kts in $workDir -- run kdr-install first.")
        exitProcess(1)
    }

    println("kdr-create-config: setting up custom configuration in $workDir")
    val configDir = File(workDir, "customConfig")

    // Scaffold the project first, so the directory exists before the (uncommented) injectComponent line lands --
    // otherwise the next Gradle build would reference a project directory that is not there.
    if (!scaffoldProject(configDir)) {
        return
    }

    // Wire settings: the prologue (defines injectComponent), then the injectComponent(":customConfig") call.
    // One decision, not two -- the call without the prologue is a settings file that cannot compile, and that
    // stops every Gradle command in the workspace rather than just this feature (issue #257). So a declined or
    // non-interactive prompt leaves the scaffold in place and the wiring undone, which a developer can finish
    // by hand or by re-running.
    if (!ensureInjectionPrologue(workDir, examples)) {
        println("kdr-create-config: scaffolded customConfig/, but left settings.gradle.kts alone.")
        println("                   The injectComponent(\":customConfig\", ...) line needs the prologue above,")
        println("                   so it was skipped. Add the prologue and re-run to finish the wiring")
        println("                   (re-running is safe -- it only ever adds what is missing).")
        return
    }
    ensureInjectComponent(settings, ":customConfig", "customConfig")

    println("kdr-create-config: done. Edit customConfig/$kdrConfigRelPath to set your configuration,")
    println("                   then boot with it (it is the default; KDR_CUSTOM_CONFIG selects among appliers).")
}

/**
 * Creates the `customConfig/` project pieces that are missing, non-destructively. Returns false (and changes
 * nothing) when a *pre-existing* `customConfig/` lacks the ServiceLoader structure and the user declines the
 * update -- so the caller leaves settings alone too. A brand-new directory, or one already carrying the service
 * file, proceeds without that prompt.
 */
private fun scaffoldProject(configDir: File): Boolean {
    val buildFile = File(configDir, "build.gradle.kts")
    val serviceFile = File(configDir, serviceFileRelPath)
    val kdrConfig = File(configDir, kdrConfigRelPath)

    // A directory that exists but predates the ServiceLoader structure is the one case where we would alter an
    // existing setup rather than create from scratch -- so ask first (issue #177).
    if (configDir.exists() && !serviceFile.isFile) {
        println("An existing customConfig/ lacks the ServiceLoader structure ($serviceFileRelPath).")
        print("Update it to the current structure (add the service file; enable resources)? [y/N] ")
        if (!readYes()) {
            println("Left customConfig/ unchanged.")
            return false
        }
    }

    if (!buildFile.isFile) {
        buildFile.parentFile.mkdirs()
        buildFile.writeText(buildFileContent)
        println("  created customConfig/build.gradle.kts")
    } else if (!buildFile.readText().contains("""setSrcDirs(listOf("resources")""")) {
        // The one place we alter existing content: an old build file that disables resources for `main`.
        print("  customConfig/build.gradle.kts does not enable resources (needed for the service file). Enable it? [y/N] ")
        if (readYes()) {
            val updated = buildFile.readText()
                .replaceFirst(Regex("""resources\.setSrcDirs\(\s*emptyList[^)]*\)\s*\)"""), """resources.setSrcDirs(listOf("resources"))""")
            buildFile.writeText(updated)
            println("  enabled resources in customConfig/build.gradle.kts")
        } else {
            println("  left customConfig/build.gradle.kts unchanged (the service file may not reach the classpath).")
        }
    }

    if (!serviceFile.isFile) {
        serviceFile.parentFile.mkdirs()
        serviceFile.writeText(serviceFileContent)
        println("  created customConfig/$serviceFileRelPath")
    }

    if (!kdrConfig.isFile) {
        kdrConfig.parentFile.mkdirs()
        kdrConfig.writeText(kdrConfigContent)
        println("  created customConfig/$kdrConfigRelPath")
    }
    return true
}
