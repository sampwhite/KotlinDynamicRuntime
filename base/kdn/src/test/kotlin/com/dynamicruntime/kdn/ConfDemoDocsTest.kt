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
 *
 * Theme: #DOCDR.
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
        checkDocBlocks(pages, ::codeSlotsOf).shouldBeEmpty()
    }

    "every JSON block shows what the demo holds, as stored, but for the web resources only code may name" {
        checkDocBlocks(pages, ::storedSlotsOf, ignoring = setOf(CLD.webResourcesId)).shouldBeEmpty()
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

private val gKeywordRegex = Regex("""`(g-[A-Za-z]+)`""")
