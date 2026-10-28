package com.dynamicruntime.webapp

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The profile's table of a person's users (issue #1330): its rows, when it shows, and what its help is filled from. */
class ProfileUsersTest {
    private val member = UserChoice(1, "acme", PERSONA.member, clientName = "Acme", isCurrent = true)
    private val memberB = UserChoice(2, "acme", PERSONA.member, personaSuffix = "B", clientName = "Acme", signsInAs = true)
    private val placeholder = UserChoice(3, CL.public, PERSONA.member)

    @Test
    fun eachRowSaysWhatTheUserIsAndWhatItOffers() {
        val (active, other, public) = profileUserRows(listOf(member, memberB, placeholder))
        assertEquals("Member", active.label)
        assertEquals("Acme", active.clientLabel)
        assertTrue(active.isActive)
        // The active one is not switched to; the others are.
        assertFalse(active.canSwitch)
        assertTrue(other.canSwitch)
        assertEquals("Member B", other.label)
        assertTrue(other.signsInAs)
        // A client with no name to give is shown by its id; only a public user may be removed.
        assertEquals(CL.public, public.clientLabel)
        assertTrue(public.canRemove)
        assertFalse(other.canRemove)
    }

    @Test
    fun signingInAsAUserIsOfferedOnlyWhereASignInDoesNotAlreadyReachIt() {
        val rows = profileUserRows(listOf(member, memberB))
        assertTrue(rows[0].canMakeDefault)
        assertFalse(rows[1].canMakeDefault)
    }

    @Test
    fun theTableShowsForSeveralUsersOrAPublicOneToRemove() {
        assertFalse(showsUsersTable(listOf(member)))
        assertTrue(showsUsersTable(listOf(member, memberB)))
        // A person whose only user is a public placeholder may still remove it, so the table shows its one row.
        assertTrue(showsUsersTable(listOf(placeholder.copy(isCurrent = true))))
    }

    @Test
    fun theHelpNamesTheActiveUserAndTheOneASignInReaches() {
        assertEquals(
            mapOf("active" to "Member in Acme", "signsInAs" to "Member B in Acme"),
            usersHelpData(listOf(member, memberB)),
        )
        // With no user marked as the one a sign-in lands on, it is said to be the active one.
        assertEquals("Member in Acme", usersHelpData(listOf(member, memberB.copy(signsInAs = false)))["signsInAs"])
    }

    // Issue #1450: the session's roles speak for the active user only, in its row and in the help.
    @Test
    fun theActiveUserSaysTheAccessItHasBesideAPersonaItNoLongerMatches() {
        val promoted = listOf(ROLE.user, ROLE.admin)
        val (active, other) = profileUserRows(listOf(member, memberB), promoted)
        assertEquals("Member (Administrator access)", active.label)
        assertEquals("Member B", other.label)
        assertEquals(
            mapOf("active" to "Member (Administrator access) in Acme", "signsInAs" to "Member B in Acme"),
            usersHelpData(listOf(member, memberB), promoted),
        )
    }

    @Test
    fun aNameColumnShowsOnlyWhenTheUsersAreNamedDifferently() {
        assertFalse(showsNameColumn(listOf(member.copy(name = "Pat"), memberB.copy(name = "Pat"))))
        assertTrue(showsNameColumn(listOf(member.copy(name = "Pat"), memberB.copy(name = "Pat Lee"))))
        // A user with no name differs from a named one.
        assertTrue(showsNameColumn(listOf(member, memberB.copy(name = "Pat"))))
    }

    @Test
    fun theClientIsNamedByItsName() {
        assertEquals("Acme", profileClientName(listOf(member, memberB), "acme"))
        assertEquals("globex", profileClientName(listOf(member), "globex"))
    }
}
