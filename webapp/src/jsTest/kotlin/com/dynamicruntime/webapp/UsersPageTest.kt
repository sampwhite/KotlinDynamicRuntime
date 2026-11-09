package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AERR
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.PERSONASUFFIX
import com.dynamicruntime.common.user.ULU
import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.user.UserLabelDetail
import com.dynamicruntime.common.user.UserLabelUse
import com.dynamicruntime.common.user.UserLabelUseKind
import com.dynamicruntime.common.user.joinedWithOr
import com.dynamicruntime.common.user.userSearchFieldSpecs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

// The Users page's pure rules (issue #1740 gathered these into one file): a row's level and cells; the editor's layout,
// labels, and create form; the person behind a user; a sandbox user; the filter chips and well; and the search's
// address. Each class is the file it used to be, kept whole with its own helpers.

/** The Users editor's layout (issue #1478): its chips, its structure line, its More options, and its save. */
class UserEditorLayoutTest {
    private fun user(
        id: Long = 1L, client: String = "acme", persona: String = PERSONA.member, suffix: String = "",
        roles: List<String> = listOf(ROLE.user), enabled: Boolean = true, registered: Boolean = true,
        deleted: Boolean = false, org: String? = null, name: String? = null, labels: List<String> = emptyList(),
    ) = AdminUser(
        userId = id, primaryId = "ada@acme.test", username = "@ada", roles = roles, client = client, persona = persona,
        personaSuffix = suffix, org = org, isEntity = false, name = name, enabled = enabled, hasPassword = false,
        registered = registered, deleted = deleted, labels = labels,
    )

    @Test
    fun theChipsSayWhetherTheUserIsLiveClaimedAndInASandbox() {
        assertEquals(listOf("Enabled"), userStatusChips(user()))
        assertEquals(listOf("Disabled"), userStatusChips(user(enabled = false)))
        assertEquals(listOf("Deleted"), userStatusChips(user(deleted = true, registered = false)))
        // Unclaimed, with no word on an invitation: one may have been held back (issue #1504).
        assertEquals(listOf("Enabled", "Unclaimed"), userStatusChips(user(registered = false)))
        assertEquals(listOf("Disabled", "Unclaimed"), userStatusChips(user(enabled = false, registered = false)))
        assertEquals(listOf("Enabled", "Sandbox"), userStatusChips(user(client = "acme:sandbox")))
    }

    @Test
    fun theStructureLineIsThePersonTheirUsersAndTheOpenOnesAccess() {
        val admin = user(1L, persona = PERSONA.admin, roles = listOf(ROLE.user, ROLE.admin))
        val member = user(2L)
        // Alone, or before the person's users load: the user by its client and persona.
        val alone = userStructure(admin, null)
        assertEquals("ada@acme.test", alone.person)
        assertEquals(listOf("acme / Admin"), alone.users.map { it.label })
        assertTrue(alone.users.single().selected)
        assertEquals("Administrator access", alone.access)
        // With the person's users: each by what tells it apart, the open one marked.
        val both = userStructure(member, listOf(admin, member))
        assertEquals(listOf("Admin", "Member"), both.users.map { it.label })
        assertEquals(listOf(false, true), both.users.map { it.selected })
        assertEquals("User access", both.access)
        // A list that does not hold the open user (a stale load) is not drawn as its person's.
        assertEquals(listOf("acme / Member"), userStructure(member, listOf(admin)).users.map { it.label })
        // A person with this one user: the user alone, as before the load.
        assertEquals(listOf("acme / Member"), userStructure(member, listOf(member)).users.map { it.label })
        // A sandbox user somebody opened has the access of whom it acts as (issue #1198), not its row's.
        val opened = AdminUser(
            userId = 5L, primaryId = "ada@acme.test", username = "@ada", roles = listOf(ROLE.user), client = "acme:sandbox",
            org = null, isEntity = false, name = null, enabled = true, hasPassword = false, deleted = false,
            actingRoles = listOf(ROLE.user, ROLE.admin),
        )
        assertEquals("Administrator access", userStructure(opened, null).access)
    }

    @Test
    fun anotherUserSaysBesideItsLinkWhenItIsNotSimplyLive() {
        assertEquals(null, userStructureFlag(user()))
        assertEquals("disabled", userStructureFlag(user(enabled = false)))
        assertEquals("unclaimed", userStructureFlag(user(registered = false)))
        assertEquals("disabled, unclaimed", userStructureFlag(user(enabled = false, registered = false)))
        assertEquals("deleted", userStructureFlag(user(deleted = true, registered = false)))
    }

    @Test
    fun moreOptionsNamesWhatIsSetAndNothingAtItsDefault() {
        assertEquals(emptyList(), userMoreOptionsInForce(false, false, enabled = true, creating = false))
        assertEquals(
            listOf("Editor", "All clients", "disabled"),
            userMoreOptionsInForce(true, true, enabled = false, creating = false),
        )
        // On a create: the suffix, a collision asking for one, and starting disabled.
        assertEquals(
            listOf("persona suffix B"), userMoreOptionsInForce(false, false, true, true, personaSuffix = " B "),
        )
        assertEquals(
            listOf("a persona suffix is needed"),
            userMoreOptionsInForce(false, false, true, true, suffixNeeded = true),
        )
        assertEquals(listOf("starts disabled"), userMoreOptionsInForce(false, false, enabled = false, creating = true))
        // What it says is what opens it.
        assertTrue(moreOptionsOpens(userMoreOptionsInForce(true, false, true, false)))
        assertFalse(moreOptionsOpens(userMoreOptionsInForce(false, false, enabled = true, creating = false)))
    }

