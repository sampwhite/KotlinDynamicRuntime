package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ConfigCodeLayer
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.schema.SchGKeywords
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.webasset.WAS
import com.dynamicruntime.common.webasset.WRES
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.confdemo.confDemoConfigs
import com.dynamicruntime.kdn.confdemo.confDemoDocs
import com.dynamicruntime.kdn.confdemo.provisionConfDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Keeps the conference demo's documentation true (issue #1137). The pages in `docs/demo-client/` show the JSON of the
 * demo's bundles and walk through it; this holds every page to the demo in both forms a deployment may give it (issue
 * #1668): declared in code, as the `reference` flavor has it -- each bundle as the bundle editor serves a code bundle --
 * and data-only, provisioned as the simulation does, which fails on a configuration that does not load cleanly, each
 * bundle read back as stored. The pages show the demo as code declares it; the stored form is compared without the one
 * key only code may write, the client's `webResourcesId`:
 *
 * - **Every JSON block is marked**, on the line before its fence, with what it shows: `<!-- kdr-doc: bundle=base -->`
 *   for a bundle's whole `slots`, `<!-- kdr-doc: bundle=createTalk pointer=/kdr:workflowDef/0/definition -->` for a
 *   part of one (a JSON Pointer into the slots), or `<!-- kdr-doc: illustration -->` for a shape that is not the
 *   demo's. A marked block must equal what it names; a mismatch fails with the block as it should read, to paste in.
 * - **Every link resolves** -- checked with the rest of the repository's documentation by `RepoDocLinksTest`
 *   (`base:common`), not here.
 * - **Every backticked `g-` keyword is one the schema layer knows.**
 * - **Every page is declared** (`confDemoDocs`), and every declaration has its page.
 *
 * The comparison is of values, not text, so a block may be laid out for reading.
 */
class ConfDemoDocsTest : StringSpec({
    val cxt = TestInstances.default("confDemoDocs")
    // Data-only, provisioned once as the simulation does it: a configuration that does not load cleanly throws here.
    provisionConfDemo(cxt)
    // Declared in code: the reference flavor's demo, its bundles the kdn component's.
    val reference = TestInstances.reference("confDemoDocsReference")

    // The tests run in the module's directory (base/kdn), two below the repository root.
    val repoRoot = File("../..").canonicalFile
    val docsDir = File(repoRoot, ConfDemo.docsDir)
    val pages: List<File> = docsDir.listFiles { f -> f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }

    fun storedSlotsOf(bundle: String): Any? {
        val bound = cxt.mkSubContext("confDemoDocs", ConfDemo.client)
        val row = GedraConfigService.get(cxt).readLatest(bound, GedraId.of(GedraConfigType.configDoc, ConfDemo.client, bundle))
            ?: return null
        return row.slotsForEmission(cxt.instanceConfig.isTestInstance)
    }

    /** [bundle] of the demo declared in code, as the bundle editor's code layer serves it. */
    fun codeSlotsOf(bundle: String): Any? {
        val collector = SchemaCollector.get(reference).shouldNotBeNull()
        val config = GedraConfigLoadService.get(reference).ownSourceOf(collector, ConfDemo.client).firstOrNull { it.name == bundle }
            ?: return null
        return ConfigCodeLayer.redacted(ConfigCodeLayer.slotsOf(config), reference.instanceConfig.isTestInstance)
    }

    "the demo has its pages" {
        pages.map { it.name } shouldBe confDemoDocs().map { it.sourcePath.substringAfterLast('/') }.sorted()
    }

    "the reference deployment declares the demo in code, and a deployment otherwise stores it" {
        GedraConfigLoadService.get(reference).ownSourceOf(SchemaCollector.get(reference).shouldNotBeNull(), ConfDemo.client)
            .map { it.name } shouldBe confDemoConfigs().map { it.name }
        GedraConfigService.get(reference).listConfigs(reference.mkSubContext("confDemoDocs", ConfDemo.client)).shouldBeEmpty()
    }

    "every JSON block shows what the demo holds, declared in code" {
        checkBlocks(pages, ::codeSlotsOf).shouldBeEmpty()
    }

    "every JSON block shows what the demo holds, as stored, but for the web resources only code may name" {
        checkBlocks(pages, ::storedSlotsOf, ignoring = setOf(CLD.webResourcesId)).shouldBeEmpty()
    }

    "declared in code, the demo presents its brand mark, and leaves the tab icon the deployment's" {
        val pkg = WRES.declared(reference).single { it.def.id == ConfDemo.webResources }.def
        pkg.files shouldBe mapOf(WAS.brandMark to ConfDemo.brandMarkFile)
        ClientService.get(reference).present(ConfDemo.client).shouldNotBeNull().webResourcesId shouldBe ConfDemo.webResources
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

/**
 * What is wrong with [pages]' JSON blocks against the demo's bundles as [slotsOf] reads them, one line each, with the keys
 * [ignoring] left out of both sides.
 */
private fun checkBlocks(pages: List<File>, slotsOf: (String) -> Any?, ignoring: Set<String> = emptySet()): List<String> {
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
            val shown = runCatching { without(normalized(block.text), ignoring) }.getOrNull()
            if (shown == null || shown != without(normalized(expected.toJsonStr(compact = true)), ignoring)) {
                val at = if (target.pointer.isEmpty()) "" else " at ${target.pointer}"
                problems.add(
                    "$where: the block for bundle '${target.bundle}'$at does not match what the demo writes. " +
                        "It should read:\n```json\n${expected.toJsonStr()}\n```",
                )
            }
        }
    }
    return problems
}

/** What a JSON block shows, from its marker: a bundle and a pointer within its slots, or an illustration. */
private class DocTarget(val bundle: String, val pointer: String, val illustration: Boolean)

private class JsonBlock(val line: Int, val target: DocTarget?, val text: String)

private val markerRegex = Regex("""^\s*<!--\s*kdr-doc:\s*(.*?)\s*-->\s*$""")
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

/** [value] with every key in [keys] left out, at any depth. */
private fun without(value: Any?, keys: Set<String>): Any? = if (keys.isEmpty()) value else when (value) {
    is Map<*, *> -> value.filterKeys { it !in keys }.mapValues { without(it.value, keys) }
    is List<*> -> value.map { without(it, keys) }
    else -> value
}

/** [json] parsed, so two texts of one value compare equal however they are laid out. */
private fun normalized(json: String): Any? = "{\"v\": $json}".jsonMap()?.get("v")
