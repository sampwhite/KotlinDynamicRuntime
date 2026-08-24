package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.userSearchFieldSpecs
import kotlin.test.Test
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
        org = "eng",
        isEntity = false,
        name = "Ada Lovelace",
        enabled = true,
        hasPassword = true,
        deleted = false,
        updatedAt = "2026-08-24T18:00:00.000Z",
    )

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