    @Test
    fun aDraftChangesOnlyWhatTheBackendWouldStoreDifferently() {
        val target = user(org = "east", name = "Ada", labels = listOf("reviewer"))
        val member = UserAuthority(ROLE.user, editor = false, allClients = false)
        fun changes(
            authority: UserAuthority = member, org: String = "east", name: String = "Ada", isEntity: Boolean = false,
            enabled: Boolean = true, labels: List<String> = listOf("reviewer"), opened: Boolean = false,
        ) = userDraftChanges(target, authority, editorOffered = true, org, name, isEntity, enabled, labels, opened)
        // Re-spacing, repeating, or reordering is no change.
        assertEquals(emptyList(), changes(org = " east ", name = "Ada ", labels = listOf(" reviewer", "reviewer")))
        // Each change is one call, in the order a save sends them.
        assertEquals(
            listOf(UserChange.roles, UserChange.org, UserChange.accountName, UserChange.enabled, UserChange.labels),
            changes(member.copy(level = ROLE.admin), "", "Ada L", enabled = false, labels = emptyList()),
        )
        assertEquals(listOf(UserChange.accountName), changes(isEntity = true))
        // A sandbox user somebody opened has no roles or organization of its own to set.
        assertEquals(emptyList(), changes(member.copy(level = ROLE.admin), "west", opened = true))
    }

    @Test
    fun authorityIsComparedAsTheEditorControlsItSoAnOddListOpensClean() {
        // Two rungs, or `editor` beside `admin`: the editor draws one rung and no Editor box, and opens unchanged.
        for (roles in listOf(
            listOf(ROLE.user, ROLE.operator, ROLE.admin),
            listOf(ROLE.user, ROLE.admin, ROLE.editor),
            listOf(ROLE.admin),
        )) {
            val target = user(roles = roles)
            val draft = draftAuthority(target.level, ROLE.editor in roles, ROLE.allClients in roles, editorOffered = true)
            assertEquals(emptyList(), userDraftChanges(target, draft, true, "", "", false, true, emptyList(), false), "$roles")
        }
        // In `public`, where the Editor box is not drawn, a stored `editor` is not a difference either.
        val publicEditor = user(client = "public", roles = listOf(ROLE.user, ROLE.editor))
        val draft = draftAuthority(ROLE.user, editor = true, allClients = false, editorOffered = false)
        assertEquals(emptyList(), userDraftChanges(publicEditor, draft, false, "", "", false, true, emptyList(), false))
    }

    @Test
    fun aSavedAuthorityKeepsWhatTheEditorDoesNotDrawAndDropsAHiddenEditor() {
        val twoRungs = listOf(ROLE.user, ROLE.operator, ROLE.admin)
        // Same rung, a box ticked: the list as it is, plus the capability.
        assertEquals(
            (twoRungs + ROLE.allClients).toSet(),
            rolesForAuthority(twoRungs, UserAuthority(ROLE.admin, editor = false, allClients = true)).toSet(),
        )
        // Another rung: moved, as RoleLadder moves it.
        assertEquals(setOf(ROLE.user), rolesForAuthority(twoRungs, UserAuthority(ROLE.user, false, false)).toSet())
        // Editor ticked, then the level raised to Administrator: the box is hidden, so no `editor` is sent.
        val hidden = draftAuthority(ROLE.admin, editor = true, allClients = false, editorOffered = true)
        assertEquals(setOf(ROLE.user, ROLE.admin), rolesForAuthority(emptyList(), hidden).toSet())
    }

    @Test
    fun aFailedSaveSaysWhatItSavedFirst() {
        assertEquals("Couldn't save the labels.", userSaveFailureLead(emptyList(), UserChange.labels))
        assertEquals(
            "Saved the access and name. Couldn't save the labels.",
            userSaveFailureLead(listOf(UserChange.roles, UserChange.accountName), UserChange.labels),
        )
        assertEquals("a, b, and c", listPhrase(listOf("a", "b", "c")))
        assertEquals("Couldn't save the user.", userSaveFailureLead(emptyList(), null))
    }

    @Test
    fun anInvitationIsOfferedExceptAtTheCallersOwnAddress() {
        assertTrue(invitationOfferedFor(forSelf = false, "pat@acme.test", "me@acme.test"))
        // The form for yourself, or your own address typed, however it is spelled.
        assertFalse(invitationOfferedFor(forSelf = true, "me@acme.test", "me@acme.test"))
        assertFalse(invitationOfferedFor(forSelf = false, " ME@acme.test ", "me@acme.test"))
        // Until the caller's own address is known, the backend's rule is what answers.
        assertTrue(invitationOfferedFor(forSelf = false, "me@acme.test", null))
    }

    @Test
    fun moreOptionsNamesAnInvitationHeldBackOnlyWhileOneWouldGo() {
        assertTrue(invitationHeld(offered = true, enabled = true, invite = false))
        assertFalse(invitationHeld(offered = true, enabled = true, invite = true))
        // A disabled user is never invited, and nothing is mailed at your own address: nothing is held back.
        assertFalse(invitationHeld(offered = true, enabled = false, invite = false))
        assertFalse(invitationHeld(offered = false, enabled = true, invite = false))
        assertEquals(
            listOf("no invitation"), userMoreOptionsInForce(false, false, true, true, invitationHeld = true),
        )
    }

    @Test
    fun theNoteAfterACreateSaysWhetherTheInvitationWent() {
        fun note(invitable: Boolean, wanted: Boolean, sent: Boolean) = createdNote("a@b.test", invitable, wanted, sent)
        assertEquals("Created a@b.test. Invitation sent.", note(invitable = true, wanted = true, sent = true).text)
        assertEquals(
            "Created a@b.test. No invitation sent: open the user to send one.", note(invitable = true, wanted = false, sent = false).text,
        )
        // A mail that failed did not fail the create, so the note must not claim it went, and is drawn as a failure.
        val failed = note(invitable = true, wanted = true, sent = false)
        assertEquals("Created a@b.test, but its invitation could not be sent: open the user to send it.", failed.text)
        assertTrue(failed.failed)
        assertFalse(note(invitable = true, wanted = true, sent = true).failed)
        // The caller's own address, or a disabled user: no invitation was in question.
        assertEquals("Created a@b.test.", note(invitable = false, wanted = true, sent = false).text)
        assertFalse(note(invitable = false, wanted = true, sent = false).failed)
    }

