package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Creating a user, and reading the person behind one, tells a caller nothing about the address's users in clients
 * they cannot see (issue #1298). Each case compares an address with a user in another client to one with none, and
 * requires the two answers to be equal: the status, and the message or the user with the address masked. Over the
 * shared instance, with clients of this issue's own.
 */
class AdminCreateIdentityLeakTest : StringSpec({
    val cxt = TestInstances.default("leak1298")
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("leak", client).also { it.userId = 12980L }

    fun defineClient(client: String) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        }
        GedraConfigService.get(cxt).writeConfig(asClient(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    val mine = "leakmine1298"
    val theirs = "leaktheirs1298"
    defineClient(mine)
    defineClient(theirs)
    val admin = TestUser.create(cxt, "admin@$mine.test", level = ROLE.admin, userClient = mine)
    /** An address with a user in the other client, and one with none anywhere. */
    val elsewhere = "pat@elsewhere1298.test"
    val nowhere = "kim@nowhere1298.test"
    TestUser.create(cxt, elsewhere, userClient = theirs)

    /** The status and message of the response [env], every value in `masked` replaced. */
    fun masked(env: Map<String, Any?>, masked: List<String>): Pair<Int, String> {
        var message = env[EP.errorMessage]?.toString().orEmpty()
        for (value in masked) message = message.replace(value, "<named>")
        return ((env[EP.status] as? Number)?.toInt() ?: 200) to message
    }

    "a username that holds an address is refused as no username before it is looked for, at sign-in" {
        // A placeholder username holds an address and a client: were "taken" asked first, anybody signing in could
        // ask it of `@<address>|<client>|...` and learn whether that address has a user there.
        val browser = TestHttpClient(cxt.instanceConfig)
        val address = "prober@leak1298.test"
        val token = browser.sendJsonGetRequest(AEP.createToken)[EP.results].toJsonMapOrEmpty()[AFLD.formAuthToken].toOptStr()!!
        browser.sendJsonPostRequest(
            AEP.newContactSendVerify,
            mapOf(AFLD.contactAddress to address, AFLD.contactType to "email", AFLD.formAuthToken to token),
        )
        val code = NodeService.get(cxt).computeVerifyCode(token, address)
        val userId = browser.sendJsonPutRequest(
            AEP.createInitial,
            mapOf(AFLD.contactAddress to address, AFLD.contactType to "email", AFLD.formAuthToken to token, AFLD.verifyCode to code),
        )[EP.results].toJsonMapOrEmpty()[AFLD.userId].toOptLong()!!
        val probes = listOf(elsewhere, nowhere).map { AuthUserRow.placeholderUsername(it, theirs, PERSONA.member, "") }
        fun attempt(username: String) = masked(
            browser.sendEditRequest(
                AEP.setLoginData, null,
                mapOf(AFLD.userId to userId, AFLD.username to username, AFLD.formAuthToken to token, AFLD.verifyCode to code),
                HttpMethod.PUT,
            ).rptResponseData?.jsonMap() ?: emptyMap(),
            probes,
        )
        val taken = attempt(probes[0])
        taken.first shouldNotBe 200
        attempt(probes[1]) shouldBe taken
    }

    "a public user's create at their own address says nothing about a username" {
        // Every `public` registrant administers their own users, so reaches the create: a username checked there
        // before the key would answer "taken" for one somebody holds and "that user exists" for a free one.
        val self = TestUser.register(cxt, "self@leak1298.test", "selfleak1298")
        TestUser.register(cxt, "other@leak1298.test", "otherleak1298")
        fun attempt(username: String) = masked(
            self.client.sendEditRequest(
                UADEP.userCreate, null,
                mapOf(ADF.primaryId to "self@leak1298.test", ADF.persona to PERSONA.member, ADF.username to username),
                HttpMethod.POST,
            ).rptResponseData?.jsonMap() ?: emptyMap(),
            listOf("otherleak1298", "freeleak1298"),
        )
        val held = attempt("otherleak1298")
        held.first shouldNotBe 200
        attempt("freeleak1298") shouldBe held
    }

    "a user created at an address is named and described alike, whatever the address has elsewhere" {
        fun created(address: String): Map<String, Any?> = admin.postData(UADEP.userCreate, mapOf(ADF.primaryId to address))
        val there = created(elsewhere)
        val fresh = created(nowhere)
        // One placeholder form for every user, never `@<address>` for an address's first.
        there[ADF.username] shouldBe AuthUserRow.placeholderUsername(elsewhere, mine, PERSONA.member, "")
        fresh[ADF.username] shouldBe AuthUserRow.placeholderUsername(nowhere, mine, PERSONA.member, "")
        // The person's password is theirs, set in whichever client: not said to a client administrator.
        there.keys shouldNotContain ADF.hasPassword
        there.keys shouldBe fresh.keys

        // Nor in the person view, which leaves out every fact of the identity's.
        fun person(user: Map<String, Any?>) = admin.getData(UADEP.userIdentity, mapOf(ADF.userId to user[ADF.userId].toString()))
        val thereView = person(there)
        thereView.keys shouldNotContain ADF.hasPassword
        thereView.keys shouldNotContain ADF.verifiedAt
        thereView.keys shouldNotContain ADF.signsInAsUserId
        thereView.keys shouldBe person(fresh).keys
    }

    "the identity's facts are told to a caller who sees every client, and to a person about themself" {
        val full = TestUser.createFullAdmin(cxt, "full@leak1298.test")
        val user = TestUser.create(cxt, "facts@leak1298.test", userClient = mine)
        val forFull = full.getData(UADEP.userIdentity, mapOf(ADF.userId to user.userId.toString()))
        forFull.keys shouldContain ADF.hasPassword
        forFull.keys shouldContain ADF.verifiedAt
        (forFull[ADF.users] as List<*>).map { it.toJsonMapOrEmpty() }.single().keys shouldContain ADF.hasPassword
        // A client administrator about their own identity.
        admin.getData(UADEP.userIdentity, mapOf(ADF.userId to admin.userId.toString())).keys shouldContain ADF.hasPassword
        admin.getData(UADEP.userIdentity, mapOf(ADF.userId to user.userId.toString())).keys shouldNotContain ADF.hasPassword
    }
})
