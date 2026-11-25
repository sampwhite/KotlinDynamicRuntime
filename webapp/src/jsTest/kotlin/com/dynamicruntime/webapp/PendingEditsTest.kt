package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A page's register of its editors' unsaved edits (issue #1922), and what it says. */
class PendingEditsTest {
    private fun edit(key: String, label: String, panel: String? = "forms", section: String? = null) =
        PendingEdit(key, label, panel, section, key)

    @Test
    fun theRegisterHoldsEachEditorsEditOnceAndSaysWhenItChanges() {
        val edits = PendingEdits()
        var told = 0
        edits.onChange = { told++ }
        edits.set(edit("a", "Workflow newForm", section = "workflows"))
        edits.set(edit("b", "Copy: home", panel = "copy"))
        assertEquals(listOf("Workflow newForm", "Copy: home"), edits.all.map { it.label })
        assertEquals(2, told)
        // The same edit again changes nothing, and says nothing.
        edits.set(edit("a", "Workflow newForm", section = "workflows"))
        assertEquals(2, told)
        // Where they are.
        assertTrue(edits.inPanel("forms"))
        assertTrue(edits.inSection("workflows"))
        assertFalse(edits.inSection("traits"))
        // Saved or dropped: out, and said once.
        edits.clear("a")
        edits.clear("a")
        assertEquals(3, told)
        assertFalse(edits.inPanel("forms"))
        assertTrue(edits.any())
        edits.clear("b")
        assertFalse(edits.any())
    }

    @Test
    fun itSaysHowManyAndWhatLeavingLoses() {
        assertEquals("1 unsaved change", pendingCountText(1))
        assertEquals("3 unsaved changes", pendingCountText(3))
        assertEquals(
            "You have unsaved changes on this page: Workflow newForm, Copy: home. Leave the page and lose them?",
            pendingLeavePrompt(listOf(edit("a", "Workflow newForm"), edit("b", "Copy: home"))),
        )
    }
}