    @Test
    fun aClientIsCarriedByWhatTheCallerWasTold() {
        // A scoped administrator is told no clients, and works in one that is carried.
        assertTrue(clientCarried("acme", emptySet()))
        assertTrue(clientCarried("acme", setOf("acme", "hub")))
        assertFalse(clientCarried("retired", setOf("acme", "hub")))
        // A sandbox goes with its parent.
        assertTrue(clientCarried("acme:sandbox", setOf("acme")))
        assertFalse(clientCarried("retired:sandbox", setOf("acme")))
    }

    @Test
    fun aUserIsOfferedTheMailThatFitsThem() {
        fun offer(target: AdminUser, own: Boolean = false, carried: Boolean = true) = userMailOffer(target, own, carried)
        assertEquals(UserMailOffer.invitation, offer(user(registered = false)))
        assertEquals(UserMailOffer.reminder, offer(user()))
        // A claimed user's invitation would sign in whoever held the mail, and nobody reminds themself.
        assertEquals(UserMailOffer.none, offer(user(), own = true))
        // A sandbox's own user signs in; one somebody opened, or a client not carried here, cannot.
        assertEquals(UserMailOffer.reminder, offer(user(client = "acme:sandbox")))
        val opened = AdminUser(
            userId = 5L, primaryId = "ada@acme.test", username = "@ada", roles = listOf(ROLE.user), client = "acme:sandbox",
            org = null, isEntity = false, name = null, enabled = true, hasPassword = false, deleted = false, registered = true,
            opened = true,
        )
        assertEquals(UserMailOffer.none, offer(opened))
        assertEquals(UserMailOffer.none, offer(user(), carried = false))
        // Nobody can sign in to a disabled or deleted user.
        assertEquals(UserMailOffer.none, offer(user(enabled = false)))
        assertEquals(UserMailOffer.none, offer(user(enabled = false, registered = false)))
        assertEquals(UserMailOffer.none, offer(user(deleted = true, registered = false)))
    }

    @Test
    fun aCreateIsDirtyOnceAnyFieldDiffersFromWhatItOpenedWith() {
        val seeded = CreateDraft(
            "", "", false, ROLE.user, false, false, "", "acme", PERSONA.member, "", emptyList(), enabled = true,
        )
        assertFalse(createDraftDirty(seeded, seeded))
        // Whitespace alone, or a label typed twice and taken back, is nothing changed.
        assertFalse(createDraftDirty(seeded.copy(name = "  ", personaSuffix = " "), seeded))
        // Every field counts: not only what is typed, but a client, a persona, a rung, a box, or Enabled.
        for (changed in listOf(
            seeded.copy(email = "a@b.test"), seeded.copy(client = "globex"), seeded.copy(persona = PERSONA.admin),
            seeded.copy(level = ROLE.admin), seeded.copy(allClients = true), seeded.copy(org = "east"),
            seeded.copy(enabled = false), seeded.copy(labels = listOf("reviewer")), seeded.copy(invite = false),
        )) {
            assertTrue(createDraftDirty(changed, seeded), "$changed")
        }
        // The caller's own address, filled in for them, is what the form opened with.
        val forSelf = seeded.copy(email = "me@b.test")
        assertFalse(createDraftDirty(forSelf, forSelf))
    }

    @Test
    fun theHintsUnderMoreOptionsSayWhenToUseThem() {
        for (hint in listOf(editorHint, enabledHint(creating = true), enabledHint(creating = false), sendInvitationHint)) {
            assertTrue(hint.startsWith("Use this when"), hint)
        }
    }
}

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

/** The persona controls on the create form (issue #750): the level a persona implies, and when the personaSuffix box is offered. */
class PersonaFormTest {
    @Test
    fun aPersonaMovesTheLevelToItsDefault() {
        assertEquals(ROLE.admin, levelForPersona(PERSONA.admin))
        assertEquals(ROLE.user, levelForPersona(PERSONA.member))
        assertEquals(ROLE.user, levelForPersona("wizard"))
    }

    @Test
    fun theLevelProvidesTheDefaultPersona() {
        assertEquals(PERSONA.admin, personaForLevel(ROLE.admin))
        assertEquals(PERSONA.member, personaForLevel(ROLE.user))
        // No persona names the operator rung, so an operator is a member.
        assertEquals(PERSONA.member, personaForLevel(ROLE.operator))
        assertEquals(PERSONA.admin, PERSONA.defaultFor(listOf(ROLE.user, ROLE.admin, ROLE.allClients)))
    }

    @Test
    fun anEditorIsARoleBesideTheLevelWithAPersonaOfItsOwn() {
        // The checkbox adds or drops the role and keeps the level; the level keeps the role (issue #1432).
        assertEquals(listOf(ROLE.user, ROLE.editor), rolesWithEditor(listOf(ROLE.user), granted = true))
        assertEquals(listOf(ROLE.user), rolesWithEditor(listOf(ROLE.user, ROLE.editor), granted = false))
        assertEquals(listOf(ROLE.user, ROLE.operator, ROLE.editor), RoleLadder.rolesAtLevel(listOf(ROLE.user, ROLE.editor), ROLE.operator))
        // The persona follows: an editor below the admin level is an Editor, and an administrator stays one.
        assertEquals(PERSONA.editor, personaForRoles(ROLE.user, editor = true))
        assertEquals(PERSONA.member, personaForRoles(ROLE.user, editor = false))
        assertEquals(PERSONA.admin, personaForRoles(ROLE.admin, editor = true))
        assertEquals(ROLE.user, levelForPersona(PERSONA.editor))
        // How the read-only details name a user's access.
        assertEquals("Editor", accessText(listOf(ROLE.user, ROLE.editor)))
        assertEquals("Administrator", accessText(listOf(ROLE.user, ROLE.admin, ROLE.editor)))
        assertEquals("User", accessText(listOf(ROLE.user)))
        // Not offered in `public`, where it would confer nothing.
        val offered: (Boolean) -> List<String> = { on -> personaOptions(editorOffered = on).map { it.value as String } }
        assertTrue(PERSONA.editor in offered(true))
        assertFalse(PERSONA.editor in offered(false))
        assertEquals(listOf(PERSONA.member, PERSONA.admin), offered(false))
    }

