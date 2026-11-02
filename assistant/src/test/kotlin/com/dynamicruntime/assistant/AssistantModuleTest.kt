package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.operator.OENV
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The assistant module's provider and operator controls (issue #1525): which model a node has -- none without a key,
 * the demo only on a test instance, and never the real one under the unit environment -- and the operator's switch,
 * budget, and the use read back. Each boot names its own instance, since the components and overlay differ.
 */
class AssistantModuleTest : StringSpec({
    val components = listOf(AssistantComponent())
    val scripted = ScriptedModel(emptyList())
    val cxt = Startup.mkTestBootCxt(
        "assistant1525", "assistantModuleTest", mapOf(AssistantService.modelConfigKey to scripted), additionalComponents = components,
    )
    val service = AssistantService.get(cxt)

    /** The `assistant:on` line of the cfacts reference, as [user] reads it. */
    fun onFor(user: TestUser): String {
        val doc = user.getItem(CFD.cfactsPath)[CFD.markdown].toOptStr().orEmpty()
        return doc.substringAfter("`${ASST.onCFact}`\n\n**For you now:** ").substringBefore("\n").substringBefore(" ")
    }

    /** [client]'s line of the operator's listing. */
    fun lineOf(operator: TestUser, client: String): Map<String, Any?> =
        operator.getItems(ASST.clientsPath).first { it[ASST.client] == client }

    "with no key the assistant is unavailable, says which secret it looked for, and is on for nobody" {
        val noKey = Startup.mkTestBootCxt("assistantNoKey", "assistantNoKeyTest", additionalComponents = components)
        val noKeyService = AssistantService.get(noKey)
        noKeyService.available shouldBe false
        // A unit test that names no secret reads none, so a developer's real key never reaches one.
        noKeyService.missingSecret shouldBe AENV.noKeyInUnitTests

        val operator = TestUser.createOperator(noKey, "asst-nokey-op@example.com")
        val summary = operator.client.sendJsonGetRequest(ASST.clientsPath)[EP.summary].toJsonMapOrEmpty()
        summary[ASST.available] shouldBe false
        summary[ASST.missingSecret] shouldBe AENV.noKeyInUnitTests
        summary[ASST.reason].toOptStr().shouldNotBeNull() shouldContain AENV.noKeyInUnitTests
        // Its variables are in the operator's reference, under their own group.
        val reference = operator.getData(OENV.envReferencePath)[OENV.markdown].toString()
        reference shouldContain ENVGRP.assistant
        reference shouldContain AENV.keySecret.name

        // Switched on ahead of a key, the client still has no assistant: no cfact, so no menu entry.
        operator.postItem(ASST.clientSetPath, mapOf(ASST.client to CL.hub, ASST.enabled to true, ASST.acknowledgeDataNotice to true))
        noKeyService.isOn(noKey, CL.hub) shouldBe false
        onFor(TestUser.create(noKey, "asst-nokey-admin@hub.test", level = ROLE.admin, userClient = CL.hub)) shouldBe "absent"
    }

    "the demo provider answers on a test instance, and is refused at boot on any other" {
        val demo = Startup.mkTestBootCxt(
            "assistantDemo", "assistantDemoTest", mapOf(AENV.provider.name to AENV.fakeProvider), additionalComponents = components,
        )
        val model = AssistantService.get(demo).model.shouldNotBeNull()
        val answer = model.respond(demo, ModelRequest(AENV.defaultModel, 100, emptyList(), emptyList(), emptyList(), null))
        answer.stopReason shouldBe MSG.endTurn
        answer.content.single()[MSG.text].toOptStr().shouldNotBeNull() shouldContain "demo assistant"

        val refused = shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "assistantDemoReal", "assistantDemoRealTest",
                Startup.realNodeOverlay(ENV.dev) + mapOf(AENV.provider.name to AENV.fakeProvider),
                additionalComponents = components,
            )
        }
        generateSequence<Throwable>(refused) { it.cause }.joinToString(" ") { it.message.orEmpty() } shouldContain
            "not a test instance"
    }

    "the real model refuses under the unit environment, before it makes any call" {
        val refused = shouldThrow<KdrException> {
            AnthropicModel("not-a-key").respond(cxt, ModelRequest(AENV.defaultModel, 100, emptyList(), emptyList(), emptyList(), null))
        }
        refused.message.shouldNotBeNull() shouldContain "unit test environment"
    }

    "an operator switches a client on, sets its budget, and reads its use back, a sandbox's counted as its parent's" {
        service.available shouldBe true
        val operator = TestUser.createOperator(cxt, "asst-op@example.com")
        val admin = TestUser.create(cxt, "asst-admin@hub.test", level = ROLE.admin, userClient = CL.hub)
        val before = lineOf(operator, CL.hub)
        before[ASST.enabled] shouldBe false
        before[ASST.budgetIsDefault] shouldBe true
        before[ASST.monthlyBudget] shouldBe service.settings.defaultMonthlyBudget
        onFor(admin) shouldBe "absent"

        // Switching it on needs the data notice acknowledged.
        val unacknowledged = operator.client.sendJsonPostRequest(ASST.clientSetPath, mapOf(ASST.client to CL.hub, ASST.enabled to true))
        unacknowledged[EP.status] shouldBe EXC.badInput
        unacknowledged[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain ASST.acknowledgeDataNotice

        val on = operator.postItem(
            ASST.clientSetPath,
            mapOf(ASST.client to CL.hub, ASST.enabled to true, ASST.monthlyBudget to 50_000, ASST.acknowledgeDataNotice to true),
        )
        on[ASST.enabled] shouldBe true
        on[ASST.monthlyBudget] shouldBe 50_000
        on[ASST.budgetIsDefault] shouldBe false
        on[ASST.noticeAcknowledgedBy] shouldBe operator.userId
        on[PF.updatedBy] shouldBe operator.userId
        service.isOn(cxt, CL.hub) shouldBe true
        service.isOn(cxt, sandboxOf(CL.hub)) shouldBe true
        onFor(admin) shouldBe "present"

        // Use made in the sandbox is the parent's: 1000 input, 200 cache writes, 3000 cache reads, and 100 output
        // tokens weigh 1000 + 250 + 300 + 500 = 2050.
        val usage = ModelUsage(inputTokens = 1000, cacheWriteTokens = 200, cacheReadTokens = 3000, outputTokens = 100)
        service.recordUsage(cxt, sandboxOf(CL.hub), AENV.defaultModel, usage) shouldBe 2050
        service.recordUsage(cxt, CL.hub, AENV.defaultModel, usage) shouldBe 2050
        val used = lineOf(operator, CL.hub)
        used[ASST.used] shouldBe 4100
        used[ASST.calls] shouldBe 2
        used[ASST.cacheReadTokens] shouldBe 6000
        (used[ASST.cacheHitRatio] as Double) shouldBe (6000.0 / 8400.0 plusOrMinus 1e-6)
        used[ASST.lastUsedAt].shouldNotBeNull()
        used[ASST.month] shouldBe AssistantService.monthOf(cxt.now())
        operator.getItems(ASST.clientsPath).none { it[ASST.client] == sandboxOf(CL.hub) } shouldBe true

        // Back to the default budget, then off: the cache sees it at once on this node.
        operator.postItem(ASST.clientSetPath, mapOf(ASST.client to CL.hub, ASST.budgetIsDefault to true))[ASST.budgetIsDefault] shouldBe true
        operator.postItem(ASST.clientSetPath, mapOf(ASST.client to CL.hub, ASST.enabled to false))[ASST.enabled] shouldBe false
        service.isOn(cxt, CL.hub) shouldBe false
        onFor(admin) shouldBe "absent"
    }

    "a client is set by its own id: a sandbox or an unknown client is refused" {
        val operator = TestUser.createOperator(cxt, "asst-op2@example.com")
        operator.client.sendJsonPostRequest(ASST.clientSetPath, mapOf(ASST.client to sandboxOf(CL.hub), ASST.enabled to false))[EP.status] shouldBe
            EXC.badInput
        operator.client.sendJsonPostRequest(ASST.clientSetPath, mapOf(ASST.client to "noSuchClient1525", ASST.enabled to false))[EP.status] shouldBe
            EXC.notFound
    }

    "the controls are a deployment operator's: a client's own administrator is refused them" {
        val admin = TestUser.create(cxt, "asst-admin2@hub.test", level = ROLE.admin, userClient = CL.hub)
        val refused = admin.client.sendJsonGetRequest(ASST.clientsPath)
        refused[EP.status].shouldNotBeNull()
        refused[EP.items].toJsonListOfMaps().isEmpty() shouldBe true
    }

    "a request and a response pass through the SDK's own types with nothing dropped" {
        val thinking = mapOf(MSG.type to MSG.thinking, MSG.thinking to "", "signature" to "sig-1525")
        val tool = mapOf(
            "name" to "list_traits", "description" to "Lists the client's traits.",
            "input_schema" to mapOf(MSG.type to "object", "properties" to emptyMap<String, Any?>()),
        )
        val messages = listOf(
            mapOf(MSG.role to MSG.user, MSG.content to listOf(mapOf(MSG.type to MSG.text, MSG.text to "Add an RSVP workflow."))),
            mapOf(MSG.role to MSG.assistant, MSG.content to listOf(thinking, mapOf(MSG.type to MSG.text, MSG.text to "Looking."))),
            mapOf(MSG.role to MSG.user, MSG.content to listOf(mapOf(MSG.type to MSG.text, MSG.text to "Go on."))),
        )
        val wire = ModelRequest(
            AENV.defaultModel, 16_000, listOf(mapOf(MSG.type to MSG.text, MSG.text to "You draft workflows.")), listOf(tool), messages, "medium",
        ).toWire()
        val sent = AnthropicModel.requestThroughSdk(wire)
        for (key in wire.keys) sent[key] shouldBe wire[key]

        val raw = mapOf(
            "id" to "msg_1525", "type" to "message", MSG.role to MSG.assistant, MSG.model to AENV.defaultModel,
            MSG.content to listOf(thinking, mapOf(MSG.type to MSG.text, MSG.text to "Done.")),
            MSG.stopReason to MSG.endTurn, "stop_sequence" to null,
            MSG.usage to mapOf(MSG.inputTokens to 10L, MSG.outputTokens to 2L),
            // A field the SDK may not have a type for is kept, not dropped.
            "stop_details" to mapOf("category" to "none1525"),
        )
        val stored = AnthropicModel.responseThroughSdk(raw)
        stored[MSG.content] shouldBe raw[MSG.content]
        stored["stop_details"] shouldBe raw["stop_details"]
        ModelResponse.fromWire(stored).usage.inputTokens shouldBe 10
    }

    "weighted tokens round up, and a fallback model is counted at its own price" {
        ModelUsage(cacheReadTokens = 1).weighted(AENV.defaultModel) shouldBe 1
        ModelUsage(inputTokens = 1000).weighted("claude-opus-4-8") shouldBe 2500
        ModelUsage(inputTokens = 1000).weighted("claude-opus-5-5") shouldBe 2000
        ModelUsage(inputTokens = 1000).weighted("some-future-model") shouldBe 2500
    }

    "a request goes on the wire with adaptive thinking, fallbacks, and caching; a response is read back whole" {
        val wire = ModelRequest(AENV.defaultModel, 1000, emptyList(), emptyList(), listOf(mapOf(MSG.role to MSG.user, MSG.content to "hi")), "medium")
            .toWire()
        wire[MSG.thinking].toJsonMapOrEmpty()[MSG.type] shouldBe MSG.adaptive
        wire[MSG.outputConfig].toJsonMapOrEmpty()[MSG.effort] shouldBe "medium"
        wire[MSG.fallbacks] shouldBe MSG.default
        wire[MSG.cacheControl].toJsonMapOrEmpty()[MSG.type] shouldBe MSG.ephemeral
        wire[MSG.system].shouldBeNull()

        val thinking = mapOf(MSG.type to MSG.thinking, MSG.thinking to "", "signature" to "abc")
        val response = ModelResponse.fromWire(
            mapOf(
                MSG.role to MSG.assistant, MSG.model to "claude-opus-4-8", MSG.stopReason to MSG.toolUse,
                MSG.content to listOf(thinking), MSG.usage to mapOf(MSG.inputTokens to 10L, MSG.outputTokens to 2L),
            ),
        )
        response.content.single() shouldBe thinking
        response.model shouldBe "claude-opus-4-8"
        response.usage.outputTokens shouldBe 2
    }
})
