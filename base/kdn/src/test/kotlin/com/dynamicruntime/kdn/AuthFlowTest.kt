package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.user.AERR
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.AUTHC
import com.dynamicruntime.common.user.RL
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.sanitizeForDisplay
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * End-to-end walk-through of verify-code auth (issues #67, #69), plus the expiry and recovery halves that time
 * travel finally makes reachable (issue #183) -- all driven through the in-process [TestHttpClient].
 *
 * This is a **functional flow test**, not a collection of unit tests, and it is deliberately long. Every block
 * below shares ONE booted instance, declared once at spec level, and they run in declaration order as a single
 * continuous session. Two reasons. Booting the application per assertion is the redundant setup that makes a
 * mature suite slow; more importantly, per-test isolation *defines away* the bugs that only appear when
 * features meet -- a session opened at the start of the flow lapsing while a device trusted later still works,
 * a throttle reopening after an unrelated jump. Those are asserted here precisely because a fresh instance per
 * test cannot see them.
 *
 * The price is order dependence: a block that breaks takes the ones after it with it, and state accumulates.
 * Two conventions keep that manageable:
 *  - **The clock only moves forward.** "travel" never rewinds, because `now()` also stamps persisted
 *    `createdAt`/`touchedAt` (issue #160) -- a rewind would future-date rows already written and then surface
 *    as an unrelated failure somewhere far away.
 *  - **Each block owns its identifiers** -- its own contact, username, and source IP -- so one block's
 *    per-contact and per-IP rate-limit keys cannot silently throttle the next. Where a block deliberately
 *    reuses an earlier block's user, it says so.
 *
 * A test needing a *different instance config* cannot join the flow at all; the obfuscation block at the
 * bottom boots its own instance for exactly that reason. Verification codes are computed directly (they are a
 * deterministic hash of the token + contact), so no email parsing is needed.
 */
@Suppress("UnnecessaryVariable")
class AuthFlowTest : StringSpec({

    // The one instance the whole flow runs on.
    val cxt = Startup.mkTestBootCxt("auth", "authFlowTest")

    val email = "jason@example.com"
    val username = "jason"

    // A long flow needs its failures to name themselves: an unexpected error envelope here reports its own
    // status and message, rather than the "key results is missing" that a bare unwrapping would raise ten steps in.
    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp[EP.results]?.toJsonMap()
        ?: throw AssertionError("Expected a success but got ${resp[EP.status]}: ${resp[EP.errorMessage]}")

    /** Moves the shared instance clock forward. Never backwards -- see this class's note on monotonicity. */
    fun travel(amount: Duration) = cxt.instanceConfig.clock.advanceBy(amount)

    /** A browser: its own cookie jar, its own source IP (so its rate-limit keys are its own). */
    fun mkClient(ip: String, userAgent: String = "Fake Chrome (test)"): TestHttpClient =
        TestHttpClient(cxt.instanceConfig).also {
            it.setHeader("User-Agent", userAgent)
            it.setHeader("X-Forwarded-For", ip)
        }

    fun tokenOf(client: TestHttpClient): String =
        results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String

    // The verification code the server would compute, obtained the way the server does -- through the
    // instance's NodeService, under its secret key. A white-box test holds the node it drives; the attacker,
    // who has only HTTP, cannot reach the key, which is the whole point of the code being an HMAC now.
    fun codeFor(token: String, contact: String): String =
        NodeService.get(cxt)!!.computeVerifyCode(token, contact)

    /** The rendered auth.md copy for an error key, so assertions check the plumbing rather than the wording. */
    fun authMsg(key: String): String? =
        MarkdownFragmentService.get(cxt)!!.resolveFragment(cxt, AFRAG.auth, AERR.ns, key)

    // Registers a user and logs them in by verification code (which also makes the client's device familiar),
    // returning the new userId. The code is reused for the createInitial + setLoginData sequence, as the flow allows.
    fun registerByCode(client: TestHttpClient, contact: String, name: String): Long {
        val token = tokenOf(client)
        client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to token),
        )
        val code = codeFor(token, contact)
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
        val token = tokenOf(client)
        client.sendJsonPostRequest(
            "/auth/user/sendVerify",
            mapOf("loginId" to name, "formAuthToken" to token, "addPassword" to true),
        )
        MailService.get(cxt)!!.lastEmailTo(contact)!!.text.contains("password") shouldBe true
        val code = codeFor(token, contact)
        return client.sendJsonPutRequest(
            "/auth/user/setPassword",
            mapOf("loginId" to name, "password" to password, "formAuthToken" to token, "verifyCode" to code),
        )
    }

    /** Logs in by password, returning the raw handler so a caller can assert either success or a status. */
    fun pwLogin(client: TestHttpClient, loginId: String, password: String): RequestHandler =
        client.sendEditRequest(
            "/auth/login/byPassword", null, mapOf("loginId" to loginId, "password" to password), isPut = false,
        )

    // Two browsers outlive their own block, because later blocks act on the sessions and devices they leave
    // behind -- the interactions this flow exists to catch.
    val jasonBrowser = mkClient("10.10.10.10")
    val robertNewBrowser = mkClient("10.0.0.2", "Other Browser")

    "register, set login data, reach self info, log out, then log back in by code" {
        val client = jasonBrowser

        // 1. A form token (no captcha).
        val token1 = tokenOf(client)

        // 2. Email a verification code to the new contact; the (simulated) email is captured.
        client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to email, "contactType" to "email", "formAuthToken" to token1),
        )
        MailService.get(cxt)!!.lastEmailTo(email).shouldNotBeNull()

        // 3. Provision the initial user with the (deterministic) verification code.
        val code1 = codeFor(token1, email)
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
        val token2 = tokenOf(client)
        client.sendJsonPostRequest(
            "/auth/user/sendVerify",
            mapOf("loginId" to username, "formAuthToken" to token2),
        )
        val code2 = codeFor(token2, email)
        val login2 = client.sendJsonPostRequest(
            "/auth/login/byCode",
            mapOf("loginId" to username, "formAuthToken" to token2, "verifyCode" to code2),
        )
        results(login2)["userId"] shouldBe userId

        // 8. Authenticated again -- and this session is the one the thirty-day block at the end comes back to.
        results(client.sendJsonGetRequest("/auth/self/info"))["userId"] shouldBe userId
    }

    "a form auth token expires once it is older than its lifetime" {
        val client = mkClient("10.30.30.30")
        val contact = "kate@example.com"
        val stale = tokenOf(client)

        travel((AUTHC.formTokenMillis + 1000).milliseconds)
        val resp = client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to stale),
        )
        resp[EP.status] shouldBe EXC.badInput
        resp[EP.errorMessage] shouldBe authMsg(AERR.tokenExpired)

        // A token minted after the jump is fine: what expires is the token's own age, not the instance.
        client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to tokenOf(client)),
        )[EP.status] shouldBe null
    }

    "verification-code attempts are throttled, and the window reopens once it has passed" {
        val client = mkClient("10.31.31.31")
        val contact = "nina@example.com"
        registerByCode(client, contact, "nina")
        client.sendGetRequest("/logout")

        // Wrong codes up to the limit each get the ordinary "that code is incorrect".
        val token = tokenOf(client)
        val wrong = mapOf("loginId" to "nina", "formAuthToken" to token, "verifyCode" to "WRONGCODE")
        repeat(RL.verifyMax) {
            client.sendEditRequest("/auth/login/byCode", null, wrong, isPut = false).rptStatusCode shouldBe EXC.badInput
        }
        // One past it and the throttle answers instead of the code check -- the point of running it first.
        client.sendEditRequest("/auth/login/byCode", null, wrong, isPut = false)
            .rptStatusCode shouldBe EXC.tooManyRequests

        // The counter is a fixed window, so traveling past it reopens attempts. The form token lives on the
        // same fifteen-minute scale (the previous block), so this necessarily needs a fresh one.
        travel((RL.verifyWindowMs + 1000).milliseconds)
        val afterWindow = mapOf("loginId" to "nina", "formAuthToken" to tokenOf(client), "verifyCode" to "WRONGCODE")
        client.sendEditRequest("/auth/login/byCode", null, afterWindow, isPut = false)
            .rptStatusCode shouldBe EXC.badInput
    }

    "a correct verification code clears the failure counter" {
        val client = mkClient("10.32.32.32")
        val contact = "omar@example.com"
        registerByCode(client, contact, "omar")
        client.sendGetRequest("/logout")

        val token = tokenOf(client)
        val wrong = mapOf("loginId" to "omar", "formAuthToken" to token, "verifyCode" to "WRONGCODE")

        // Stop one short of the limit, then succeed -- which resets the counter for this contact.
        repeat(RL.verifyMax - 1) {
            client.sendEditRequest("/auth/login/byCode", null, wrong, isPut = false).rptStatusCode shouldBe EXC.badInput
        }
        val good = mapOf("loginId" to "omar", "formAuthToken" to token, "verifyCode" to codeFor(token, contact))
        results(client.sendJsonPostRequest("/auth/login/byCode", good))["publicName"] shouldBe "omar"

        // Proof that the reset happened: a further full run of failures still never trips. Had the counter carried
        // over, it would already stand at verifyMax - 1, and these would start returning 429 partway through.
        repeat(RL.verifyMax) {
            client.sendEditRequest("/auth/login/byCode", null, wrong, isPut = false).rptStatusCode shouldBe EXC.badInput
        }
    }

    "verification emails are throttled per contact, and the throttle lifts after its window" {
        val client = mkClient("10.33.33.33")
        val contact = "pearl@example.com"
        fun send(token: String): Map<String, Any?> = client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to token),
        )

        val token = tokenOf(client)
        repeat(RL.sendPerContactMax) { send(token)[EP.status] shouldBe null }
        send(token)[EP.status] shouldBe EXC.tooManyRequests

        // An hour on, the contact can be mailed again (a fresh token, since the "send window" outlasts it).
        travel((RL.sendPerContactWindowMs + 60_000).milliseconds)
        send(tokenOf(client))[EP.status] shouldBe null
    }

    "activate a password, log in by it from a familiar device, then opt back out" {
        val client = mkClient("10.10.10.10")
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
        pwLogin(client, "amelia", "sekret-pw-123").rptStatusCode shouldBe EXC.authNeeded
    }

    "password login is refused from an unfamiliar device, then allowed once it is verified" {
        val contact = "bob@example.com"
        val first = mkClient("10.0.0.1", "First Browser")
        val userId = registerByCode(first, contact, "robert")
        activatePassword(first, cxt, contact, "robert", "sekret-pw-123")

        // A different browser (no device cookie) cannot use the password -- unfamiliar device.
        val other = robertNewBrowser
        pwLogin(other, "robert", "sekret-pw-123").rptStatusCode shouldBe EXC.authNeeded

        // But a code login from the new browser works and makes it familiar...
        val token = tokenOf(other)
        other.sendJsonPostRequest("/auth/user/sendVerify", mapOf("loginId" to "robert", "formAuthToken" to token))
        val code = codeFor(token, contact)
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

    "repeated failed password logins are rate-limited, and recover once the window passes" {
        val client = mkClient("10.5.5.5")

        // No such user: every attempt fails 401 until the per-username limit trips, then it is 429.
        repeat(RL.pwPerUserMax) { pwLogin(client, "ghost", "whatever").rptStatusCode shouldBe EXC.authNeeded }
        pwLogin(client, "ghost", "whatever").rptStatusCode shouldBe EXC.tooManyRequests

        // Travel past the window, and the fixed window reopens -- back to a plain rejection, not a throttle.
        travel((RL.pwWindowMs + 1000).milliseconds)
        pwLogin(client, "ghost", "whatever").rptStatusCode shouldBe EXC.authNeeded
    }

    "a successful password login clears the failure counter" {
        // Reuses robert and the browser the previous block made familiar: a real user with a real password is
        // the only way to reach the success path that resets the counter.
        val client = robertNewBrowser
        client.sendGetRequest("/logout")

        repeat(RL.pwPerUserMax - 1) { pwLogin(client, "robert", "wrong-pw").rptStatusCode shouldBe EXC.authNeeded }
        results(
            client.sendJsonPostRequest("/auth/login/byPassword", mapOf("loginId" to "robert", "password" to "sekret-pw-123")),
        )["publicName"] shouldBe "robert"

        // Reset proven the same way as the verify-code counter: a full further run of failures never trips.
        client.sendGetRequest("/logout")
        repeat(RL.pwPerUserMax) { pwLogin(client, "robert", "wrong-pw").rptStatusCode shouldBe EXC.authNeeded }
    }

    "a returning user can log in by email as the login id, not just username" {
        val client = mkClient("10.20.20.20")
        val contact = "erin@example.com"

        val userId = registerByCode(client, contact, "erinny")
        client.sendGetRequest("/logout")

        // Log back in by code using the EMAIL as the login id (the frontend never surfaces the username).
        val token = tokenOf(client)
        client.sendJsonPostRequest("/auth/user/sendVerify", mapOf("loginId" to contact, "formAuthToken" to token))
        val code = codeFor(token, contact)
        results(
            client.sendJsonPostRequest(
                "/auth/login/byCode", mapOf("loginId" to contact, "formAuthToken" to token, "verifyCode" to code),
            ),
        )["userId"] shouldBe userId
    }

    "auth error messages are rendered from the auth.md fragment (issue #108)" {
        val client = mkClient("10.60.60.60")
        val token = tokenOf(client)

        // The expected message is computed from the fragment itself -- resolve the same key and substitute the
        // same (sanitized) param the handler does -- so this checks the render *plumbing*, not the wording:
        // someone can reword auth.md without breaking it. That evalTemplate/sanitize is correct is covered by
        // ErrorMessageRenderTest and StrUtilTest.
        //
        // A param-bearing message end to end: register an email, then try to register it again, which returns
        // the emailNotAvailable copy with the address substituted in. (This used the noAccount message until
        // issue #275 removed it -- an auth endpoint must not confirm whether an account exists.)
        val taken = "taken@example.com"
        registerByCode(client, taken, "takenuser")
        val token2 = tokenOf(client)
        val takenExpected = authMsg(AERR.emailNotAvailable)!!
            .evalTemplate(mapOf(AERR.emailParam to taken.sanitizeForDisplay()))
        val dup = client.sendJsonPutRequest(
            "/auth/user/createInitial",
            mapOf(
                "contactAddress" to taken, "contactType" to "email",
                "formAuthToken" to token2, "verifyCode" to codeFor(token2, taken),
            ),
        )
        dup[EP.errorMessage] shouldBe takenExpected
        // Rendered from the fragment (designed copy). A test instance does not obfuscate, so even this
        // sensitive message renders its real text and carries the flag (issue #108).
        dup[EP.errorFromFragment] shouldBe true

        // A wrong verification code on registration: the parameter-free codeIncorrect template, likewise
        // compared against the fragment's own current text. Its own contact, so the attempt counts against no
        // other block's throttle.
        val contact = "trish@example.com"
        client.sendJsonPostRequest(
            "/auth/newContact/sendVerify",
            mapOf("contactAddress" to contact, "contactType" to "email", "formAuthToken" to token),
        )
        val badCode = client.sendJsonPutRequest(
            "/auth/user/createInitial",
            mapOf(
                "contactAddress" to contact, "contactType" to "email",
                "formAuthToken" to token, "verifyCode" to "WRONGCODE",
            ),
        )
        badCode[EP.errorMessage] shouldBe authMsg(AERR.codeIncorrect)
    }

    /**
     * The auth entry points must not reveal whether an account exists (issue #275). The bug was that a login
     * id with no account produced a distinct answer -- a 404 "no account was found" -- so anyone could probe
     * an address and learn if it was registered. Every path now answers a missing account *identically* to the
     * ordinary failure for a real one.
     */
    "an unknown account is indistinguishable from an ordinary failure" {
        val client = mkClient("10.61.61.61")

        // A real account for the "known" side of each comparison.
        val known = "known@example.com"
        registerByCode(client, known, "knownuser")

        // 'Email me a code' answers success for both -- no 404 that would confirm the address is registered.
        // (The unknown one silently sends nothing; the point is that the response does not differ.)
        fun sendVerify(who: String): Map<String, Any?> {
            val token = tokenOf(client)
            return client.sendJsonPostRequest("/auth/user/sendVerify", mapOf("loginId" to who, "formAuthToken" to token))
        }
        sendVerify(known)[EP.status] shouldBe null           // success
        sendVerify("ghost@example.com")[EP.status] shouldBe null // *also* success, not a 404

        // Login by code with a wrong code: a real account and a missing one fail the same way -- same status
        // and same message. Before the fix the missing one was a 404 with a different message.
        fun loginWrongCode(who: String): Map<String, Any?> {
            val token = tokenOf(client)
            return client.sendJsonPostRequest(
                "/auth/login/byCode",
                mapOf("loginId" to who, "formAuthToken" to token, "verifyCode" to "WRONGCODE"),
            )
        }
        val realWrong = loginWrongCode(known)
        val ghostWrong = loginWrongCode("ghost@example.com")
        realWrong[EP.status] shouldBe EXC.badInput
        ghostWrong[EP.status] shouldBe realWrong[EP.status]           // same status
        ghostWrong[EP.errorMessage] shouldBe realWrong[EP.errorMessage] // same message
        ghostWrong[EP.errorMessage] shouldBe authMsg(AERR.codeIncorrect)
    }

    // The flow's long jump lives last: everything above runs within about an hour of the boot, and nothing
    // after it has to reason about a world thirty days on.
    "after thirty days device trust and an idle session both lapse -- and a password login never extended trust" {
        val client = mkClient("10.40.40.40", "Piper Browser")
        val contact = "piper@example.com"
        registerByCode(client, contact, "piper")
        activatePassword(client, cxt, contact, "piper", "sekret-pw-123")

        // Ten days on, the password still works from this familiar device. That login writes a *fresh* session
        // cookie (good for another thirty days) while deliberately not touching the device's trust expiry.
        travel(10.days)
        client.sendGetRequest("/logout")
        results(
            client.sendJsonPostRequest("/auth/login/byPassword", mapOf("loginId" to "piper", "password" to "sekret-pw-123")),
        )["publicName"] shouldBe "piper"

        // Twenty-one days further on: day 31 overall, so device trust (granted on day 0) has lapsed, but the
        // session minted on day 10 has not. Both halves matter.
        travel(21.days)
        results(client.sendJsonGetRequest("/auth/self/info"))["publicName"] shouldBe "piper" // session still good

        // The password is now refused from the very browser that just used it. Had the day-10 password login
        // extended device trust, trust would run to day 40, and this would still succeed -- so this is what
        // asserts the "rides trust but never grants it" invariant that AUTHC.deviceTrustMillis documents.
        client.sendGetRequest("/logout")
        pwLogin(client, "piper", "sekret-pw-123").rptStatusCode shouldBe EXC.authNeeded

        // A code login re-familiarizes the same device, and the password is usable again.
        val token = tokenOf(client)
        client.sendJsonPostRequest("/auth/user/sendVerify", mapOf("loginId" to "piper", "formAuthToken" to token))
        results(
            client.sendJsonPostRequest(
                "/auth/login/byCode",
                mapOf("loginId" to "piper", "formAuthToken" to token, "verifyCode" to codeFor(token, contact)),
            ),
        )["publicName"] shouldBe "piper"
        client.sendGetRequest("/logout")
        results(
            client.sendJsonPostRequest("/auth/login/byPassword", mapOf("loginId" to "piper", "password" to "sekret-pw-123")),
        )["publicName"] shouldBe "piper"

        // And the session jason opened in the first block -- untouched for thirty-one days, on an instance that
        // has been serving other users the whole time -- has quietly lapsed. Only a shared instance can see it.
        results(jasonBrowser.sendJsonGetRequest("/auth/self/info"))["authId"] shouldBe UserProfile.anonymousAuthId
    }

    // Its own instance: a different config cannot join the flow.
    "a sensitive error is obfuscated to a generic message where the deployment obfuscates (issue #108)" {
        // Boot with obfuscation on (a prod deployment has it on by default; here the config option forces it).
        val obfCxt = Startup.mkTestBootCxt("authObf", "authObfTest", mapOf(ACFG.obfuscateSensitiveErrors to true))
        val client = TestHttpClient(obfCxt.instanceConfig)
        val token = results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String

        // The exemplar is the "email already taken" error (sensitive): register an address, then register it
        // again. (This test used the noAccount error until issue #275 removed it; the remaining sensitive
        // message carries the demonstration.) Codes are computed via this instance's own key.
        val taken = "taken-obf@example.com"
        fun obfCode(t: String) = NodeService.get(obfCxt)!!.computeVerifyCode(t, taken)
        client.sendJsonPostRequest(
            "/auth/newContact/sendVerify", mapOf("contactAddress" to taken, "contactType" to "email", "formAuthToken" to token),
        )
        val userId = results(client.sendJsonPutRequest(
            "/auth/user/createInitial",
            mapOf("contactAddress" to taken, "contactType" to "email", "formAuthToken" to token, "verifyCode" to obfCode(token)),
        ))["userId"] as Long
        client.sendJsonPutRequest(
            "/auth/user/setLoginData",
            mapOf("userId" to userId, "username" to "takenobf", "formAuthToken" to token, "verifyCode" to obfCode(token)),
        )

        // Now the duplicate registration is refused with a *sensitive* error, which obfuscation replaces by the
        // generic message from errors.md -- so the address never appears on the wire.
        val token2 = results(client.sendJsonGetRequest("/auth/form/createToken"))["formAuthToken"] as String
        val obf = RequestHandler.obfuscatedErrorMsg
        val expected = MarkdownFragmentService.get(obfCxt)!!.resolveFragment(obfCxt, obf.fileId, obf.namespace, obf.key)
        val resp = client.sendJsonPutRequest(
            "/auth/user/createInitial",
            mapOf("contactAddress" to taken, "contactType" to "email", "formAuthToken" to token2,
                "verifyCode" to NodeService.get(obfCxt)!!.computeVerifyCode(token2, taken)),
        )
        resp[EP.errorMessage] shouldBe expected
        resp[EP.errorFromFragment] shouldBe true // still designed copy
        (resp[EP.errorMessage] as String).contains(taken) shouldBe false
    }
})
