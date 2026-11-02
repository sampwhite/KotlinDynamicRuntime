package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.cfact.CFD
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.operator.OENV
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
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
 * the demo only on a test instance, and never the real one under the unit environment -- the settings a boot refuses,
 * the operator's switch and budget, and the use read back, priced attempt by attempt. Each boot names its own instance,
 * since the components and overlay differ; the shared one gives each case that changes a client a client of its own.
 */
class AssistantModuleTest : StringSpec({
    val components = listOf(AssistantComponent())
    val cxt = Startup.mkTestBootCxt(
        "assistant1525", "assistantModuleTest", mapOf(AssistantService.modelConfigKey to ScriptedModel(emptyList())),
        additionalComponents = components,
    )
    val service = AssistantService.get(cxt)
    val emptyRequest = ModelRequest(AENV.defaultModel, 100, emptyList(), emptyList(), emptyList(), null)

    /** A client of the test's own on [on]'s instance, so no other case's switch or use is in its way. */
    fun newClient(on: KdrCxt, client: String): String {
        val setup = on.mkSubContext("setup", client).also { it.userId = 15250L }
        GedraConfigService.get(on).writeConfig(
            setup,
            gedraConfig(on, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        GedraConfigReload.reloadClient(on, client)
        return client
    }

    /** The `assistant:on` line of the cfacts reference, as [user] reads it: `present` or `absent`. */
    fun onFor(user: TestUser): String {
        val doc = user.getItem(CFD.cfactsPath)[CFD.markdown].toOptStr().orEmpty()
        return doc.substringAfter("`${ASST.onCFact}`\n\n**For you now:** ").substringBefore("\n").substringBefore(" ")
    }

    /** [client]'s line of the operator's listing. */
    fun lineOf(operator: TestUser, client: String): Map<String, Any?> =
        operator.getItems(ASST.clientsPath).first { it[ASST.client] == client }

    /** Every message in the chain of what a refused boot threw. */
    fun refusedBoot(instance: String, overlay: Map<String, Any?>, test: Boolean = true): String {
        val refused = shouldThrow<KdrException> {
            if (test) {
                Startup.mkTestBootCxt(instance, instance, overlay, additionalComponents = components)
            } else {
                Startup.mkBootCxt(instance, instance, Startup.realNodeOverlay(ENV.dev) + overlay, additionalComponents = components)
            }
        }
        return generateSequence<Throwable>(refused) { it.cause }.joinToString(" ") { it.message.orEmpty() }
    }

    "with no key the assistant is unavailable, says which secret it looked for, and is on for nobody" {
        val noKey = Startup.mkTestBootCxt("assistantNoKey", "assistantNoKeyTest", additionalComponents = components)
        val noKeyService = AssistantService.get(noKey)
        noKeyService.available shouldBe false
        // A test's node names a secret no secrets file holds, so a developer's real key never reaches one.
        noKeyService.missingSecret shouldBe ASST.noKeyInUnitTests

        val operator = TestUser.createOperator(noKey, "asst-nokey-op@example.com")
        val summary = operator.client.sendJsonGetRequest(ASST.clientsPath)[EP.summary].toJsonMapOrEmpty()
        summary[ASST.available] shouldBe false
        summary[ASST.missingSecret] shouldBe ASST.noKeyInUnitTests
        summary[ASST.defaultMonthlyBudgetDollars] shouldBe 20.0
        summary[ASST.reason].toOptStr().shouldNotBeNull() shouldContain ASST.noKeyInUnitTests
        // Its variables are in the operator's reference, under their own group.
        val reference = operator.getData(OENV.envReferencePath)[OENV.markdown].toString()
        reference shouldContain ENVGRP.assistant
        reference shouldContain AENV.keySecret.name

        // Switched on ahead of a key, the client still has no assistant: no cfact, so no menu entry.
        operator.postItem(ASST.clientSetPath, mapOf(ASST.client to CL.hub, ASST.enabled to true, ASST.acknowledgeDataNotice to true))
        noKeyService.isOn(noKey, CL.hub) shouldBe false
        onFor(TestUser.create(noKey, "asst-nokey-admin@hub.test", level = ROLE.admin, userClient = CL.hub)) shouldBe "absent"
    }

    "the demo provider answers on a test instance, and is refused at boot on a node that is not one" {
        val demo = Startup.mkTestBootCxt(
            "assistantDemo", "assistantDemoTest", mapOf(AENV.provider.name to AENV.fakeProvider), additionalComponents = components,
        )
        // A message from the person: the demo calls list_workflows.
        val model = AssistantService.get(demo).model.shouldNotBeNull()
        val hello = listOf(mapOf(MSG.role to MSG.user, MSG.content to "Hello"))
        val call = model.respond(demo, ModelRequest(AENV.defaultModel, 100, emptyList(), emptyList(), hello, null))
        call.stopReason shouldBe MSG.toolUse
        val use = call.content.single()
        use[TOOL.name] shouldBe TOOL.listWorkflows
        // Its result: the demo names what it found.
        val catalog = mapOf(CWF.workflows to listOf(mapOf(CWF.definition to mapOf(CCT.workflowId to "rsvp", WFD.label to "RSVP"))))
        val answered = hello + call.message + mapOf(
            MSG.role to MSG.user,
            MSG.content to listOf(TurnRunner.resultBlock(use[TOOL.id].toString(), ToolResult(catalog.toJsonStr(compact = true), false))),
        )
        val answer = model.respond(demo, ModelRequest(AENV.defaultModel, 100, emptyList(), emptyList(), answered, null))
        answer.stopReason shouldBe MSG.endTurn
        answer.content.single()[MSG.text].toOptStr().shouldNotBeNull() shouldContain "found 1: RSVP (rsvp)"

        // A development node, as a real deployment boots one.
        refusedBoot("assistantDemoDev", mapOf(AENV.provider.name to AENV.fakeProvider), test = false) shouldContain
            "not a test instance"
    }

    "a setting the assistant cannot use refuses the boot, naming the variable" {
        refusedBoot("assistantBadProvider", mapOf(AENV.provider.name to "openai")) shouldContain AENV.provider.name
        refusedBoot("assistantBadEffort", mapOf(AENV.effort.name to "extreme")) shouldContain AENV.effort.name
        // A model that does not accept the request's progress updates and fallbacks would answer every call with a 400.
        refusedBoot("assistantBadModel", mapOf(AENV.model.name to "claude-haiku-5-5")) shouldContain AENV.model.name
        refusedBoot("assistantBadTokens", mapOf(AENV.maxTokens.name to "200000")) shouldContain AENV.maxTokens.name
        refusedBoot("assistantBadSteps", mapOf(AENV.maxSteps.name to "many")) shouldContain AENV.maxSteps.name
    }

    "the real model refuses under the unit environment, and once closed, before it makes any call" {
        shouldThrow<KdrException> { AnthropicModel("not-a-key").respond(cxt, emptyRequest) }.message.shouldNotBeNull() shouldContain
            "unit test environment"
        val dev = Startup.mkBootCxt("assistantClosed", "assistantClosedTest", Startup.realNodeOverlay(ENV.dev))
        val closed = AnthropicModel("not-a-key").also { it.close() }
        shouldThrow<KdrException> { closed.respond(dev, emptyRequest) }.message.shouldNotBeNull() shouldContain "closed"
    }

    "an operator switches a client on, sets its budget, and reads its use back, a sandbox's counted as its parent's" {
        val client = newClient(cxt, "asst1525on")
        val operator = TestUser.createOperator(cxt, "asst-op@example.com")
        val admin = TestUser.create(cxt, "asst-admin@$client.test", level = ROLE.admin, userClient = client)
        service.available shouldBe true
        val before = lineOf(operator, client)
        before[ASST.enabled] shouldBe false
        before[ASST.budgetIsDefault] shouldBe true
        before[ASST.monthlyBudget] shouldBe service.settings.defaultMonthlyBudget
        before[ASST.monthlyBudgetDollars] shouldBe 20.0
        before[ASST.used] shouldBe 0
        onFor(admin) shouldBe "absent"
        // The set's client field is served as a choice of the clients the operator may name: this one among them. Read
        // with env auth, as a developer's catalog page is: without it, the catalog lists published endpoints only.
        val served = operator.withTestAccess {
            operator.getData("/schema/endpoint", mapOf(EI.method to "POST", EI.path to ASST.clientSetPath))
        }
        served.toJsonStr() shouldContain "\"$client\""

        // Switching it on needs the data notice acknowledged.
        operator.expectError(EXC.badInput, ASST.clientSetPath, mapOf(ASST.client to client, ASST.enabled to true))[EP.errorMessage]
            .toOptStr().shouldNotBeNull() shouldContain ASST.acknowledgeDataNotice
        lineOf(operator, client)[PF.updatedAt].shouldBeNull()

        val on = operator.postItem(
            ASST.clientSetPath,
            mapOf(ASST.client to client, ASST.enabled to true, ASST.monthlyBudget to 50_000, ASST.acknowledgeDataNotice to true),
        )
        on[ASST.enabled] shouldBe true
        on[ASST.monthlyBudget] shouldBe 50_000
        on[ASST.budgetIsDefault] shouldBe false
        on[ASST.noticeAcknowledgedBy] shouldBe operator.userId
        on[PF.updatedBy] shouldBe operator.userId
        service.isOn(cxt, client) shouldBe true
        service.isOn(cxt, sandboxOf(client)) shouldBe true
        onFor(admin) shouldBe "present"
        // Answered from the table cache, which holds the row now that it is on.
        val cache = service.clientCache.shouldNotBeNull()
        cache.checkRefresh(cxt)
        cache.snapshot.get(cache.idOf(client)).shouldNotBeNull()

        // Use made in the sandbox is the parent's: 1000 input, 200 cache writes, 3000 cache reads, and 100 output
        // tokens weigh 1000 + 250 + 300 + 500 = 2050.
        val usage = ModelUsage(inputTokens = 1000, cacheWriteTokens = 200, cacheReadTokens = 3000, outputTokens = 100)
        service.recordUsage(cxt, sandboxOf(client), AENV.defaultModel, usage) shouldBe 2050
        service.recordUsage(cxt, client, AENV.defaultModel, usage) shouldBe 2050
        val used = lineOf(operator, client)
        used[ASST.used] shouldBe 4100
        // 4,100 weighted tokens at 500,000 to the dollar: under a cent, shown to the cent.
        used[ASST.usedDollars] shouldBe 0.01
        used[ASST.calls] shouldBe 2
        used[ASST.cacheReadTokens] shouldBe 6000
        // Written as JSON to seven digits.
        (used[ASST.cacheHitRatio] as Double) shouldBe (6000.0 / 8400.0 plusOrMinus 1e-6)
        used[ASST.lastUsedAt].shouldNotBeNull()
        used[ASST.month] shouldBe AssistantService.monthOf(cxt.now())
        operator.getItems(ASST.clientsPath).none { it[ASST.client] == sandboxOf(client) } shouldBe true

        // A budget in dollars, the operator's own terms: $10 is 5,000,000 weighted tokens.
        val inDollars = operator.postItem(ASST.clientSetPath, mapOf(ASST.client to client, ASST.monthlyBudgetDollars to 10))
        inDollars[ASST.monthlyBudget] shouldBe 5_000_000
        inDollars[ASST.monthlyBudgetDollars] shouldBe 10.0
        operator.expectError(
            EXC.badInput, ASST.clientSetPath, mapOf(ASST.client to client, ASST.monthlyBudgetDollars to 10, ASST.monthlyBudget to 5),
        )
        operator.expectError(EXC.badInput, ASST.clientSetPath, mapOf(ASST.client to client, ASST.monthlyBudgetDollars to 0))

        // Back to the default budget, then off: the cache sees it at once on this node.
        val reset = operator.postItem(ASST.clientSetPath, mapOf(ASST.client to client, ASST.budgetIsDefault to true))
        reset[ASST.budgetIsDefault] shouldBe true
        reset[ASST.enabled] shouldBe true
        operator.postItem(ASST.clientSetPath, mapOf(ASST.client to client, ASST.enabled to false))[ASST.enabled] shouldBe false
        service.isOn(cxt, client) shouldBe false
        onFor(admin) shouldBe "absent"
    }

    "use is recorded outside a transaction only" {
        val client = newClient(cxt, "asst1525tran")
        val db = SqlTopicService.mkSqlCxt(cxt, ATBL.topic).sqlDb
        db.withTran(cxt) {
            shouldThrow<KdrException> { service.recordUsage(cxt, client, AENV.defaultModel, ModelUsage(inputTokens = 1)) }
                .message.shouldNotBeNull() shouldContain "transaction"
        }
        service.usageIn(cxt, AssistantService.monthOf(cxt.now()))[client].shouldBeNull()
    }

    "a client is set by its own id: a sandbox or an unknown client is refused" {
        val operator = TestUser.createOperator(cxt, "asst-op2@example.com")
        operator.expectError(EXC.badInput, ASST.clientSetPath, mapOf(ASST.client to sandboxOf(CL.hub), ASST.enabled to false))
        operator.expectError(EXC.notFound, ASST.clientSetPath, mapOf(ASST.client to "noSuchClient1525", ASST.enabled to false))
    }

    "the controls are a deployment operator's: a client's own administrator is refused them" {
        val admin = TestUser.create(cxt, "asst-admin2@hub.test", level = ROLE.admin, userClient = CL.hub)
        admin.expectError(EXC.notAuthorized, ASST.clientsPath)
        admin.expectError(EXC.notAuthorized, ASST.clientSetPath, mapOf(ASST.client to CL.hub, ASST.enabled to false))
    }

    "weighted tokens round up, and each model is counted at its own price" {
        ModelUsage(cacheReadTokens = 1).weighted(listOf(AENV.defaultModel)) shouldBe 1
        ModelUsage(inputTokens = 1000).weighted(listOf("claude-opus-4-8")) shouldBe 2500
        ModelUsage(inputTokens = 1000, cacheReadTokens = 1000).weighted(listOf("claude-opus-5-5")) shouldBe 2100
        ModelUsage(inputTokens = 1000).weighted(listOf("claude-fable-5-1")) shouldBe 5000
        // A model not listed is counted at the dearest price known, kind by kind.
        ModelUsage(inputTokens = 1000, cacheReadTokens = 1000).weighted(listOf("some-future-model")) shouldBe 5500
    }

    "with fallbacks, every billed attempt counts, each at its own model's price" {
        val usage = ModelUsage.fromWire(
            mapOf(
                // The top level covers only the attempt that answered.
                MSG.inputTokens to 1000L, MSG.outputTokens to 100L,
                MSG.iterations to listOf(
                    mapOf(MSG.type to "message", MSG.model to "claude-sonnet-5-5", MSG.inputTokens to 1000L, MSG.outputTokens to 400L),
                    mapOf(
                        MSG.type to "fallback_message", MSG.model to "claude-opus-4-8", MSG.inputTokens to 1000L, MSG.outputTokens to 100L,
                    ),
                ),
            ),
        )
        usage.attempts.size shouldBe 2
        usage.outputTokens shouldBe 500
        // Sonnet: 1000 + 400 * 5 = 3000. Opus 4.8 at 2.5 times: (1000 + 100 * 5) * 2.5 = 3750.
        usage.weighted(listOf("claude-opus-4-8")) shouldBe 6750
        // An attempt that names no model is counted at the dearer of those it could be.
        val unnamed = ModelUsage.fromWire(mapOf(MSG.iterations to listOf(mapOf(MSG.inputTokens to 1000L))))
        unnamed.weighted(listOf("claude-sonnet-5-5", "claude-opus-4-8")) shouldBe 2500
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
        val system = listOf(mapOf(MSG.type to MSG.text, MSG.text to "You draft workflows."))
        val wire = ModelRequest(AENV.defaultModel, 16_000, system, listOf(tool), messages, "medium").toWire()
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

    "a request goes on the wire with adaptive thinking, fallbacks, and caching; a response is read back whole" {
        val messages = listOf(mapOf(MSG.role to MSG.user, MSG.content to "hi"))
        val wire = ModelRequest(AENV.defaultModel, 1000, emptyList(), emptyList(), messages, "medium").toWire()
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
