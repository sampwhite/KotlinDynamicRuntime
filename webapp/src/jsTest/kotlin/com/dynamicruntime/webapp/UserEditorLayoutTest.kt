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
    }

    @Test
    fun moreOptionsNamesWhatIsSetAndNothingAtItsDefault() {
        assertEquals(emptyList(), userMoreOptionsInForce(false, false, emptyList(), enabled = true, creating = false))
        assertEquals(
            listOf("Editor", "All clients", "labels: reviewer, siteLead", "disabled"),
            userMoreOptionsInForce(true, true, listOf(" reviewer", "siteLead", "reviewer"), enabled = false, creating = false),
        )
        // On a create: the suffix, a collision asking for one, and starting disabled.
        assertEquals(listOf("persona suffix B"), userMoreOptionsInForce(false, false, emptyList(), true, true, personaSuffix = " B "))
        assertEquals(listOf("a persona suffix is needed"), userMoreOptionsInForce(false, false, emptyList(), true, true, suffixNeeded = true))
        assertEquals(listOf("starts disabled"), userMoreOptionsInForce(false, false, emptyList(), enabled = false, creating = true))
        // What it says is what opens it.
        assertTrue(moreOptionsOpens(userMoreOptionsInForce(true, false, emptyList(), true, false)))
    }

    @Test
    fun aDraftChangesOnlyWhatTheBackendWouldStoreDifferently() {
        val target = user(org = "east", name = "Ada", labels = listOf("reviewer"))
        fun changes(
            roles: List<String> = listOf(ROLE.user), org: String = "east", name: String = "Ada", isEntity: Boolean = false,
            enabled: Boolean = true, labels: List<String> = listOf("reviewer"), opened: Boolean = false,
        ) = userDraftChanges(target, roles, org, name, isEntity, enabled, labels, opened)
        // Re-spacing, repeating, or reordering is no change.
        assertEquals(emptyList(), changes(org = " east ", name = "Ada ", labels = listOf(" reviewer", "reviewer")))
        // Each change is one call, in the order a save sends them.
        assertEquals(
            listOf(UserChange.roles, UserChange.org, UserChange.accountName, UserChange.enabled, UserChange.labels),
            changes(listOf(ROLE.user, ROLE.admin), "", "Ada L", enabled = false, labels = emptyList()),
        )
        assertEquals(listOf(UserChange.accountName), changes(isEntity = true))
        // A sandbox user somebody opened has no roles or organization of its own to set.
        assertEquals(emptyList(), changes(listOf(ROLE.user, ROLE.admin), "west", opened = true))
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
    fun aCreateIsDirtyOnceAnythingWasTyped() {
        assertFalse(createDraftDirty("", forSelf = false, name = "", personaSuffix = "", labels = emptyList()))
        assertTrue(createDraftDirty("a@b.test", forSelf = false, name = "", personaSuffix = "", labels = emptyList()))
        // The caller's own address is filled in for them, so it alone is nothing typed.
        assertFalse(createDraftDirty("me@b.test", forSelf = true, name = "", personaSuffix = "", labels = emptyList()))
        assertTrue(createDraftDirty("", forSelf = false, name = "", personaSuffix = "", labels = listOf("reviewer")))
    }
}
