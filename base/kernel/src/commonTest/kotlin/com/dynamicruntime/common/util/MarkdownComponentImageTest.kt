package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An image naming one a component ships, `![alt](img:sample:diagram)` (issue #1339), through the kernel renderer: drawn
 * from the address the surface gives for its rooted name, apart from file refs, and as its alt text where it cannot be.
 */
class MarkdownComponentImageTest {
    private val names = mapOf("sample:diagram" to "sample:diagram:abc123")
    private val hooks = MarkdownHooks(
        refAddress = { id -> if (id == "logo") "/st/kdr/ref/logo:def456" else null },
        imageAddress = { name -> names[name]?.let { "/st/kdr/img/$it" } },
    )

    @Test
    fun aComponentImageIsDrawnFromTheAddressTheSurfaceGivesForItsRootedName() {
        assertEquals(
            "<img src=\"/st/kdr/img/sample:diagram:abc123\" alt=\"A diagram\" width=\"240\">",
            "![A diagram](img:sample:diagram){width=240}".renderMarkdownInline(hooks),
        )
    }

    @Test
    fun imagesAndRefsAreSeparateNamespaces() {
        // A ref's id is never asked of the image hook, nor an image's name of the ref hook.
        assertEquals("Our logo", "![Our logo](img:logo)".renderMarkdownInline(hooks))
        assertEquals("A diagram", "![A diagram](ref:sample:diagram)".renderMarkdownInline(hooks))
    }

    @Test
    fun anImageTheSurfaceCannotDrawIsItsAltText() {
        assertEquals("Nothing", "![Nothing](img:sample:none)".renderMarkdownInline(hooks))
        // A mail: no hooks at all.
        assertEquals("A diagram", "![A diagram](img:sample:diagram)".renderMarkdownInline())
        assertEquals("A diagram", "![A diagram](img:sample:diagram)".renderMarkdownText())
    }

    @Test
    fun copyIsAskedWhichComponentImagesItNames() {
        assertEquals(
            listOf("sample:diagram", "kdr:icon"),
            "![a](img:sample:diagram) ![b](ref:logo) ![c](img:kdr:icon) ![d](img:sample:diagram)".componentImageNames(),
        )
        // The report records an image's name, and does not call it an inert link.
        val link = "![a](img:sample:diagram)".analyzeMarkdown(hooks).links.single()
        assertEquals("sample:diagram", link.imageName)
        assertEquals(false, link.inert)
    }
}
