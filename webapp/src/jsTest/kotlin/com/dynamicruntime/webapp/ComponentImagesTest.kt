package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.util.renderMarkdownInline
import kotlin.test.Test
import kotlin.test.assertEquals

/** The images components ship (issue #1339), as the webapp takes them from the shell config and draws them from copy. */
class ComponentImagesTest {
    private val hash = "a".repeat(64)

    @Test
    fun theShellConfigsImagesAreKeptOnlyWhenTheyAreWhatTheySay() {
        val names = componentImageNamesFrom(
            mapOf(
                "sample:diagram" to "sample:diagram:$hash",
                // Not rooted, a served name not the name's, and no hash: left out.
                "diagram" to "diagram:$hash",
                "sample:other" to "sample:diagram:$hash",
                "sample:bare" to "sample:bare",
            ),
        )
        assertEquals(mapOf("sample:diagram" to "sample:diagram:$hash"), names)
        // The home config carries them into the shell's facts.
        val facts = shellFactsOf(homeConfigFrom(UiConfig(FragmentRef("home", "b1"), emptyMap(), emptyMap(), mapOf(HFLD.images to names))))
        assertEquals(names, facts.images)
    }

    @Test
    fun everyCopySurfaceDrawsComponentImagesFromTheShellEvenOneDrawingAnotherClientsRefs() {
        val facts = ShellFacts(images = mapOf("sample:diagram" to "sample:diagram:$hash"))
        val imageAddressOf: (String) -> String = { "/st/kdr.en/img/$it" }
        val drawn = "<img src=\"/st/kdr.en/img/sample:diagram:$hash\" alt=\"D\">"
        assertEquals(drawn, "![D](img:sample:diagram)".renderMarkdownInline(markdownHooksFor(null, null, facts, imageAddressOf = imageAddressOf)))
        // The copy editor says where another client's refs are drawn from; the images are the shell's still.
        val editor = markdownHooksFor(null, { id -> "download/$id" }, facts, imageAddressOf = imageAddressOf)
        assertEquals(drawn, "![D](img:sample:diagram)".renderMarkdownInline(editor))
        // One no component ships is its alt text.
        assertEquals("N", "![N](img:sample:none)".renderMarkdownInline(editor))
    }
}
