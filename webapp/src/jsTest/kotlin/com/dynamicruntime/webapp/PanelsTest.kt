package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The page-structure building blocks' pure rules (issue #1477). */
class PanelsTest {
    @Test
    fun moreOptionsSaysWhatIsSetAndOpensWhenAnythingIs() {
        // Everything at its default: closed, and the summary is the plain words.
        assertEquals("More options", moreOptionsSummary(emptyList()))
        assertFalse(moreOptionsOpens(emptyList()))
        // Anything set: open, and named in the summary, so a setting in effect is never out of sight.
        assertEquals("More options · Editor, labels: reviewer", moreOptionsSummary(listOf("Editor", "labels: reviewer")))
        assertTrue(moreOptionsOpens(listOf("Editor")))
    }

    @Test
    fun onlyTheDangerZoneIsSetApart() {
        assertEquals("panel panel-danger", panelClasses(PanelKind.danger))
        for (kind in listOf(PanelKind.view, PanelKind.draft, PanelKind.live)) assertEquals("panel", panelClasses(kind))
    }
}
