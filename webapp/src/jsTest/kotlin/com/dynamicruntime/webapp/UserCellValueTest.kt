package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.user.userSearchFieldSpecs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    )

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
        for (field in listOf(USF.registered.at, USF.activated.at, "type", "roles")) assertEquals(null, pinnedSide(field))
    }

    /** antd's third click on a header cancels the sort; that must land on the default order, not be dropped. */
    @Test
    fun cancellingASortReturnsToTheDefaultOrder() {
        assertEquals("activatedAt" to false, sortAfterHeaderClick("activatedAt", "ascend"))
        assertEquals("activatedAt" to true, sortAfterHeaderClick("activatedAt", "descend"))
        assertEquals(defaultUserSortKey to defaultUserSortDescending, sortAfterHeaderClick("activatedAt", null))
        assertEquals(null, sortAfterHeaderClick(null, null))
    }

    @Test
    fun thePersonaColumnShowsTheLabelAndThePersonaSuffix() {
        assertEquals("Admin B", personaCell("admin", "B"))
        assertEquals("Member", personaCell("member", ""))
    }

    @Test
    fun everySpecFieldHasADisplayValue() {
        val user = populatedUser()
        for (spec in userSearchFieldSpecs) {
            val value = cellValue(spec.name, user)
            // A fully-populated user should produce a real, non-placeholder value for every column.
            assertTrue(
                value.isNotBlank() && value != unmappedCell,
                "Spec field '${spec.name}' has no cellValue branch (got '$value'); it would render a blank column.",
            )
        }
    }
}
