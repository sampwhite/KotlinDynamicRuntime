package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ACCT
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFEAT
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AKEY
import com.dynamicruntime.common.user.AccountHistory
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.defaultDisplayLen
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.minutes

/**
 * The signed-in user's profile (issue #1739 gathered these): setting its name, and its own history on the profile config
 * (issue #1480). Each part below is one of the specs that used to be a file of its own, kept whole.
 */
class ProfileTest : StringSpec({
    include(profileNameTests)
    include(profileAccountTests)
})

/**
 * Setting the name you are shown under (issue #323).
 *
 * The gap this closes is narrow but user-visible: `name` had writers at registration and in the admin console
 * and none for the account itself, so a user provisioned without one was displayed by their **email address**
 * with no way to change it. That is the state every `becomeUser` account is in.
 *
 * The call is session-authorized with no verification code, unlike the password calls beside it, and the tests
 * below pin both halves of that choice: a logged-in user may rename themselves, and a logged-out one may not.
 */
private val profileNameTests = stringSpec {

    "a user sets the name they are shown under, and it survives the round trip" {
        val cxt = TestInstances.default("profileName")
        val user = TestUser.create(cxt, "profile-name@example.com")

        // Before: no name, so the display falls back to the login name -- which for a provisioned user with a
        // placeholder username is their email address. That is the symptom in the issue.
        val before = user.getData(AEP.profileUiConfig)
        before.toString().contains("profile-name@example.com") shouldBe true

        val info = user.postData(AEP.profileSetName, mapOf(AFLD.name to "Grace Hopper"))
        info[UPF.name] shouldBe "Grace Hopper"

        // Durable: a fresh read of who I am carries it, rather than only the response that set it.
        user.getData(AEP.selfInfo)[UPF.name] shouldBe "Grace Hopper"
    }

    "a blank name clears it rather than being rejected as an empty form" {
        val cxt = TestInstances.default("profileNameClear")
        val user = TestUser.create(cxt, "profile-clear@example.com")

        user.postData(AEP.profileSetName, mapOf(AFLD.name to "Temporary"))[UPF.name] shouldBe "Temporary"
        // Clearing is a legitimate thing to want: the display falls back to the login name.
        val cleared = user.postData(AEP.profileSetName, mapOf(AFLD.name to "  "))
        cleared.containsKey(UPF.name) shouldBe false
    }

    /** Whitespace is trimmed on the way in, so a name never carries padding into a heading or the app bar. */
    "the stored name is trimmed" {
        val cxt = TestInstances.default("profileNameTrim")
        val user = TestUser.create(cxt, "profile-trim@example.com")
        user.postData(AEP.profileSetName, mapOf(AFLD.name to "  Ada Lovelace  "))[UPF.name] shouldBe "Ada Lovelace"
    }

    /**
     * Capped by the input schema rather than by hand, at the length past which a display would truncate it
     * anyway. The point is that an over-long value is *refused* rather than silently stored and then cut off
     * wherever it happens to be rendered.
     */
    "an over-long name is refused by validation" {
        val cxt = TestInstances.default("profileNameLong")
        val user = TestUser.create(cxt, "profile-long@example.com")
        val tooLong = "x".repeat(defaultDisplayLen + 1)
        user.expectError(EXC.badInput, AEP.profileSetName, mapOf(AFLD.name to tooLong))
        // And the one that just fits is accepted, so the boundary is where it claims to be.
        val atLimit = "y".repeat(defaultDisplayLen)
        user.postData(AEP.profileSetName, mapOf(AFLD.name to atLimit))[UPF.name] shouldBe atLimit
    }

    /**
     * A name is display copy, not an identifier, so two accounts may share one -- unlike `username`, which
     * carries a unique index. This is why the endpoint needs no collision handling at all.
     */
    "two users may hold the same name" {
        val cxt = TestInstances.default("profileNameDup")
        TestUser.create(cxt, "profile-dup-a@example.com")
            .postData(AEP.profileSetName, mapOf(AFLD.name to "Alex Taylor"))[UPF.name] shouldBe "Alex Taylor"
        TestUser.create(cxt, "profile-dup-b@example.com")
            .postData(AEP.profileSetName, mapOf(AFLD.name to "Alex Taylor"))[UPF.name] shouldBe "Alex Taylor"
    }

    /**
     * The session is the authority, so no session is no rename. It answers **401**, not 403: there is nothing
     * to authorize, the caller simply is not signed in -- which is also what tells the frontend to send them to
     * the login page rather than show a refusal.
     */
    "a logged-out caller cannot set a name" {
        val cxt = TestInstances.default("profileNameAnon")
        val anon = TestHttpClient(cxt.instanceConfig)
        val resp = anon.sendJsonPostRequest(AEP.profileSetName, mapOf(AFLD.name to "Nobody"))
        (resp[EP.status] as? Number)?.toInt() shouldBe EXC.authNeeded
    }
}

