package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.computeVerifyCode
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The admin user-management surface: who becomes an administrator, and what one can then do.
 *
 * Setup uses [TestUser.create] (issue #125), which reaches an authenticated session in one call instead of
 * walking the verification-code flow -- so these tests state what they are about rather than how to log in. The
 * one exception is deliberate: the auto-admin-at-registration test keeps the real self-service flow, because
 * the thing under test *is* what `createInitialUser` grants, and `becomeUser` does not go through it.
 *
 * Everything runs through the in-process [TestHttpClient], so the section's role gate -- which lives in the
 * dispatcher, not in the handlers -- is exercised for real. A rejected call comes back as the standard error
 * envelope (issue #103), not a thrown exception, so refusals are asserted on [EP.status].
 *
 * Emails are distinct per test: the in-memory database is keyed by name, not by instance, so rows outlive a
 * single boot within the JVM.
 */
class AdminUserTest : StringSpec({

    val adminDomain = "acme.com"

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()

    @Suppress("UNCHECKED_CAST")
    fun items(resp: Map<String, Any?>): List<Map<String, Any?>> =
        (resp[EP.items] as? List<Map<String, Any?>>) ?: emptyList()

    fun roles(user: Map<String, Any?>): List<String> = (user[ADF.roles] as List<*>).map { it.toString() }

    // --- the auto-admin rule (pure logic; no boot needed) --------------------

    "the admin email domain grants only plain addresses at that domain or a subdomain" {
        AdminRules.isAutoAdminAddress("boss@acme.com", adminDomain) shouldBe true
        AdminRules.isAutoAdminAddress("BOSS@ACME.COM", adminDomain) shouldBe true // case-insensitive
        AdminRules.isAutoAdminAddress("ops@mail.acme.com", adminDomain) shouldBe true // subdomain

        AdminRules.isAutoAdminAddress("boss+qa@acme.com", adminDomain) shouldBe false // plus-addressed
        AdminRules.isAutoAdminAddress("someone@other.com", adminDomain) shouldBe false // other domain
        AdminRules.isAutoAdminAddress("boss@notacme.com", adminDomain) shouldBe false // suffix, not the domain
        AdminRules.isAutoAdminAddress("@acme.com", adminDomain) shouldBe false // no local part
        AdminRules.isAutoAdminAddress("boss@acme.com", null) shouldBe false // unconfigured: nobody qualifies
    }

    // Registers a user through the real self-service verification-code flow, returning their userId. Kept only
    // for the test below it: `becomeUser` provisions rows directly, so it is the wrong instrument for asking
    // what an ordinary registration grants.
    fun registerByCode(client: TestHttpClient, contact: String, name: String): Long {
        val token = results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String
        client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to token),
        )
        val code = computeVerifyCode(token, contact)
        val created = client.sendJsonPutRequest(
            "/auth/user/createInitial",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to token, "verifyCode" to code),
        )
        val userId = results(created)["userId"] as Long
        client.sendJsonPutRequest(
            "/auth/user/setLoginData",
            mapOf("userId" to userId, "username" to name, "formAuthToken" to token, "verifyCode" to code),
        )
        return userId
    }

    "registering at the configured domain grants admin, and a plus-addressed sibling gets nothing" {
        val cxt = Startup.mkTestBootCxt("admin", "adminGrantTest", mapOf(ACFG.adminEmailDomain to adminDomain))

        val boss = TestHttpClient(cxt.instanceConfig)
        registerByCode(boss, "boss@acme.com", "boss")
        roles(results(boss.sendJsonGetRequest("/auth/self/info"))) shouldContain ROLE.admin
        items(boss.sendJsonGetRequest(ADEP.users)).isEmpty() shouldBe false // the role actually opens the door

        // The same mailbox, plus-addressed: an ordinary user, which is the point of the exclusion.
        val bossQa = TestHttpClient(cxt.instanceConfig)
        registerByCode(bossQa, "boss+qa@acme.com", "bossqa")
        roles(results(bossQa.sendJsonGetRequest("/auth/self/info"))) shouldNotContain ROLE.admin
        bossQa.sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded
    }

    // --- the gate ------------------------------------------------------------

    "neither an anonymous caller nor a plain user can reach the admin endpoints" {
        val cxt = Startup.mkTestBootCxt("admin", "adminGateTest")

        TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded

        val plain = TestUser.create(cxt, "outsider@other.com")
        plain.client.sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded
    }

    // --- the whole flow ------------------------------------------------------

    "an admin lists, creates, promotes, and disables users" {
        val cxt = Startup.mkTestBootCxt("admin", "adminFlowTest")
        val admin = TestUser.create(cxt, "chief@other.com", admin = true)

        // Create a user directly, bypassing email verification.
        val created = admin.postData(
            ADEP.userCreate, mapOf(ADF.primaryId to "newbie@other.com", ADF.username to "newbie"),
        )
        val newbieId = created[ADF.userId] as Long
        roles(created) shouldBe listOf(ROLE.user)
        created[ADF.enabled] shouldBe true

        // A duplicate email is refused.
        admin.client.sendJsonPostRequest(
            ADEP.userCreate, mapOf(ADF.primaryId to "newbie@other.com"),
        )[EP.status] shouldBe EXC.badInput

        // List and search.
        items(admin.client.sendJsonGetRequest(ADEP.users)).map { it[ADF.primaryId] } shouldContain "newbie@other.com"
        val searched = items(admin.client.sendJsonGetRequest(ADEP.users, mapOf(ADF.search to "newbie")))
        searched.size shouldBe 1
        searched[0][ADF.username] shouldBe "newbie"

        // Promote the new user to admin -- the point of the whole feature.
        roles(
            admin.postData(ADEP.userSetRoles, mapOf(ADF.userId to newbieId, ADF.roles to listOf(ROLE.user, ROLE.admin))),
        ) shouldContain ROLE.admin

        // Roles without the base user role are refused (the account could not log in).
        val noUserRole = admin.client.sendJsonPostRequest(
            ADEP.userSetRoles, mapOf(ADF.userId to newbieId, ADF.roles to listOf(ROLE.admin)),
        )
        noUserRole[EP.status] shouldBe EXC.badInput
        (noUserRole[EP.errorMessage] as String) shouldContain ROLE.user

        // Self-demotion and self-disabling are refused: the last admin cannot lock the deployment out.
        admin.client.sendJsonPostRequest(
            ADEP.userSetRoles, mapOf(ADF.userId to admin.userId, ADF.roles to listOf(ROLE.user)),
        )[EP.status] shouldBe EXC.badInput
        admin.client.sendJsonPostRequest(
            ADEP.userSetEnabled, mapOf(ADF.userId to admin.userId, ADF.enabled to false),
        )[EP.status] shouldBe EXC.badInput

        // Disable the other user. Asserted by RE-READING the list, not by trusting the response: the write
        // path stamps protocol columns on its way to the database, and an earlier version of this endpoint
        // returned a correctly-disabled row while leaving the stored one enabled.
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to newbieId, ADF.enabled to false))[ADF.enabled] shouldBe
            false
        val reread = items(admin.client.sendJsonGetRequest(ADEP.users, mapOf(ADF.search to "newbie")))
        reread.single()[ADF.enabled] shouldBe false

        // And re-enabling round-trips just as durably.
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to newbieId, ADF.enabled to true))
        items(admin.client.sendJsonGetRequest(ADEP.users, mapOf(ADF.search to "newbie")))
            .single()[ADF.enabled] shouldBe true
    }

    // --- revocation takes effect without waiting for the session to expire ----

    "granting and revoking admin take effect on an existing session's next request" {
        val cxt = Startup.mkTestBootCxt("admin", "adminRevokeTest")
        val chief = TestUser.create(cxt, "chief2@other.com", admin = true)

        // A plain user with a live session of their own, promoted *after* their cookie was issued.
        val deputy = TestUser.create(cxt, "deputy@other.com")
        deputy.client.sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded
        chief.postData(
            ADEP.userSetRoles, mapOf(ADF.userId to deputy.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
        )
        // Their cookie still says "plain user", yet the live role read lets them in -- no re-login needed.
        items(deputy.client.sendJsonGetRequest(ADEP.users)).isEmpty() shouldBe false

        // Revoke it: the same session must lose admin access on its very next request, rather than keeping it
        // for the 30-day life of the cookie it is holding.
        chief.postData(ADEP.userSetRoles, mapOf(ADF.userId to deputy.userId, ADF.roles to listOf(ROLE.user)))
        deputy.client.sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded

        // Their ordinary (non-admin) access is untouched by all of this.
        roles(results(deputy.client.sendJsonGetRequest("/auth/self/info"))) shouldNotContain ROLE.admin
    }

    // --- nobody edits their own administrator status -------------------------

    "an admin may edit their own other roles, but not their own admin status" {
        val cxt = Startup.mkTestBootCxt("admin", "adminSelfRoleTest")
        val admin = TestUser.create(cxt, "self@other.com", admin = true)
        val other = "auditor" // a role some deployment might add; not special to the runtime

        // Adding an unrelated role to yourself is allowed: the guard is about the admin role alone.
        roles(
            admin.postData(
                ADEP.userSetRoles,
                mapOf(ADF.userId to admin.userId, ADF.roles to listOf(ROLE.user, ROLE.admin, other)),
            ),
        ) shouldContain other

        // Dropping your own admin role is refused, even while keeping the rest.
        val demote = admin.client.sendJsonPostRequest(
            ADEP.userSetRoles, mapOf(ADF.userId to admin.userId, ADF.roles to listOf(ROLE.user, other)),
        )
        demote[EP.status] shouldBe EXC.badInput
        (demote[EP.errorMessage] as String) shouldContain ROLE.admin

        // The refusal did not partially apply: the caller is still an admin, and still holds the extra role.
        val stored = items(admin.client.sendJsonGetRequest(ADEP.users, mapOf(ADF.search to "self@other.com")))
        roles(stored.single()) shouldContain ROLE.admin
        roles(stored.single()) shouldContain other
    }

    "a user cannot promote themselves to admin" {
        val cxt = Startup.mkTestBootCxt("admin", "adminSelfPromoteTest")
        val plain = TestUser.create(cxt, "climber@other.com")

        // Today the section gate alone stops this -- a non-admin never reaches the endpoint. The assertion
        // stands guard for when canManageUsers admits a weaker caller (an account-scoped manager), for whom
        // self-promotion would be the obvious escalation path.
        plain.client.sendJsonPostRequest(
            ADEP.userSetRoles, mapOf(ADF.userId to plain.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
        )[EP.status] shouldBe EXC.authNeeded

        // And the attempt changed nothing.
        val admin = TestUser.create(cxt, "chief3@other.com", admin = true)
        val stored = items(admin.client.sendJsonGetRequest(ADEP.users, mapOf(ADF.search to "climber")))
        roles(stored.single()) shouldNotContain ROLE.admin
    }

})
