package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.user.RL
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

    // Registers a user and logs them in by verification code (which also makes the client's device familiar),
    // returning the new userId. The code is reused for the createInitial + setLoginData sequence, as the flow allows.
    fun registerByCode(client: TestHttpClient, contact: String, name: String): Long {
        val token = results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String
        client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to token),
        )
        val code = computeVerifyCode(token, contact)
        val createResp = client.sendJsonPutRequest(
            "/auth/user/createInitial",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to token, "verifyCode" to code),
        )
        val userId = results(createResp)["userId"] as Long
        val loginResp = client.sendJsonPutRequest(
            "/auth/user/setLoginData",
            mapOf("userId" to userId, "username" to name, "formAuthToken" to token, "verifyCode" to code),
        )
        results(loginResp)["publicName"] shouldBe name // fails loudly if the username was rejected
        return userId
    }

    // Sets a password on an existing user via a fresh addPassword-framed verification code.
    fun activatePassword(client: TestHttpClient, cxt: KdrCxt, contact: String, name: String, password: String): Map<String, Any?> {
        val token = results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String
        client.sendJsonPostRequest(
            "/auth/user/sendVerify",
            mapOf("loginId" to name, "formAuthToken" to token, "addPassword" to true),
        )
        MailService.get(cxt)!!.lastEmailTo(contact)!!.text.contains("password") shouldBe true
        val code = computeVerifyCode(token, contact)
        return client.sendJsonPutRequest(
            "/auth/user/setPassword",
            mapOf("loginId" to name, "password" to password, "formAuthToken" to token, "verifyCode" to code),
        )
    }

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
            mapOf("loginId" to username, "formAuthToken" to token2),
        )
        val code2 = computeVerifyCode(token2, email)
        val login2 = client.sendJsonPostRequest(
            "/auth/login/byCode",
            mapOf("loginId" to username, "formAuthToken" to token2, "verifyCode" to code2),
        )
        results(login2)["userId"] shouldBe userId

        // 8. Authenticated again.
        results(client.sendJsonGetRequest("/auth/self/info"))["userId"] shouldBe userId
    }

    "activate a password, log in by it from a familiar device, then opt back out" {
        val cxt = Startup.mkTestBootCxt("authPw", "authPwTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader("User-Agent", "Fake Chrome (test)")
        client.setHeader("X-Forwarded-For", "10.10.10.10")
        val contact = "amy@example.com"

        // Register + code login (the device becomes familiar), then activate a password.
        val userId = registerByCode(client, contact, "amelia")
        results(activatePassword(client, cxt, contact, "amelia", "sekret-pw-123"))["hasPassword"] shouldBe true

        // Log out -- the device cookie stays, so the device is still familiar -- then log in by password.
        client.sendGetRequest("/logout")
        results(client.sendJsonGetRequest("/auth/self/info"))["authId"] shouldBe UserProfile.anonymousAuthId
        val byPw = client.sendJsonPostRequest(
            "/auth/login/byPassword", mapOf("loginId" to "amelia", "password" to "sekret-pw-123"),
        )
        results(byPw)["userId"] shouldBe userId

        // Opt out (needs the logged-in session), then password login is refused.
        results(client.sendJsonPostRequest("/profile/self/clearPassword", emptyMap()))["hasPassword"] shouldBe false
        client.sendGetRequest("/logout")
        client.sendEditRequest(
            "/auth/login/byPassword", null, mapOf("loginId" to "amelia", "password" to "sekret-pw-123"), isPut = false,
        ).rptStatusCode shouldBe 401
    }

    "password login is refused from an unfamiliar device, then allowed once it is verified" {
        val cxt = Startup.mkTestBootCxt("authPw2", "authPwTest2")
        val contact = "bob@example.com"
        val first = TestHttpClient(cxt.instanceConfig)
        first.setHeader("User-Agent", "First Browser")
        first.setHeader("X-Forwarded-For", "10.0.0.1")
        val userId = registerByCode(first, contact, "robert")
        activatePassword(first, cxt, contact, "robert", "sekret-pw-123")

        // A different browser (no device cookie) cannot use the password -- unfamiliar device.
        val other = TestHttpClient(cxt.instanceConfig)
        other.setHeader("User-Agent", "Other Browser")
        other.setHeader("X-Forwarded-For", "10.0.0.2")
        other.sendEditRequest(
            "/auth/login/byPassword", null, mapOf("loginId" to "robert", "password" to "sekret-pw-123"), isPut = false,
        ).rptStatusCode shouldBe 401

        // But a code login from the new browser works and makes it familiar...
        val token = results(other.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String
        other.sendJsonPostRequest("/auth/user/sendVerify", mapOf("loginId" to "robert", "formAuthToken" to token))
        val code = computeVerifyCode(token, contact)
        results(
            other.sendJsonPostRequest(
                "/auth/login/byCode", mapOf("loginId" to "robert", "formAuthToken" to token, "verifyCode" to code),
            ),
        )["userId"] shouldBe userId

        // ...so now the password works from this browser too.
        other.sendGetRequest("/logout")
        results(
            other.sendJsonPostRequest("/auth/login/byPassword", mapOf("loginId" to "robert", "password" to "sekret-pw-123")),
        )["userId"] shouldBe userId
    }

    "repeated failed password logins are rate-limited" {
        val cxt = Startup.mkTestBootCxt("authPw3", "authPwTest3")
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader("X-Forwarded-For", "10.5.5.5")
        val attempt = mapOf("loginId" to "ghost", "password" to "whatever")

        // No such user: every attempt fails 401 until the per-username limit trips, then it is 429.
        repeat(RL.pwPerUserMax) {
            client.sendEditRequest("/auth/login/byPassword", null, attempt, isPut = false).rptStatusCode shouldBe 401
        }
        client.sendEditRequest("/auth/login/byPassword", null, attempt, isPut = false).rptStatusCode shouldBe 429
    }

    "a returning user can log in by email as the login id, not just username" {
        val cxt = Startup.mkTestBootCxt("authEmail", "authEmailTest")
        val client = TestHttpClient(cxt.instanceConfig)
        client.setHeader("User-Agent", "Fake Chrome (test)")
        client.setHeader("X-Forwarded-For", "10.20.20.20")
        val contact = "erin@example.com"

        val userId = registerByCode(client, contact, "erinny")
        client.sendGetRequest("/logout")

        // Log back in by code using the EMAIL as the login id (the frontend never surfaces the username).
        val token = results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String
        client.sendJsonPostRequest("/auth/user/sendVerify", mapOf("loginId" to contact, "formAuthToken" to token))
        val code = computeVerifyCode(token, contact)
        results(
            client.sendJsonPostRequest(
                "/auth/login/byCode", mapOf("loginId" to contact, "formAuthToken" to token, "verifyCode" to code),
            ),
        )["userId"] shouldBe userId
    }
})
