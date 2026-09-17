package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
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
        // The same (identity, client, persona, personId) again: refused by the unique index, not by a check.
        shouldThrow<Exception> { users.provisionUser(cxt, address, CL.public, listOf(ROLE.user)) }
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

    "a user whose identity is missing fails loudly rather than reading as nobody" {
        // A user row must always point at an identity; extracting one that does not is a broken invariant.
        shouldThrow<Exception> { AuthUserRow.extract(mapOf("userId" to 1L, "identityId" to "nope", "client" to "acme")) { null } }
            .message.shouldNotBeNull() shouldNotBe ""
        // EXC is referenced so a future refusal code has a home; the invariant itself is what is under test.
        EXC.badInput shouldNotBe null
    }
})
