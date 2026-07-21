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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The admin user-management surface: who becomes an administrator and what one can then do.
 *
 * Setup uses [TestUser] (issue #125): [TestUser.create] reaches an authenticated session in one call for tests
 * that just need a user, while [TestUser.register] walks the real verification-code flow for the one thing that
 * turns on it -- the auto-admin-at-registration rule, whose subject *is* what an ordinary registration grants
 * (`becomeUser` provisions rows directly and does not go through it).
 *
 * Everything runs through the in-process [TestHttpClient], so the section's role gate -- which lives in the
 * dispatcher, not in the handlers -- is exercised for real. A rejected call comes back as the standard error
 * envelope (issue #103), not a thrown exception, so refusals are asserted with [TestUser.expectError] on the
 * envelope's `status`.
 *
 * Emails are distinct per test: the in-memory database is keyed by name, not by instance, so rows outlive a
 * single boot within the JVM.
 */
class AdminUserTest : StringSpec({

    val adminDomain = "acme.com"

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

    "registering at the configured domain grants admin, and a plus-addressed sibling gets nothing" {
        val cxt = Startup.mkTestBootCxt("admin", "adminGrantTest", mapOf(ACFG.adminEmailDomain to adminDomain))

        // The real registration flow, because what is under test is what `createInitialUser` grants.
        val boss = TestUser.register(cxt, "boss@acme.com", "boss")
        boss.selfRoles() shouldContain ROLE.admin
        boss.getItems(ADEP.users).isEmpty() shouldBe false // the role actually opens the door

        // The same mailbox, plus-addressed: an ordinary user, which is the point of the exclusion.
        val bossQa = TestUser.register(cxt, "boss+qa@acme.com", "bossqa")
        bossQa.selfRoles() shouldNotContain ROLE.admin
        bossQa.expectError(EXC.authNeeded, ADEP.users)
    }

    // --- the gate ------------------------------------------------------------

    "neither an anonymous caller nor a plain user can reach the admin endpoints" {
        val cxt = Startup.mkTestBootCxt("admin", "adminGateTest")

        TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded

        val plain = TestUser.create(cxt, "outsider@other.com")
        plain.expectError(EXC.authNeeded, ADEP.users)
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
        TestUser.rolesOf(created) shouldBe listOf(ROLE.user)
        created[ADF.enabled] shouldBe true

        // A duplicate email is refused.
        admin.expectError(EXC.badInput, ADEP.userCreate, mapOf(ADF.primaryId to "newbie@other.com"))

        // List and search.
        admin.getItems(ADEP.users).map { it[ADF.primaryId] } shouldContain "newbie@other.com"
        val searched = admin.getItems(ADEP.users, mapOf(ADF.search to "newbie"))
        searched.size shouldBe 1
        searched[0][ADF.username] shouldBe "newbie"

        // Promote the new user to admin -- the point of the whole feature.
        TestUser.rolesOf(
            admin.postData(ADEP.userSetRoles, mapOf(ADF.userId to newbieId, ADF.roles to listOf(ROLE.user, ROLE.admin))),
        ) shouldContain ROLE.admin

        // Roles without the base user role are refused (the account could not log in).
        val noUserRole = admin.expectError(
            EXC.badInput, ADEP.userSetRoles, mapOf(ADF.userId to newbieId, ADF.roles to listOf(ROLE.admin)),
        )
        (noUserRole[EP.errorMessage] as String) shouldContain ROLE.user

        // Self-demotion and self-disabling are refused: the last admin cannot lock the deployment out.
        admin.expectError(
            EXC.badInput, ADEP.userSetRoles, mapOf(ADF.userId to admin.userId, ADF.roles to listOf(ROLE.user)),
        )
        admin.expectError(
            EXC.badInput, ADEP.userSetEnabled, mapOf(ADF.userId to admin.userId, ADF.enabled to false),
        )

        // Disable the other user. Asserted by RE-READING the list, not by trusting the response: the "write"
        // path stamps protocol columns on its way to the database, and an earlier version of this endpoint
        // returned a correctly disabled row while leaving the stored one enabled.
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to newbieId, ADF.enabled to false))[ADF.enabled] shouldBe
            false
        admin.getItems(ADEP.users, mapOf(ADF.search to "newbie")).single()[ADF.enabled] shouldBe false

        // And re-enabling round-trips just as durably.
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to newbieId, ADF.enabled to true))
        admin.getItems(ADEP.users, mapOf(ADF.search to "newbie")).single()[ADF.enabled] shouldBe true
    }

    // --- revocation takes effect without waiting for the session to expire ----

    "granting and revoking admin take effect on an existing session's next request" {
        val cxt = Startup.mkTestBootCxt("admin", "adminRevokeTest")
        val chief = TestUser.create(cxt, "chief2@other.com", admin = true)

        // A plain user with a live session of their own, promoted *after* their cookie was issued.
        val deputy = TestUser.create(cxt, "deputy@other.com")
        deputy.expectError(EXC.authNeeded, ADEP.users)
        chief.postData(
            ADEP.userSetRoles, mapOf(ADF.userId to deputy.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
        )
        // Their cookie still says "plain user", yet the live role read lets them in -- no re-login needed.
        deputy.getItems(ADEP.users).isEmpty() shouldBe false

        // Revoke it: the same session must lose admin access on its very next request, rather than keeping it
        // for the 30-day life of the cookie it is holding.
        chief.postData(ADEP.userSetRoles, mapOf(ADF.userId to deputy.userId, ADF.roles to listOf(ROLE.user)))
        deputy.expectError(EXC.authNeeded, ADEP.users)

        // Their ordinary (non-admin) access is untouched by all of this.
        deputy.selfRoles() shouldNotContain ROLE.admin
    }

    // --- nobody edits their own administrator status -------------------------

    "an admin may edit their own other roles, but not their own admin status" {
        val cxt = Startup.mkTestBootCxt("admin", "adminSelfRoleTest")
        val admin = TestUser.create(cxt, "self@other.com", admin = true)
        val other = "auditor" // a role some deployment might add; not special to the runtime

        // Adding an unrelated role to yourself is allowed: the guard is about the admin role alone.
        TestUser.rolesOf(
            admin.postData(
                ADEP.userSetRoles,
                mapOf(ADF.userId to admin.userId, ADF.roles to listOf(ROLE.user, ROLE.admin, other)),
            ),
        ) shouldContain other

        // Dropping your own admin role is refused, even while keeping the rest.
        val demote = admin.expectError(
            EXC.badInput, ADEP.userSetRoles, mapOf(ADF.userId to admin.userId, ADF.roles to listOf(ROLE.user, other)),
        )
        (demote[EP.errorMessage] as String) shouldContain ROLE.admin

        // The refusal did not partially apply: the caller is still an admin and still holds the extra role.
        val stored = admin.getItems(ADEP.users, mapOf(ADF.search to "self@other.com")).single()
        TestUser.rolesOf(stored) shouldContain ROLE.admin
        TestUser.rolesOf(stored) shouldContain other
    }

    "a user cannot promote themselves to admin" {
        val cxt = Startup.mkTestBootCxt("admin", "adminSelfPromoteTest")
        val plain = TestUser.create(cxt, "climber@other.com")

        // Today the section gate alone stops this -- a non-admin never reaches the endpoint. The assertion
        // stands guard for when canManageUsers admits a weaker caller (an account-scoped manager), for whom
        // self-promotion would be the obvious escalation path.
        plain.expectError(
            EXC.authNeeded, ADEP.userSetRoles, mapOf(ADF.userId to plain.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
        )

        // And the attempt changed nothing.
        val admin = TestUser.create(cxt, "chief3@other.com", admin = true)
        val stored = admin.getItems(ADEP.users, mapOf(ADF.search to "climber")).single()
        TestUser.rolesOf(stored) shouldNotContain ROLE.admin
    }

})
