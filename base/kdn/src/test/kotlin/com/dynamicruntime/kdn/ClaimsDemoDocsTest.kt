package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.kdn.claimsdemo.ClaimsDemo
import com.dynamicruntime.kdn.claimsdemo.claimsDemoConfigs
import com.dynamicruntime.kdn.claimsdemo.claimsDemoDocs
import com.dynamicruntime.kdn.claimsdemo.provisionClaimsDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Keeps the claims demo's documentation true (issue #1897), as `ConfDemoDocsTest` does the conference demo's: every JSON
 * block on its pages shows what the demo stores (`DemoDocBlocks.kt`), every bundle is shown whole on some page but the
 * base one, whose parts are, and every `g-` keyword named is one the schema layer knows.
 *
 * Theme: #DOCDR.
 */
class ClaimsDemoDocsTest : StringSpec({
    val cxt = TestInstances.default("claimsDemoDocs")
    // Provisioned as the simulation does it: a configuration that does not load cleanly throws here.
    provisionClaimsDemo(cxt)

    // The tests run in the module's directory (base/kdn), two below the repository root.
    val repoRoot = File("../..").canonicalFile
    val docsDir = File(repoRoot, ClaimsDemo.docsDir)
    val pages: List<File> = docsDir.listFiles { f -> f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }

    fun slotsOf(bundle: String): Any? {
        val bound = cxt.mkSubContext("claimsDemoDocs", ClaimsDemo.client)
        val row = GedraConfigService.get(cxt).readLatest(bound, GedraId.of(GedraConfigType.configDoc, ClaimsDemo.client, bundle))
            ?: return null
        return row.slotsForEmission(cxt.instanceConfig.isTestInstance)
    }

    "the demo has its pages" {
        pages.map { it.name } shouldBe claimsDemoDocs().map { it.sourcePath.substringAfterLast('/') }.sorted()
    }

    "every JSON block shows what the demo stores" {
        checkDocBlocks(pages, ::slotsOf).shouldBeEmpty()
    }

    "every bundle is shown: the base one in parts, and each other one whole" {
        val text = pages.joinToString("\n") { it.readText() }
        val slots = (slotsOf(ClaimsDemo.baseConfig) as Map<*, *>).keys
        val missing = buildList {
            for (slot in slots) if ("bundle=${ClaimsDemo.baseConfig} pointer=/$slot" !in text) add("${ClaimsDemo.baseConfig} $slot")
            for (config in claimsDemoConfigs()) {
                if (config.name != ClaimsDemo.baseConfig && "<!-- kdr-doc: bundle=${config.name} -->" !in text) add(config.name)
            }
        }
        missing.shouldBeEmpty()
    }

    "every g- keyword named is one the schema layer knows" {
        unknownGKeywords(pages).shouldBeEmpty()
    }
})
