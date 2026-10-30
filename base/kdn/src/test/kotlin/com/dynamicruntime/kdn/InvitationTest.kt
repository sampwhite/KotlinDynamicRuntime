package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
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
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.RL
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UCF
import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.days

/**
 * Invitations (issue #751): an administrator provisions a user for an address that is not their own, the
 * person gets a mailed link, and opening it and accepting is the proof -- it registers the user, verifies a new
 * identity, and signs them in. Driven through the endpoints, reading the link back from the simulated mail sink.
 */
class InvitationTest : StringSpec({
    // Its own instance (issue #1075): it travels the instance clock, which is instance-wide.
    val cxt = Startup.mkTestBootCxt("invitation", "invitationTest")
    val users = UserService.get(cxt)

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp[EP.results]?.toJsonMap()
        ?: throw AssertionError("Expected a success but got ${resp[EP.status]}: ${resp[EP.errorMessage]}")

    /** The token in the last invitation mailed to [address], read the way the person would: out of the link. */
    fun tokenMailedTo(address: String): String {
        val text = MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text
        val marker = "${HMENU.inviteTokenParam}="
        return text.substringAfter(marker).takeWhile { !it.isWhitespace() }
    }

    fun accept(browser: TestHttpClient, token: String): Map<String, Any?> =
        browser.sendJsonPostRequest(AEP.invitationAccept, mapOf(AFLD.invitationToken to token))

    "an invitation to a fresh address, accepted, verifies the identity, registers the user and signs in" {
        val admin = TestUser.createFullAdmin(cxt, "invite-admin@other.test")
        val address = "invited-new@other.test"
        val made = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.persona to PERSONA.admin, ADF.client to CL.hub))
        val userId = made[ADF.userId] as Long
        // Unclaimed, and its identity unproven: nothing but the mailed link can change either.
        made[USF.registered.at].shouldBeNull()
        users.queryIdentityByAddress(cxt, address).shouldNotBeNull().verifiedAt.shouldBeNull()
        val mailed = MailService.get(cxt).lastEmailTo(address).shouldNotBeNull()
        mailed.text shouldContain "at Hub, as Admin"
        val token = tokenMailedTo(address)
        // The token rides the fragment (issue #1371), which reaches no server or proxy log when the link is opened.
        mailed.text shouldContain "/wa/${HMENU.pageInvite}#${HMENU.inviteTokenParam}=$token"

        // Merely opening the link previews and changes nothing.
        val browser = TestHttpClient(cxt.instanceConfig)
        val preview = results(browser.sendJsonPostRequest(AEP.invitationPreview, mapOf(AFLD.invitationToken to token)))
        preview[AFLD.email] shouldBe address
        preview[AFLD.client] shouldBe CL.hub
        preview[AFLD.persona] shouldBe PERSONA.admin
        users.queryByUserId(cxt, userId).shouldNotBeNull().isRegistered shouldBe false

        // Accepting is the claim: registered, the identity verified, and this browser signed in as the user.
        val info = results(accept(browser, token))
        info[UPF.userId] shouldBe userId
        info[UPF.client] shouldBe CL.hub
        users.queryByUserId(cxt, userId).shouldNotBeNull().isRegistered shouldBe true
        users.queryIdentityByAddress(cxt, address).shouldNotBeNull().verifiedAt.shouldNotBeNull()
        results(browser.sendJsonGetRequest(AEP.selfInfo))[UPF.userId] shouldBe userId
        // A mailed link is not a standing login: a second acceptance is refused.
        accept(TestHttpClient(cxt.instanceConfig), token)[EP.status] shouldBe EXC.badInput
    }

    "an expired or tampered link is refused" {
        val admin = TestUser.createFullAdmin(cxt, "invite-admin2@other.test")
        val address = "invited-late@other.test"
        admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address))
        val token = tokenMailedTo(address)
        // Tampered: not decryptable, so not an invitation.
        accept(TestHttpClient(cxt.instanceConfig), token.dropLast(4) + "AAAA")[EP.status] shouldBe EXC.badInput
        accept(TestHttpClient(cxt.instanceConfig), "not-a-token")[EP.status] shouldBe EXC.badInput
        // Expired: eight days on, the link has lapsed; the administrator re-sends one, and that one works.
        cxt.instanceConfig.clock.advanceBy(8.days)
        accept(TestHttpClient(cxt.instanceConfig), token)[EP.status] shouldBe EXC.badInput
        val userId = users.queryByPrimaryId(cxt, address).shouldNotBeNull().userId
        // Signed in again: the administrator's own session (two days, issue #1308) lapsed with the link.
        val later = TestUser.createFullAdmin(cxt, "invite-admin2@other.test")
        later.postData(ADEP.userInvite, mapOf(ADF.userId to userId))
        results(accept(TestHttpClient(cxt.instanceConfig), tokenMailedTo(address)))[UPF.userId] shouldBe userId
        // Once claimed, there is nothing to re-send.
        later.expectError(EXC.badInput, ADEP.userInvite, mapOf(ADF.userId to userId))
    }

    // Issue #1504: whether a create mails is the administrator's choice, and its answer says what happened.
    "a create invites by default, not when told not to, and never a disabled user" {
        val admin = TestUser.createFullAdmin(cxt, "invite-choice-admin@other.test")
        val mail = MailService.get(cxt)
        fun create(address: String, vararg more: Pair<String, Any?>) =
            admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.client to CL.hub) + more)

        val sent = "invite-default@other.test"
        create(sent)[ADF.invitationSent] shouldBe true
        mail.lastEmailTo(sent).shouldNotBeNull()

        val held = "invite-held@other.test"
        val heldUser = create(held, ADF.sendInvitation to false)
        heldUser[ADF.invitationSent] shouldBe false
        heldUser[USF.registered.at].shouldBeNull()
        mail.lastEmailTo(held).shouldBeNull()
        // Held back, not lost: the invitation can be sent later, and works.
        admin.postData(ADEP.userInvite, mapOf(ADF.userId to heldUser[ADF.userId]))
        results(accept(TestHttpClient(cxt.instanceConfig), tokenMailedTo(held)))[UPF.userId] shouldBe heldUser[ADF.userId]

        val disabled = "invite-disabled@other.test"
        create(disabled, ADF.enabled to false)[ADF.invitationSent] shouldBe false
        mail.lastEmailTo(disabled).shouldBeNull()
        // Asking for one does not override a disabled user.
        val disabledAsked = "invite-disabled-asked@other.test"
        create(disabledAsked, ADF.enabled to false, ADF.sendInvitation to true)[ADF.invitationSent] shouldBe false
        mail.lastEmailTo(disabledAsked).shouldBeNull()
    }

    "a claimed user can be reminded where to sign in, by a mail that signs nobody in" {
        val admin = TestUser.createFullAdmin(cxt, "remind-admin@other.test")
        val address = "remind-claimed@other.test"
        val claimed = TestUser.create(cxt, address, userClient = CL.hub)
        admin.postData(ADEP.userRemind, mapOf(ADF.userId to claimed.userId))
        val mailed = MailService.get(cxt).lastEmailTo(address).shouldNotBeNull()
        mailed.text shouldContain "/wa/${HMENU.pageLogin}"
        mailed.text shouldContain address
        // The ordinary sign-in page: no token, so holding the mail proves nothing.
        mailed.text shouldNotContain HMENU.inviteTokenParam
        mailed.text shouldNotContain HMENU.pageInvite
    }

    "a reminder is refused for anyone who could not use it, saying why" {
        val admin = TestUser.createFullAdmin(cxt, "remind-admin2@other.test")
        fun refusal(userId: Any?) = admin.expectError(EXC.badInput, ADEP.userRemind, mapOf(ADF.userId to userId))[EP.errorMessage].toString()

        val unclaimed = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to "remind-unclaimed@other.test", ADF.client to CL.hub))
        refusal(unclaimed[ADF.userId]) shouldContain "send an invitation instead"
        val off = TestUser.create(cxt, "remind-off@other.test", userClient = CL.hub)
        admin.postData(ADEP.userSetEnabled, mapOf(ADF.userId to off.userId, ADF.enabled to false))
        refusal(off.userId) shouldContain "is disabled"
        val gone = TestUser.create(cxt, "remind-gone@other.test", userClient = CL.hub)
        admin.client.sendDeleteRequest(ADEP.userDelete, mapOf(ADF.userId to gone.userId, ADF.permanent to true))
        refusal(gone.userId) shouldContain "deleted"

        val parent = "remindsbx"
        GedraConfigService.get(cxt).writeConfig(
            cxt.mkSubContext("setup", parent).also { it.userId = 15040L },
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
        val chief = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        // A user somebody opened in the sandbox is reached only through opening it...
        refusal(chief.postData(AEP.openSandbox, emptyMap())[UPF.userId]) shouldContain "opened in a sandbox"
        // ...where a sandbox's own claimed user signs in like any other.
        val tester = users.provisionUser(
            cxt, "remind-tester@other.test", sandboxOf(parent), listOf(ROLE.user), createdAt = cxt.now(), verifiedAt = cxt.now(), registered = true,
        )
        admin.postData(ADEP.userRemind, mapOf(ADF.userId to tester))
        MailService.get(cxt).lastEmailTo("remind-tester@other.test").shouldNotBeNull()
    }

    "a client administrator reminds the users they reach, and no others" {
        val chief = TestUser.create(cxt, "chief-remind@other.test", level = ROLE.admin, userClient = CL.hub)
        val colleague = TestUser.create(cxt, "colleague-remind@other.test", userClient = CL.hub)
        chief.postData(UADEP.userRemind, mapOf(ADF.userId to colleague.userId))
        MailService.get(cxt).lastEmailTo("colleague-remind@other.test").shouldNotBeNull().subject shouldContain "Your account at"
        // Another client's user reads as absent, and gets nothing.
        val outsider = TestUser.create(cxt, "outsider-remind@other.test")
        chief.expectError(EXC.notFound, UADEP.userRemind, mapOf(ADF.userId to outsider.userId))
        MailService.get(cxt).lastEmailTo("outsider-remind@other.test").shouldBeNull()
    }

    "the mail an administrator sends one address is limited, and a create that is refused says it sent none" {
        val admin = TestUser.createFullAdmin(cxt, "limit-admin@other.test")
        val address = "limited@other.test"
        val person = TestUser.create(cxt, address, userClient = CL.hub)
        repeat(RL.sendPerContactMax) { admin.postData(ADEP.userRemind, mapOf(ADF.userId to person.userId)) }
        admin.expectError(EXC.tooManyRequests, ADEP.userRemind, mapOf(ADF.userId to person.userId))
        // The address's limit reached, a further user at it is still made, and the answer does not claim a mail went.
        val made = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.client to CL.hub, ADF.persona to PERSONA.admin))
        made[ADF.invitationSent] shouldBe false
        made[ADF.userId].shouldNotBeNull()
    }

    "a create at the caller's own address mails nothing, and says so" {
        val address = "own-address@other.test"
        val admin = TestUser.createFullAdmin(cxt, address)
        val made = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.client to CL.hub, ADF.persona to PERSONA.member))
        made[ADF.invitationSent] shouldBe false
        MailService.get(cxt).lastEmailTo(address).shouldBeNull()
    }

    "a reminder needs the deployment's own URL, since a request's headers are the caller's to set" {
        val real = TestInstances.notTestInstance("remindNoUrl1504")
        real.instanceConfig.isTestInstance shouldBe false // guard the premise, or the next line proves nothing
        val realUsers = UserService.get(real)
        val person = realUsers.provisionUser(
            real, "remind-real-person@other.test", CL.hub, listOf(ROLE.user), createdAt = real.now(), verifiedAt = real.now(), registered = true,
        )
        // In process: such a node has no fixture to sign an administrator in with.
        val refused = shouldThrow<KdrException> { realUsers.authFormHandler.remindUser(real, realUsers.queryByUserId(real, person).shouldNotBeNull()) }
        refused.message.orEmpty() shouldContain "public URL"
    }

    "an invitation to a registered person attaches the user to them, and needs no code" {
        val admin = TestUser.createFullAdmin(cxt, "invite-admin3@other.test")
        val address = "invited-existing@other.test"
        val person = TestUser.create(cxt, address) // registered, verified, in public
        val made = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.client to CL.hub))
        val userId = made[ADF.userId] as Long
        // Unclaimed, so not yet the person's to switch into...
        person.getData(AEP.selfUsers)[AFLD.users].toJsonListOfMaps().map { it[UCF.userId] } shouldBe listOf(person.userId)
        // ...until they accept the link, from any browser, with no code involved.
        results(accept(TestHttpClient(cxt.instanceConfig), tokenMailedTo(address)))[UPF.userId] shouldBe userId
        person.getData(AEP.selfUsers)[AFLD.users].toJsonListOfMaps().map { it[UCF.userId] } shouldContain userId
    }

    "an allClients caller registers a new address straight into a client with a persona" {
        val admin = TestUser.createFullAdmin(cxt, "invite-admin4@other.test")
        val placed = TestUser.register(
            cxt, "placed-new@other.test", "placednew",
            userClient = CL.hub, persona = PERSONA.admin, personaSuffix = "Q", asClient = admin.client,
        )
        placed.selfClient() shouldBe CL.hub
        placed.userInfo[UPF.persona] shouldBe PERSONA.admin
        placed.selfRoles() shouldContain ROLE.admin
        users.queryByUserId(cxt, placed.userId).shouldNotBeNull().let {
            it.personaSuffix shouldBe "Q"
            it.isRegistered shouldBe true
        }
        // A client this node does not carry is refused, as the fixture refuses it.
        admin.expectError(EXC.badInput, AEP.createInitial, mapOf(
            AFLD.contactAddress to "placed-nowhere@other.test", AFLD.contactType to "email",
            AFLD.formAuthToken to "x", AFLD.verifyCode to "y", AFLD.client to "nosuch",
        ), method = com.dynamicruntime.common.endpoint.HttpMethod.PUT)
    }

    "anyone else naming a client, persona or personaSuffix on registration is refused" {
        val ordinary = TestUser.create(cxt, "invite-ordinary@other.test")
        // A real form token, so the refusal is about the placing and not about the token.
        val token = ordinary.getData(AEP.createToken)[AFLD.formAuthToken] as String
        val refused = ordinary.expectError(EXC.badInput, AEP.createInitial, mapOf(
            AFLD.contactAddress to "placed-refused@other.test", AFLD.contactType to "email",
            AFLD.formAuthToken to token, AFLD.verifyCode to "y", AFLD.persona to PERSONA.admin,
        ), method = com.dynamicruntime.common.endpoint.HttpMethod.PUT)
        (refused[EP.errorMessage] as String) shouldContain ROLE.allClients
    }

    // `auth` is not a gated section, so nothing refreshes a caller's roles there: the capability has to be read as it
    // is now, or one taken away would keep placing users for as long as the session cookie lived.
    "a placing caller's capability is read as it is now, not as the session cookie recorded it" {
        val admin = TestUser.createFullAdmin(cxt, "invite-revoked@other.test")
        val row = users.queryByUserId(cxt, admin.userId).shouldNotBeNull()
        row.roles = listOf(ROLE.user, ROLE.admin)
        users.updateUser(cxt, row)
        val token = admin.getData(AEP.createToken)[AFLD.formAuthToken] as String
        val refused = admin.expectError(EXC.badInput, AEP.createInitial, mapOf(
            AFLD.contactAddress to "placed-revoked@other.test", AFLD.contactType to "email",
            AFLD.formAuthToken to token, AFLD.verifyCode to "y", AFLD.client to CL.hub,
        ), method = com.dynamicruntime.common.endpoint.HttpMethod.PUT)
        (refused[EP.errorMessage] as String) shouldContain ROLE.allClients
    }
})

