package com.dynamicruntime.script

import java.io.File
import kotlin.system.exitProcess

/**
 * The clever half of the idempotent installer (the dumb half is `bin/kdr-install`, which sniffs for a JDK and
 * copies a minimal `settings.gradle.kts` so this can run). Launched via `kdr-run` with the deployment root as
 * the working directory and the repo path as its one argument. It brings the deployment's per-machine,
 * non-versioned configuration up to date -- and will grow over time (installing databases, OpenSearch, ...).
 *
 * Everything here is idempotent: it checks before it acts, prompts before anything a user might not want, and
 * does nothing when already configured.
 */

/** Minimum Gradle heap the Kotlin/JS webapp build needs, from `examples/gradle.properties.example`. */
private const val minGradleXmxGb = 4

fun main(args: Array<String>) {
    val workDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
    val repoDir = File(args.firstOrNull() ?: File(workDir, "KotlinDynamicRuntime").path).absoluteFile
    val examples = File(repoDir, "examples")
    if (!examples.isDirectory) {
        System.err.println("install: examples directory not found at $examples")
        exitProcess(1)
    }
    println("kdr-install: checking the deployment configuration in $workDir")
    ensureGradleProperties(workDir, examples)
    syncSettingsWithExample(workDir, examples)
    syncGradleWrapper(workDir, repoDir)
    ensureBinOnPath(repoDir)
    println("kdr-install: done.")
}

// --- gradle.properties ------------------------------------------------------------------------------------

/**
 * Ensures `gradle.properties` exists with the JVM heap the webapp build needs. Missing file -> copy the
 * example; present but no `org.gradle.jvmargs` -> add it silently; present but under-provisioned -> warn only.
 */
private fun ensureGradleProperties(workDir: File, examples: File) {
    val gp = File(workDir, "gradle.properties")
    val example = File(examples, "gradle.properties.example")
    if (!gp.exists()) {
        gp.writeText(stripExampleHeader(example.readText()))
        println("gradle.properties: created from the example.")
        return
    }
    val liveText = gp.readText()
    val exampleText = example.readText()

    // Add each heap property the live file lacks -- only the missing ones, so an already-present setting is
    // never duplicated -- each carrying its explanatory comment over from the example. Done silently.
    val heapKeys = listOf("org.gradle.jvmargs", "kotlin.daemon.jvmargs")
    val missing = heapKeys.filterNot { key -> liveText.lineSequence().any { it.trimStart().startsWith(key) } }
    if (missing.isNotEmpty()) {
        val blocks = missing.joinToString("\n\n") { propertyBlock(exampleText, it) }
        val lead = if (liveText.isEmpty() || liveText.endsWith("\n")) "\n" else "\n\n"
        gp.appendText(lead + blocks + "\n")
    }

    // Warn (only) when an already-present org.gradle.jvmargs is under-provisioned for the webapp build.
    val jvmLine = liveText.lineSequence().firstOrNull { it.trimStart().startsWith("org.gradle.jvmargs") }
    val xmxGb = jvmLine?.let { parseXmxGb(it) }
    if (xmxGb != null && xmxGb < minGradleXmxGb) {
        println("WARNING: gradle.properties sets org.gradle.jvmargs to '${jvmLine.substringAfter('=').trim()}',")
        println("         but the webapp's Kotlin/JS build needs at least -Xmx${minGradleXmxGb}g. Consider raising it.")
    }
}

/**
 * The example's declaration of [key] -- the property line plus the contiguous `#` comment lines directly
 * above it -- so a property added to a live file arrives self-documented. Empty if the example lacks the key.
 */
private fun propertyBlock(exampleText: String, key: String): String {
    val lines = exampleText.lines()
    val idx = lines.indexOfFirst { it.trimStart().startsWith(key) }
    if (idx < 0) {
        return ""
    }
    var start = idx
    while (start > 0 && lines[start - 1].trimStart().startsWith("#")) {
        start--
    }
    return lines.subList(start, idx + 1).joinToString("\n")
}

