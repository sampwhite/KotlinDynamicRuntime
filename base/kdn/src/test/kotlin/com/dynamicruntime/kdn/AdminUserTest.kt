package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AdminRules
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
 * Driven through the in-process [TestHttpClient] like [AuthFlowTest], so the section's role gate -- which lives
 * in the dispatcher, not in the handlers -- is exercised for real rather than assumed. A rejected call comes
 * back as the standard *error envelope* (issue #103), not a thrown exception, so refusals are asserted on
 * [EP.status].
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

    // Registers a user by verification code and logs them in, returning their userId.
    fun register(client: TestHttpClient, contact: String, name: String): Long {
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

    // --- the gate ------------------------------------------------------------

    "a plain user cannot reach the admin endpoints" {
        val cxt = Startup.mkTestBootCxt("admin", "adminGateTest")
        val client = TestHttpClient(cxt.instanceConfig)

        // Anonymous first: no session at all.
        client.sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded

        // And a logged-in user without the role fares no better.
        register(client, "outsider@other.com", "outsider")
        client.sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded
    }

    // --- the whole flow ------------------------------------------------------

    "an auto-admin lists, creates, promotes, and disables users" {
        val cxt = Startup.mkTestBootCxt("admin", "adminFlowTest", mapOf(ACFG.adminEmailDomain to adminDomain))
        val client = TestHttpClient(cxt.instanceConfig)

        // Registering at the configured domain grants the admin role at provisioning time.
        val adminId = register(client, "boss@acme.com", "boss")
        val self = results(client.sendJsonGetRequest("/auth/self/info"))
        (self["roles"] as List<*>).map { it.toString() } shouldContain ROLE.admin

        // A plus-addressed sibling of the same mailbox is deliberately NOT an admin.
        val plusClient = TestHttpClient(cxt.instanceConfig)
        register(plusClient, "boss+qa@acme.com", "bossqa")
        plusClient.sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded

        // Create a user directly, bypassing email verification.
        val created = results(
            client.sendJsonPostRequest(
                ADEP.userCreate, mapOf(ADF.primaryId to "newbie@other.com", ADF.username to "newbie"),
            ),
        )
        val newbieId = created[ADF.userId] as Long
        roles(created) shouldBe listOf(ROLE.user)
        created[ADF.enabled] shouldBe true

        // A duplicate email is refused.
        client.sendJsonPostRequest(ADEP.userCreate, mapOf(ADF.primaryId to "newbie@other.com"))[EP.status] shouldBe
            EXC.badInput

        // List and search.
        items(client.sendJsonGetRequest(ADEP.users)).map { it[ADF.primaryId] } shouldContain "newbie@other.com"
        val searched = items(client.sendJsonGetRequest(ADEP.users, mapOf(ADF.search to "newbie")))
        searched.size shouldBe 1
        searched[0][ADF.username] shouldBe "newbie"

        // Promote the new user to admin -- the point of the whole feature.
        val promoted = results(
            client.sendJsonPostRequest(
                ADEP.userSetRoles, mapOf(ADF.userId to newbieId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
            ),
        )
        roles(promoted) shouldContain ROLE.admin

        // Roles without the base user role are refused (the account could not log in).
        val noUserRole = client.sendJsonPostRequest(
            ADEP.userSetRoles, mapOf(ADF.userId to newbieId, ADF.roles to listOf(ROLE.admin)),
        )
        noUserRole[EP.status] shouldBe EXC.badInput
        (noUserRole[EP.errorMessage] as String) shouldContain ROLE.user

        // Self-demotion and self-disabling are refused: the last admin cannot lock the deployment out.
        client.sendJsonPostRequest(
            ADEP.userSetRoles, mapOf(ADF.userId to adminId, ADF.roles to listOf(ROLE.user)),
        )[EP.status] shouldBe EXC.badInput
        client.sendJsonPostRequest(
            ADEP.userSetEnabled, mapOf(ADF.userId to adminId, ADF.enabled to false),
        )[EP.status] shouldBe EXC.badInput

        // Disable the other user; the change is visible immediately.
        val disabled = results(
            client.sendJsonPostRequest(ADEP.userSetEnabled, mapOf(ADF.userId to newbieId, ADF.enabled to false)),
        )
        disabled[ADF.enabled] shouldBe false
    }

    // --- revocation takes effect without waiting for the session to expire ----

    "revoking the admin role ends an existing session's admin access on the next request" {
        val cxt = Startup.mkTestBootCxt("admin", "adminRevokeTest", mapOf(ACFG.adminEmailDomain to adminDomain))
        val first = TestHttpClient(cxt.instanceConfig)
        register(first, "chief@acme.com", "chief")

        // A second admin, promoted by the first, with a live logged-in session of their own.
        val second = TestHttpClient(cxt.instanceConfig)
        val secondId = register(second, "deputy@other.com", "deputy")
        first.sendJsonPostRequest(
            ADEP.userSetRoles, mapOf(ADF.userId to secondId, ADF.roles to listOf(ROLE.user, ROLE.admin)),
        )
        // Their session cookie predates the grant, yet the live role read lets them straight in -- no re-login.
        items(second.sendJsonGetRequest(ADEP.users)).isEmpty() shouldBe false

        // Revoke it; their *existing* session must lose admin access on its very next request, rather than
        // keeping it for the 30-day life of the cookie it is holding.
        first.sendJsonPostRequest(ADEP.userSetRoles, mapOf(ADF.userId to secondId, ADF.roles to listOf(ROLE.user)))
        second.sendJsonGetRequest(ADEP.users)[EP.status] shouldBe EXC.authNeeded

        // Their ordinary (non-admin) access is untouched by all of this.
        roles(results(second.sendJsonGetRequest("/auth/self/info"))) shouldNotContain ROLE.admin
    }
})
