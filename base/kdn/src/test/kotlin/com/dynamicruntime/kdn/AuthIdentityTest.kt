package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.IDD
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UCF
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The identity split. Phase A (issue #747): an `AuthIdentities` row above every user, keyed by a random id and
 * reachable by the address; the user keyed `(identityId, client, persona, personId)` by the database; the
 * session carrying the identity -- behavior-preserving, so those cases are about the rows and the key. Phase
 * B (issue #748): the password, the verification, and the familiar device are the identity's, so they serve
 * every user the person holds; those cases are driven through the real login endpoints, since what they claim
 * is what a person sees.
 */
class AuthIdentityTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("identity", "authIdentityTest")
    val users = UserService.get(cxt)

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp[EP.results]?.toJsonMap()
        ?: throw AssertionError("Expected a success but got ${resp[EP.status]}: ${resp[EP.errorMessage]}")

    /** A browser of its own: its own cookie jar (session and device cookies) and its own source IP. */
    fun mkBrowser(ip: String): TestHttpClient = TestHttpClient(cxt.instanceConfig).also { it.setHeader("X-Forwarded-For", ip) }

    fun tokenOf(browser: TestHttpClient): String =
        results(browser.sendJsonGetRequest(AEP.createToken))[AFLD.formAuthToken] as String

    /** The code the server would email to [address] for [token], computed the way the server does. */
    fun codeFor(token: String, address: String): String = NodeService.get(cxt).computeVerifyCode(token, address)

    /** Logs [browser] in by verification code as [loginId] (a username or the [address] itself), as a person would. */
    fun loginByCode(browser: TestHttpClient, loginId: String, address: String): Map<String, Any?> {
        val token = tokenOf(browser)
        results(browser.sendJsonPostRequest(AEP.userSendVerify, mapOf(AFLD.loginId to loginId, AFLD.formAuthToken to token)))
        return results(browser.sendJsonPostRequest(
            AEP.loginByCode, mapOf(AFLD.loginId to loginId, AFLD.formAuthToken to token, AFLD.verifyCode to codeFor(token, address)),
        ))
    }

    /** Sets a password through the code-verified flow, logging [browser] in as [loginId] on the way. */
    fun setPassword(browser: TestHttpClient, loginId: String, address: String, password: String): Map<String, Any?> {
        val token = tokenOf(browser)
        results(browser.sendJsonPostRequest(
            AEP.userSendVerify, mapOf(AFLD.loginId to loginId, AFLD.formAuthToken to token, AFLD.addPassword to true),
        ))
        return results(browser.sendJsonPutRequest(
            AEP.setPassword,
            mapOf(AFLD.loginId to loginId, AFLD.password to password, AFLD.formAuthToken to token, AFLD.verifyCode to codeFor(token, address)),
        ))
    }

    /** Logs in by password, returning the raw envelope so a caller can assert either success or a status. */
    fun loginByPassword(browser: TestHttpClient, loginId: String, password: String): Map<String, Any?> =
        browser.sendJsonPostRequest(AEP.loginByPassword, mapOf(AFLD.loginId to loginId, AFLD.password to password))

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
        // Unnamed, the address logs in as the identity's default user: with none chosen, the one most recently
        // acted as (every login stamps it, issue #748) -- batchA, just now. Naming the client picks the ordinary
        // user, and from then on that is the most recently used one.
        TestUser.create(cxt, address).userId shouldBe batchA.userId
        TestUser.create(cxt, address, userClient = CL.public).userId shouldBe first.userId
        TestUser.create(cxt, address).userId shouldBe first.userId
        users.usersOfIdentity(cxt, users.queryIdentityByAddress(cxt, address)!!.identityId) shouldHaveSize 2
    }

    "a permanent delete of an identity's last user retires its address, freeing it for re-registration" {
        val admin = TestUser.createFullAdmin(cxt, "ident-deleter@example.com")
        val victim = TestUser.create(cxt, "ident-gone@example.com")
        val identity = users.queryIdentityByAddress(cxt, "ident-gone@example.com").shouldNotBeNull()
        admin.deleteData(ADEP.userDelete, mapOf(ADF.userId to victim.userId, ADF.permanent to true))
        users.queryIdentityByAddress(cxt, "ident-gone@example.com").shouldBeNull()
        val retired = users.queryIdentityById(cxt, identity.identityId).shouldNotBeNull()
        retired.primaryId shouldBe AuthUserRow.deletedPrimaryId(victim.userId)
        // Retired with the address: the credentials and contacts (issue #748), so nothing of the person is left.
        retired.hasPassword shouldBe false
        retired.hasContact shouldBe false
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
        row.hasPassword shouldBe false
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

    // --- phase B (issue #748): what proves who you are belongs to the identity -------------------------

    "a code login proves an identity an administrator created" {
        val admin = TestUser.createFullAdmin(cxt, "ident-prover-admin@example.com")
        val address = "ident-prove@other.com"
        admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address))
        // Unverified: the administrator asserted the address, nobody has read a code from its inbox yet.
        users.queryIdentityByAddress(cxt, address).shouldNotBeNull().verifiedAt.shouldBeNull()
        // ...and the user is not yet the person's: unregistered (issue #749).
        users.queryByPrimaryId(cxt, address).shouldNotBeNull().isRegistered shouldBe false
        val landedOn = loginByCode(mkBrowser("10.48.0.1"), address, address)[UPF.userId]
        val proven = users.queryIdentityByAddress(cxt, address).shouldNotBeNull()
        proven.verifiedAt.shouldNotBeNull()
        proven.identityData[IDD.validatedContacts] shouldBe listOf(address)
        // The code was used for this user, so it is registered by it.
        users.queryByUserId(cxt, landedOn as Long).shouldNotBeNull().isRegistered shouldBe true
    }

    "an unregistered sibling is neither the default nor switchable until a code lands on it" {
        val address = "ident-unclaimed@example.com"
        val mine = TestUser.create(cxt, address)
        // Provisioned for the person by somebody else (what an administrator's create will do in phase D):
        // enabled, but nobody has claimed it.
        // A lowercase personId, because the placeholder username below doubles as the login id here, and a login
        // id carrying an `@` is normalized as an address -- lowercased -- before it is looked up.
        val unclaimed = users.provisionUser(cxt, address, CL.public, listOf(ROLE.user), personId = "u")
        val row = users.queryByUserId(cxt, unclaimed).shouldNotBeNull()
        row.isRegistered shouldBe false
        // Not offered, not switchable, and not where the address lands -- the registered user is.
        mine.getData(AEP.selfUsers)[AFLD.users].toJsonListOfMaps().map { it[UCF.userId] } shouldBe listOf(mine.userId)
        mine.expectError(EXC.badInput, AEP.switchUser, mapOf(AFLD.userId to unclaimed))
        users.queryByPrimaryId(cxt, address).shouldNotBeNull().userId shouldBe mine.userId
        // A code login naming that user (its placeholder username is a login id) is the person claiming it.
        loginByCode(mkBrowser("10.48.0.4"), row.username, address)[UPF.userId] shouldBe unclaimed
        users.queryByUserId(cxt, unclaimed).shouldNotBeNull().isRegistered shouldBe true
        mine.getData(AEP.selfUsers)[AFLD.users].toJsonListOfMaps() shouldHaveSize 2
    }

    "the password and the familiar device are the person's, and serve every user they hold" {
        val address = "ident-pw@example.com"
        val password = "sekret-pw-123"
        val first = TestUser.create(cxt, address)
        val second = TestUser.create(cxt, address, personId = "2")
        // The placeholder usernames are login ids too, and each names its own user of the identity.
        val firstName = users.queryByUserId(cxt, first.userId).shouldNotBeNull().username
        val secondName = users.queryByUserId(cxt, second.userId).shouldNotBeNull().username
        val browser = mkBrowser("10.48.0.2")

        // A code login by address lands on the identity's default user -- `second`, the most recently used --
        // and makes this browser familiar to the *identity*. Setting a password through it sets the person's.
        loginByCode(browser, address, address)[UPF.userId] shouldBe second.userId
        setPassword(browser, address, address, password)[UPF.hasPassword] shouldBe true
        users.queryByUserId(cxt, first.userId).shouldNotBeNull().hasPassword shouldBe true // derived through the identity
        users.queryByUserId(cxt, second.userId).shouldNotBeNull().hasPassword shouldBe true

        // From the familiar browser the one password logs the person in as whichever user the login id names,
        // though neither user ever had a code login of its own from here.
        browser.sendGetRequest(AEP.logout)
        results(loginByPassword(browser, firstName, password))[UPF.userId] shouldBe first.userId
        browser.sendGetRequest(AEP.logout)
        results(loginByPassword(browser, secondName, password))[UPF.userId] shouldBe second.userId
        // A browser the identity has never proven itself from is refused, for either user.
        loginByPassword(mkBrowser("10.48.0.3"), firstName, password)[EP.status] shouldBe EXC.authNeeded

        // Opting out through one user drops the person's password: it is gone for the other user too.
        results(browser.sendJsonPostRequest(AEP.profileClearPassword, emptyMap()))[UPF.hasPassword] shouldBe false
        users.queryByUserId(cxt, first.userId).shouldNotBeNull().hasPassword shouldBe false
        browser.sendGetRequest(AEP.logout)
        loginByPassword(browser, firstName, password)[EP.status] shouldBe EXC.authNeeded
    }

    "a user detached from the session's identity is cut off at the next gated request" {
        val admin = TestUser.createFullAdmin(cxt, "ident-detach@example.com")
        admin.getItems(ADEP.users, mapOf(ADF.search to "ident-detach")).shouldNotBeEmpty()
        // No path moves a user between identities today; the check is there for whatever one may come, so the
        // detachment is staged by hand: the row re-pointed at an identity that holds no user in this client.
        val elsewhere = users.getOrCreateIdentity(cxt, "ident-detach-elsewhere@example.com")
        val row = users.queryByUserId(cxt, admin.userId).shouldNotBeNull()
        val moved = AuthUserRow(row.userId, row.client, elsewhere.identityId, elsewhere.primaryId).also {
            it.username = row.username
            it.roles = row.roles
            it.enabled = true
            it.data = row.data
        }
        users.updateUser(cxt, moved)
        // The session cookie still names the old identity; the gate re-reads the row, finds it is no longer
        // that person's, and the roles the cookie carried count for nothing -- refused as a revoked
        // administrator is.
        admin.expectError(EXC.notAuthorized, ADEP.users, args = mapOf(ADF.search to "ident-detach"))
    }

    "a user whose identity is missing fails loudly rather than reading as nobody" {
        // A user row must always point at an identity; extracting one that does not is a broken invariant.
        shouldThrow<Exception> { AuthUserRow.extract(mapOf("userId" to 1L, "identityId" to "nope", "client" to "acme")) { null } }
            .message.shouldNotBeNull() shouldNotBe ""
    }
})
