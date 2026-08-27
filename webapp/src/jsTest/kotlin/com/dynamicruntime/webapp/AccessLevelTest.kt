package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #161) for [AdminUser.level] -- the level the Users page reads off a row's role
 * list. Plain list-in / value-out, so it needs no server, browser, or DOM.
 *
 * Its inverse, `RoleLadder.rolesAtLevel`, is covered in the kernel's `RoleLadderTest` instead: it moved there
 * when test provisioning came to need the same rule, and the kernel suite runs it on JVM as well as JS.
 *
 * [rolesWithCapability] stays here, because it is the admin console's half of the pair: the level moves
 * someone between rungs, this moves a capability on and off, and the Users editor composes the two.
 */
class AccessLevelTest {

    private fun user(vararg roles: String) =
        AdminUser(
            userId = 1L, primaryId = "a@b.com", username = "a", roles = roles.toList(),
            client = CL.public, org = null, isEntity = false, name = null, enabled = true, hasPassword = false, deleted = false,
        )

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

    // --- toggling a capability -------------------------------------------------

    @Test
    fun grantingAndRevokingACapability() {
        assertEquals(
            listOf(ROLE.user, ROLE.admin, ROLE.allClients),
            rolesWithCapability(listOf(ROLE.user, ROLE.admin), ROLE.allClients, granted = true),
        )
        assertEquals(
            listOf(ROLE.user, ROLE.admin),
            rolesWithCapability(listOf(ROLE.user, ROLE.admin, ROLE.allClients), ROLE.allClients, granted = false),
        )
    }

    // --- a capability that is held but inert -----------------------------------

    /**
     * The full-scope surface wants the level *and* the capability, so the capability alone reaches nothing.
     * The editor allows the combination -- it is what a demotion leaves behind -- and says so rather than
     * refusing it, which is what this predicate drives.
     */
    @Test
    fun theCapabilityIsDormantBelowAdmin() {
        assertEquals(true, isAllClientsDormant(ROLE.user, granted = true))
        assertEquals(true, isAllClientsDormant(ROLE.operator, granted = true))
        assertEquals(false, isAllClientsDormant(ROLE.admin, granted = true))
    }

    /** Nothing to say when the capability is not being granted at all, at any level. */
    @Test
    fun anUngrantedCapabilityIsNotReportedAsDormant() {
        assertEquals(false, isAllClientsDormant(ROLE.user, granted = false))
        assertEquals(false, isAllClientsDormant(ROLE.admin, granted = false))
    }

    /** A role list is stored as written, so a duplicate would show up in the console as a repeated role. */
    @Test
    fun grantingWhatIsAlreadyHeldDoesNotDuplicateIt() {
        assertEquals(
            listOf(ROLE.user, ROLE.allClients),
            rolesWithCapability(listOf(ROLE.user, ROLE.allClients), ROLE.allClients, granted = true),
        )
    }

    @Test
    fun revokingWhatIsNotHeldChangesNothing() {
        assertEquals(
            listOf(ROLE.user, ROLE.admin),
            rolesWithCapability(listOf(ROLE.user, ROLE.admin), ROLE.allClients, granted = false),
        )
    }

    /** The capability is not a rung, so toggling it must leave the level alone -- and vice versa. */
    @Test
    fun theTwoAxesDoNotDisturbEachOther() {
        // Granting the capability keeps the rung and any other capability.
        assertEquals(
            listOf(ROLE.user, ROLE.operator, "billing", ROLE.allClients),
            rolesWithCapability(listOf(ROLE.user, ROLE.operator, "billing"), ROLE.allClients, granted = true),
        )
        // And a level change over the top preserves the capability, which is what the editor composes.
        val promoted = RoleLadder.rolesAtLevel(
            rolesWithCapability(listOf(ROLE.user), ROLE.allClients, granted = true), ROLE.admin,
        )
        assertEquals(listOf(ROLE.user, ROLE.admin, ROLE.allClients), promoted)
    }

    // --- how a client reads in the create form's selector ---------------------

    @Test
    fun clientLabelCarriesBothTheNameAndTheId() {
        assertEquals("Hub (hub)", clientChoiceLabel(ClientChoice("hub", "Hub")))
        assertEquals("Acme Corp (acme)", clientChoiceLabel(ClientChoice("acme", "Acme Corp")))
    }

    /** An unnamed client shows its id rather than an empty pair of brackets. */
    @Test
    fun clientLabelFallsBackToTheIdAlone() {
        assertEquals("acme", clientChoiceLabel(ClientChoice("acme", "")))
    }

    // --- which access levels the editor offers (issue #464) ------------------

    /**
     * The operator rung is deployment-wide, so it is offered only to a caller who can grant that reach. When
     * they can, all three rungs are on the ladder in order; when they cannot, operator drops out and user and
     * administrator remain -- a scoped administrator may still appoint a client administrator, just not a
     * deployment operator.
     */
    @Test
    fun operatorIsOfferedOnlyWhenSelectable() {
        assertEquals(listOf(ROLE.user, ROLE.operator, ROLE.admin), offeredAccessLevels(operatorSelectable = true))
        assertEquals(listOf(ROLE.user, ROLE.admin), offeredAccessLevels(operatorSelectable = false))
    }
}
