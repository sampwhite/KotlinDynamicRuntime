package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The panels the paneled pages name for their top links and their side menus (issue #1924): each the same list the page
 * draws, so the menu never offers a place that is not there.
 */
class PanelPagesTest {
    private fun headings(panels: List<Pair<String, String>>) = panels.map { it.second }

    @Test
    fun aUsersEditorNamesThePanelsItDraws() {
        fun panels(
            inEditor: Boolean = true,
            open: Boolean = true,
            deleted: Boolean = false,
            readOnly: Boolean = false,
            mail: UserMailOffer = UserMailOffer.none,
            keys: Boolean = true,
            danger: Boolean = true,
        ) = headings(userEditorPanels(inEditor, open, deleted, readOnly, mail, keys, danger))
        // The list: no panels.
        assertEquals(emptyList(), panels(inEditor = false))
        // An open user, with each offer.
        assertEquals(listOf("User", "Activity", "API keys", "Danger zone"), panels())
        assertEquals(listOf("User", "Invitation", "Activity", "API keys", "Danger zone"), panels(mail = UserMailOffer.invitation))
        assertEquals(listOf("User", "Sign-in reminder", "Activity"), panels(mail = UserMailOffer.reminder, keys = false, danger = false))
        // A user being created: the draft alone.
        assertEquals(listOf("User"), panels(open = false))
        // An editor's read-only view, and a deleted user's record.
        assertEquals(listOf("User", "Activity"), panels(readOnly = true))
        assertEquals(listOf("User"), panels(deleted = true, mail = UserMailOffer.invitation))
    }

    @Test
    fun theAssistantNamesItsSandboxPanelOnlyWithoutASandbox() {
        assertEquals(listOf("Sandbox", "Conversation", "Drafts", "Allowance"), headings(assistantPanels(hasSandbox = false)))
        assertEquals(listOf("Conversation", "Drafts", "Allowance"), headings(assistantPanels(hasSandbox = true)))
    }
}
