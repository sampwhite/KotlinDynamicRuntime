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
        opened: Boolean = openedBy != null,
        enabled: Boolean = true,
    ) = AdminUser(
        userId = 7L, primaryId = "chief@acme.test", username = "chief", roles = roles, client = "acme:sandbox",
        org = null, isEntity = false, name = null, enabled = enabled, hasPassword = false, deleted = false,
        actingRoles = actingRoles, opened = opened, openedBy = openedBy, actsAsClient = actsAsClient,
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
        assertTrue(actsAs.contains("acts as user 3 in acme, with that user's roles and organization."))
        // Opened from another client by an administrator of every client: its roles, and no organization of theirs.
        val fromHub = openedUserNote(user(listOf(ROLE.user, ROLE.admin), listOf(ROLE.user, ROLE.admin, ROLE.allClients), openedBy = 4L, actsAsClient = "hub"))!!
        assertTrue(fromHub.contains("acts as user 4 in hub, with that user's roles."))
        val gone = openedUserNote(user(listOf(ROLE.user, ROLE.admin), emptyList(), openedBy = 3L))!!
        assertTrue(gone.contains("by a user who no longer gives it access: it acts with no roles."))
        val off = openedUserNote(user(listOf(ROLE.user, ROLE.admin), emptyList(), openedBy = 3L, actsAsClient = "acme", enabled = false))!!
        assertTrue(off.contains("disabled here: it acts with no roles."))
        // Opened by a user the caller does not administer: said to be opened, and that user left unnamed.
        val unnamed = openedUserNote(user(listOf(ROLE.user, ROLE.admin), listOf(ROLE.user, ROLE.admin, ROLE.allClients), opened = true))!!
        assertTrue(unnamed.contains("by an administrator of another client: it acts with that user's roles."))
    }
}
