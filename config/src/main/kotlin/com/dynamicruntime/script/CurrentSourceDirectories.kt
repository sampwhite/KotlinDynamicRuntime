package com.dynamicruntime.script

import java.io.File
import kotlin.system.exitProcess

/**
 * A utility "script" -- launched from `bin/` via `kdr-run` (see `bin/kdr-source-dirs`) -- that regenerates
 * `current-source-directories.txt` in the working directory: the JVM source directories that make up the app,
 * one relative path per line, for a *dumb* shell to consume. A launcher can then cheaply decide whether the
 * fat jar is stale (any source newer than it) before paying for a Gradle rebuild.
 *
 * This is the "clever Kotlin, dumb shell" split: the analysis -- which Gradle projects exist and where their
 * sources live -- is the clever part, cached to a file the shell only has to read and timestamp-compare.
 *
 * The authoritative source is Gradle itself. Rather than hand-parse `settings.gradle.kts` / `build.gradle.kts`
 * (brittle for custom layouts and KMP), this runs `gradlew` with a throwaway init script that reports each
 * JVM project's main source dirs -- so `launch`'s `apps/` is handled and the Kotlin/JS frontend (no
 * `kotlin-jvm` plugin) is excluded, for free. That is the pattern in miniature: Kotlin executing shell, on
 * demand, to get authoritative data it then reasons over.
 */

private const val outputFileName = "current-source-directories.txt"

/** Tab-delimited prefixes the init script tags each reported path with (a source dir, or a build script). */
private const val srcMarker = "KDR_SRC\t"
private const val buildMarker = "KDR_BUILD\t"

/**
 * Groovy init script: for every JVM (`kotlin-jvm`) project across the build (including the `build-logic`
 * included build, whose plugins affect compilation), print its `build.gradle.kts` and its `main` kotlin +
 * resources source dirs. Custom srcDirs (`apps`) come through; the KMP webapp, lacking the `kotlin-jvm`
 * plugin, is skipped. Written as a multi-dollar raw string so Groovy's own `${...}` interpolation passes
 * through untouched.
 */
private val initScript = $$"""
    gradle.projectsEvaluated {
        rootProject.allprojects.each { p ->
            if (p.plugins.hasPlugin('org.jetbrains.kotlin.jvm')) {
                if (p.buildFile.exists()) println "KDR_BUILD\t${p.buildFile}"
                def main = p.extensions.findByName('sourceSets')?.findByName('main')
                if (main != null) {
                    def k = main.extensions.findByName('kotlin')
                    if (k != null) k.srcDirs.each { println "KDR_SRC\t${it}" }
                    main.resources.srcDirs.each { println "KDR_SRC\t${it}" }
                }
            }
        }
    }
""".trimIndent()

fun main() {
    val workDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
    val gradlew = File(workDir, "gradlew")
    if (!gradlew.canExecute()) {
        System.err.println("$outputFileName: no executable gradlew in $workDir")
        exitProcess(1)
    }

    val lines = gradleReportedLines(workDir, gradlew)
    // Source roots: real, existing dirs only -- drop nonexistent convention dirs (e.g. empty src/main/java)
    // and anything under a `build/` directory (generated sources are outputs, not tracked source).
    val sourceDirs = lines.taggedPaths(srcMarker)
        .filter { it.isDirectory && "${File.separator}build${File.separator}" !in "${it.path}${File.separator}" }
        .relativeSorted(workDir)
    // Build scripts: a change to one means a recompile, so a launcher rebuilds the jar when one is newer.
    val buildFiles = lines.taggedPaths(buildMarker)
        .filter { it.isFile }
        .relativeSorted(workDir)

    val output = File(workDir, outputFileName)
    output.printWriter().use { w ->
        w.println("# Assists optimized launching of Kotlin from the command line: the source directories and")
        w.println("# build scripts a launcher timestamp-compares against the fat jar to decide on a rebuild.")
        w.println("# Regenerate with bin/kdr-source-dirs. Lines starting with '#' are comments, ignored on parse.")
        sourceDirs.forEach { w.println(it) }
        buildFiles.forEach { w.println(it) }
    }
    println("Wrote ${sourceDirs.size} source directories and ${buildFiles.size} build scripts to ${output.name}")
}

/** The paths carried on lines tagged with [tag], as [File]s. */
private fun List<String>.taggedPaths(tag: String): List<File> =
    filter { it.startsWith(tag) }.map { File(it.removePrefix(tag)) }

/** Distinct paths relative to [workDir], sorted. */
private fun List<File>.relativeSorted(workDir: File): List<String> =
    map { it.relativeTo(workDir).path }.distinct().sorted()

/** Runs `gradlew` with the init script and returns its tagged output lines. */
private fun gradleReportedLines(workDir: File, gradlew: File): List<String> {
    val initFile = File.createTempFile("kdr-srcdirs", ".init.gradle").apply { deleteOnExit() }
    initFile.writeText(initScript)

    val proc = ProcessBuilder(gradlew.path, "--init-script", initFile.path, "-q", "help")
        .directory(workDir)
        .redirectErrorStream(true) // one stream: no risk of a full stderr buffer deadlocking the read
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    if (proc.waitFor() != 0) {
        System.err.println("$outputFileName: gradlew failed:\n$out")
        exitProcess(1)
    }
    return out.lines()
}
