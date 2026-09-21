package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UCF
import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.days

/**
 * Invitations (issue #751): an administrator provisions a user for an address that is not their own, the
 * person gets a mailed link, and opening it and accepting is the proof -- it registers the user, verifies a new
 * identity, and signs them in. Driven through the endpoints, reading the link back from the simulated mail sink.
 */
class InvitationTest : StringSpec({
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
        mailed.text shouldContain "'${CL.hub}' as Admin"
        val token = tokenMailedTo(address)

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
        admin.postData(ADEP.userInvite, mapOf(ADF.userId to userId))
        results(accept(TestHttpClient(cxt.instanceConfig), tokenMailedTo(address)))[UPF.userId] shouldBe userId
        // Once claimed, there is nothing to re-send.
        admin.expectError(EXC.badInput, ADEP.userInvite, mapOf(ADF.userId to userId))
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
            userClient = CL.hub, persona = PERSONA.admin, personId = "Q", asClient = admin.client,
        )
        placed.selfClient() shouldBe CL.hub
        placed.userInfo[UPF.persona] shouldBe PERSONA.admin
        placed.selfRoles() shouldContain ROLE.admin
        users.queryByUserId(cxt, placed.userId).shouldNotBeNull().let {
            it.personId shouldBe "Q"
            it.isRegistered shouldBe true
        }
        // A client this node does not carry is refused, as the fixture refuses it.
        admin.expectError(EXC.badInput, AEP.createInitial, mapOf(
            AFLD.contactAddress to "placed-nowhere@other.test", AFLD.contactType to "email",
            AFLD.formAuthToken to "x", AFLD.verifyCode to "y", AFLD.client to "nosuch",
        ), method = com.dynamicruntime.common.endpoint.HttpMethod.PUT)
    }

    "anyone else naming a client, persona or personId on registration is refused" {
        val ordinary = TestUser.create(cxt, "invite-ordinary@other.test")
        // A real form token, so the refusal is about the placing and not about the token.
        val token = ordinary.getData(AEP.createToken)[AFLD.formAuthToken] as String
        val refused = ordinary.expectError(EXC.badInput, AEP.createInitial, mapOf(
            AFLD.contactAddress to "placed-refused@other.test", AFLD.contactType to "email",
            AFLD.formAuthToken to token, AFLD.verifyCode to "y", AFLD.persona to PERSONA.admin,
        ), method = com.dynamicruntime.common.endpoint.HttpMethod.PUT)
        (refused[EP.errorMessage] as String) shouldContain ROLE.allClients
    }
})

/**
 * Claiming an account created for you from the login page (issue #751): the address, client and persona name
 * the user, the mailed code proves the address *and* the key, and the page is no oracle -- the send always
 * answers as a success, and the mail says what matched.
 */
class ClaimAccountTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("claim", "claimAccountTest")
    val users = UserService.get(cxt)
    val node = com.dynamicruntime.common.node.NodeService.get(cxt)

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp[EP.results]?.toJsonMap()
        ?: throw AssertionError("Expected a success but got ${resp[EP.status]}: ${resp[EP.errorMessage]}")

    fun tokenOf(browser: TestHttpClient): String = results(browser.sendJsonGetRequest(AEP.createToken))[AFLD.formAuthToken] as String

    fun sendClaim(browser: TestHttpClient, token: String, address: String, client: String?, persona: String?, personId: String? = null) =
        browser.sendJsonPostRequest(AEP.claimSendVerify, buildMap {
            put(AFLD.contactAddress, address); put(AFLD.formAuthToken, token)
            client?.let { put(AFLD.client, it) }; persona?.let { put(AFLD.persona, it) }; personId?.let { put(AFLD.personId, it) }
        })

    fun claim(browser: TestHttpClient, token: String, code: String, address: String, client: String?, persona: String?, personId: String? = null) =
        browser.sendJsonPostRequest(AEP.claimAccount, buildMap {
            put(AFLD.contactAddress, address); put(AFLD.formAuthToken, token); put(AFLD.verifyCode, code)
            client?.let { put(AFLD.client, it) }; persona?.let { put(AFLD.persona, it) }; personId?.let { put(AFLD.personId, it) }
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
        MailService.get(cxt).lastEmailTo(address).shouldNotBeNull().text shouldContain "client \"${CL.hub}\" and persona \"${PERSONA.admin}\""

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

    "the defaults apply, and a claimed user is simply logged into" {
        val address = "claim-default@other.test"
        val person = TestUser.create(cxt, address) // registered, in public, a member: the defaults name it
        val browser = TestHttpClient(cxt.instanceConfig)
        val token = tokenOf(browser)
        sendClaim(browser, token, address, client = null, persona = null)[EP.status].shouldBeNull()
        results(claim(browser, token, codeMailedTo(address), address, client = null, persona = null))[UPF.userId] shouldBe person.userId
    }
})
