package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The assistant offered to self-serve clients (issue #1568): where a deployment switches it on for them
 * ([AENV.selfServe]), a self-serve client's administrator switches it on for their client, acknowledging the data
 * notice, at the self-serve budget -- and nobody else's client, and no client an operator has set, is offered it. An
 * instance of its own, since the switch is a deployment's setting.
 */
class AssistantSelfServeTest : StringSpec({
    val budget = 1_234_567L
    fun boot(name: String, selfServe: Boolean, withModel: Boolean = true): KdrCxt = Startup.mkTestBootCxt(
        name, name,
        buildMap {
            if (withModel) put(AssistantService.modelConfigKey, ScriptedModel(emptyList()))
            if (selfServe) {
                put(AENV.selfServe.name, "true")
                put(AENV.selfServeBudget.name, budget.toString())
            }
        },
        additionalComponents = listOf(AssistantComponent()),
    )
    val cxt = boot("assistantSelfServe1568", selfServe = true)
    val service = AssistantService.get(cxt)

    /** A person's own client, made from `public`, and the person: now its administrator there. */
    fun ownClient(on: KdrCxt, email: String): Pair<String, TestUser> {
        val person = TestUser.create(on, email)
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Shop"))[CLD.client].toOptStr().shouldNotBeNull()
        return client to person
    }

    fun menuIds(user: TestUser): List<Any?> =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps().map { it[HFLD.id] }

    fun cardIds(user: TestUser): List<Any?> =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HCARD.cards].toJsonListOfMaps().map { it[HFLD.id] }

    "Home shows the assistant: to a public visitor as the reason to make a client, then to its administrator (issue #1636)" {
        val person = TestUser.create(cxt, "cards@asst1636.test")
        // Signed in to public, on a deployment that offers it to self-serve clients: the card that says so.
        cardIds(person) shouldBe listOf(ASST.createHomeCard)
        person.postData(AEP.createOwnClient, mapOf(CLD.name to "Cards"))[CLD.client].toOptStr().shouldNotBeNull()
        // In the client made: the assistant's card, ahead of core's for administering it, offered as its menu entry is.
        cardIds(person) shouldBe listOf(ASST.homeCard, HCARD.administer)
        menuIds(person) shouldContain ASST.menuItem
        // An ordinary member of a client is offered neither.
        cardIds(TestUser.create(cxt, "member@asst1636.test", userClient = CL.hub)) shouldBe emptyList()
    }

    "a self-serve client's administrator switches the assistant on, acknowledging the data notice, at the self-serve budget" {
        val (client, admin) = ownClient(cxt, "maker@asst1568.test")
        admin.getData(ACNV.statusPath).let {
            it[ACNV.on] shouldBe false
            it[ACNV.offered] shouldBe true
            // The name the person gave it, not its generated id.
            it[ACNV.clientName] shouldBe "Shop"
        }
        // The page is offered, to switch it on from; a message is still refused until it is on.
        menuIds(admin) shouldContain ASST.menuItem
        admin.expectError(EXC.notAuthorized, ACNV.messagePath, mapOf(ACNV.clientMessageId to "m1", ACNV.text to "Hi"))
            .toString() shouldContain ACNV.notOnCode
        // Not by a member or an editor of the client: the section is its administrators'.
        val member = TestUser.create(cxt, "member@asst1568.test", userClient = client)
        val editor = TestUser.create(cxt, "editor@asst1568.test", capabilities = listOf(ROLE.editor), userClient = client)
        for (other in listOf(member, editor)) {
            other.userInfo[UPF.client] shouldBe client
            other.expectError(EXC.notAuthorized, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
        }
        // Not without acknowledging what is sent, and a refusal writes nothing.
        admin.expectError(EXC.badInput, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to false))
            .toString() shouldContain ASST.acknowledgeDataNotice
        service.clientRow(cxt, client) shouldBe null
        val status = admin.postData(ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
        status[ACNV.on] shouldBe true
        status[ACNV.offered] shouldBe false
        // Recorded as an operator's switch is, with the self-serve budget, which an operator may change after.
        val row = service.clientRow(cxt, client).shouldNotBeNull()
        row[ASST.monthlyBudget].toOptLong() shouldBe budget
        row[ASST.noticeAcknowledgedBy].toOptLong() shouldBe admin.getData(AEP.selfInfo)[UPF.userId].toOptLong()
        menuIds(admin) shouldContain ASST.menuItem
        // Once on, there is nothing to switch on: a second is refused.
        admin.expectError(EXC.notAuthorized, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
            .toString() shouldContain ACNV.notOfferedCode
    }

    "a client an operator has set is not offered, whether switched off or on" {
        val (client, admin) = ownClient(cxt, "held@asst1568.test")
        // Offered first, as a page left open would show it -- so the refusal below is the lock's, not a stale answer's.
        admin.getData(ACNV.statusPath)[ACNV.offered] shouldBe true
        service.setClient(cxt, client, enabled = false, monthlyBudget = null, useDefaultBudget = false, acknowledgedNotice = false)
        admin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
        menuIds(admin) shouldNotContain ASST.menuItem
        admin.expectError(EXC.notAuthorized, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
            .toString() shouldContain ACNV.notOfferedCode
        // The operator's off stands: the switch-on wrote nothing over it.
        service.clientRow(cxt, client).shouldNotBeNull()[PF.enabled] shouldBe false

        val (onClient, onAdmin) = ownClient(cxt, "operated@asst1568.test")
        service.setClient(cxt, onClient, enabled = true, monthlyBudget = null, useDefaultBudget = false, acknowledgedNotice = true)
        onAdmin.getData(ACNV.statusPath).let {
            it[ACNV.on] shouldBe true
            it[ACNV.offered] shouldBe false
        }
    }

    "it is switched on from the client itself, not from its sandbox" {
        val (client, admin) = ownClient(cxt, "boxed@asst1568.test")
        admin.postData(AEP.openSandbox, emptyMap())
        // Offered still, so the page can say where to switch it on from.
        admin.getData(ACNV.statusPath).let {
            it[ACNV.offered] shouldBe true
            it[ACNV.inSandbox] shouldBe true
        }
        val refusal = admin.expectError(EXC.badInput, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true)).toString()
        refusal shouldContain "from '$client' itself"
        refusal shouldContain ACNV.inSandboxCode
        service.clientRow(cxt, client) shouldBe null
    }

    "a client that is not self-serve is never offered it: the platform's, and a customer's" {
        val hubAdmin = TestUser.create(cxt, "chief@asst1568.test", level = ROLE.admin, userClient = CL.hub)
        hubAdmin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
        hubAdmin.expectError(EXC.notAuthorized, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
            .toString() shouldContain ACNV.notOfferedCode
        // A customer's client: its id is prefixed, but by the customer, not as a self-serve one's is.
        val root = "cust1568"
        TestUser.createFullAdmin(cxt, "operator@asst1568.test").postData(
            ADEP.clientCreate,
            mapOf(
                CLD.clientId to root, CLD.name to "Customer", CLD.usageType to ClientUsageType.production.name,
                CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
                CLD.newCustomer to mapOf(CUST.name to "Customer Co"),
            ),
        )
        val made = "${root}_east"
        TestUser.create(cxt, "admin@cust1568.test", level = ROLE.admin, userClient = root)
            .postData(UADEP.clientCreate, mapOf(CLD.clientId to made, CLD.name to "East"))
        val madeAdmin = TestUser.create(cxt, "admin@cust1568.test", userClient = made)
        madeAdmin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
        madeAdmin.expectError(EXC.notAuthorized, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
            .toString() shouldContain ACNV.notOfferedCode
    }

    "a deployment that does not offer it to self-serve clients offers it to none" {
        val plain = boot("assistantSelfServeOff1568", selfServe = false)
        val (client, admin) = ownClient(plain, "plain@asst1568.test")
        AssistantService.get(plain).offeredTo(plain, client) shouldBe false
        admin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
        menuIds(admin) shouldNotContain ASST.menuItem
        // Nor is Home: no reason given in public to make a client for it, and no card in the client made (issue #1636).
        cardIds(TestUser.create(plain, "visitor@asst1636.test")) shouldBe emptyList()
        cardIds(admin) shouldBe listOf(HCARD.administer)
    }

    "a node with no model offers it to nobody" {
        val noModel = boot("assistantSelfServeNoModel1568", selfServe = true, withModel = false)
        val (client, admin) = ownClient(noModel, "nomodel@asst1568.test")
        AssistantService.get(noModel).offeredTo(noModel, client) shouldBe false
        admin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
    }

    "a switch that is not true or false refuses the boot" {
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "assistantSelfServeBad1568", "assistantSelfServeBad1568", mapOf(AENV.selfServe.name to "maybe"),
                additionalComponents = listOf(AssistantComponent()),
            )
        }.message.shouldNotBeNull() shouldContain AENV.selfServe.name
    }
})
