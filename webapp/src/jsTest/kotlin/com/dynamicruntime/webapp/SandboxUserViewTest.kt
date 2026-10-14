package com.dynamicruntime.webapp

import com.dynamicruntime.common.http.request.ROLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the Users page shows a sandbox user (issue #1198): what it acts with beside what its row holds, and for one
 * somebody opened, which user it acts as -- in place of controls the backend would refuse.
 */
class SandboxUserViewTest {
    private fun user(
        roles: List<String>,
        actingRoles: List<String>? = null,
        openedBy: Long? = null,
        actsAsClient: String? = null,
    ) = AdminUser(
        userId = 7L, primaryId = "chief@acme.test", username = "chief", roles = roles, client = "acme:sandbox",
        org = null, isEntity = false, name = null, enabled = true, hasPassword = false, deleted = false,
        actingRoles = actingRoles, openedBy = openedBy, actsAsClient = actsAsClient,
    )

    @Test
    fun theRolesCellSaysWhatTheUserActsWithWhenItDiffers() {
        // Outside a sandbox, or acting with exactly its row's roles: the row's roles alone.
        assertEquals("user, admin", rolesCellText(user(listOf(ROLE.user, ROLE.admin))))
        assertEquals("user, admin", rolesCellText(user(listOf(ROLE.user, ROLE.admin), listOf(ROLE.admin, ROLE.user))))
        // Withheld, or given from outside: said beside them.
        assertEquals("user, admin (acts with: user)", rolesCellText(user(listOf(ROLE.user, ROLE.admin), listOf(ROLE.user))))
        assertEquals("user, admin (acts with: none)", rolesCellText(user(listOf(ROLE.user, ROLE.admin), emptyList())))
    }

    @Test
    fun anOpenedUserSaysWhomItActsAsOrThatItNoLongerDoes() {
        assertNull(openedUserNote(user(listOf(ROLE.user), listOf(ROLE.user))))
        val actsAs = openedUserNote(user(listOf(ROLE.user, ROLE.admin), listOf(ROLE.user, ROLE.admin), openedBy = 3L, actsAsClient = "acme"))!!
        assertTrue(actsAs.contains("acts as user 3 in acme"))
        val gone = openedUserNote(user(listOf(ROLE.user, ROLE.admin), emptyList(), openedBy = 3L))!!
        assertTrue(gone.contains("user 3, which no longer gives it access"))
    }
}
