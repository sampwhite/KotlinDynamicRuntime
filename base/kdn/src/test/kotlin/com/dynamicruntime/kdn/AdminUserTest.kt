package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ENV
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
import io.kotest.matchers.collections.shouldHaveSize
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

    /** A context in a developer environment, with [domain] configured as the auto-admin domain (or none). */
    fun cxtWith(domain: String?): KdrCxt {
        val config = KdrInstanceConfig("adminRules-$domain", ENV.local, ENV.liveSource)
        domain?.let { config.put(ACFG.adminEmailDomain, it) }
        return KdrCxt("adminRules", config)
    }

    // --- the auto-admin rule (pure logic; no boot needed) --------------------

    "an address auto-qualifies on a controlled domain when it carries no plus tag" {
        // The domain half is `AddressRules.isControlledDomain`, exercised in its own spec; what is checked
        // here is the half this rule owns -- that a `+` tag disqualifies, and that an unconfigured deployment
        // still has `example.com` outside production.
        val configured = cxtWith(adminDomain)
        AdminRules.isAutoAdminAddress(configured, "boss@acme.com") shouldBe true
        AdminRules.isAutoAdminAddress(configured, "BOSS@ACME.COM") shouldBe true // case-insensitive
        AdminRules.isAutoAdminAddress(configured, "ops@mail.acme.com") shouldBe true // subdomain

        // A `+` tag no longer means "deliberately not an admin" -- it names a client (issue #352) -- but it
        // still takes the address out of the blanket grant, which is what this asserts.
        AdminRules.isAutoAdminAddress(configured, "boss+qa@acme.com") shouldBe false
        AdminRules.isAutoAdminAddress(configured, "someone@other.com") shouldBe false // other domain
        AdminRules.isAutoAdminAddress(configured, "boss@notacme.com") shouldBe false // suffix, not the domain
        AdminRules.isAutoAdminAddress(configured, "@acme.com") shouldBe false // no local part

        // Unconfigured, `example.com` is still controlled outside production -- which is what makes a test
        // instance able to mint its own first administrator.
        val unconfigured = cxtWith(null)
        AdminRules.isAutoAdminAddress(unconfigured, "boss@acme.com") shouldBe false
        AdminRules.isAutoAdminAddress(unconfigured, "boss@example.com") shouldBe true
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
        bossQa.expectError(EXC.notAuthorized, ADEP.users)
    }

    "a plain address on a controlled domain is provisioned as a full-scope administrator" {
        // No admin domain configured, so this is `example.com` doing the work -- which is how a test or a
        // fresh developer instance mints its own first administrator without the GrantRole script.
        val cxt = Startup.mkTestBootCxt("autoAdmin", "autoAdminExampleTest")
        val boss = TestUser.register(cxt, "auto-boss@example.com", "autoboss")
        boss.selfRoles() shouldContain ROLE.admin
        boss.selfRoles() shouldContain ROLE.allClients
        boss.getItems(ADEP.users).isEmpty() shouldBe false
    }

    /**
     * The rule applies at provisioning and nowhere else (issue #352).
     *
     * It used to be re-applied on every login, and only ever granted -- so a role an administrator had
     * deliberately removed came back the next time its owner logged in, silently, with the demotion still
     * sitting in the audit trail. This is the test that says the address decides what an account is *created*
     * as and stops there.
     */
    "a role an administrator removes stays removed across a login" {
        val cxt = Startup.mkTestBootCxt("autoAdmin", "autoAdminNoResyncTest")
        val chief = TestUser.register(cxt, "auto-chief@example.com", "autochief")
        val demoted = TestUser.register(cxt, "auto-demoted@example.com", "autodemoted")
        demoted.selfRoles() shouldContain ROLE.admin // provisioned as one, being a plain controlled address

        // Co-equal administration: one admin may edit another, which is what makes the demotion possible.
        chief.postData(ADEP.userSetRoles, mapOf(ADF.userId to demoted.userId, ADF.roles to listOf(ROLE.user)))

        // Logging in again is exactly where the old top-up ran.
        val afterLogin = TestUser.create(cxt, "auto-demoted@example.com")
        afterLogin.userId shouldBe demoted.userId
        afterLogin.selfRoles() shouldNotContain ROLE.admin
        afterLogin.selfRoles() shouldNotContain ROLE.allClients
    }

    // --- the gate ------------------------------------------------------------

    "neither an anonymous caller nor a plain user can reach the admin endpoints" {
        val cxt = Startup.mkTestBootCxt("admin", "adminGateTest")

        // Refused either way, but not with the same answer (issue #211): nobody logged in is a 401, which
        // says "authenticate and retry", while a logged-in non-admin is a 403, where retrying as themselves
        // never works. The pair is asserted together because the distinction is the whole point.
        TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded

        val plain = TestUser.create(cxt, "outsider@other.com")
        plain.expectError(EXC.notAuthorized, ADEP.users)
    }

    // --- the whole flow ------------------------------------------------------

    "an admin lists, creates, promotes, and disables users" {
        val cxt = Startup.mkTestBootCxt("admin", "adminFlowTest")
        val admin = TestUser.createFullAdmin(cxt, "chief@other.com")

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

    "creating a user with a malformed email address is refused" {
        val cxt = Startup.mkTestBootCxt("admin", "adminEmailValidationTest")
        val admin = TestUser.createFullAdmin(cxt, "chief@emailval.com")

        // The reported gap: a bare username (no '@') was accepted as an address, minting a permanent account
        // that could never be reached by verification mail. It is now a plain input error, before any row.
        val refused = admin.expectError(EXC.badInput, ADEP.userCreate, mapOf(ADF.primaryId to "test_august"))
        (refused[EP.errorMessage] as String) shouldContain "test_august"
        admin.expectError(EXC.badInput, ADEP.userCreate, mapOf(ADF.primaryId to "nope@localhost"))

        // Nothing was created: the address is free, and a well-formed one goes through as before.
        admin.getItems(ADEP.users, mapOf(ADF.search to "test_august")) shouldHaveSize 0
        admin.postData(
            ADEP.userCreate, mapOf(ADF.primaryId to "valid@emailval.com"),
        )[ADF.primaryId] shouldBe "valid@emailval.com"
    }

    // --- revocation takes effect without waiting for the session to expire ----

    "granting and revoking admin take effect on an existing session's next request" {
        val cxt = Startup.mkTestBootCxt("admin", "adminRevokeTest")
        val chief = TestUser.createFullAdmin(cxt, "chief2@other.com")

        // A plain user with a live session of their own, promoted *after* their cookie was issued.
        val deputy = TestUser.create(cxt, "deputy@other.com")
        deputy.expectError(EXC.notAuthorized, ADEP.users)
        chief.postData(
            ADEP.userSetRoles,
            mapOf(ADF.userId to deputy.userId, ADF.roles to listOf(ROLE.user, ROLE.admin, ROLE.allClients)),
        )
        // Their cookie still says "plain user", yet the live role read lets them in -- no re-login needed.
        deputy.getItems(ADEP.users).isEmpty() shouldBe false

        // Revoke it: the same session must lose admin access on its very next request, rather than keeping it
        // for the 30-day life of the cookie it is holding.
        chief.postData(ADEP.userSetRoles, mapOf(ADF.userId to deputy.userId, ADF.roles to listOf(ROLE.user)))
        // A 403, not a 401: their session is perfectly valid, it just no longer carries the role.
        deputy.expectError(EXC.notAuthorized, ADEP.users)

        // Their ordinary (non-admin) access is untouched by all of this.
        deputy.selfRoles() shouldNotContain ROLE.admin
    }

    // --- nobody edits their own administrator status -------------------------

    "an admin may edit their own other roles, but not their own admin status" {
        val cxt = Startup.mkTestBootCxt("admin", "adminSelfRoleTest")
        val admin = TestUser.createFullAdmin(cxt, "self@other.com")
        val other = "auditor" // a role some deployment might add; not special to the runtime

        // Adding an unrelated role to yourself is allowed: the guard is about the admin role alone.
        TestUser.rolesOf(
            admin.postData(
                ADEP.userSetRoles,
                mapOf(ADF.userId to admin.userId, ADF.roles to listOf(ROLE.user, ROLE.admin, ROLE.allClients, other)),
            ),
        ) shouldContain other

        // Dropping your own admin role is refused, even while keeping the rest.
        val demote = admin.expectError(
            EXC.badInput,
            ADEP.userSetRoles,
            mapOf(ADF.userId to admin.userId, ADF.roles to listOf(ROLE.user, ROLE.allClients, other)),
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
        // stands guard for when canManageUsers admits a weaker caller (a client-scoped manager), for whom
        // self-promotion would be the obvious escalation path.
        plain.expectError(
            EXC.notAuthorized, ADEP.userSetRoles, mapOf(ADF.userId to plain.userId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
        )

        // And the attempt changed nothing.
        val admin = TestUser.createFullAdmin(cxt, "chief3@other.com")
        val stored = admin.getItems(ADEP.users, mapOf(ADF.search to "climber")).single()
        TestUser.rolesOf(stored) shouldNotContain ROLE.admin
    }

})
