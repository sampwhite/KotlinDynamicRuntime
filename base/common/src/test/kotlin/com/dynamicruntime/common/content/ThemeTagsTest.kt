package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

@Suppress("ConstPropertyName")
object TTAG {
    /** The system property `themeTagsTest` passes the scanned files in, separated by the platform's path separator. */
    const val filesProperty = "kdr.themeTagFiles"

    /** The registry: every tag, what it names, and when to tag a place with it. */
    const val registryPath = "docs/development/theme-tags.md"

    /** Fewer places than this, and a tag is unused or names no theme. */
    const val minPlaces = 3

    /** More places than this, and a tag is too broad to review: a candidate to split. A warning, not a failure. */
    const val broadPlaces = 40

    /** Where a tag must never be: what a client or an end user reads. */
    val forbiddenPrefixes = listOf("docs/reference/")
    const val copyFileDir = "/md-fragments/"
}

/**
 * Theme tags (issue #1630): invented tokens such as `SBXAU`, written as a hashtag, each marking every place that
 * implements or describes one cross-cutting idea, so a change to it can find every description it might make wrong.
 * The registry, [TTAG.registryPath], lists them; this holds the repository to it. Every hashtag of four to six capitals
 * is a registered tag; every registered tag marks at least [TTAG.minPlaces] places; none is in a generated reference or
 * a copy file. Each tag's count is printed, with a warning past [TTAG.broadPlaces].
 *
 * A hashtag made only of the letters A to F is a hex color (`#FFFFFF`), not a tag, so no tag may be spelled that way.
 *
 * The files scanned are listed once, in base/common's build, and passed in ([TTAG.filesProperty]) as the inputs of the
 * `themeTagsTest` task that runs this, as `docLinksTest` does. Run it with `./gradlew :base:common:themeTagsTest`.
 */
class ThemeTagsTest : StringSpec({
    // The tests run in the module's directory (base/common), two below the repository root.
    val repoRoot = File("../..").canonicalFile
    val registryFile = File(repoRoot, TTAG.registryPath)

    fun scanned(): List<File> {
        val paths = System.getProperty(TTAG.filesProperty)
            ?: throw KdrException("No '${TTAG.filesProperty}' system property: run this through Gradle, as " +
                "`./gradlew :base:common:themeTagsTest`, which passes the files to scan.")
        return paths.split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it).canonicalFile }
            .filter { it.isFile && it != registryFile }
    }

    fun path(file: File) = file.relativeTo(repoRoot).invariantSeparatorsPath

    "the tag pattern takes a hashtag of four to six capitals, and leaves a color and a longer word alone" {
        val h = "#"
        tagsIn("Theme: ${h}QWERT. See ${h}ZXCV, and ${h}ASDFG.").shouldContainExactly("QWERT", "ZXCV", "ASDFG")
        tagsIn("color ${h}FFFFFF, ${h}ABCDE; ${h}QWERTYU; ${h}QWE; ${h}Qwert; page${h}QWERT; &${h}QWERT").shouldBeEmpty()
    }

    "the registry lists each tag once, each shaped as a tag" {
        val registered = registeredTags(registryFile)
        registered.size shouldBeGreaterThan 0
        registered.groupBy { it }.filterValues { it.size > 1 }.keys.shouldBeEmpty()
        registered.filter { tagsIn("#$it") != listOf(it) }.shouldBeEmpty()
    }

    "every tag used is registered, none is in a generated reference or a copy file, and each marks enough places" {
        val registered = registeredTags(registryFile).toSet()
        val files = scanned()
        files.size shouldBeGreaterThan 100
        val counts = registered.associateWith { 0 }.toMutableMap()
        val unregistered = mutableListOf<String>()
        val misplaced = mutableListOf<String>()
        for (file in files) {
            val where = path(file)
            val forbidden = TTAG.forbiddenPrefixes.any { where.startsWith(it) } || "/$where".contains(TTAG.copyFileDir)
            file.readLines().forEachIndexed { i, line ->
                for (tag in tagsIn(line)) {
                    if (tag !in registered) {
                        unregistered.add("$where, line ${i + 1}: '#$tag' is not in ${TTAG.registryPath}.")
                    } else {
                        counts[tag] = counts.getValue(tag) + 1
                        if (forbidden) misplaced.add("$where, line ${i + 1}: '#$tag' is in what a client or an end user reads.")
                    }
                }
            }
        }
        println("Theme tags, places marked: " + counts.entries.joinToString(", ") { "#${it.key} ${it.value}" })
        counts.filterValues { it > TTAG.broadPlaces }.forEach { (tag, n) ->
            println("WARNING: #$tag marks $n places, more than ${TTAG.broadPlaces}: too broad to review; consider splitting it.")
        }
        withClue("Hashtags shaped as theme tags but not registered") { unregistered.shouldBeEmpty() }
        withClue("Theme tags where they must not be: a generated reference or a copy file") { misplaced.shouldBeEmpty() }
        withClue("Registered tags marking fewer than ${TTAG.minPlaces} places: unused, or not a theme") {
            counts.filterValues { it < TTAG.minPlaces }.keys.shouldBeEmpty()
        }
    }
})

/** A theme tag as written: a hashtag of four to six capitals, not inside a word or an address, nor an entity. */
private val tagRegex = Regex("""(?<![\w&/#])#([A-Z]{4,6})(?!\w)""")

/** The theme tags in [text], in order, leaving out a hex color: a hashtag made only of the letters A to F. */
private fun tagsIn(text: String): List<String> =
    tagRegex.findAll(text).map { it.groupValues[1] }.filterNot { tag -> tag.all { it in 'A'..'F' } }.toList()

/** The tags the registry lists: the first cell of each table row, a code span holding the hashtag. */
private fun registeredTags(registry: File): List<String> =
    registry.readLines().mapNotNull { Regex("""^\|\s*`#([A-Z]{4,6})`\s*\|""").find(it)?.groupValues?.get(1) }
