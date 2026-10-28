package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-logic coverage for the public home page (issue #1324): who gets it, and what its sections list. */
class PublicHomeTest {

    @Test
    fun itIsForSomeoneSignedInToPublicOnly() {
        assertTrue(isPublicHome(UserProfile(authId = "a1", client = CL.public)))
        // A visitor who has not signed in acts in public too, and keeps the ordinary home page.
        assertTrue(!isPublicHome(UserProfile.anonymous()))
        assertTrue(!isPublicHome(UserProfile(authId = "a2", client = "acme")))
    }

    @Test
    fun otherAccountsLeaveOutTheUserTheSessionActsAs() {
        val users = listOf(
            UserChoice(1, CL.public, PERSONA.member, isCurrent = true),
            UserChoice(2, "acme", PERSONA.admin, clientName = "Acme"),
            UserChoice(3, "confdemo", PERSONA.editor),
        )
        assertEquals(listOf(2L, 3L), otherAccounts(users).map { it.userId })
        assertTrue(otherAccounts(listOf(users[0])).isEmpty())
    }

    @Test
    fun aDemoOffersToOpenWhatWasJoinedAndToJoinWhatRemains() {
        fun role(name: String, userId: Long? = null, removed: Boolean = false) = JoinRoleView(name, name, PERSONA.admin, "", userId, removed)
        val none = JoinableView("acme", "Acme", null, listOf(role("admin")))
        assertTrue(joinedRoles(none).isEmpty())
        assertTrue(hasRoleToJoin(none))
        val joined = JoinableView("acme", "Acme", null, listOf(role("admin", userId = 7)))
        assertEquals(listOf("admin"), joinedRoles(joined).map { it.name })
        assertTrue(!hasRoleToJoin(joined))
        // A role an administrator took away is neither opened nor offered again.
        val taken = JoinableView("acme", "Acme", null, listOf(role("admin", removed = true)))
        assertTrue(joinedRoles(taken).isEmpty())
        assertTrue(!hasRoleToJoin(taken))
        // ...and is said, so the card is not left with nothing to do and no reason.
        assertEquals(listOf("admin"), removedRoles(taken).map { it.name })
        assertTrue(removedRoles(none).isEmpty())
        val partly = JoinableView("confdemo", "Conference demo", null, listOf(role("speaker", userId = 9), role("organizer")))
        assertEquals(listOf("speaker"), joinedRoles(partly).map { it.name })
        assertTrue(hasRoleToJoin(partly))
    }
}
