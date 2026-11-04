package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure-logic coverage for the home page's jump links (issue #1576): which of the copy's links are kept. */
class JumpLinksTest {
    private val shipped = """
        - [Try a demo](#demos)
        - [Your other accounts](#accounts)
        - [Your own client](#own-client)
    """.trimIndent()

    @Test
    fun aLinkToASectionIsKeptOnlyWhileTheSectionIsDrawn() {
        assertEquals(shipped, jumpLinksFor(shipped, setOf(HSEC.demos, HSEC.accounts, HSEC.ownClient)))
        assertEquals(
            "- [Try a demo](#demos)\n- [Your own client](#own-client)",
            jumpLinksFor(shipped, setOf(HSEC.demos, HSEC.ownClient)),
        )
        assertEquals("- [Your own client](#own-client)", jumpLinksFor(shipped, setOf(HSEC.ownClient)))
    }

    @Test
    fun nothingIsDrawnWhenNoLinkIsLeftOrTheCopyIsBlank() {
        assertNull(jumpLinksFor(shipped, emptySet()))
        assertNull(jumpLinksFor("", setOf(HSEC.ownClient)))
        assertNull(jumpLinksFor("   \n", setOf(HSEC.ownClient)))
        assertNull(jumpLinksFor(null, setOf(HSEC.ownClient)))
        // Prose alone is no row of links.
        assertNull(jumpLinksFor("Jump to:", setOf(HSEC.ownClient)))
    }

    @Test
    fun aLinkToAnythingButAnAnchorIsAlwaysKept() {
        val mixed = """
            - [Start here](/docs/start-here.md)
            - [Try a demo](#demos)
            - [Join](/wa/join "Join a demo")
            - [The project](https://example.com)
        """.trimIndent()
        assertEquals(
            "- [Start here](/docs/start-here.md)\n- [Join](/wa/join \"Join a demo\")\n- [The project](https://example.com)",
            jumpLinksFor(mixed, emptySet()),
        )
        // An item with a document link beside a hidden anchor still leads somewhere.
        val both = "- [Try a demo](#demos), or [read first](/docs/start-here.md)"
        assertEquals(both, jumpLinksFor(both, emptySet()))
    }

    @Test
    fun proseAndOtherListMarkersAreReadAsWritten() {
        val source = """
            Jump to:

            * [Try a demo](#demos)
              which is shared with everyone
            + [Your own client](#own-client)
            1. [Your other accounts](#accounts)
        """.trimIndent()
        // The dropped item takes its continuation line with it; the prose stays.
        assertEquals(
            "Jump to:\n\n+ [Your own client](#own-client)",
            jumpLinksFor(source, setOf(HSEC.ownClient)),
        )
    }
}
