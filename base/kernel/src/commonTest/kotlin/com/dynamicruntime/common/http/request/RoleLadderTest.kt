package com.dynamicruntime.common.http.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The privilege ladder's rules. Pure logic, so it runs on both JVM and JS -- the frontend reads the same
 * roles off a `UserInfo` payload and will eventually shape screens with the same ordering.
 */
class RoleLadderTest {
    private val userOnly = setOf(ROLE.user)
    private val operatorRoles = setOf(ROLE.user, ROLE.operator)
    private val adminRoles = setOf(ROLE.user, ROLE.admin)

    @Test
    fun ranksRunLowToHigh() {
        assertEquals(0, RoleLadder.rankOf(ROLE.user))
        assertEquals(1, RoleLadder.rankOf(ROLE.operator))
        assertEquals(2, RoleLadder.rankOf(ROLE.admin))
    }

    @Test
    fun aRoleOffTheLadderHasNoRank() {
        assertNull(RoleLadder.rankOf("billing"))
        assertNull(RoleLadder.rankOf(""))
    }

    @Test
    fun anOperatorReachesOperatorAndUserButNotAdmin() {
        assertTrue(RoleLadder.satisfies(operatorRoles, ROLE.user))
        assertTrue(RoleLadder.satisfies(operatorRoles, ROLE.operator))
        assertFalse(RoleLadder.satisfies(operatorRoles, ROLE.admin))
    }

    @Test
    fun anOrdinaryUserReachesNeitherOperatorNorAdmin() {
        assertTrue(RoleLadder.satisfies(userOnly, ROLE.user))
        assertFalse(RoleLadder.satisfies(userOnly, ROLE.operator))
        assertFalse(RoleLadder.satisfies(userOnly, ROLE.admin))
    }

    /** The point of ranking: an admin passes an operator section without anyone granting them `operator`. */
    @Test
    fun anAdminReachesEveryLadderLevelWithoutHoldingTheLowerRoles() {
        assertTrue(RoleLadder.satisfies(adminRoles, ROLE.operator))
        assertTrue(RoleLadder.satisfies(setOf(ROLE.admin), ROLE.operator))
        assertTrue(RoleLadder.satisfies(setOf(ROLE.admin), ROLE.user))
        assertTrue(RoleLadder.satisfies(setOf(ROLE.admin), ROLE.admin))
    }

    @Test
    fun highestHeldIsTheTopRung() {
        assertEquals(ROLE.user, RoleLadder.highestHeld(userOnly))
        assertEquals(ROLE.operator, RoleLadder.highestHeld(operatorRoles))
        assertEquals(ROLE.admin, RoleLadder.highestHeld(adminRoles))
        assertEquals(ROLE.admin, RoleLadder.highestHeld(setOf(ROLE.user, ROLE.operator, ROLE.admin)))
    }

    @Test
    fun highestHeldIgnoresRolesOffTheLadder() {
        assertEquals(ROLE.user, RoleLadder.highestHeld(setOf(ROLE.user, "billing")))
        assertNull(RoleLadder.highestHeld(setOf("billing")))
        assertNull(RoleLadder.highestHeld(emptySet()))
    }

    @Test
    fun noRolesSatisfiesNothing() {
        assertFalse(RoleLadder.satisfies(emptySet(), ROLE.user))
        assertFalse(RoleLadder.satisfies(emptySet(), ROLE.operator))
        assertFalse(RoleLadder.satisfies(emptySet(), ROLE.admin))
    }

    // --- rolesAtLevel: composing the list that puts someone at a level ---------

    @Test
    fun promotingAddsTheRungAndKeepsTheFloor() {
        assertEquals(listOf(ROLE.user, ROLE.operator), RoleLadder.rolesAtLevel(listOf(ROLE.user), ROLE.operator))
        assertEquals(listOf(ROLE.user, ROLE.admin), RoleLadder.rolesAtLevel(listOf(ROLE.user), ROLE.admin))
    }

    /** The rungs are exclusive: demoting must remove the old one, or the "demotion" demotes nothing. */
    @Test
    fun demotingReplacesTheRungRatherThanAddingToIt() {
        val demoted = RoleLadder.rolesAtLevel(listOf(ROLE.user, ROLE.admin), ROLE.operator)
        assertEquals(listOf(ROLE.user, ROLE.operator), demoted)
        assertFalse(ROLE.admin in demoted)

        assertEquals(listOf(ROLE.user), RoleLadder.rolesAtLevel(listOf(ROLE.user, ROLE.admin), ROLE.user))
    }

    /** A level change must never cost someone a deployment's own role -- those are capabilities, not levels. */
    @Test
    fun rolesOffTheLadderSurviveALevelChange() {
        assertEquals(
            listOf(ROLE.user, ROLE.operator, "billing", "support"),
            RoleLadder.rolesAtLevel(listOf(ROLE.user, ROLE.admin, "billing", "support"), ROLE.operator),
        )
        assertEquals(
            listOf(ROLE.user, "billing"),
            RoleLadder.rolesAtLevel(listOf(ROLE.user, ROLE.admin, "billing"), ROLE.user),
        )
    }

    /** The base role is the floor of every level, because the backend refuses a role set without it. */
    @Test
    fun theBaseRoleIsAlwaysPresent() {
        for (level in RoleLadder.ordered) {
            assertTrue(ROLE.user in RoleLadder.rolesAtLevel(emptyList(), level), "level $level dropped ${ROLE.user}")
        }
    }

    /** Provisioning a fresh user is the empty-current case -- what test provisioning and create both use. */
    @Test
    fun provisioningFromNothingYieldsJustTheLevel() {
        assertEquals(listOf(ROLE.user), RoleLadder.rolesAtLevel(emptyList(), ROLE.user))
        assertEquals(listOf(ROLE.user, ROLE.operator), RoleLadder.rolesAtLevel(emptyList(), ROLE.operator))
        assertEquals(listOf(ROLE.user, ROLE.admin), RoleLadder.rolesAtLevel(emptyList(), ROLE.admin))
    }

    /** A value that is not a rung can only ever under-grant, never invent a role. */
    @Test
    fun anUnknownLevelLeavesTheUserAtTheFloor() {
        assertEquals(listOf(ROLE.user), RoleLadder.rolesAtLevel(listOf(ROLE.user, ROLE.admin), "wizard"))
        assertEquals(listOf(ROLE.user, "billing"), RoleLadder.rolesAtLevel(listOf(ROLE.user, "billing"), ""))
    }

    /** Setting the level someone already has is a no-op, which is what lets an editor skip the write. */
    @Test
    fun reassigningTheSameLevelIsStable() {
        val current = listOf(ROLE.user, ROLE.operator, "billing")
        assertEquals(current, RoleLadder.rolesAtLevel(current, ROLE.operator))
    }

    /**
     * A deployment's own role is a capability, not a level: it confers nothing on the ladder, and holding a
     * ladder role confers nothing on it. Both directions matter -- the first is what stops a new deployment
     * role becoming an accidental privilege escalation.
     */
    @Test
    fun rolesOffTheLadderAreOrthogonal() {
        assertFalse(RoleLadder.satisfies(setOf("billing"), ROLE.user))
        assertFalse(RoleLadder.satisfies(setOf("billing"), ROLE.operator))
        assertTrue(RoleLadder.satisfies(setOf("billing"), "billing"))
        assertFalse(RoleLadder.satisfies(adminRoles, "billing"))
        assertTrue(RoleLadder.satisfies(adminRoles + "billing", "billing"))
    }
}