/**
 * The signed-in user's own history on the profile config (issue #1480): when the user was registered, the sign-in
 * before this one, and when its person's address was proven -- the caller's own row and identity, and nothing of the
 * person's other users. A switch between users is not a sign-in, so it moves neither stamp. The config is a session's
 * only: an API key is refused it. The instance validates every response against its schema, so each read also pins
 * that the history is declared as it is sent.
 *
 * An invited user nobody has claimed cannot sign in to read it (accepting the invitation is the claim), so the invited
 * case reads it at the acceptance.
 */
private val profileAccountTests = stringSpec {
    // Its own instance: it moves the instance clock, which is instance-wide.
    val cxt = Startup.mkTestBootCxt("profileAccount1480", "profileAccountTest")
    val users = UserService.get(cxt)

    /** The profile config's state and features, as [browser] reads them. */
    fun configOf(browser: TestHttpClient): Pair<Map<String, Any?>, Map<String, Any?>> {
        val data = browser.sendJsonGetRequest(AEP.profileUiConfig)[EP.results].toJsonMapOrEmpty()
        return data[UIC.state].toJsonMapOrEmpty() to data[UIC.features].toJsonMapOrEmpty()
    }

    /** The history in a profile config's [state], read as the page reads it. */
    fun historyIn(state: Map<String, Any?>): AccountHistory = AccountHistory.fromInfo(state[AFLD.account].toJsonMapOrEmpty())

    /** The history in [user]'s profile config. */
    fun historyOf(user: TestUser): AccountHistory = historyIn(configOf(user.client).first)

    "a registered user's history is their row's registration and their own identity's proof, with no sign-in before" {
        val email = "acct-registered@example.com"
        val user = TestUser.register(cxt, email, "acctRegistered")
        val (state, features) = configOf(user.client)
        val history = historyIn(state)
        val row = users.queryByUserId(cxt, user.userId).shouldNotBeNull()
        history.registeredAt.shouldNotBeNull() shouldBe row.registeredAt
        // The proof is this user's identity's -- the code the registration used -- not some other row's.
        history.verifiedAt.shouldNotBeNull() shouldBe users.identityOfUser(cxt, row).verifiedAt
        // The first sign-in has none before it.
        history.previousLoggedInAt.shouldBeNull()
        // No password was set on the way: the page says so from its features, beside the history.
        features[AFEAT.hasPassword] shouldBe false

        // Signing in again moves the last sign-in to the previous one.
        val firstSignIn = row.lastLoggedInAt.shouldNotBeNull()
        cxt.instanceConfig.clock.advanceBy(1.minutes)
        val again = TestUser.create(cxt, email)
        again.userId shouldBe user.userId
        historyOf(again).previousLoggedInAt shouldBe firstSignIn
        users.queryByUserId(cxt, user.userId).shouldNotBeNull().lastLoggedInAt.shouldNotBeNull() shouldBeGreaterThan firstSignIn
    }

    "an invited user's acceptance registers them and proves their address" {
        val admin = TestUser.createFullAdmin(cxt, "acct-admin@other.test")
        val address = "acct-invited@other.test"
        val userId = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.persona to PERSONA.member, ADF.client to CL.hub))[ADF.userId]
            .toOptLong().shouldNotBeNull()
        users.queryByUserId(cxt, userId).shouldNotBeNull().registeredAt.shouldBeNull()
        val text = MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text
        val token = text.substringAfter("${HMENU.inviteTokenParam}=").takeWhile { !it.isWhitespace() }

        // Accepting is the claim, and signs this browser in as the user.
        val browser = TestHttpClient(cxt.instanceConfig)
        browser.sendJsonPostRequest(AEP.invitationAccept, mapOf(AFLD.invitationToken to token))[EP.status].shouldBeNull()
        val history = historyIn(configOf(browser).first)
        val row = users.queryByUserId(cxt, userId).shouldNotBeNull()
        history.registeredAt.shouldNotBeNull() shouldBe row.registeredAt
        history.verifiedAt.shouldNotBeNull() shouldBe users.identityOfUser(cxt, row).verifiedAt
        history.previousLoggedInAt.shouldBeNull()
    }

    "a sandbox user's history is its own, and opening and leaving the sandbox is no sign-in" {
        val parent = "acctsbx"
        val setup = cxt.mkSubContext("setup", parent).also { it.userId = 14800L }
        GedraConfigService.get(cxt).writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(parent), parent) {
                defineClient(
                    ClientDef(
                        clientId = parent, name = "Client $parent", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                    ),
                )
            },
        )
        GedraConfigReload.reloadClient(cxt, parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val parentHistory = historyOf(admin)
        val parentSignIn = users.queryByUserId(cxt, admin.userId).shouldNotBeNull().lastLoggedInAt.shouldNotBeNull()

        cxt.instanceConfig.clock.advanceBy(1.minutes)
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        sandboxUserId shouldNotBe admin.userId
        val (state, _) = configOf(admin.client)
        state[AFLD.userInfo].toJsonMapOrEmpty()[UPF.client] shouldBe sandboxOf(parent)
        val history = historyIn(state)
        val row = users.queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        // Its own row's registration -- made as the sandbox opened, after the parent user's -- and the same person's proof.
        history.registeredAt.shouldNotBeNull() shouldBe row.registeredAt
        history.registeredAt shouldNotBe parentHistory.registeredAt
        history.verifiedAt.shouldNotBeNull() shouldBe parentHistory.verifiedAt
        // Opening the sandbox is a session change, not a sign-in: the sandbox user has none, before or last.
        history.previousLoggedInAt.shouldBeNull()
        row.lastLoggedInAt.shouldBeNull()

        // Leaving is no sign-in either: the parent user's stamps are as they were.
        admin.postData(AEP.leaveSandbox, mapOf(AFLD.userId to admin.userId))
        val back = users.queryByUserId(cxt, admin.userId).shouldNotBeNull()
        back.lastLoggedInAt shouldBe parentSignIn
        historyOf(admin).previousLoggedInAt shouldBe parentHistory.previousLoggedInAt
    }

    "the profile config is a session's only: an API key is refused it" {
        val user = TestUser.create(cxt, "acct-keyed@example.com")
        val secret = user.postData(AKEY.selfCreate, mapOf(AKEY.name to "script", AKEY.access to AKEY.read))[AKEY.secret]
            .toOptStr().shouldNotBeNull()
        val keyed = TestHttpClient(cxt.instanceConfig).apply { setHeader(AKEY.authorizationHeader, "${AKEY.bearerScheme}$secret") }
        val refused = keyed.sendJsonGetRequest(AEP.profileUiConfig)
        refused[EP.status].shouldNotBeNull()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "not open to API keys"
        refused[EP.results].shouldBeNull()
        // The history field travels under its kernel name to a session, as the cases above read it.
        configOf(user.client).first[AFLD.account].toJsonMapOrEmpty().keys shouldBe setOf(ACCT.registeredAt, ACCT.verifiedAt)
    }
}