    @Test
    fun thePersonaSuffixIsOfferedOnlyForAKeyCollision() {
        val taken = ApiError("A user for a@b.com already exists in client public with persona member.", fromFragment = true, status = 400, errorCode = AERR.userKeyTaken, traceId = null)
        assertTrue(isUserKeyCollision(taken))
        // The wording is not what decides it: the same sentence with no code, or another refusal, offers nothing.
        assertFalse(isUserKeyCollision(ApiError(taken.message, fromFragment = true, status = 400, errorCode = null, traceId = null)))
        assertFalse(isUserKeyCollision(IllegalStateException("Username 'ada' has already been taken.")))
    }
}

/**
 * Every spec field the table renders a column for must have a display branch in [cellValue] (issue #411, the
 * SDUI slice). This is the front-end half of the "add a field and it flows through" contract -- the backend
 * half is pinned by `UserSearchTest`. Without this, adding a field to `userSearchFieldSpecs` and forgetting its
 * `cellValue` branch would ship a **silently blank column**; here it fails the build instead.
 */
class UserCellValueTest {

    private fun populatedUser(): AdminUser = AdminUser(
        userId = 1L,
        primaryId = "ada@example.com",
        username = "ada_l",
        roles = listOf("user"),
        client = "acme",
        persona = "admin",
        personaSuffix = "B",
        org = "eng",
        isEntity = false,
        name = "Ada Lovelace",
        enabled = true,
        hasPassword = true,
        deleted = false,
        updatedAt = "2026-08-24T18:00:00.000Z",
        lastEditedAt = "2026-08-24T18:00:00.000Z",
        lastLoggedInAt = "2026-08-24T18:00:00.000Z",
        activatedAt = "2026-08-24T18:00:00.000Z",
        registeredAt = "2026-08-24T18:00:00.000Z",
        labels = listOf("reviewer", "siteLead"),
    )

    // Issue #1450: the Persona column says the access a user has beside a persona it no longer matches, read from the
    // roles it acts with -- in a sandbox, those, not its row's.
    @Test
    fun thePersonaSaysTheAccessTheUserHasWhenItDiffers() {
        val now = Clock.System.now()
        fun member(roles: List<String>, actingRoles: List<String>? = null) = AdminUser(
            userId = 2L, primaryId = "ada@example.com", username = "ada_l", roles = roles, client = "acme:sandbox",
            persona = "member", org = null, isEntity = false, name = null, enabled = true, hasPassword = false,
            deleted = false, actingRoles = actingRoles,
        )
        assertEquals("Member (Administrator access)", cellValue(USF.persona, member(listOf("user", "admin")), now))
        assertEquals("Member", cellValue(USF.persona, member(listOf("user")), now))
        // In a sandbox the roles it acts with win over its row's -- and acting with nothing says so.
        assertEquals("Member (No access)", cellValue(USF.persona, member(listOf("user", "admin"), emptyList()), now))
    }

    @Test
    fun theStatusSaysUnclaimedForAUserNobodyHasClaimed() {
        val claimed = populatedUser()
        assertEquals(listOf("enabled", "password set"), statusWords(claimed))
        assertEquals(listOf("enabled", "unclaimed"), statusWords(AdminUser(
            userId = 2L, primaryId = "b@example.com", username = "@b@example.com", roles = listOf("user"), client = "acme",
            org = null, isEntity = false, name = null, enabled = true, hasPassword = false, registered = false, deleted = false,
        )))
        // A tombstone is deleted, whatever it was before; it never reads as unclaimed.
        assertEquals(listOf("deleted"), statusWords(AdminUser(
            userId = 3L, primaryId = "deleted-3@deleted.invalid", username = "deleted-3", roles = emptyList(), client = "acme",
            org = null, isEntity = false, name = null, enabled = false, hasPassword = false, registered = false, deleted = true,
        )))
    }

    @Test
    fun theIdentifyingColumnsArePinnedAndTheRestScroll() {
        for (field in listOf("userId", USF.email, USF.name, USF.client, USF.persona)) assertEquals("left", pinnedSide(field))
        assertEquals("right", pinnedSide("status"))
        // The links column follows the pinned status, so it is pinned with it rather than sliding beneath it.
        assertEquals("right", pinnedSide("links"))
        for (field in listOf(USF.registered.at, USF.activated.at, "type", USF.roles)) assertEquals(null, pinnedSide(field))
    }

    /** antd's third click on a header cancels the sort; that must land on the default order, not be dropped. */
    @Test
    fun cancellingASortReturnsToTheDefaultOrder() {
        assertEquals("activatedAt" to false, sortAfterHeaderClick("activatedAt", "ascend"))
        assertEquals("activatedAt" to true, sortAfterHeaderClick("activatedAt", "descend"))
        assertEquals(defaultUserSortKey to defaultUserSortDescending, sortAfterHeaderClick("activatedAt", null))
        // antd's cancel names no field either: still the default order, not "no sort" (which made cancel do nothing).
        assertEquals(defaultUserSortKey to defaultUserSortDescending, sortAfterHeaderClick(null, null))
        // An order with no field is not a click anyone makes.
        assertEquals(null, sortAfterHeaderClick(null, "ascend"))
        // The default column never offers a cancel that would change nothing: newest-first, oldest-first, and back.
        assertEquals(listOf("descend", "ascend", "descend"), userSortCycle(defaultUserSortKey))
        // Every other column keeps antd's own cycle, whose cancel returns to the default order.
        assertEquals(null, userSortCycle("activatedAt"))
    }

