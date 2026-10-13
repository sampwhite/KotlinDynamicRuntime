package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.schema.SchGKeywords
import com.dynamicruntime.common.util.headingSlug
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.confdemo.confDemoDocs
import com.dynamicruntime.kdn.confdemo.provisionConfDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Keeps the conference demo's documentation true (issue #1137). The pages in `docs/demo-client/` show the JSON of the
 * demo's bundles and walk through it; this provisions the demo as a reader would -- which fails on a configuration that
 * does not load cleanly -- reads each bundle back as stored, and holds every page to it:
 *
 * - **Every JSON block is marked**, on the line before its fence, with what it shows: `<!-- kdr-doc: bundle=base -->`
 *   for a bundle's whole `slots`, `<!-- kdr-doc: bundle=createTalk pointer=/kdr:workflowDef/0/definition -->` for a
 *   part of one (a JSON Pointer into the slots), or `<!-- kdr-doc: illustration -->` for a shape that is not the
 *   demo's. A marked block must equal what it names; a mismatch fails with the block as it should read, to paste in.
 * - **Every link resolves**: a page or repo file that exists, and a `#section` that is a heading of the page it names.
 *   An app route (`#page=...`) and an outside address are left alone.
 * - **Every backticked `g-` keyword is one the schema layer knows.**
 * - **Every page is declared** (`confDemoDocs`), and every declaration has its page.
 *
 * The comparison is of values, not text, so a block may be laid out for reading.
 */
class ConfDemoDocsTest : StringSpec({
    val cxt = TestInstances.default("confDemoDocs")
    // Provisioned once, as the simulation does it: a configuration that does not load cleanly throws here.
    provisionConfDemo(cxt)

    // The tests run in the module's directory (base/kdn), two below the repository root.
    val repoRoot = File("../..").canonicalFile
    val docsDir = File(repoRoot, ConfDemo.docsDir)
    val pages: List<File> = docsDir.listFiles { f -> f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }

    fun slotsOf(bundle: String): Any? {
        val bound = cxt.mkSubContext("confDemoDocs", ConfDemo.client)
        val row = GedraConfigService.get(cxt).readLatest(bound, GedraId.of(GedraConfigType.configDoc, ConfDemo.client, bundle))
            ?: return null
        return row.slotsForEmission(cxt.instanceConfig.isTestInstance)
    }

    "the demo has its pages" {
        pages.map { it.name } shouldBe confDemoDocs().map { it.sourcePath.substringAfterLast('/') }.sorted()
    }

    "every JSON block shows what the demo writes" {
        val problems = mutableListOf<String>()
        for (page in pages) {
            for (block in jsonBlocks(page)) {
                val where = "${page.name}, line ${block.line}"
                val target = block.target ?: run {
                    problems.add("$where: a JSON block with no `<!-- kdr-doc: ... -->` marker on the line before it.")
                    null
                } ?: continue
                if (target.illustration) continue
                val slots = slotsOf(target.bundle) ?: run {
                    problems.add("$where: the demo writes no bundle '${target.bundle}'.")
                    null
                } ?: continue
                val expected = valueAt(slots, target.pointer) ?: run {
                    problems.add("$where: '${target.pointer}' names nothing in bundle '${target.bundle}'.")
                    null
                } ?: continue
                val shown = runCatching { normalized(block.text) }.getOrNull()
                if (shown == null || shown != normalized(expected.toJsonStr(compact = true))) {
                    val at = if (target.pointer.isEmpty()) "" else " at ${target.pointer}"
                    problems.add(
                        "$where: the block for bundle '${target.bundle}'$at does not match what the demo writes. " +
                            "It should read:\n```json\n${expected.toJsonStr()}\n```",
                    )
                }
            }
        }
        problems.shouldBeEmpty()
    }

    "every link resolves, to a page or file that exists and a section it has" {
        val problems = mutableListOf<String>()
        for (page in pages) {
            for ((line, target) in links(page)) {
                val where = "${page.name}, line $line"
                // An app route, which only the running app answers, or an address outside the repository.
                if (target.contains("://") || target.startsWith("mailto:") || (target.startsWith("#") && target.contains("="))) continue
                val path = target.substringBefore('#')
                val anchor = target.substringAfter('#', "").ifEmpty { null }
                val file = when {
                    path.isEmpty() -> page
                    path.startsWith("/") -> File(repoRoot, path.removePrefix("/"))
                    else -> File(page.parentFile, path)
                }.canonicalFile
                if (!file.exists() || !file.path.startsWith(repoRoot.path)) {
                    problems.add("$where: '$target' names '$path', which is not in the repository.")
                    continue
                }
                if (anchor != null && anchor !in headingSlugs(file)) {
                    problems.add("$where: '$target' names the section '#$anchor', which '${file.name}' has no heading for.")
                }
            }
        }
        problems.shouldBeEmpty()
    }

    "every g- keyword named is one the schema layer knows" {
        val known = SchGKeywords.keywords
        val problems = pages.flatMap { page ->
            gKeywordRegex.findAll(page.readText()).map { it.groupValues[1] }.filter { it !in known }
                .map { "${page.name}: `$it` is not a keyword the schema layer knows." }.toList()
        }
        problems.shouldBeEmpty()
    }
})

