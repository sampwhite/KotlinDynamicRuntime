package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.kdn.grantsdemo.GrantsDemo
import com.dynamicruntime.kdn.grantsdemo.grantsDemoConfigs
import com.dynamicruntime.kdn.grantsdemo.grantsDemoDocs
import com.dynamicruntime.kdn.grantsdemo.provisionGrantsDemo
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Keeps the grants demo, its documentation, and the copy notes true to each other (issue #1799). The demo rewords every
 * piece of copy a client is likely to reword: each key whose copy note names where a person sees it. So:
 *
 * - **The demo rewords exactly the located keys.** A key that gains a location -- a new mail's subject, say -- fails
 *   here until the demo rewords it, and a key the demo rewords with no location fails until it has one.
 * - **Each located key has one row on the pages**, in a table headed `| Where it is | Key | As shipped | In the demo |`:
 *   its note, its address (`home: home.title`), the words the runtime ships, and the demo's. A cell may end in `…` to
 *   show only the start of long words, and `(none)` is no words at all.
 * - **Every JSON block shows what the demo stores**, marked as the conference demo's pages are (`DemoDocBlocks.kt`).
 * - **Every page is declared** (`grantsDemoDocs`), and every declaration has its page.
 *
 * The demo is provisioned as its simulation does, which fails on a configuration that does not load cleanly.
 *
 * Theme: #CUSTX, #DOCDR.
 */
class GrantsDemoDocsTest : StringSpec({
    val cxt = TestInstances.default("grantsDemoDocs")
    provisionGrantsDemo(cxt)
    val fragments = MarkdownFragmentService.get(cxt)

    val repoRoot = File("../..").canonicalFile
    val pages: List<File> = File(repoRoot, GrantsDemo.docsDir).listFiles { f -> f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }

    /** Every located key on this node, by address, with its note. */
    val located: Map<CopyAddress, String> = MarkdownFragmentService.registeredFragmentSources(cxt)
        .filter { !it.isOverlay && it.client == null }.map { it.fileId }.distinct()
        .flatMap { fileId ->
            val notes = fragments.copyNotes(fileId)
            fragments.effectiveFragmentsFor(cxt, fileId, null)?.content.orEmpty().flatMap { (ns, keys) ->
                keys.keys.mapNotNull { key -> notes.whereOf(ns, key)?.let { CopyAddress(fileId, ns, key) to it } }
            }
        }.toMap()

    /** What the demo's words bundles say, by address. */
    val reworded: Map<CopyAddress, String> = grantsDemoConfigs(cxt).filter { it.name in GrantsDemo.wordsConfigs }
        .flatMap { config -> config.fragments }
        .flatMap { source ->
            source.load().orEmpty().flatMap { (ns, keys) -> keys.map { (key, value) -> CopyAddress(source.fileId, ns, key) to value } }
        }.toMap()

    fun shipped(at: CopyAddress): String? = fragments.effectiveFragmentsFor(cxt, at.fileId, null)?.content?.get(at.namespace)?.get(at.key)

    "the demo has its pages" {
        pages.map { it.name } shouldBe grantsDemoDocs().map { it.sourcePath.substringAfterLast('/') }.sorted()
    }

    "the demo rewords exactly the keys whose copy note says where a person sees them" {
        withClue("Located keys the demo does not reword; add each to one of its words bundles (GrantsDemo.kt) and its page") {
            (located.keys - reworded.keys).map { it.toString() }.shouldBeEmpty()
        }
        withClue("Keys the demo rewords that say nowhere; give each a copy note, or leave it to the platform") {
            (reworded.keys - located.keys).map { it.toString() }.shouldBeEmpty()
        }
    }

    "each located key has one row on the pages, saying where it is, what ships, and what the demo says" {
        val rows = pages.flatMap { page -> wordsRows(page) }
        val problems = mutableListOf<String>()
        for (row in rows) {
            val where = "${row.page}, line ${row.line}"
            val at = row.address ?: run {
                problems.add("$where: the key `${row.key}` is not written `<file>: <namespace>.<key>`.")
                null
            } ?: continue
            val note = located[at] ?: run {
                problems.add("$where: $at is not a key whose copy note says where it is.")
                null
            } ?: continue
            if (row.where != note) problems.add("$where: $at is where its note says: \"$note\".")
            if (!cellShows(row.shipped, shipped(at))) problems.add("$where: $at ships as \"${shipped(at)}\".")
            if (!cellShows(row.demo, reworded[at])) problems.add("$where: the demo says $at is \"${reworded[at]}\".")
        }
        val shown = rows.mapNotNull { it.address }
        shown.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { problems.add("$it has more than one row.") }
        (located.keys - shown.toSet()).forEach { problems.add("$it has no row on any page, under the table heading the rows share.") }
        problems.shouldBeEmpty()
    }

    "every JSON block shows what the demo stores" {
        fun storedSlotsOf(bundle: String): Any? {
            val bound = cxt.mkSubContext("grantsDemoDocs", GrantsDemo.client)
            val row = GedraConfigService.get(cxt).readLatest(bound, GedraId.of(GedraConfigType.configDoc, GrantsDemo.client, bundle))
                ?: return null
            return row.slotsForEmission(cxt.instanceConfig.isTestInstance)
        }
        checkDocBlocks(pages, ::storedSlotsOf).shouldBeEmpty()
    }
})

/** A copy key's address: its file, namespace, and key. */
private data class CopyAddress(val fileId: String, val namespace: String, val key: String) {
    override fun toString(): String = "`$fileId: $namespace.$key`"
}

/** One row of a page's words table. */
private class WordsRow(val page: String, val line: Int, val where: String, val key: String, val shipped: String, val demo: String) {
    val address: CopyAddress? = Regex("""^([A-Za-z0-9]+): ([A-Za-z0-9]+)\.([A-Za-z0-9]+)$""").find(key)?.groupValues
        ?.let { CopyAddress(it[1], it[2], it[3]) }
}

private const val wordsHeading = "| Where it is | Key | As shipped | In the demo |"

/** The rows of every words table on [page]: those under [wordsHeading], to the table's end. */
private fun wordsRows(page: File): List<WordsRow> {
    val lines = page.readLines()
    val rows = mutableListOf<WordsRow>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].trim() != wordsHeading) {
            i++
            continue
        }
        i += 2
        while (i < lines.size && lines[i].trim().startsWith("|")) {
            val cells = splitRow(lines[i])
            if (cells.size == 4) rows.add(WordsRow(page.name, i + 1, cells[0], unwrap(cells[1]), cells[2], cells[3]))
            i++
        }
    }
    return rows
}

/** A table row's cells, trimmed, an escaped `\|` kept as a `|` within its cell. */
private fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").replace("\\|", "\u0000").split('|').map { it.replace('\u0000', '|').trim() }

/** A cell without the backticks around it, if it is one code span. */
private fun unwrap(cell: String): String =
    if (cell.length >= 2 && cell.startsWith('`') && cell.endsWith('`')) cell.substring(1, cell.length - 1) else cell

/**
 * Whether [cell] shows [value]: the same words, however they are spaced (a table row is one line), or the start of them
 * followed by `…`. `(none)` shows no words.
 */
private fun cellShows(cell: String, value: String?): Boolean {
    fun spaced(text: String) = text.replace(Regex("\\s+"), " ").trim()
    val words = spaced(unwrap(cell))
    val actual = spaced(value.orEmpty())
    if (words == "(none)") return actual.isEmpty()
    return if (words.endsWith("…")) actual.startsWith(words.removeSuffix("…").trimEnd()) else words == actual
}