    @Test
    fun thePersonaColumnShowsTheLabelAndThePersonaSuffix() {
        assertEquals("Admin B", personaCell("admin", "B"))
        assertEquals("Member", personaCell("member", ""))
    }

    // Issue #786: the Labels column lists them, and a dash for none, as an absent date has.
    @Test
    fun theLabelsColumnListsTheLabels() {
        assertEquals("reviewer, siteLead", cellValue(USF.labels, populatedUser(), Clock.System.now()))
        val unlabeled = AdminUser(
            userId = 2L, primaryId = "b@example.com", username = "b", roles = listOf("user"), client = "acme",
            org = null, isEntity = false, name = null, enabled = true, hasPassword = false, deleted = false,
        )
        assertEquals("—", cellValue(USF.labels, unlabeled, Clock.System.now()))
    }

    @Test
    fun everySpecFieldHasADisplayValue() {
        val user = populatedUser()
        for (spec in userSearchFieldSpecs) {
            val value = cellValue(spec.name, user, Clock.System.now())
            // A fully-populated user should produce a real, non-placeholder value for every column.
            assertTrue(
                value.isNotBlank() && value != unmappedCell,
                "Spec field '${spec.name}' has no cellValue branch (got '$value'); it would render a blank column.",
            )
        }
    }
}

/**
 * The users console's filter chips (issue #683): the filters in force said in words while the panel is closed,
 * built from the shared spec so a chip can never disagree with the count; the sort as its own chip; and the
 * shared chip vocabulary both listing pages draw on. Pure -- no React, no location -- like the address round-trip
 * beside it.
 */
class UserFilterChipsTest {

    @Test
    fun nothingInForceGivesNoChips() {
        assertEquals(emptyList(), userFilterChips(emptyMap(), emptyMap()))
        // A whitespace-only term is kept while it is typed but filters nothing, so it is not a chip either.
        assertEquals(emptyList(), userFilterChips(mapOf(USF.email to "  "), emptyMap()))
        // A bound present but blank (a hand-edited link) is no bound.
        assertEquals(emptyList(), userFilterChips(emptyMap(), mapOf(USF.lastEdited.at to DateRange("", null))))
    }

    @Test
    fun eachFilterKindReadsInItsOwnWordsAndDatesOnTheTablesClock() {
        val chips = userFilterChips(
            texts = mapOf(USF.email to "ada", USF.name to "Lovelace", USF.client to "acme"),
            ranges = mapOf(
                USF.lastEdited.at to DateRange("2026-01-01T00:00:00.000Z", "2026-06-01T00:00:00.000Z"),
                USF.lastLoggedIn.at to DateRange("2026-08-01T10:00:00.000Z", null),
                USF.activated.at to DateRange(null, "2025-12-31T00:00:00.000Z"),
            ),
        )
        // A date bound reads exactly as the table renders the column it filters: in the client's zone (here the
        // deployment's, UTC-8, issue #1115), to the minute.
        assertEquals(
            listOf(
                "Email contains \"ada\"",
                "Name contains \"Lovelace\"",
                "Client is \"acme\"",
                "Edited Dec 31, 2025, 4:00 PM GMT-8 – May 31, 2026, 4:00 PM GMT-8",
                "Last login ≥ Aug 1, 2026, 2:00 AM GMT-8",
                "Activated ≤ Dec 30, 2025, 4:00 PM GMT-8",
            ),
            chips,
        )
    }

    @Test
    fun aClientTermIsAChipWhoeverTheCallerIs() {
        // A shared link may carry a client term the caller's own well does not offer. It still counts as a
        // filter (Clear resets it, the count line reports it), so it must be visible somewhere -- and the chip is
        // the only somewhere.
        assertEquals(listOf("Client is \"acme\""), userFilterChips(mapOf(USF.client to "acme"), emptyMap()))
    }

    @Test
    fun aPickedPersonaOrRoleReadsAsItsLabel() {
        // The picked value's label, as the picker showed it (issue #1432); a list-valued field says `include`.
        assertEquals(
            listOf("Persona is \"Editor\"", "Roles include \"All clients\""),
            userFilterChips(mapOf(USF.persona to "editor", USF.roles to "allClients"), emptyMap()),
        )
        // A value no choice names, from a hand-edited link, shows as it came rather than vanishing.
        assertEquals(listOf("Persona is \"B\""), userFilterChips(mapOf(USF.persona to "B"), emptyMap()))
    }

    // Issue #1481: a label has no option to take a label from, so it shows as typed, case kept, and says `include`.
    @Test
    fun aLabelReadsAsTypedAndIsIncluded() {
        assertEquals(listOf("Labels include \"siteLead\""), userFilterChips(mapOf(USF.labels to "siteLead"), emptyMap()))
    }

    @Test
    fun theSortIsItsOwnChipAndOnlyWhenNotTheDefault() {
        // The default -- newest edit first -- is not a chip.
        assertNull(userSortChip(USF.lastEdited.at, descending = true))
        // The default field the other way round, and a date field's words are about time.
        assertEquals("Sorted by Edited, oldest first", userSortChip(USF.lastEdited.at, descending = false))
        assertEquals("Sorted by Last login, newest first", userSortChip(USF.lastLoggedIn.at, descending = true))
        // A text field's words are alphabetical.
        assertEquals("Sorted by Name, A–Z", userSortChip(USF.name, descending = false))
        assertEquals("Sorted by Email, Z–A", userSortChip(USF.email, descending = true))
    }

