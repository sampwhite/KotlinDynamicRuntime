package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.refreshActingRoles
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for `refreshActingRoles` (issue #282) -- the re-read that gives a request the caller's *live* roles
 * before a gated section, replacing the identity-only profile the session cookie carries.
 *
 * It had **no test at all**, which is how it twice shipped rebuilding [UserProfile] field by field and
 * silently dropping one: `org` when #225 added it, then the entity fields when #284 did. Each time the result
 * compiled, ran, and served a profile quietly missing a field -- the app bar showing a username where a
 * business name belonged was the only symptom.
 *
 * So the assertion here is deliberately on the **whole profile**, not a list of fields. Naming the fields would
 * reproduce the bug in the test: it would pass while ignoring whatever field nobody remembered to add.
 *
 * The email addresses are prefixed to keep them unique across the whole suite: every test in a run shares one
 * in-memory database, so a plain `chief@example.com` here quietly stole the one `HomeMenuTest` builds its
 * administrator from, and that test failed instead of this one.
 */
class ActingProfileTest : StringSpec({

    fun users(cxt: KdrCxt): UserService =
        (UserService.get(cxt) ?: error("UserService is required by this test.")).also { it.checkInit(cxt) }

    "refreshing the acting roles keeps every other field of the live profile" {
        val cxt = Startup.mkTestBootCxt("actingProfile", "actingProfileTest")
        val service = users(cxt)

        // A user with the optional identity fields actually populated -- a profile of all defaults could not
        // tell a dropped field from an absent one.
        val userId = service.insertUser(
            cxt,
            AuthUserRow.mkInitialUser("acting-chief@example.com", CL.public, listOf(ROLE.user), org = "engineering"),
        )
        val row = service.queryAdministrableUser(cxt, userId, ReadScope.unrestricted)
            ?: error("Seeded user should be readable.")
        row.isEntity = true
        row.name = "Chief Industries"
        service.updateUser(cxt, row)

        // What the session cookie yields: identity and roles, nothing else (no org, name, or display name).
        cxt.bindToUserProfile(
            UserProfile(
                authId = userId.toString(), userId = userId, client = CL.public, roles = setOf(ROLE.user),
            ),
        )
        cxt.userProfile.org shouldBe null
        cxt.userProfile.name shouldBe null

        refreshActingRoles(cxt)

        // The whole live profile, compared as one object: this is the assertion a newly added field cannot
        // slip past. `toUserProfile` is the definition of "the live profile", so the refreshed one must equal
        // it exactly -- the roles included, since nothing changed them between the write and the re-read.
        val live = service.queryAdministrableUser(cxt, userId, ReadScope.unrestricted)!!.toUserProfile()
        cxt.userProfile shouldBe live

        // And spelled out once, so a failure above reads as something concrete rather than a diff of objects.
        cxt.userProfile.org shouldBe "engineering"
        cxt.userProfile.isEntity shouldBe true
        cxt.userProfile.name shouldBe "Chief Industries"
        cxt.userProfile.publicName shouldBe "acting-chief@example.com"
        cxt.userProfile.hasPassword shouldBe false
    }

    /**
     * The roles are the one thing it *is* meant to change: a revoked role has to bite on this request, not at
     * cookie expiry. A disabled account drops to no roles at all.
     */
    "refreshing picks up a role change, and a disabled account loses its roles" {
        val cxt = Startup.mkTestBootCxt("actingRoles", "actingRolesTest")
        val service = users(cxt)
        val userId = service.insertUser(
            cxt, AuthUserRow.mkInitialUser("acting-promoted@example.com", CL.public, listOf(ROLE.user, ROLE.admin)),
        )

        // A cookie issued before the grant carries only the old role.
        cxt.bindToUserProfile(
            UserProfile(
                authId = userId.toString(), userId = userId, client = CL.public, roles = setOf(ROLE.user),
            ),
        )
        refreshActingRoles(cxt)
        cxt.userProfile.roles shouldBe setOf(ROLE.user, ROLE.admin)

        // Disabling the account empties them, whatever the row still lists.
        val row = service.queryAdministrableUser(cxt, userId, ReadScope.unrestricted)!!
        row.enabled = false
        service.updateUser(cxt, row)
        refreshActingRoles(cxt)
        cxt.userProfile.roles shouldBe emptySet()
    }
})
