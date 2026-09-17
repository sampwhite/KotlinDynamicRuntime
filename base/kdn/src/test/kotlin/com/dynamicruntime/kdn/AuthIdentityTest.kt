package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The identity split, phase A (issue #747): an `AuthIdentities` row above every user, keyed by a random id and
 * reachable by the address; the user keyed `(identityId, client, persona, personId)` by the database; the
 * session carrying the identity. Behavior-preserving -- every identity has one user until phase C -- so the
 * cases here are about the rows and the key, not about anything a person would see.
 */
class AuthIdentityTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("identity", "authIdentityTest")
    val users = UserService.get(cxt)

    "registering through the real flow creates a verified identity and one user pointing at it" {
        val user = TestUser.register(cxt, "ident-reg@example.com", "identreg")
        val identity = users.queryIdentityByAddress(cxt, "ident-reg@example.com").shouldNotBeNull()
        identity.verifiedAt.shouldNotBeNull()
        val row = users.queryByUserId(cxt, user.userId).shouldNotBeNull()
        row.identityId shouldBe identity.identityId
        row.primaryId shouldBe "ident-reg@example.com" // derived through the identity, not stored on the row
        row.persona shouldBe PERSONA.user
        row.personId shouldBe ""
        users.usersOfIdentity(cxt, identity.identityId).map { it.userId } shouldBe listOf(user.userId)
        // The address resolves to that user: the identity's default (its only one).
        users.queryByPrimaryId(cxt, "ident-reg@example.com").shouldNotBeNull().userId shouldBe user.userId
    }

    "an admin-created user gets an identity that is not yet verified, and can still log in by code" {
        val admin = TestUser.createFullAdmin(cxt, "ident-admin@example.com")
        val created = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to "ident-made@other.com"))
        created[ADF.primaryId] shouldBe "ident-made@other.com"
        val identity = users.queryIdentityByAddress(cxt, "ident-made@other.com").shouldNotBeNull()
        identity.verifiedAt.shouldBeNull()
        users.queryByPrimaryId(cxt, "ident-made@other.com").shouldNotBeNull().userId shouldBe created[ADF.userId]
    }

    "the session carries the identity and persona, on login and on the fast path" {
        val user = TestUser.create(cxt, "ident-session@example.com")
        val identity = users.queryIdentityByAddress(cxt, "ident-session@example.com").shouldNotBeNull()
        // The login response, and then self-info -- which is restored from the cookie, not the row.
        user.userInfo[UPF.identityId] shouldBe identity.identityId
        user.userInfo[UPF.persona] shouldBe PERSONA.user
        val self = user.getData(AEP.selfInfo)
        self[UPF.identityId] shouldBe identity.identityId
        self[UPF.persona] shouldBe PERSONA.user
    }

    "the database holds the key: a second user of one identity needs a different client, persona or personId" {
        val address = "ident-key@example.com"
        TestUser.create(cxt, address)
        val identity = users.queryIdentityByAddress(cxt, address).shouldNotBeNull()
        // The same (identity, client, persona, personId) again, while that user is enabled: refused as a
        // duplicate -- a plain input error, with the unique index as the backstop behind it.
        shouldThrow<KdrException> { users.provisionUser(cxt, address, CL.public, listOf(ROLE.user)) }.code shouldBe EXC.badInput
        // A different personId is a different user of the same identity -- the UAT batch case.
        val second = users.provisionUser(cxt, address, CL.public, listOf(ROLE.user), personId = "2")
        users.usersOfIdentity(cxt, identity.identityId) shouldHaveSize 2
        users.queryByUserId(cxt, second).shouldNotBeNull().personId shouldBe "2"
        // Both users read the one address off the identity.
        users.usersOfIdentity(cxt, identity.identityId).map { it.primaryId }.toSet() shouldBe setOf(address)
    }

    "the fixture names which of an address's users to become, creating it when there is none" {
        val address = "ident-fixture@example.com"
        val first = TestUser.create(cxt, address)
        val batchA = TestUser.create(cxt, address, personId = "A")
        batchA.userId shouldNotBe first.userId
        // Asking again finds the same user rather than making a third.
        TestUser.create(cxt, address, personId = "A").userId shouldBe batchA.userId
        // Unnamed, the address logs in as the identity's default user -- the first one.
        TestUser.create(cxt, address).userId shouldBe first.userId
        users.usersOfIdentity(cxt, users.queryIdentityByAddress(cxt, address)!!.identityId) shouldHaveSize 2
    }

    "a permanent delete of an identity's last user retires its address, freeing it for re-registration" {
        val admin = TestUser.createFullAdmin(cxt, "ident-deleter@example.com")
        val victim = TestUser.create(cxt, "ident-gone@example.com")
        val identity = users.queryIdentityByAddress(cxt, "ident-gone@example.com").shouldNotBeNull()
        admin.deleteData(ADEP.userDelete, mapOf(ADF.userId to victim.userId, ADF.permanent to true))
        users.queryIdentityByAddress(cxt, "ident-gone@example.com").shouldBeNull()
        users.queryIdentityById(cxt, identity.identityId).shouldNotBeNull().primaryId shouldBe AuthUserRow.deletedPrimaryId(victim.userId)
        // The tombstone reads the retired address too, as it always has.
        users.queryByUserId(cxt, victim.userId).shouldNotBeNull().primaryId shouldBe AuthUserRow.deletedPrimaryId(victim.userId)
        // The address is free again: a fresh registration makes a fresh identity.
        val again = TestUser.register(cxt, "ident-gone@example.com", "identagain")
        users.queryByUserId(cxt, again.userId).shouldNotBeNull().identityId shouldNotBe identity.identityId
    }

    "provisioning the key of a recoverably deleted user recovers it, unregistered, with its content" {
        val admin = TestUser.createFullAdmin(cxt, "ident-recover-admin@example.com")
        val address = "ident-recover@example.com"
        val original = TestUser.create(cxt, address, level = ROLE.admin, name = "Ada Lovelace")
        val before = users.queryByUserId(cxt, original.userId).shouldNotBeNull()
        before.roles shouldContain ROLE.admin
        // A recoverable delete: disabled, everything kept.
        admin.deleteData(ADEP.userDelete, mapOf(ADF.userId to original.userId))
        users.queryByUserId(cxt, original.userId).shouldNotBeNull().enabled shouldBe false
        // Provisioning the same key again is not a create: the same user comes back, enabled and unregistered
        // (placeholder username, no password, the provisioned roles), its name kept.
        val recovered = users.provisionUser(cxt, address, CL.public, listOf(ROLE.user), createdAt = cxt.now())
        recovered shouldBe original.userId
        val row = users.queryByUserId(cxt, recovered).shouldNotBeNull()
        row.enabled shouldBe true
        row.needsRealUsername shouldBe true
        row.encodedPassword.shouldBeNull()
        row.roles shouldBe listOf(ROLE.user)
        row.name shouldBe "Ada Lovelace"
        users.usersOfIdentity(cxt, row.identityId) shouldHaveSize 1
        // The fixture reaches the same rule: becoming the address finds the recovered user.
        TestUser.create(cxt, address).userId shouldBe original.userId
    }

    "a permanent delete frees the key, so the person can hold that user again" {
        val admin = TestUser.createFullAdmin(cxt, "ident-free-admin@example.com")
        val address = "ident-free@example.com"
        val a = TestUser.create(cxt, address)
        val b = TestUser.create(cxt, address, personId = "B") // keeps the identity alive past a's deletion
        admin.deleteData(ADEP.userDelete, mapOf(ADF.userId to a.userId, ADF.permanent to true))
        val identityId = users.queryByUserId(cxt, b.userId).shouldNotBeNull().identityId
        users.queryIdentityByAddress(cxt, address).shouldNotBeNull().identityId shouldBe identityId // not retired: b is live
        users.queryByUserId(cxt, a.userId).shouldNotBeNull().personId shouldBe AuthUserRow.deletedPersonId(a.userId)
        // The ordinary key is free again: a new user, not the tombstone.
        val again = users.provisionUser(cxt, address, CL.public, listOf(ROLE.user))
        again shouldNotBe a.userId
        users.usersOfIdentity(cxt, identityId).map { it.userId }.toSet() shouldBe setOf(a.userId, b.userId, again)
    }

    "a user whose identity is missing fails loudly rather than reading as nobody" {
        // A user row must always point at an identity; extracting one that does not is a broken invariant.
        shouldThrow<Exception> { AuthUserRow.extract(mapOf("userId" to 1L, "identityId" to "nope", "client" to "acme")) { null } }
            .message.shouldNotBeNull() shouldNotBe ""
    }
})