    @Test
    fun theSharedChipWordsDropABlankValue() {
        assertNull(textChip("Name", "  ", contains = true))
        assertEquals("Name is \"x\"", textChip("Name", "x", contains = false))
        assertNull(rangeChip("Year", " ", null))
        assertEquals("Year ≥ 2020", rangeChip("Year", "2020", " "))
        assertEquals("Year ≤ 2025", rangeChip("Year", null, "2025"))
    }

    @Test
    fun theToggleReportsTheCountWhileClosed() {
        assertEquals("Filters", filterToggleLabel(open = false, filterCount = 0))
        assertEquals("Filters (2)", filterToggleLabel(open = false, filterCount = 2))
        assertEquals("Hide filters", filterToggleLabel(open = true, filterCount = 2))
    }
}

/**
 * The filter well's rows: how many columns a width lays out, and when the Users page's tall date ranges start a row of
 * their own rather than stretching the text boxes beside them -- only when that saves height.
 */
class FilterWellLayoutTest {
    @Test
    fun aWidthLaysOutWhatTheGridFitsAndAtLeastOneColumn() {
        // Inner width after 12px padding each side; each further track needs 300 plus a 24px gap.
        assertEquals(1, filterWellColumns(0.0))
        assertEquals(1, filterWellColumns(500.0))
        assertEquals(2, filterWellColumns(2 * 12.0 + 300 + 24 + 300))
        assertEquals(3, filterWellColumns(1160.0))
        assertEquals(4, filterWellColumns(1580.0))
    }

    /** Measured on the Users page: both shapes, with the well heights each choice gave. */
    @Test
    fun theDatesBreakOnlyWhenThatSavesARow() {
        // Three text boxes in four columns: a date range beside them stretched them (272px); breaking gave 205px.
        assertTrue(breakBeforeTall(shortCount = 3, tallCount = 4, columns = 4))
        // Four in three: the fourth shares a tall row that is there anyway (334px); breaking added a row (396px).
        assertFalse(breakBeforeTall(shortCount = 4, tallCount = 4, columns = 3))
        // A full last row needs no break; neither does a well with nothing tall, or nothing short.
        assertFalse(breakBeforeTall(shortCount = 3, tallCount = 4, columns = 3))
        assertFalse(breakBeforeTall(shortCount = 3, tallCount = 0, columns = 4))
        assertFalse(breakBeforeTall(shortCount = 0, tallCount = 4, columns = 4))
    }
}

/**
 * The shareable user-search URL (issue #411): the search encodes to route params and reads back to the same
 * query, only the non-default parts appear (so a plain search is a clean URL), and a hand-edited or stale link
 * cannot ask for a sort field the endpoint would reject. The address keys are the endpoint's own arg names (from
 * the shared spec), so this exercises the spec-driven encoding too. Pure -- no React, no location.
 */
class UserSearchRouteTest {

    private fun roundTrip(q: UserSearchQuery): UserSearchQuery =
        searchQueryFromRoute(searchRouteParams(q).toMap())

    @Test
    fun aDefaultSearchEmitsNoParams() {
        assertTrue(searchRouteParams(UserSearchQuery()).isEmpty())
    }

    @Test
    fun onlyNonDefaultPartsAreEmittedUnderTheWireKeys() {
        val params = searchRouteParams(
            UserSearchQuery(textTerms = mapOf(USF.email to "ada"), sortBy = USF.name, descending = false),
        ).toMap()
        assertEquals("ada", params[USF.email])
        assertEquals(USF.name, params[USF.sortBy])
        assertEquals("false", params[USF.descending])
        // Untouched fields carry nothing.
        assertEquals(null, params[USF.client])
    }

    @Test
    fun aFullSearchRoundTrips() {
        val q = UserSearchQuery(
            textTerms = mapOf(USF.email to "a@b", USF.name to "Ada", USF.client to "acme"),
            ranges = mapOf(USF.lastEdited.at to DateRange("2026-01-01T00:00:00.000Z", "2026-06-01T00:00:00.000Z")),
            sortBy = USF.client, descending = false,
        )
        val back = roundTrip(q)
        assertEquals("a@b", back.textTerms[USF.email])
        assertEquals("Ada", back.textTerms[USF.name])
        assertEquals("acme", back.textTerms[USF.client])
        assertEquals("2026-01-01T00:00:00.000Z", back.ranges[USF.lastEdited.at]?.after)
        assertEquals("2026-06-01T00:00:00.000Z", back.ranges[USF.lastEdited.at]?.before)
        assertEquals(USF.client, back.sortBy)
        assertEquals(false, back.descending)
    }

    @Test
    fun theAnyTextTermRoundTrips() {
        // The single "any text" term (issue #581) encodes to the wire `q` and reads back, so a shared link
        // keeps it rather than dropping it on decode.
        val params = searchRouteParams(UserSearchQuery(anyText = "dana")).toMap()
        assertEquals("dana", params["q"])
        assertEquals("dana", roundTrip(UserSearchQuery(anyText = "dana")).anyText)
        // Absent by default, so a plain search stays a clean URL.
        assertEquals(null, searchRouteParams(UserSearchQuery()).toMap()["q"])
        assertEquals(null, roundTrip(UserSearchQuery()).anyText)
    }

    @Test
    fun anEmptyAddressIsTheDefaultSearch() {
        val q = searchQueryFromRoute(emptyMap())
        assertTrue(q.textTerms.isEmpty())
        assertTrue(q.ranges.isEmpty())
        assertEquals(USF.lastEdited.at, q.sortBy)
        assertEquals(true, q.descending)
    }

    @Test
    fun aBlankBoundInTheAddressIsNoBound() {
        // `lastEditedAfter=` from a hand-edited link: present but empty must not become a filter -- it would
        // count toward Clear and the count line, be sent, and read as a chip with nothing after it (issue #683).
        assertTrue(searchQueryFromRoute(mapOf(USF.lastEdited.after to "")).ranges.isEmpty())
        // A real bound beside a blank one keeps only the real one.
        val q = searchQueryFromRoute(mapOf(USF.lastEdited.after to "2026-01-01T00:00:00.000Z", USF.lastEdited.before to " "))
        assertEquals("2026-01-01T00:00:00.000Z", q.ranges[USF.lastEdited.at]?.after)
        assertEquals(null, q.ranges[USF.lastEdited.at]?.before)
    }

