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
    }

    @Test
    fun anythingElseLeavesTheLabelAsTheTitle() {
        assertFalse(docHasOwnTitle("MIT License\n\nCopyright"))
        assertFalse(docHasOwnTitle("## Overview\n"))
        assertFalse(docHasOwnTitle("#Heading without a space"))
        assertFalse(docHasOwnTitle(""))
    }
}
