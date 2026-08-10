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