    @Test
    fun anUnknownSortKeyFallsBackToTheDefault() {
        // A hand-edited or stale link must not send a sort the endpoint would 400 on.
        assertEquals(USF.lastEdited.at, searchQueryFromRoute(mapOf(USF.sortBy to "bogusField")).sortBy)
        // publicName is a backend axis but not a console column, so it is not an accepted address sort either.
        assertEquals(USF.lastEdited.at, searchQueryFromRoute(mapOf(USF.sortBy to USF.publicName)).sortBy)
    }
}

/** The person behind a user in the Users editor (issue #770): the sibling rows and the summary. */
class UserIdentityViewTest {
    private fun user(
        id: Long, client: String, persona: String = PERSONA.member, suffix: String = "",
        enabled: Boolean = true, registered: Boolean = true, roles: List<String> = listOf("user"),
    ) = AdminUser(
        userId = id, primaryId = "p@example.com", username = "@p", roles = roles, client = client,
        persona = persona, personaSuffix = suffix, org = null, isEntity = false, name = null, enabled = enabled,
        hasPassword = false, registered = registered, deleted = false,
    )

    // Issue #1450: each sibling's persona says the access it has when that differs, from its own roles.
    @Test
    fun aSiblingSaysTheAccessItHasBesideAPersonaItNoLongerMatches() {
        val promoted = user(1, CL.hub, roles = listOf("user", "admin"))
        val rows = identitySiblings(listOf(promoted, user(2, CL.hub, persona = PERSONA.admin)), 1L)
        assertEquals(listOf("Member (Administrator access)", "Admin (User access)"), rows.map { it.label })
    }

    @Test
    fun aPersonWithOneUserHasNoSiblingList() {
        assertTrue(identitySiblings(listOf(user(1, CL.hub)), 1L).isEmpty())
    }

    @Test
    fun siblingsKeepTheirOrderAndMarkTheOpenOne() {
        val users = listOf(user(1, CL.public), user(2, CL.hub), user(3, CL.hub, suffix = "B"))
        val rows = identitySiblings(users, 2L)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.user.userId })
        assertEquals(listOf(false, true, false), rows.map { it.selected })
        // Each says what tells it apart, as the badge does.
        assertEquals(listOf("public · Member", "hub · Member", "hub · Member B"), rows.map { it.label })
    }

    @Test
    fun aSiblingsStatusIsTheListsStatus() {
        val rows = identitySiblings(listOf(user(1, CL.hub), user(2, CL.hub, suffix = "B", registered = false)), 1L)
        assertEquals("enabled", rows[0].status)
        assertEquals("enabled, unclaimed", rows[1].status)
    }

    @Test
    fun theSummarySaysWhoALoginLandsOn() {
        val me = user(1, CL.hub)
        val other = user(2, CL.hub, suffix = "B")
        fun signsInAs(id: Long?) =
            identitySummary(AdminIdentity(null, false, id, listOf(me, other)), me).toMap().getValue("Signs in as")
        assertEquals("This user", signsInAs(1L))
        assertEquals("Member B", signsInAs(2L))
        // Absent when it is nobody the viewer administers.
        assertEquals("—", signsInAs(null))
    }

    @Test
    fun theSummaryStatesTheIdentitysFacts() {
        val me = user(1, CL.hub)
        val summary = identitySummary(AdminIdentity(null, false, 1L, listOf(me)), me).toMap()
        assertEquals("Not yet proven", summary.getValue("Address"))
        assertEquals("Not set", summary.getValue("Password"))
        assertEquals("—", summary.getValue("Registered"))
        val proven = identitySummary(AdminIdentity("2026-09-23T10:00:00Z", true, 1L, listOf(me)), me).toMap()
        assertTrue(proven.getValue("Address").startsWith("Proven "))
        assertEquals("Set", proven.getValue("Password"))
    }

    @Test
    fun aCallerNotToldTheIdentitysFactsIsShownNoneOfThem() {
        // The backend leaves them out for a client administrator (issue #1298); "Not yet proven" would be a guess.
        val me = user(1, CL.hub)
        val summary = identitySummary(AdminIdentity(null, null, null, listOf(me)), me).toMap()
        assertEquals(listOf("Registered", "Activated", "Last login", "Last edited", "Updated"), summary.keys.toList())
    }

    // Issue #1478: the editor's view, and an administrator's before the person loads, have no identity -- the dates
    // alone, with nothing said about the address, password, or where a login lands.
    @Test
    fun withNoIdentityTheSummaryIsTheUsersDatesAlone() {
        val me = user(1, CL.hub)
        assertEquals(
            listOf("Registered", "Activated", "Last login", "Last edited", "Updated"),
            identitySummary(null, me).map { it.first },
        )
    }
}

/**
 * The Users editor's Labels group (issue #1659): a row per suggested label saying what the client's workflows do with
 * it, free entry for any other, and the prompt for a user with none -- as read from the label-suggestions answer
 * (issue #1658), the client's notes included (issue #1660).
 */
class UserLabelChoicesTest {
    private val approves = UserLabelUse(UserLabelUseKind.approves, "plan", "Plan the logistics", "approve", "Approve the plan", "kdr:reviewer")
    private val sees = UserLabelUse(UserLabelUseKind.sees, "plan", "Plan the logistics")
    private val shows = UserLabelUse(UserLabelUseKind.tests, "plan", "Plan the logistics", "arrange", "Arrange", "kdr:reviewer")
    private val details = listOf(
        UserLabelDetail("reviewer", listOf(approves, sees), carriers = 2, note = "Whoever signs off plans."),
        UserLabelDetail("siteLead", listOf(shows), carriers = 0),
        UserLabelDetail("spare", emptyList(), carriers = 1),
    )

