package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.PERSONASUFFIX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Users page's create editor (issue #797): the caller's own address has a form of its own, the persona
 * suffix is always on offer, and each hint says what the backend will actually do.
 */
class SelfCreateTest {
    @Test
    fun theEditorSaysWhichRecordItIsOpenOn() {
        assertEquals("Create a user for me", editorTitle(creating = true, forSelf = true))
        assertEquals("Create a user", editorTitle(creating = true, forSelf = false))
        assertEquals("Edit user", editorTitle(creating = false, forSelf = false))
    }

    @Test
    fun theAddressHintSaysWhetherAnInvitationGoesOut() {
        // Your own address: registered at once, nothing mailed. Anyone else's: an invitation.
        assertTrue(createAddressHint(forSelf = true).contains("no invitation is sent"))
        assertTrue(createAddressHint(forSelf = false).contains("invitation is mailed"))
        // The old hint claimed every address was taken as confirmed, which stopped being true with invitations.
        assertTrue(!createAddressHint(forSelf = false).contains("skips email verification"))
    }

    @Test
    fun theSuffixHintExplainsTheFieldAndThenTheCollision() {
        val plain = personaSuffixHint(collided = false)
        val collided = personaSuffixHint(collided = true)
        assertTrue(plain.startsWith("Optional."))
        assertTrue(collided.contains("already exists"))
        assertNotEquals(plain, collided)
        // Both state the limit the backend enforces.
        assertTrue(plain.contains("${PERSONASUFFIX.maxLength}"))
        assertTrue(collided.contains("${PERSONASUFFIX.maxLength}"))
    }

    @Test
    fun aPublicAdministratorCreatesOnlyUsersOfTheirOwn() {
        // In `public` the ordinary create is not offered (issue #805); anywhere else, and with allClients, it is.
        assertTrue(!mayCreateForOthers(UserProfile(client = CL.public, roles = setOf(ROLE.user, ROLE.admin))))
        assertTrue(mayCreateForOthers(UserProfile(client = CL.public, roles = setOf(ROLE.user, ROLE.admin, ROLE.allClients))))
        assertTrue(mayCreateForOthers(UserProfile(client = CL.hub, roles = setOf(ROLE.user, ROLE.admin))))
    }

    @Test
    fun theSelfRecordIsItsOwnHashValue() {
        // Distinct from a plain create, so Back and Forward reopen the right form, and not a user id.
        assertNotEquals(HP.newRecord, HP.selfRecord)
        assertTrue(HP.selfRecord.toLongOrNull() == null)
    }
}
