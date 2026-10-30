package com.dynamicruntime.webapp

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.PERSONA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        // Unclaimed: an invitation is out for a live user; a disabled one waits for its enable.
        assertEquals(listOf("Enabled", "Invitation pending"), userStatusChips(user(registered = false)))
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
        assertEquals(emptyList(), userMoreOptionsInForce(false, false, emptyList(), enabled = true, creating = false))
        assertEquals(
            listOf("Editor", "All clients", "labels: reviewer, siteLead", "disabled"),
            userMoreOptionsInForce(true, true, listOf(" reviewer", "siteLead", "reviewer"), enabled = false, creating = false),
        )
        // On a create: the suffix, a collision asking for one, and starting disabled.
        assertEquals(
            listOf("persona suffix B"), userMoreOptionsInForce(false, false, emptyList(), true, true, personaSuffix = " B "),
        )
        assertEquals(
            listOf("a persona suffix is needed"),
            userMoreOptionsInForce(false, false, emptyList(), true, true, suffixNeeded = true),
        )
        assertEquals(listOf("starts disabled"), userMoreOptionsInForce(false, false, emptyList(), enabled = false, creating = true))
        // What it says is what opens it.
        assertTrue(moreOptionsOpens(userMoreOptionsInForce(true, false, emptyList(), true, false)))
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
            seeded.copy(enabled = false), seeded.copy(labels = listOf("reviewer")),
        )) {
            assertTrue(createDraftDirty(changed, seeded), "$changed")
        }
        // The caller's own address, filled in for them, is what the form opened with.
        val forSelf = seeded.copy(email = "me@b.test")
        assertFalse(createDraftDirty(forSelf, forSelf))
    }

    @Test
    fun theHintsUnderMoreOptionsSayWhenToUseThem() {
        for (hint in listOf(editorHint, enabledHint(creating = true), enabledHint(creating = false))) {
            assertTrue(hint.startsWith("Use this when"), hint)
        }
    }
}