    @Test
    fun theAnswerIsReadIntoWhatTheControlOffersAndEachLabelsDetails() {
        val read = labelSuggestionsFrom(
            mapOf(
                ADF.labels to listOf("reviewer"),
                ADF.labelsInUse to listOf("Reviewer", "adHoc"),
                ULU.details to listOf(details[0].toJsonMap()),
            ),
        )
        assertEquals(listOf("reviewer", "adHoc"), read.offered)
        val detail = read.details.single()
        assertEquals("Whoever signs off plans.", detail.note)
        assertEquals(listOf(UserLabelUseKind.approves, UserLabelUseKind.sees), detail.uses.map { it.kind })
        assertEquals(2, detail.carriers)
        // A backend that sends no details leaves the rows empty, not the page broken.
        assertEquals(emptyList(), labelSuggestionsFrom(mapOf(ADF.labels to listOf("reviewer"))).details)
    }

    @Test
    fun eachSuggestedLabelIsARowSayingWhatItDoes() {
        val rows = labelRows(details, listOf(" reviewer", "adHoc"))
        assertEquals(listOf("reviewer", "siteLead", "spare"), rows.map { it.label })
        assertEquals(listOf(true, false, false), rows.map { it.checked })
        assertEquals("Whoever signs off plans.", rows[0].note)
        assertEquals(
            listOf(
                "Approves “Approve the plan” in “Plan the logistics”",
                "Sees “Plan the logistics”, which is restricted to the holders of its labels",
            ),
            rows[0].uses,
        )
        assertEquals("2 users carry it.", rows[0].carriers)
        assertNull(rows[1].note)
        assertEquals("Nobody carries it yet.", rows[1].carriers)
        assertEquals("1 user carries it.", rows[2].carriers)
    }

    @Test
    fun checkingAndTypingKeepTheOtherHalfOfTheDraft() {
        val draft = listOf("adHoc", "reviewer")
        assertEquals(listOf("adHoc"), otherLabels(details, draft))
        assertEquals(listOf("adHoc", "reviewer", "spare"), withLabel(draft, "spare", on = true))
        assertEquals(listOf("adHoc"), withLabel(draft, "reviewer", on = false))
        // Typing replaces only the labels the client does not suggest.
        assertEquals(listOf("reviewer", "other"), withOtherLabels(details, draft, listOf("other")))
    }

    @Test
    fun aUserWithNoLabelsIsToldWhatTheyCannotDo() {
        assertEquals(
            listOf("Without “reviewer”, this user can't approve “Approve the plan” in “Plan the logistics” or see “Plan the logistics”."),
            missingLabelsPrompt(details, emptyList()),
        )
        // Once any label is chosen the rows have been seen; and a label that only changes what a task shows withholds nothing.
        assertEquals(emptyList(), missingLabelsPrompt(details, listOf("spare")))
        assertEquals(emptyList(), missingLabelsPrompt(listOf(details[1]), emptyList()))
    }

    @Test
    fun aListIsJoinedAsASentenceListsIt() {
        assertEquals("a", joinedWithOr(listOf("a")))
        assertEquals("a or b", joinedWithOr(listOf("a", "b")))
        assertEquals("a, b, or c", joinedWithOr(listOf("a", "b", "c")))
    }
}

/**
 * The Users page's create editor (issue #797): the caller's own address has a form of its own, the persona
 * suffix is always on offer, and each hint says what the backend will actually do.
 */
class SelfCreateTest {
    @Test
    fun theEditorSaysWhichRecordItIsOpenOn() {
        val titles = setOf(editorTitle(creating = true, forSelf = true), editorTitle(creating = true, forSelf = false), editorTitle(creating = false, forSelf = false))
        assertEquals(3, titles.size)
    }

    @Test
    fun theAddressHintSaysWhetherAnInvitationGoesOut() {
        // Your own address: registered at once, nothing mailed. Anyone else's: an invitation.
        assertTrue(createAddressHint(forSelf = true).contains("no invitation is sent"))
        assertTrue(createAddressHint(forSelf = false).contains("invitation is mailed"))
        // The hint names what stops it, rather than promise a mail regardless (issue #1504).
        assertTrue(createAddressHint(forSelf = false).contains("turn it off or disable the user"))
        // The old hint claimed every address was taken as confirmed, which stopped being true with invitations.
        assertTrue(!createAddressHint(forSelf = false).contains("skips email verification"))
    }

    @Test
    fun theSuffixHintExplainsTheFieldAndThenTheCollision() {
        val plain = personaSuffixHint(collided = false)
        val collided = personaSuffixHint(collided = true)
        // Under More options (issue #1478), it says when to use it.
        assertTrue(plain.startsWith("Use this when"))
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
    fun aUsersLinksAreOfferedToAClientsAdministratorOnly() {
        // The way to a user's forms and client (issue #1081): a public self-administrator reads only their own
        // forms and is refused the Clients page, so for them both links could only fail.
        assertTrue(!userLinksOffered(UserProfile(client = CL.public, roles = setOf(ROLE.user, ROLE.admin))))
        assertTrue(userLinksOffered(UserProfile(client = CL.public, roles = setOf(ROLE.user, ROLE.admin, ROLE.allClients))))
        assertTrue(userLinksOffered(UserProfile(client = "acme", roles = setOf(ROLE.user, ROLE.admin))))
    }

    @Test
    fun theSelfRecordIsItsOwnRouteValue() {
        // Distinct from a plain create, so Back and Forward reopen the right form, and not a user id.
        assertNotEquals(HP.newRecord, HP.selfRecord)
        assertTrue(HP.selfRecord.toLongOrNull() == null)
    }
}

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
