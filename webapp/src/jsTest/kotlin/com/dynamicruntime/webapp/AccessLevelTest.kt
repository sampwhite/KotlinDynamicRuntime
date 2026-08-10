package com.dynamicruntime.webapp

import com.dynamicruntime.common.http.request.ROLE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #161) for [AdminUser.level] -- the level the Users page reads off a row's role
 * list. Plain list-in / value-out, so it needs no server, browser, or DOM.
 *
 * Its inverse, `RoleLadder.rolesAtLevel`, is covered in the kernel's `RoleLadderTest` instead: it moved there
 * when test provisioning came to need the same rule, and the kernel suite runs it on JVM as well as JS.
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
}
