package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.UCF
import com.dynamicruntime.common.user.UserChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the account menu's user switcher (issue #749): the users-list parse,
 * the menu labels, and the two rules that decide whether the switcher and the persona chip show at all.
 */
class UserSwitcherTest {

    private val ada = UserChoice(7L, "acme", "admin", name = "Ada Lovelace", isCurrent = true)
    private val batch = UserChoice(8L, "acme", "user", personId = "2")

    @Test
    fun labelsClientPersonaPersonIdAndName() {
        assertEquals("acme / admin -- Ada Lovelace", ada.label())
        assertEquals("acme / user 2", batch.label())
        // A blank name is no name; a personId is shown only when there is one.
        assertEquals("public / user", UserChoice(9L, "public", "user", name = "  ").label())
    }

    @Test
    fun parsesTheListTheBackendServesAndDropsAnEntryWithoutAnId() {
        val parsed = userChoicesFrom(
            listOf(
                mapOf(UCF.userId to 7L, UCF.client to "acme", UCF.persona to "admin", UCF.name to "Ada Lovelace", UCF.isCurrent to true),
                mapOf(UCF.userId to 8L, UCF.client to "acme", UCF.persona to "user", UCF.personId to "2", UCF.isDefault to true),
                mapOf(UCF.client to "acme"),
            ),
        )
        assertEquals(listOf(ada, batch.copy(isDefault = true)), parsed)
        // Absent or malformed: nothing, never a crash.
        assertEquals(emptyList(), userChoicesFrom(null))
        assertEquals(emptyList(), userChoicesFrom("not a list"))
    }

    @Test
    fun theSwitcherShowsOnlyWhenThereIsSomeoneElseToBe() {
        assertEquals(emptyList(), switchableUsers(emptyList()))
        assertEquals(emptyList(), switchableUsers(listOf(ada)))
        assertEquals(listOf(ada, batch), switchableUsers(listOf(ada, batch)))
    }

    @Test
    fun thePersonaChipShowsOnlyForAPersonWithSeveralUsers() {
        assertNull(personaLabel("user", userCount = 1))
        assertNull(personaLabel("user", userCount = 0))
        assertEquals("admin", personaLabel("admin", userCount = 2))
        assertNull(personaLabel(null, userCount = 2))
        assertTrue(personaLabel("  ", userCount = 2) == null)
    }
}
