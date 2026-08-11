package com.dynamicruntime.script

import java.io.File

/**
 * Helpers for reading and editing the deployment's `settings.gradle.kts`, shared by the `kdr command`s that
 * touch it -- `kdr-install` (com.dynamicruntime.script.Install) and `kdr-create-config`
 * (com.dynamicruntime.script.CreateCustomConfig). Everything here is non-destructive and idempotent: it detects
 * what is already present (a *commented-out* construct counts as present -- leaving it out was a deliberate
 * choice) and only adds what is genuinely missing.
 */

/** Reads a yes/no answer; a bare Enter, EOF, or non-interactive stdin all mean "no". */
internal fun readYes(): Boolean = readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")

internal data class Include(val path: String, val projectDir: String?)

/**
 * The `include(...)` entries in a settings file, each paired with its `projectDir` if one is declared.
 * Commented-out includes are returned only when [includeCommented] is set (used to detect deliberate opt-outs).
 */
internal fun parseIncludes(text: String, includeCommented: Boolean): List<Include> {
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

/** The sentinel marker that delimits the deployment-injection prologue in `settings.gradle.kts(.example)`. */
internal const val injectionMarker = "kdr-injection-prologue"

/**
 * Ensures the live `settings.gradle.kts` carries the deployment-injection prologue (issue #171) — the
 * `injectComponent(...)` helper and the registry handoff that let a deployment co-build custom config (and,
 * later, custom components) and wire them onto `launch`'s runtime classpath. A settings file predating the
 * prologue has the `include(...)` lines but not this block, so `injectComponent(...)` calls would fail to
 * resolve. Offers to insert the canonical block (lifted verbatim from the example) just after
 * `rootProject.name`; silent when it is already present. It prompts and never edits without a yes.
 *
 * **Returns whether the prologue is present when it returns** -- already there, or just added. A caller about
 * to write an `injectComponent(...)` call must check it: writing that call without the prologue leaves a
 * settings file that cannot compile, which stops *every* Gradle command in the workspace rather than just the
 * feature being wired (issue #257). There are three ways to come back false -- a declined prompt,
 * a non-interactive run (see [readYes], where EOF means no), and a settings file with no `rootProject.name`
 * to anchor on -- and the original bug was that none of them were distinguishable from success.
 */
internal fun ensureInjectionPrologue(workDir: File, examples: File): Boolean {
    val settings = File(workDir, "settings.gradle.kts")
    if (!settings.isFile) {
        return false
    }
    // Checked before the example file is needed: a workspace that already carries the prologue is wired
    // whether or not this checkout can produce the block to insert.
    val liveText = settings.readText()
    if (hasInjectionPrologue(liveText)) {
        return true
    }
    val example = File(examples, "settings.gradle.kts.example")
    if (!example.isFile) {
        return false
    }
    val block = extractMarkedBlock(example.readText(), injectionMarker) ?: return false
    println("Your settings.gradle.kts is missing the deployment-injection prologue (issue #171):")
    println("it defines injectComponent(...), used to co-build and inject custom config / custom components.")
    print("Add it (just after rootProject.name)? [y/N] ")
    if (!readYes()) {
        println("Left settings.gradle.kts unchanged. To add it later, copy the marked block from")
        println("  ${example.path}")
        return false
    }
    val updated = insertInjectionPrologue(liveText, block)
    if (updated == null) {
        println("WARNING: no 'rootProject.name' line to anchor on; add the prologue by hand, copying the marked")
        println("         block from ${example.path}.")
        return false
    }
    settings.writeText(updated)
    println("Added the injection prologue to settings.gradle.kts.")
    return true
}

/** Whether [settingsText] already defines the deployment-injection prologue (by its `injectComponent` helper). */
fun hasInjectionPrologue(settingsText: String): Boolean = settingsText.contains("fun injectComponent(")

/**
 * The marker-delimited block from [text] — the `>>> [marker]` line through the matching `<<< [marker]` line,
 * inclusive — or null if that pair is absent. Lets a caller lift a canonical block out of the example verbatim,
 * so there is a single source of truth for what gets inserted. Non-private for tests.
 */
fun extractMarkedBlock(text: String, marker: String): String? {
    val lines = text.split("\n")
    val start = lines.indexOfFirst { it.contains(">>> $marker") }
    if (start < 0) {
        return null
    }
    val relEnd = lines.drop(start + 1).indexOfFirst { it.contains("<<< $marker") }
    if (relEnd < 0) {
        return null
    }
    return lines.subList(start, start + 1 + relEnd + 1).joinToString("\n")
}

/** Returns [text] with the `>>> [marker]` .. `<<< [marker]` span removed (plus one blank line directly above it,
 * so no double gap is left), or [text] unchanged when the markers are absent. Non-private for tests. */
fun removeMarkedBlock(text: String, marker: String): String {
    val lines = text.split("\n")
    val start = lines.indexOfFirst { it.contains(">>> $marker") }
    if (start < 0) {
        return text
    }
    val relEnd = lines.drop(start + 1).indexOfFirst { it.contains("<<< $marker") }
    if (relEnd < 0) {
        return text
    }
    var s = start
    val e = start + 1 + relEnd
    if (s > 0 && lines[s - 1].isBlank()) {
        s--
    }
    return (lines.subList(0, s) + lines.subList(e + 1, lines.size)).joinToString("\n")
}

/**
 * The line-index of a trailing "keep me last" sentinel in a shell rc -- a line declaring it must stay at the end
 * of the file, as sdkman writes (`# THIS MUST BE AT THE END ...`) -- or -1 if there is none.
 */
private fun tailSentinelIndex(lines: List<String>): Int =
    lines.indexOfFirst { it.contains("MUST BE AT THE END", ignoreCase = true) }

/**
 * Splices [addition] into [rcText] as a self-contained block, separated from its neighbors by a blank line:
 * immediately *before* a trailing "keep me last" sentinel (see [tailSentinelIndex]) when one is present -- so a
 * tool like sdkman that must have the final word keeps it -- otherwise at the end. The result ends with a newline.
 * Non-private for tests.
 */
fun spliceIntoRc(rcText: String, addition: String): String {
    val add = addition.trim('\n')
    val lines = if (rcText.isEmpty()) emptyList() else rcText.split("\n")
    val sentinel = tailSentinelIndex(lines)
    val insertAt = if (sentinel < 0) {
        lines.size
    } else {
        var t = sentinel
        while (t > 0 && lines[t - 1].isBlank()) {
            t--
        }
        t
    }
    val before = lines.subList(0, insertAt).joinToString("\n").trimEnd('\n')
    val after = lines.subList(insertAt, lines.size).joinToString("\n").trim('\n')
    val parts = mutableListOf<String>()
    if (before.isNotEmpty()) {
        parts += before
        parts += ""
    }
    parts += add
    if (after.isNotEmpty()) {
        parts += ""
        parts += after
    }
    return parts.joinToString("\n") + "\n"
}

/** Whether the `>>> [marker]` block sits *below* a trailing "keep me last" sentinel, so it should be moved up. */
fun isBelowTailSentinel(text: String, marker: String): Boolean {
    val lines = text.split("\n")
    val sentinel = tailSentinelIndex(lines)
    if (sentinel < 0) {
        return false
    }
    return lines.indexOfFirst { it.contains(">>> $marker") } > sentinel
}

/**
 * Inserts [prologue] into [settingsText] just after the `rootProject.name` line (with a blank line before it),
 * or null when there is no such line to anchor on. The prologue's registry handoff runs deferred, so this
 * placement — above any later `injectComponent(...)` calls — is correct. Non-private for tests.
 */
fun insertInjectionPrologue(settingsText: String, prologue: String): String? {
    val lines = settingsText.split("\n")
    val idx = lines.indexOfFirst { it.trimStart().startsWith("rootProject.name") }
    if (idx < 0) {
        return null
    }
    val result = lines.subList(0, idx + 1) + listOf("", prologue.trimEnd()) + lines.subList(idx + 1, lines.size)
    return result.joinToString("\n")
}

/**
 * Whether [settingsText] already references `injectComponent(...)` for [path] — active OR commented out. A
 * deliberately commented-out call counts as present (leaving it out was a choice), mirroring how `include(...)`
 * lines are treated, so a caller never re-adds it. Non-private for tests.
 */
fun hasInjectComponent(settingsText: String, path: String): Boolean =
    Regex("""^\s*(//\s*)?injectComponent\(\s*"${Regex.escape(path)}"""", RegexOption.MULTILINE)
        .containsMatchIn(settingsText)

/**
 * Ensures the live [settings] contains `injectComponent("[path]", "[dir]")`, so `launch` co-builds the project
 * and wires it onto its runtime classpath. A no-op when it is already present (a commented-out call counts).
 * Appends the call; the prologue's registry handoff runs deferred, so a call after it is fine.
 *
 * **Refuses when the injection prologue is absent** ([ensureInjectionPrologue] defines `injectComponent`),
 * returning false rather than writing a call to something undefined. That precondition was documented here
 * and merely trusted, and a caller that wrote the call anyway produced a `settings.gradle.kts` that could not
 * compile -- taking out every Gradle command in the workspace, for everyone using it, not only the feature
 * being wired (issue #257). Enforced here rather than only at the call site so a *future* caller inherits the
 * check instead of having to remember it.
 *
 * Returns whether the call is present on return.
 */
internal fun ensureInjectComponent(settings: File, path: String, dir: String): Boolean {
    val text = settings.readText()
    if (hasInjectComponent(text, path)) {
        return true
    }
    if (!hasInjectionPrologue(text)) {
        println("Skipped injectComponent(\"$path\", \"$dir\"): settings.gradle.kts has no injection prologue")
        println("to define it, and the call alone would stop the whole build from configuring.")
        return false
    }
    val lead = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
    settings.appendText(
        "$lead\n// Added by kdr-create-config: co-build and inject the customConfig provider project.\n" +
            "injectComponent(\"$path\", \"$dir\")\n",
    )
    println("Added injectComponent(\"$path\", \"$dir\") to settings.gradle.kts.")
    return true
}
