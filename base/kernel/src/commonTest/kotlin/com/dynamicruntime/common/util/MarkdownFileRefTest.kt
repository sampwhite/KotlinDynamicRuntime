package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * An image naming a client's file ref, `![alt](ref:logo)` (issue #1186), through the kernel renderer -- so the
 * frontend, which draws copy, and the backend, which judges it and renders mail, read the scheme alike.
 */
class MarkdownFileRefTest {
    private val names = mapOf("logo" to "logo:abc123")
    private val hooks = MarkdownHooks(refAddress = { id -> names[id]?.let { "/st/kdr/ref/$it" } })

    @Test
    fun aRefImageIsDrawnFromTheAddressTheSurfaceGivesForIt() {
        assertEquals(
            "<img src=\"/st/kdr/ref/logo:abc123\" alt=\"Our logo\">",
            "![Our logo](ref:logo)".renderMarkdownInline(hooks),
        )
        // Its attribute block is kept, as any image's is.
        assertEquals(
            "<img src=\"/st/kdr/ref/logo:abc123\" alt=\"x\" class=\"md-float-right\" width=\"120\">",
            "![x](ref:logo){.float-right width=120}".renderMarkdownInline(hooks),
        )
    }

    @Test
    fun aRefTheSurfaceCannotDrawIsItsAltTextNeverAnEmptyImage() {
        // No such ref.
        assertEquals("See <em>Our</em> &lt;mark&gt;", "See *Our* ![<mark>](ref:banner)".renderMarkdownInline(hooks))
        // A surface with no refs at all: a mail, the default.
        assertEquals("Our logo", "![Our logo](ref:logo)".renderMarkdownInline())
        assertEquals("<p>Our logo</p>", "![Our logo](ref:logo)".renderMarkdown(MarkdownHooks()).trim())
        // Plain text is the alt text, as for any image.
        assertEquals("Our logo", "![Our logo](ref:logo)".renderMarkdownText())
    }

    @Test
    fun theAddressIsStillGuardedAndTheLinkResolverIsNeverAskedAboutARef() {
        val asked = mutableListOf<String>()
        val bad = MarkdownHooks(resolveUrl = { asked.add(it); "/docs/$it" }, refAddress = { "javascript:alert(1)" })
        assertEquals("<img src=\"\" alt=\"a\">", "![a](ref:logo)".renderMarkdownInline(bad))
        assertEquals("<img src=\"/docs/pic.png\" alt=\"b\">", "![b](pic.png)".renderMarkdownInline(bad))
        assertEquals(listOf("pic.png"), asked)
    }

    @Test
    fun onlyAnImageSourceOfTheRefSchemeNamesARef() {
        assertEquals("logo", imageRefIdOf("ref:logo"))
        assertEquals("logo", imageRefIdOf("  ref:logo "))
        assertNull(imageRefIdOf("ref:"))
        assertNull(imageRefIdOf("refs/logo.png"))
        assertNull(imageRefIdOf("/st/kdr/ref/logo:abc"))
        assertNull(imageRefIdOf("REF:logo"))
        // A link to a ref is not one: refs are images (issue #1186 leaves link targets out), and it renders inert.
        assertEquals("<a href=\"\">x</a>", "[x](ref:logo)".renderMarkdownInline(hooks))
    }

    @Test
    fun anAnalysisSaysWhichRefsCopyNamesAndDoesNotCallThemInert() {
        val copy = "![a](ref:logo) and ![b](ref:banner)\n\n![c](ref:logo) [d](ref:other) ![e](pic.png)"
        assertEquals(listOf("logo", "banner"), copy.imageRefIds())

        val report = copy.analyzeMarkdown(hooks)
        val refs = report.links.filter { it.refId != null }
        assertEquals(listOf("/st/kdr/ref/logo:abc123", "", "/st/kdr/ref/logo:abc123"), refs.map { it.resolved })
        assertFalse(refs.any { it.inert })
        // The link to a ref is still the inert link it renders as, and the only problem.
        assertEquals(listOf(MarkdownIssue.inertLink), report.problems.map { it.code })
        assertEquals(emptyList(), "No images here, \${user.name}.".imageRefIds())
    }
}
