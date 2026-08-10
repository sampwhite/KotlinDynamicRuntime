package com.dynamicruntime.webapp

import com.dynamicruntime.common.http.request.ROLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the Users page's access-level mapping: [AdminUser.level], which reads a
 * level off a role list, and [rolesAtLevel], which composes the list to send back. Both are plain
 * list-in / list-out, so they need no server, browser, or DOM.
 *
 * Worth covering rather than eyeballing, because this is the code that decides what an administrator's click
 * actually writes to someone's roles -- and the failure modes are silent ones: a stripped capability, a
 * demotion that leaves the higher rung in place, or a user who can no longer log in.
 */
class AccessLevelTest {

    private fun user(vararg roles: String) =
        AdminUser(userId = 1L, primaryId = "a@b.com", username = "a", roles = roles.toList(), enabled = true, hasPassword = false)

    // --- reading a level off a role list -------------------------------------

    @Test
    fun levelIsTheHighestRungHeld() {
        assertEquals(ROLE.user, user(ROLE.user).level)
        assertEquals(ROLE.operator, user(ROLE.user, ROLE.operator).level)
        assertEquals(ROLE.admin, user(ROLE.user, ROLE.admin).level)
    }

    /** An admin who also literally holds `operator` is still an admin: the top rung is the one that speaks. */
    @Test
    fun levelTakesTheTopRungWhenSeveralAreHeld() {
        assertEquals(ROLE.admin, user(ROLE.user, ROLE.operator, ROLE.admin).level)
    }

    @Test
    fun rolesOffTheLadderDoNotSetALevel() {
        assertEquals(ROLE.user, user(ROLE.user, "billing").level)
        assertEquals(ROLE.user, user("billing").level) // no rung at all: floor, not a crash
    }

    // --- composing the list to send ------------------------------------------

    @Test
    fun promotingAddsTheRungAndKeepsTheFloor() {
        assertEquals(listOf(ROLE.user, ROLE.operator), rolesAtLevel(listOf(ROLE.user), ROLE.operator))
        assertEquals(listOf(ROLE.user, ROLE.admin), rolesAtLevel(listOf(ROLE.user), ROLE.admin))
    }

    /** The rungs are exclusive: demoting must remove the old one, or the "demotion" demotes nothing. */
    @Test
    fun demotingReplacesTheRungRatherThanAddingToIt() {
        val demoted = rolesAtLevel(listOf(ROLE.user, ROLE.admin), ROLE.operator)
        assertEquals(listOf(ROLE.user, ROLE.operator), demoted)
        assertTrue(ROLE.admin !in demoted)

        val toPlain = rolesAtLevel(listOf(ROLE.user, ROLE.admin), ROLE.user)
        assertEquals(listOf(ROLE.user), toPlain)
    }

    /** A level change must never cost someone a deployment's own role -- those are capabilities, not levels. */
    @Test
    fun rolesOffTheLadderSurviveALevelChange() {
        assertEquals(
            listOf(ROLE.user, ROLE.operator, "billing", "support"),
            rolesAtLevel(listOf(ROLE.user, ROLE.admin, "billing", "support"), ROLE.operator),
        )
        assertEquals(listOf(ROLE.user, "billing"), rolesAtLevel(listOf(ROLE.user, ROLE.admin, "billing"), ROLE.user))
    }

    /** The base role is the floor of every level, because the backend refuses a role set without it. */
    @Test
    fun theBaseRoleIsAlwaysPresent() {
        for (level in listOf(ROLE.user, ROLE.operator, ROLE.admin)) {
            assertTrue(ROLE.user in rolesAtLevel(emptyList(), level), "level $level dropped ${ROLE.user}")
        }
    }

    /** A value that is not a rung can only ever under-grant, never invent a role. */
    @Test
    fun anUnknownLevelLeavesTheUserAtTheFloor() {
        assertEquals(listOf(ROLE.user), rolesAtLevel(listOf(ROLE.user, ROLE.admin), "wizard"))
        assertEquals(listOf(ROLE.user, "billing"), rolesAtLevel(listOf(ROLE.user, "billing"), ""))
    }

    /** Setting the level someone already has is a no-op, which is what lets the editor skip the write. */
    @Test
    fun reassigningTheSameLevelIsStable() {
        val current = listOf(ROLE.user, ROLE.operator, "billing")
        assertEquals(current, rolesAtLevel(current, ROLE.operator))
    }
}