/** What a JSON block shows, from its marker: a bundle and a pointer within its slots, or an illustration. */
private class DocTarget(val bundle: String, val pointer: String, val illustration: Boolean)

private class JsonBlock(val line: Int, val target: DocTarget?, val text: String)

private val markerRegex = Regex("""^\s*<!--\s*kdr-doc:\s*(.*?)\s*-->\s*$""")
private val linkRegex = Regex("""\[[^]]*]\(([^)\s]+)\)""")
private val gKeywordRegex = Regex("""`(g-[A-Za-z]+)`""")

/** Each ```json block of [page], with the marker on the line before it. */
private fun jsonBlocks(page: File): List<JsonBlock> {
    val lines = page.readLines()
    val out = mutableListOf<JsonBlock>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].trim() == "```json") {
            val marker = lines.getOrNull(i - 1)?.let { markerRegex.find(it)?.groupValues?.get(1) }
            val end = (i + 1 until lines.size).firstOrNull { lines[it].trim() == "```" } ?: lines.size
            out.add(JsonBlock(i + 1, marker?.let { targetOf(it) }, lines.subList(i + 1, end).joinToString("\n")))
            i = end + 1
        } else {
            i++
        }
    }
    return out
}

/** A marker's `bundle=... pointer=...`, or `illustration`. */
private fun targetOf(marker: String): DocTarget? {
    if (marker == "illustration") return DocTarget("", "", illustration = true)
    val fields = marker.split(Regex("\\s+")).mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
        .associate { it[0] to it[1] }
    val bundle = fields["bundle"] ?: return null
    return DocTarget(bundle, fields["pointer"].orEmpty(), illustration = false)
}

/** The value at JSON Pointer [pointer] within [root] (RFC 6901), or null when it names nothing. */
private fun valueAt(root: Any?, pointer: String): Any? {
    if (pointer.isEmpty()) return root
    var at = root
    for (raw in pointer.removePrefix("/").split('/')) {
        val key = raw.replace("~1", "/").replace("~0", "~")
        at = when (at) {
            is Map<*, *> -> if (at.containsKey(key)) at[key] else return null
            is List<*> -> key.toIntOrNull()?.let { at.getOrNull(it) } ?: return null
            else -> return null
        }
    }
    return at
}

/** [json] parsed, so two texts of one value compare equal however they are laid out. */
private fun normalized(json: String): Any? = "{\"v\": $json}".jsonMap()?.get("v")

/** Each Markdown link of [page] outside code, with its line number. */
private fun links(page: File): List<Pair<Int, String>> {
    var inFence = false
    return page.readLines().flatMapIndexed { i, line ->
        if (line.trimStart().startsWith("```")) inFence = !inFence
        if (inFence) emptyList() else linkRegex.findAll(line.replace(Regex("`[^`]*`"), "")).map { i + 1 to it.groupValues[1] }.toList()
    }
}

/** The anchor ids [file]'s headings render with, as GitHub and the app both mint them. */
private fun headingSlugs(file: File): Set<String> {
    if (!file.name.endsWith(".md")) return emptySet()
    var inFence = false
    return file.readLines().mapNotNull { line ->
        if (line.trimStart().startsWith("```")) inFence = !inFence
        if (inFence) null else Regex("""^#{1,6}\s+(.*?)\s*#*\s*$""").find(line)?.groupValues?.get(1)?.let { headingSlug(it) }
    }.toSet()
}
