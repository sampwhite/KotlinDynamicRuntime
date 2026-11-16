package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.sharedCopyPageProblems
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.pageWidgetsReference
import com.dynamicruntime.common.home.shippedHomeDocument
import com.dynamicruntime.common.page.PageFenceSource
import com.dynamicruntime.common.page.PageWidgets
import com.dynamicruntime.common.page.readPageFence
import com.dynamicruntime.common.page.splitPageDocument
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Home is the page `home` (issue #1794): a document in the `home` file, drawn by the generic renderer. A wrong fence in
 * it would be left out silently where Home is drawn, so this holds the shipped document to the checks, to the order Home
 * has always drawn its parts in, and to the reference that shows it as the worked example.
 */
class HomePageDocumentTest : StringSpec({
    val cxt = TestInstances.default("homePageDocument")

    "the shipped home document places every part of Home, in its order, and passes the checks" {
        val document = shippedHomeDocument().shouldNotBeNull()
        val widgets = splitPageDocument(document).filterIsInstance<PageFenceSource>()
            .map { readPageFence(it).getOrThrow() }
            .map { it.widget ?: "copy:${it.copy}" }
        widgets shouldBe listOf(
            PageWidgets.testAccess.name, PageWidgets.hero.name, PageWidgets.pageTitle.name, PageWidgets.jumpLinks.name,
            PageWidgets.cardsBlock.name, "copy:home.intro", "copy:home.note", PageWidgets.cardsBlock.name,
            "copy:publicHome.walkThrough", PageWidgets.joinableDemos.name, PageWidgets.createOwnClient.name,
            PageWidgets.docLinks.name,
        )
        // Every layer every client shares, the shipped file and the flavors' overlays: no fence is wrong.
        sharedCopyPageProblems(cxt, MarkdownFragmentService.registeredFragmentSources(cxt)).shouldBeEmpty()
        // And it is what the shell serves, before any client's overlay.
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, null).shouldNotBeNull()
            .content[HFRAG.pageNs]?.get(HFRAG.pageHome) shouldBe document
    }

    "the page widgets reference shows the shipped document as its worked example" {
        val reference = pageWidgetsReference()
        reference shouldContain "## Home, a worked example"
        reference shouldContain shippedHomeDocument().shouldNotBeNull().trim()
    }
})