/**
 * Claiming an account created for you from the login page (issue #751): the address, client, and persona name
 * the user, the mailed code proves the address *and* the key, and the page is no oracle -- the send always
 * answers as a success, and the mail says what matched.
 */
class ClaimAccountTest : StringSpec({
    val cxt = TestInstances.default("claim")
    val users = UserService.get(cxt)
    val node = com.dynamicruntime.common.node.NodeService.get(cxt)

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp[EP.results]?.toJsonMap()
        ?: throw AssertionError("Expected a success but got ${resp[EP.status]}: ${resp[EP.errorMessage]}")

    fun tokenOf(browser: TestHttpClient): String = results(browser.sendJsonGetRequest(AEP.createToken))[AFLD.formAuthToken] as String

    fun sendClaim(browser: TestHttpClient, token: String, address: String, client: String?, persona: String?, personaSuffix: String? = null) =
        browser.sendJsonPostRequest(AEP.claimSendVerify, buildMap {
            put(AFLD.contactAddress, address); put(AFLD.formAuthToken, token)
            client?.let { put(AFLD.client, it) }; persona?.let { put(AFLD.persona, it) }; personaSuffix?.let { put(AFLD.personaSuffix, it) }
        })

    fun claim(browser: TestHttpClient, token: String, code: String, address: String, client: String?, persona: String?, personaSuffix: String? = null) =
        browser.sendJsonPostRequest(AEP.claimAccount, buildMap {
            put(AFLD.contactAddress, address); put(AFLD.formAuthToken, token); put(AFLD.verifyCode, code)
            client?.let { put(AFLD.client, it) }; persona?.let { put(AFLD.persona, it) }; personaSuffix?.let { put(AFLD.personaSuffix, it) }
        })

    /** The code the server mailed, read from the mail the way the person would. */
    fun codeMailedTo(address: String): String {
        val text = MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text
        return Regex("code for claiming the account .* is (\\S+?)\\.").find(text).shouldNotBeNull().groupValues[1]
    }

    "an invited user is claimed from the login page with the address, client and persona, and a mailed code" {
        val admin = TestUser.createFullAdmin(cxt, "claim-admin@other.test")
        val address = "claim-me@other.test"
        val userId = admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.client to CL.hub, ADF.persona to PERSONA.admin))[ADF.userId] as Long
        // The invitation mail spells out the recipe for this page.
        MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text shouldContain "- Client: ${CL.hub}\n- Persona: ${PERSONA.admin}"

        val browser = TestHttpClient(cxt.instanceConfig)
        val token = tokenOf(browser)
        sendClaim(browser, token, address, CL.hub, PERSONA.admin)[EP.status].shouldBeNull()
        val code = codeMailedTo(address)
        // The code is bound to the key: the same code against another persona is a wrong code.
        claim(browser, token, code, address, CL.hub, PERSONA.member)[EP.status] shouldBe EXC.badInput
        val info = results(claim(browser, token, code, address, CL.hub, PERSONA.admin))
        info[UPF.userId] shouldBe userId
        users.queryByUserId(cxt, userId).shouldNotBeNull().isRegistered shouldBe true
        users.queryIdentityByAddress(cxt, address).shouldNotBeNull().verifiedAt.shouldNotBeNull()
        results(browser.sendJsonGetRequest(AEP.selfInfo))[UPF.userId] shouldBe userId
    }

    "a claim that matches nothing still answers as sent, and the mail says what it can" {
        val admin = TestUser.createFullAdmin(cxt, "claim-admin2@other.test")
        val address = "claim-none@other.test"
        val browser = TestHttpClient(cxt.instanceConfig)
        // No account at all: the page is told the code is on its way; the inbox is told nothing was created.
        sendClaim(browser, tokenOf(browser), address, CL.hub, PERSONA.admin)[EP.status].shouldBeNull()
        MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text shouldContain "could not find an account"
        // A user in that client under another persona: the mail acknowledges the client and points at the persona.
        admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.client to CL.hub))
        sendClaim(browser, tokenOf(browser), address, CL.hub, PERSONA.admin)[EP.status].shouldBeNull()
        MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text shouldContain "does have an account in that client"
        // And a code for a key with no user behind it registers nobody, however it was obtained.
        val token = tokenOf(browser)
        val key = listOf(address, CL.hub, PERSONA.admin, "").joinToString("|")
        claim(browser, token, node.computeVerifyCode(token, key), address, CL.hub, PERSONA.admin)[EP.status] shouldBe EXC.badInput
    }

    "what is typed reaches the mail only when it is an id" {
        val address = "claim-echo@other.test"
        val browser = TestHttpClient(cxt.instanceConfig)
        // A stranger's sentence in the client field must not be mailed to the address in our name: the mail says
        // only that nothing matched, and names none of what was typed.
        val planted = "support. Call 555-0100 to restore your account"
        sendClaim(browser, tokenOf(browser), address, planted, PERSONA.admin)[EP.status].shouldBeNull()
        val text = MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text
        text shouldContain "could not find an account"
        (text.contains("555-0100")) shouldBe false
        // A pipe in a part is not an id either, so it cannot misalign the key it once was joined into.
        sendClaim(browser, tokenOf(browser), address, "hub|x", PERSONA.admin)[EP.status].shouldBeNull()
        (MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text.contains("hub|x")) shouldBe false
    }

    "the defaults apply, and a claimed user is simply logged into" {
        val address = "claim-default@other.test"
        val person = TestUser.create(cxt, address) // registered, in public, a member: the defaults name it
        val browser = TestHttpClient(cxt.instanceConfig)
        val token = tokenOf(browser)
        sendClaim(browser, token, address, client = null, persona = null)[EP.status].shouldBeNull()
        results(claim(browser, token, codeMailedTo(address), address, client = null, persona = null))[UPF.userId] shouldBe person.userId
    }
})
