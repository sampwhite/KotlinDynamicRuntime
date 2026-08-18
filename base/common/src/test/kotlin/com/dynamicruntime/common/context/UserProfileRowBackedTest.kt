package com.dynamicruntime.common.context

import com.dynamicruntime.common.http.request.ROLE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for the not-row-backed marker (issue #386): which profiles carry it, and what it is for.
 *
 * It exists so `refreshActingRoles` can skip **deliberately** rather than by accident of a service being
 * absent -- the difference that lets an env-authed caller work on a node with no user store, and that stops
 * the skip from being silent once `UserService.get` stops returning null.
 */
class UserProfileRowBackedTest : StringSpec({

    "the three manufactured profiles declare that nothing backs them" {
        UserProfile.systemUser().isRowBacked shouldBe false
        UserProfile.anonymous().isRowBacked shouldBe false
        UserProfile.envAuthed("sam@gyassa.com").isRowBacked shouldBe false
    }

    /**
     * The default is true because that is what a profile read from `AuthUsers` is, and because of which error
     * each direction produces: wrongly row-backed attempts a refresh, which narrows or fails loudly; wrongly
     * not-row-backed skips it and **retains a revoked role**, which widens and says nothing.
     */
    "a profile is row-backed unless it says otherwise" {
        UserProfile(authId = "7", userId = 7L).isRowBacked shouldBe true
    }

    /**
     * An env-authed caller is the first profile that is genuinely logged in and still has no row -- which is
     * exactly why `isLoggedIn` could not carry this on its own.
     */
    "an env-authed caller is logged in, and still has no row" {
        val p = UserProfile.envAuthed("sam@gyassa.com")
        p.isLoggedIn shouldBe true
        p.isRowBacked shouldBe false
        p.authId shouldBe "sam@gyassa.com"
    }

    // Operator, because the admin surface is user administration and an edge has no users to administer.
    "an env-authed caller acts for the house, at operator level" {
        val p = UserProfile.envAuthed("sam@gyassa.com")
        p.client shouldBe CL.house
        p.roles shouldBe setOf(ROLE.operator)
        // The id is left at its default rather than invented; nothing may query by it.
        p.userId shouldBe CL.systemUserId.toLong()
    }
})
