package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * `public` as a placeholder client (issue #752): the default-user fallback passes over it, and a person may
 * remove their own `public` user once they hold a registered user in a real client.
 */
class PublicPlaceholderTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("publicPlaceholder", "publicPlaceholderTest")
    val users = UserService.get(cxt)

    // --- the default-user fallback -------------------------------------------------------------------------

    "with no choice made, an address logs in as its real-client user rather than the earlier public one" {
        val address = "placeholder-fallback@example.com"
        // Provisioned directly, so no login stamps a last-used user: only the fallback decides.
        val publicId = users.provisionUser(cxt, address, CL.public, listOf(ROLE.user), registered = true)
        val hubId = users.provisionUser(cxt, address, CL.hub, listOf(ROLE.user), registered = true)
        (publicId < hubId) shouldBe true
        val identity = users.queryIdentityByAddress(cxt, address).shouldNotBeNull()
        identity.defaultUserId shouldBe null
        identity.lastUsedUserId shouldBe null
        users.defaultUserOf(cxt, identity).shouldNotBeNull().userId shouldBe hubId
    }

    "a public user the person chose is still honored" {
        val address = "placeholder-chosen@example.com"
        val publicId = users.provisionUser(cxt, address, CL.public, listOf(ROLE.user), registered = true)
        users.provisionUser(cxt, address, CL.hub, listOf(ROLE.user), registered = true)
        val identity = users.queryIdentityByAddress(cxt, address).shouldNotBeNull()
        // Acted as last: the person's own choice outranks the placeholder rule.
        identity.lastUsedUserId = publicId
        users.defaultUserOf(cxt, identity).shouldNotBeNull().userId shouldBe publicId
        // And so does an explicit default.
        identity.lastUsedUserId = null
        identity.defaultUserId = publicId
        users.defaultUserOf(cxt, identity).shouldNotBeNull().userId shouldBe publicId
    }

    "a person with only public users falls back to the earliest of them, as before" {
        val address = "placeholder-only@example.com"
        val first = users.provisionUser(cxt, address, CL.public, listOf(ROLE.user), registered = true)
        users.provisionUser(cxt, address, CL.public, listOf(ROLE.user), registered = true, personaSuffix = "2")
        val identity = users.queryIdentityByAddress(cxt, address).shouldNotBeNull()
        users.defaultUserOf(cxt, identity).shouldNotBeNull().userId shouldBe first
    }

    // --- removing your own public user ---------------------------------------------------------------------

    // A public user exists only because its owner registered, so the owner may remove everything and register
    // again: the last user goes, the identity is retired with it, and the session ends.
    "a person's only user, in public, can be removed, and the person with it" {
        val address = "placeholder-alone@example.com"
        val me = TestUser.create(cxt, address)
        val after = me.postData(AEP.removePublicUser, mapOf(AFLD.userId to me.userId))
        after[UPF.authId] shouldBe UserProfile.anonymousAuthId
        users.queryByUserId(cxt, me.userId).shouldNotBeNull().isDeleted shouldBe true
        // The session is gone, and the address is free: nothing about the person is left to log in as.
        me.getData(AEP.selfInfo)[UPF.userId] shouldNotBe me.userId
        users.queryIdentityByAddress(cxt, address) shouldBe null
        // And the address registers again, as a new person.
        val again = TestUser.create(cxt, address)
        (again.userId != me.userId) shouldBe true
    }

    "acting as the public user, removing it moves the session to the real-client user" {
        val address = "placeholder-remove-self@example.com"
        val hubUser = TestUser.create(cxt, address, userClient = CL.hub)
        // Named explicitly: given only the address, the fixture signs in as the address's default user, which
        // is now the hub one.
        val publicUser = TestUser.create(cxt, address, userClient = CL.public)
        publicUser.getData(AEP.selfInfo)[UPF.client] shouldBe CL.public

        val after = publicUser.postData(AEP.removePublicUser, mapOf(AFLD.userId to publicUser.userId))
        after[UPF.userId] shouldBe hubUser.userId
        // The browser's session is now the hub user's, and the public row is a tombstone.
        publicUser.getData(AEP.selfInfo)[UPF.userId] shouldBe hubUser.userId
        users.queryByUserId(cxt, publicUser.userId).shouldNotBeNull().isDeleted shouldBe true
        // The identity survives: the address still logs in, as the hub user.
        users.queryIdentityByAddress(cxt, address).shouldNotBeNull()
        TestUser.create(cxt, address, userClient = CL.hub).userId shouldBe hubUser.userId
    }

    "acting as the real-client user, the public one is removed and the session stays put" {
        val address = "placeholder-remove-other@example.com"
        val publicUser = TestUser.create(cxt, address, userClient = CL.public)
        val hubUser = TestUser.create(cxt, address, userClient = CL.hub)
        val after = hubUser.postData(AEP.removePublicUser, mapOf(AFLD.userId to publicUser.userId))
        after[UPF.userId] shouldBe hubUser.userId
        users.queryByUserId(cxt, publicUser.userId).shouldNotBeNull().isDeleted shouldBe true
    }

    "only your own public users can be removed this way" {
        val address = "placeholder-refuse@example.com"
        val publicUser = TestUser.create(cxt, address, userClient = CL.public)
        val hubUser = TestUser.create(cxt, address, userClient = CL.hub)
        val stranger = TestUser.create(cxt, "placeholder-stranger@example.com")
        // Another person's public user, and your own user in a real client: one refusal for both.
        hubUser.expectError(EXC.badInput, AEP.removePublicUser, mapOf(AFLD.userId to stranger.userId))
        publicUser.expectError(EXC.badInput, AEP.removePublicUser, mapOf(AFLD.userId to hubUser.userId))
        users.queryByUserId(cxt, stranger.userId).shouldNotBeNull().isDeleted shouldBe false
        users.queryByUserId(cxt, hubUser.userId).shouldNotBeNull().isDeleted shouldBe false
    }
})
