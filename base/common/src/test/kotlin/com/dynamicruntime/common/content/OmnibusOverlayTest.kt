package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * An omnibus file's layers (issue #1217): one ordinary overlay per section, so the merge and its checks treat them as
 * any other. Reads `md-fragments/omnibusTest1217_omnibus.md` from the test classpath.
 */
class OmnibusOverlayTest : StringSpec({

    val id = "omnibusTest1217"

    fun base(fileId: String, content: Map<String, Map<String, String>>) =
        FragmentSource(fileId, isOverlay = false, client = null, origin = "test base") { content }

    val home = base("home", mapOf("welcome" to mapOf("title" to "Welcome", "intro" to "Default intro")))
    val profile = base("profile", mapOf("page" to mapOf("heading" to "Profile", "note" to "Default note")))

    "each section is an overlay of its own file, from the omnibus file" {
        val layers = omnibusOverlay(id)
        layers.map { it.fileId } shouldContainExactly listOf("home", "profile", "nobody")
        layers.all { it.isOverlay && it.client == null && it.origin == omnibusOrigin(id) } shouldBe true
    }

    "one file overlays two others, each keeping the keys it does not name" {
        val sources = listOf(home, profile) + omnibusOverlay(id)
        val merged = mergeFragmentLayers("home", sources, client = null)
        merged.content.getValue("welcome") shouldBe mapOf("title" to "Omnibus title", "intro" to "Default intro")
        merged.orphans.shouldBeEmpty()
        mergeFragmentLayers("profile", sources, client = null).content.getValue("page") shouldBe
            mapOf("heading" to "Omnibus heading", "note" to "Default note")
    }

    "a section for a file no component declares is a file declared but absent" {
        // The boot check reports a merge with no base as "declared but absent", which refuses a strict boot.
        mergeFragmentLayers("nobody", listOf(home, profile) + omnibusOverlay(id), client = null).found shouldBe false
    }

    "an id declared twice is a finding, and once is not" {
        duplicateOmnibusDeclarations(listOf(home) + omnibusOverlay(id)).shouldBeEmpty()
        duplicateOmnibusDeclarations(omnibusOverlay(id) + omnibusOverlay(id)).single() shouldContain omnibusOrigin(id)
    }

    "a declared omnibus file that is absent fails as it is collected" {
        shouldThrow<KdrException> { omnibusOverlay("noSuchOmnibus1217") }.message shouldContain "declared but absent"
    }
})
