package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.UCF
import com.dynamicruntime.common.user.UserChoice
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #161) for the account menu's user switcher (issue #749): the users-list parse,
 * the menu labels, and the two rules that decide whether the switcher and the persona chip show at all.
 */
class UserSwitcherTest {

    private val ada = UserChoice(7L, "acme", "admin", name = "Ada Lovelace", isCurrent = true)
    private val batch = UserChoice(8L, "acme", "member", personId = "2")

    @Test
    fun labelsClientPersonaPersonIdAndName() {
        assertEquals("acme / Admin -- Ada Lovelace", ada.label())
        assertEquals("acme / Member 2", batch.label())
        // A blank name is no name; a personId is shown only when there is one; the persona is capitalized.
        assertEquals("public / Member", UserChoice(9L, "public", "member", name = "  ").label())
    }

    @Test
    fun theQualifierSaysOnlyWhatTellsAUserFromThePersonsOthers() {
        val member = UserChoice(8L, "acme", "member")
        val hubMember = UserChoice(9L, "hub", "member")
        // One user: nothing to tell apart.
        assertEquals("", ada.qualifierWithin(listOf(ada)))
        // Same client, personas differ: the persona alone.
        assertEquals("Admin", ada.qualifierWithin(listOf(ada, member)))
        assertEquals("Member", member.qualifierWithin(listOf(ada, member)))
        // Clients differ, same persona: the client alone.
        assertEquals("hub", hubMember.qualifierWithin(listOf(member, hubMember)))
        // A batch: the persona is shown for every user once any carries a personId, so the plain one is not
        // left with an empty bracket -- `Member` beside `Member 2`, or `hub · Member B`.
        assertEquals("Member", member.qualifierWithin(listOf(member, batch)))
        assertEquals("Member 2", batch.qualifierWithin(listOf(member, batch)))
        val hubBatch = UserChoice(10L, "hub", "member", personId = "B")
        assertEquals("hub · Member B", hubBatch.qualifierWithin(listOf(member, hubBatch)))
    }

    @Test
    fun theBadgeAppendsTheQualifierInBrackets() {
        val member = UserChoice(8L, "acme", "member")
        assertEquals("Ada [Admin]", identityBadgeText("Ada", listOf(ada, member)))
        // No current user in the list, or a single user: the label alone.
        assertEquals("Ada", identityBadgeText("Ada", listOf(ada.copy(isCurrent = false), member)))
        assertEquals("Ada", identityBadgeText("Ada", listOf(ada)))
    }

    @Test
    fun parsesTheListTheBackendServesAndDropsAnEntryWithoutAnId() {
        val parsed = userChoicesFrom(
            listOf(
                mapOf(UCF.userId to 7L, UCF.client to "acme", UCF.persona to "admin", UCF.name to "Ada Lovelace", UCF.isCurrent to true),
                mapOf(UCF.userId to 8L, UCF.client to "acme", UCF.persona to "member", UCF.personId to "2", UCF.isDefault to true),
                mapOf(UCF.client to "acme"),
            ),
        )
        assertEquals(listOf(ada, batch.copy(isDefault = true)), parsed)
        // Absent or malformed: nothing, never a crash.
        assertEquals(emptyList(), userChoicesFrom(null))
        assertEquals(emptyList(), userChoicesFrom("not a list"))
    }

    @Test
    fun theBadgeBecomesAMenuOnlyWhenThereIsSomeoneElseToBe() {
        assertEquals(emptyList(), switchableUsers(emptyList()))
        assertEquals(emptyList(), switchableUsers(listOf(ada)))
        assertEquals(listOf(ada, batch), switchableUsers(listOf(ada, batch)))
    }
}