/** Extracts the `-Xmx` value from a jvmargs line as gigabytes, or null if absent/unparseable. */
private fun parseXmxGb(jvmLine: String): Double? {
    val m = Regex("""-Xmx(\d+)([gGmMkK])""").find(jvmLine) ?: return null
    val n = m.groupValues[1].toLong()
    return when (m.groupValues[2].lowercase()) {
        "g" -> n.toDouble()
        "m" -> n / 1024.0
        "k" -> n / 1024.0 / 1024.0
        else -> null
    }
}

// --- settings.gradle.kts ----------------------------------------------------------------------------------

private data class Include(val path: String, val projectDir: String?)

/**
 * Offers to add projects the example `settings.gradle.kts` includes that the live one does not. A project that
 * is present in the live settings but *commented out* is treated as present -- leaving it out was a deliberate
 * choice -- so only genuinely-absent projects are offered, and only with the user's consent.
 */
private fun syncSettingsWithExample(workDir: File, examples: File) {
    val settings = File(workDir, "settings.gradle.kts")
    val example = File(examples, "settings.gradle.kts.example")
    if (!settings.isFile || !example.isFile) {
        return
    }
    val wanted = parseIncludes(example.readText(), includeCommented = false)
    val present = parseIncludes(settings.readText(), includeCommented = true).map { it.path }.toSet()
    val newProjects = wanted.filter { it.path !in present }
    if (newProjects.isEmpty()) {
        return
    }
    println("The example settings includes projects your settings.gradle.kts does not:")
    newProjects.forEach { println("  - ${it.path}") }
    print("Add them to settings.gradle.kts? [y/N] ")
    if (!readYes()) {
        println("Left settings.gradle.kts unchanged.")
        return
    }
    val block = buildString {
        append("\n// Added by kdr-install to match the example.\n")
        newProjects.forEach { inc ->
            append("include(\"${inc.path}\")\n")
            inc.projectDir?.let { append("project(\":${inc.path}\").projectDir = file(\"$it\")\n") }
        }
    }
    settings.appendText(block)
    println("Added ${newProjects.size} project(s) to settings.gradle.kts.")
}

/**
 * The `include(...)` entries in a settings file, each paired with its `projectDir` if one is declared.
 * Commented-out includes are returned only when [includeCommented] is set (used to detect deliberate opt-outs).
 */
private fun parseIncludes(text: String, includeCommented: Boolean): List<Include> {
    val includeRe = Regex("""^\s*(//\s*)?include\(\s*"([^"]+)"\s*\)""")
    val dirRe = Regex("""project\(\s*":([^"]+)"\s*\)\.projectDir\s*=\s*file\(\s*"([^"]+)"\s*\)""")
    val dirs = dirRe.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    return text.lineSequence().mapNotNull { line ->
        val m = includeRe.find(line) ?: return@mapNotNull null
        val commented = m.groupValues[1].isNotBlank()
        if (commented && !includeCommented) {
            return@mapNotNull null
        }
        Include(m.groupValues[2], dirs[m.groupValues[2]])
    }.toList()
}

// --- Gradle wrapper ---------------------------------------------------------------------------------------

/**
 * Keeps the parent (build root) Gradle wrapper in step with the repo's. The repo ships the canonical wrapper;
 * if the parent's `gradle-wrapper.properties` names a different Gradle version (its `distributionUrl`), offers
 * to copy the repo's `gradlew` and `gradle/wrapper` files over. (The copy takes effect on the next build --
 * the one that ran this installer already used the parent's current wrapper.)
 */
