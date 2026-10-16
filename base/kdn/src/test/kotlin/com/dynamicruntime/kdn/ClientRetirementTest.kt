package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Retiring a client and bringing it back (issue #1111): its data kept, the client absent everywhere while retired --
 * its users unable to sign in, their open sessions no longer honored -- and as it was once brought back. Over the
 * shared instance, with clients of this issue's own.
 */
class ClientRetirementTest : StringSpec({
    val cxt = TestInstances.default("retire1111")
    val operator = TestUser.createFullAdmin(cxt, "operator@retire1111.test")

    fun createBody(clientId: String) = mapOf(
        CLD.clientId to clientId, CLD.name to "Created $clientId", CLD.usageType to ClientUsageType.dev.name,
        CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
    )
    fun retire(client: String, retired: Boolean = true) =
        operator.postData(ADEP.clientRetirementSet, mapOf(CLD.client to client, CLD.retired to retired))
    /**
     * Signs [email] in by verification code from a fresh browser, as a person would -- reaching the login's own gate,
     * which the fixture's create does not when it names a client -- and returns the response envelope.
     */
    fun loginByCode(email: String): Map<String, Any?> {
        val browser = TestHttpClient(cxt.instanceConfig)
        val token = browser.sendJsonGetRequest(AEP.createToken)[EP.results].toJsonMapOrEmpty()[AFLD.formAuthToken].toOptStr()!!
        browser.sendJsonPostRequest(AEP.userSendVerify, mapOf(AFLD.loginId to email, AFLD.formAuthToken to token))
        val code = NodeService.get(cxt).computeVerifyCode(token, email)
        return browser.sendJsonPostRequest(
            AEP.loginByCode, mapOf(AFLD.loginId to email, AFLD.formAuthToken to token, AFLD.verifyCode to code),
        )
    }
    fun refusal(client: String): String =
        operator.expectError(EXC.badInput, ADEP.clientRetirementSet, mapOf(CLD.client to client, CLD.retired to true)).toString()

    "a retired client is absent, its users kept out, and it comes back as it was" {
        val client = "ret1111"
        operator.postData(ADEP.clientCreate, createBody(client))[CLD.present] shouldBe true
        val email = "member@$client.test"
        val member = TestUser.create(cxt, email, userClient = client)
        member.selfClient() shouldBe client

        val retired = retire(client)
        retired[CLD.retired] shouldBe true
        retired[CLD.present] shouldBe false
        ClientService.get(cxt).known(client).shouldNotBeNull().retired shouldBe true
        // Listed as what it is.
        operator.getItems(ADEP.clientSummaries, mapOf(CLD.allKnown to true))
            .single { it[CLD.clientId] == client }[CLD.status] shouldBe ClientStatus.retired.name
        // The open session is no longer honored, and signing in again is refused.
        member.selfClient() shouldBe CL.public
        loginByCode(email)[EP.errorMessage].toOptStr().orEmpty() shouldContain "The account's client '$client' has been retired"

        // Brought back: present, with the environments it had, and the same session honored again.
        retire(client, retired = false)[CLD.present] shouldBe true
        ClientService.get(cxt).present(client).shouldNotBeNull().enabledEnvironments shouldBe setOf(ENV.unit, ENV.local)
        member.selfClient() shouldBe client
        // Setting what it already is changes nothing.
        retire(client, retired = false)[CLD.retired] shouldBe false
    }

    "a person whose last-used user is in a retired client signs in as another of their users" {
        val kept = "kept1111"
        val gone = "gone1111"
        operator.postData(ADEP.clientCreate, createBody(kept))
        operator.postData(ADEP.clientCreate, createBody(gone))
        val email = "both@both1111.test"
        TestUser.create(cxt, email, userClient = kept)
        // Made last, so it is the user the person last acted as.
        TestUser.create(cxt, email, userClient = gone)
        retire(gone)

        loginByCode(email)[EP.results].toJsonMapOrEmpty()[UPF.client] shouldBe kept
    }

    "a source client, a sandbox, and a customer's root are not retired here" {
        refusal(CL.hub) shouldContain "is defined in source code, so it is retired there"
        refusal(sandboxOf("ret1111")) shouldContain "it is retired with its parent"
        val root = "rootco1111"
        operator.postData(ADEP.clientCreate, createBody(root) + (CLD.newCustomer to mapOf(CUST.name to "Rootco")))
        refusal(root) shouldContain "is the root of the customer '$root'; name another root before retiring it"
        ClientService.get(cxt).isPresent(root) shouldBe true
        // The Clients overview says it is a root (issue #1182), so the page offers no retirement that could only fail.
        val rows = operator.getItems(UADEP.clientsOverview).associateBy { it[CLD.clientId] }
        rows.getValue(root)[CLD.rootOf] shouldBe root
    }

    "retiring a client is the platform's" {
        val client = "own1111"
        operator.postData(ADEP.clientCreate, createBody(client))
        val clientAdmin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
        clientAdmin.expectError(EXC.notAuthorized, ADEP.clientRetirementSet, mapOf(CLD.client to client, CLD.retired to true))
        ClientService.get(cxt).isPresent(client) shouldBe true
    }
})
