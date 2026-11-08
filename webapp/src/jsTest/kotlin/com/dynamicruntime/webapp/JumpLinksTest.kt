package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HSEC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure-logic coverage for the home page's jump links (issue #1576): which of the copy's links are kept. */
class JumpLinksTest {
    private val shipped = """
        - [Try a demo](#home.demos)
        - [Your own client](#home.own-client)
    """.trimIndent()

    @Test
    fun aLinkToASectionIsKeptOnlyWhileTheSectionIsDrawn() {
        assertEquals(shipped, jumpLinksFor(shipped, setOf(HSEC.demos, HSEC.ownClient)))
        assertEquals("- [Your own client](#home.own-client)", jumpLinksFor(shipped, setOf(HSEC.ownClient)))
    }

    @Test
    fun nothingIsDrawnWhenNoLinkIsLeftOrTheCopyIsBlank() {
        assertNull(jumpLinksFor(shipped, emptySet()))
        assertNull(jumpLinksFor("", setOf(HSEC.ownClient)))
        assertNull(jumpLinksFor("   \n", setOf(HSEC.ownClient)))
        assertNull(jumpLinksFor(null, setOf(HSEC.ownClient)))
        // Prose alone is no row of links, and neither is an image.
        assertNull(jumpLinksFor("Jump to:", setOf(HSEC.ownClient)))
        assertNull(jumpLinksFor("![](img:sample:arrow)\n- [Try a demo](#home.demos)", emptySet()))
    }

    @Test
    fun aLinkToAnythingButAnAnchorIsAlwaysKept() {
        val mixed = """
            - [Start here](/docs/start-here.md)
            - [Try a demo](#home.demos)
            - [Join](/wa/join "Join a demo")
            - [The project](https://example.com)
        """.trimIndent()
        assertEquals(
            "- [Start here](/docs/start-here.md)\n- [Join](/wa/join \"Join a demo\")\n- [The project](https://example.com)",
            jumpLinksFor(mixed, emptySet()),
        )
    }

    @Test
    fun aRowOnOneLineLosesTheHiddenLinkAndItsSeparator() {
        val row = "[Try a demo](#home.demos) · [Start here](/docs/start-here.md) · [Your own client](#home.own-client)"
        assertEquals(row, jumpLinksFor(row, setOf(HSEC.demos, HSEC.ownClient)))
        assertEquals(
            "[Start here](/docs/start-here.md) · [Your own client](#home.own-client)",
            jumpLinksFor(row, setOf(HSEC.ownClient)),
        )
        assertEquals(
            "[Try a demo](#home.demos) · [Start here](/docs/start-here.md)",
            jumpLinksFor(row, setOf(HSEC.demos)),
        )
        assertEquals("[Start here](/docs/start-here.md)", jumpLinksFor(row, emptySet()))
        val sectionsOnly = "[Try a demo](#home.demos) · [Your own client](#home.own-client)"
        assertEquals("[Try a demo](#home.demos)", jumpLinksFor(sectionsOnly, setOf(HSEC.demos)))
        assertNull(jumpLinksFor(sectionsOnly, emptySet()))
        // A lead-in stays; the separator after a removed first link goes with it.
        assertEquals(
            "Jump to: [Your own client](#home.own-client).",
            jumpLinksFor("Jump to: [Try a demo](#home.demos) | [Your own client](#home.own-client).", setOf(HSEC.ownClient)),
        )
    }

    @Test
    fun aHiddenLinkInProseKeepsItsWords() {
        assertEquals(
            "- Try a demo, or [read first](/docs/start-here.md)",
            jumpLinksFor("- [Try a demo](#home.demos), or [read first](/docs/start-here.md)", emptySet()),
        )
        assertEquals(
            "Start with a demo, then [read on](/docs/start-here.md).",
            jumpLinksFor("Start with [a demo](#home.demos), then [read on](/docs/start-here.md).", emptySet()),
        )
    }

    @Test
    fun aDroppedItemTakesItsContinuationAndNestedItemsWithIt() {
        val source = """
            Jump to:

            * [Try a demo](#home.demos)
              which is shared with everyone
              - [How joining works](/docs/joining.md)

              More about demos.
            + [Your own client](#home.own-client)
            1. [Demos again](#home.demos)
        """.trimIndent()
        assertEquals(
            "Jump to:\n\n\n+ [Your own client](#home.own-client)",
            jumpLinksFor(source, setOf(HSEC.ownClient)),
        )
        // Kept, the item keeps what is under it.
        assertEquals(
            "- [Try a demo](#home.demos)\n  - [How joining works](/docs/joining.md)",
            jumpLinksFor("- [Try a demo](#home.demos)\n  - [How joining works](/docs/joining.md)", setOf(HSEC.demos)),
        )
    }
}