private fun syncGradleWrapper(workDir: File, repoDir: File) {
    val liveProps = File(workDir, "gradle/wrapper/gradle-wrapper.properties")
    val repoProps = File(repoDir, "gradle/wrapper/gradle-wrapper.properties")
    if (!liveProps.isFile || !repoProps.isFile) {
        return
    }
    val liveUrl = distributionUrl(liveProps)
    val repoUrl = distributionUrl(repoProps)
    if (liveUrl == null || repoUrl == null || liveUrl == repoUrl) {
        return
    }
    println("Your Gradle wrapper (${gradleVersion(liveUrl)}) differs from the repository's (${gradleVersion(repoUrl)}).")
    print("Update your wrapper to match the repository? [y/N] ")
    if (!readYes()) {
        println("Left the Gradle wrapper unchanged.")
        return
    }
    File(repoDir, "gradlew").copyTo(File(workDir, "gradlew"), overwrite = true).setExecutable(true)
    File(repoDir, "gradle/wrapper/gradle-wrapper.jar")
        .copyTo(File(workDir, "gradle/wrapper/gradle-wrapper.jar"), overwrite = true)
    repoProps.copyTo(liveProps, overwrite = true)
    println("Updated the Gradle wrapper to ${gradleVersion(repoUrl)}.")
}

/** The `distributionUrl` value from a `gradle-wrapper.properties` file, or null if absent. */
private fun distributionUrl(props: File): String? =
    props.readLines().firstOrNull { it.trimStart().startsWith("distributionUrl") }?.substringAfter('=')?.trim()

/** The Gradle version encoded in a `distributionUrl` (e.g. `...gradle-9.6.0-bin.zip` -> `9.6.0`), else the URL. */
private fun gradleVersion(url: String): String = Regex("""gradle-([\d.]+)""").find(url)?.groupValues?.get(1) ?: url

// --- PATH / shell rc --------------------------------------------------------------------------------------

/**
 * Offers to put this repo's `bin` on the user's PATH (via their shell rc file). If a `KotlinDynamicRuntime`
 * bin is already on PATH -- even from a different checkout or worktree -- it does nothing, so a second checkout
 * never fights the first.
 */
private fun ensureBinOnPath(repoDir: File) {
    val binDir = File(repoDir, "bin").absoluteFile.path
    val path = System.getenv("PATH").orEmpty()
    if (path.split(File.pathSeparatorChar).any { it.contains("KotlinDynamicRuntime") && it.trimEnd('/').endsWith("bin") }) {
        return // a KotlinDynamicRuntime bin is already on PATH; leave the user's setup alone.
    }
    val line = "export PATH=\"\$PATH:$binDir\""
    val rc = shellRcFile()
    if (rc == null) {
        println("This repo's bin is not on your PATH. Add it manually (shell config not recognized):")
        println("  $line")
        return
    }
    println("This repo's bin directory is not on your PATH.")
    println("I can append this line to ${rc.path}:")
    println("  $line")
    print("Add it? [y/N] ")
    if (!readYes()) {
        println("Left ${rc.name} unchanged.")
        return
    }
    rc.appendText("\n# KotlinDynamicRuntime bin (kdr-* command-line scripts)\n$line\n")
    println("Added. Refresh PATH in your current shell with:  source ${rc.path}")
}

/** The shell rc file to edit for PATH, per the current shell and OS, or null if it can't be determined. */
private fun shellRcFile(): File? {
    val home = File(System.getProperty("user.home"))
    val shell = System.getenv("SHELL").orEmpty().substringAfterLast('/')
    val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
    return when (shell) {
        "zsh" -> File(home, ".zshrc")
        "bash" -> File(home, if (isMac) ".bash_profile" else ".bashrc")
        else -> null
    }
}

/** Reads a yes/no answer; a bare Enter, EOF, or non-interactive stdin all mean "no". */
private fun readYes(): Boolean = readLine()?.trim()?.lowercase() in setOf("y", "yes")

/**
 * Drops the leading "EXAMPLE ... TEMPLATE, copy and rename me" fenced comment block from an example file, so
 * the live copy does not carry template instructions. The fence is a comment line of `=` characters; the
 * block runs from the first such line to the next. Files without that header are returned unchanged.
 */
private fun stripExampleHeader(text: String): String {
    val lines = text.lines()
    val isFence = { l: String -> l.trimStart('/', '#', ' ').startsWith("=====") }
    if (lines.isEmpty() || !isFence(lines[0])) {
        return text
    }
    val close = lines.drop(1).indexOfFirst(isFence)
    if (close < 0) {
        return text
    }
    return lines.drop(close + 2).dropWhile { it.isBlank() }.joinToString("\n") + "\n"
}
