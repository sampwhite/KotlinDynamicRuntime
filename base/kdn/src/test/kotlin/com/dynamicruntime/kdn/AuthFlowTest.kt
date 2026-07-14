package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.user.computeVerifyCode
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * End-to-end walk-through of verify-code auth (issue #67), driven through the in-process [TestHttpClient]:
 * request a form token, email + verify a new contact, provision the user, set a username (no password --
 * passwords are optional), reach an authenticated endpoint via the session cookie, log out, and log back in
 * by verification code. Mirrors dn's stepwise `AuthFormHandlerTest` as one ordered flow. Verification codes
 * are computed directly (they are a deterministic hash of the token + contact), so no email parsing is needed.
 */
class AuthFlowTest : StringSpec({

    val email = "jason@example.com"
    val username = "jason"

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()

    "register, set login data, reach self info, log out, then log back in by code" {
        val cxt = Startup.mkTestBootCxt("auth", "authFlowTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader("User-Agent", "Fake Chrome (test)")
        client.setHeader("X-Forwarded-For", "10.10.10.10")

        // 1. A form token (no captcha).
        val token1 = results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String

        // 2. Email a verification code to the new contact; the (simulated) email is captured.
        client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to email, "contactType" to "email", "formAuthToken" to token1),
        )
        MailService.get(cxt)!!.lastEmailTo(email).shouldNotBeNull()

        // 3. Provision the initial user with the (deterministic) verification code.
        val code1 = computeVerifyCode(token1, email)
        val createResp = client.sendJsonPutRequest(
            "/auth/user/createInitial",
            mapOf("contactAddress" to email, "contactType" to "email", "formAuthToken" to token1, "verifyCode" to code1),
        )
        val userId = results(createResp)["userId"] as Long
        (userId > 0L) shouldBe true

        // 4. Set a username (no password -- login by code alone); this logs the user in (sets the session cookie).
        val loginResp = client.sendJsonPutRequest(
            "/auth/user/setLoginData",
            mapOf("userId" to userId, "username" to username, "formAuthToken" to token1, "verifyCode" to code1),
        )
        results(loginResp)["userId"] shouldBe userId
        results(loginResp)["publicName"] shouldBe username

        // 5. An authenticated endpoint works, thanks to the session cookie the client is now carrying.
        results(client.sendJsonGetRequest("/auth/self/info"))["publicName"] shouldBe username

        // 6. Log out, then self-info returns the anonymous profile (it does not require a login).
        client.sendGetRequest("/logout")
        results(client.sendJsonGetRequest("/auth/self/info"))["authId"] shouldBe UserProfile.anonymousAuthId

        // 7. Log back in by verification code (fresh token; the code targets the user's primary contact).
        val token2 = results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String
        client.sendJsonPostRequest(
            "/auth/user/sendVerify",
            mapOf("username" to username, "formAuthToken" to token2),
        )
        val code2 = computeVerifyCode(token2, email)
        val login2 = client.sendJsonPostRequest(
            "/auth/login/byCode",
            mapOf("username" to username, "formAuthToken" to token2, "verifyCode" to code2),
        )
        results(login2)["userId"] shouldBe userId

        // 8. Authenticated again.
        results(client.sendJsonGetRequest("/auth/self/info"))["userId"] shouldBe userId
    }
})
