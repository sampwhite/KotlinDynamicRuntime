package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
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
    fun boot(name: String, selfServe: Boolean): KdrCxt = Startup.mkTestBootCxt(
        name, name,
        buildMap {
            put(AssistantService.modelConfigKey, ScriptedModel(emptyList()))
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

    "a self-serve client's administrator switches the assistant on, acknowledging the data notice, at the self-serve budget" {
        val (client, admin) = ownClient(cxt, "maker@asst1568.test")
        admin.getData(ACNV.statusPath).let {
            it[ACNV.on] shouldBe false
            it[ACNV.offered] shouldBe true
        }
        // The page is offered, to switch it on from; a message is still refused until it is on.
        menuIds(admin) shouldContain ASST.menuItem
        admin.expectError(EXC.notAuthorized, ACNV.messagePath, mapOf(ACNV.clientMessageId to "m1", ACNV.text to "Hi"))
            .toString() shouldContain ACNV.notOnCode
        // Not without acknowledging what is sent.
        admin.expectError(EXC.badInput, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to false))
            .toString() shouldContain ASST.acknowledgeDataNotice
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

    "a client an operator has set is not offered, even switched off" {
        val (client, admin) = ownClient(cxt, "held@asst1568.test")
        service.setClient(cxt, client, enabled = false, monthlyBudget = null, useDefaultBudget = false, acknowledgedNotice = false)
        admin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
        menuIds(admin) shouldNotContain ASST.menuItem
        admin.expectError(EXC.notAuthorized, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
            .toString() shouldContain ACNV.notOfferedCode
    }

    "it is switched on from the client itself, not from its sandbox" {
        val (client, admin) = ownClient(cxt, "boxed@asst1568.test")
        admin.postData(AEP.openSandbox, emptyMap())
        admin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
        admin.expectError(EXC.badInput, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
            .toString() shouldContain "from '$client' itself"
        service.clientRow(cxt, client) shouldBe null
    }

    "a client that is not self-serve is never offered it" {
        val hubAdmin = TestUser.create(cxt, "chief@asst1568.test", level = ROLE.admin, userClient = CL.hub)
        hubAdmin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
        hubAdmin.expectError(EXC.notAuthorized, ACNV.switchOnPath, mapOf(ASST.acknowledgeDataNotice to true))
    }

    "a deployment that does not offer it to self-serve clients offers it to none" {
        val plain = boot("assistantSelfServeOff1568", selfServe = false)
        val (client, admin) = ownClient(plain, "plain@asst1568.test")
        AssistantService.get(plain).offeredTo(plain, client) shouldBe false
        admin.getData(ACNV.statusPath)[ACNV.offered] shouldBe false
        menuIds(admin) shouldNotContain ASST.menuItem
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
