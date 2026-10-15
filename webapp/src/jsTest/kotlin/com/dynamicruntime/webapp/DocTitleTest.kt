package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which documents show their own title (issue #1238). */
class DocTitleTest {
    @Test
    fun aLeadingLevelOneHeadingIsTheTitle() {
        assertTrue(docHasOwnTitle("# Reporting\n\nText."))
        assertTrue(docHasOwnTitle("\n<!-- a note -->\n# Reporting\n"))
        assertTrue(docHasOwnTitle("<!-- a note\n     over lines -->\n\n# Reporting\n"))
        // A navigation line before the heading, as the demo's pages have.
        assertTrue(docHasOwnTitle("[← Back](a.md) · [Contents](b.md)\n\n# Reports\n"))
    }

    @Test
    fun anythingElseLeavesTheLabelAsTheTitle() {
        assertFalse(docHasOwnTitle("MIT License\n\nCopyright"))
        assertFalse(docHasOwnTitle("## Overview\n"))
        assertFalse(docHasOwnTitle("#Heading without a space"))
        assertFalse(docHasOwnTitle(""))
        assertFalse(docHasOwnTitle("<!-- never closed\n# Reporting\n"))
        // A heading inside a code fence is not a heading.
        assertFalse(docHasOwnTitle("```\n# not a heading\n```\n## Overview\n"))
    }
}
