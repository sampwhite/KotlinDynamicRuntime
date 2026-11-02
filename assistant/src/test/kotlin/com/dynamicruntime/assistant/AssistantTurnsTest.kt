package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.resolveEndpointInputType
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.InProcessCaller
import com.dynamicruntime.common.http.request.InProcessPrincipal
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.operator.OENV
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * The assistant's turns (issue #1526), driven through the administrator's own endpoints with a [ScriptedModel] that
 * calls the real tools: what a turn stores, how it ends for every reason the model stops, the limits that refuse or stop
 * it, and what the tools may and may not reach. One shared instance, a client of its own per case; the lapsed-lease
 * case moves the clock, so it boots its own.
 */
class AssistantTurnsTest : StringSpec({
    val model = ScriptedModel(emptyList())
    fun boot(name: String, with: ScriptedModel): KdrCxt = Startup.mkTestBootCxt(
        name, name,
        mapOf(AssistantService.modelConfigKey to with, AENV.maxSteps.name to "4", AENV.turnsPerHour.name to "6"),
        additionalComponents = listOf(AssistantComponent()),
    )
    val cxt = boot("assistantTurns1526", model)
    val service = AssistantService.get(cxt)

    fun GedraConfigBuilder.flows(count: Int = 1) {
        for (w in 1..count) {
            workflow(if (w == 1) "review" else "review$w", WfEntry.normal) {
                task("draft", "Draft it") { trait("memo"); save("saveMemo", "Save", WfSaveKind.edit) }
            }
        }
    }

    /**
     * A client with a sandbox, a published `main` (its definition and a trait) and `flows` (its workflows), the
     * assistant switched on, and its administrator. [sandbox] false leaves the sandbox off.
     */
    fun clientWithAssistant(on: KdrCxt, client: String, sandbox: Boolean = true, workflows: Int = 1): TestUser {
        val setup = on.mkSubContext("setup", client).also { it.userId = 15260L }
        val configs = GedraConfigService.get(on)
        configs.writeConfig(
            setup,
            gedraConfig(on, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                    ),
                )
                trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
            },
        )
        configs.writeConfig(setup, gedraConfig(on, "flows", clientNamespace(client), client) { flows(workflows) })
        configs.publish(setup, listOf("main", "flows").map { GedraId.of(GedraConfigType.configDoc, client, it) })
        GedraConfigReload.reloadClient(on, client)
        AssistantService.get(on).setClient(
            on, client, enabled = true, monthlyBudget = null, useDefaultBudget = false, acknowledgedNotice = true,
        )
        return TestUser.create(on, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    var messageIds = 0

    /** A message's request body. */
    fun message(clientMessageId: String, text: String, conversationId: String? = null): Map<String, Any?> = buildMap {
        put(ACNV.clientMessageId, clientMessageId)
        put(ACNV.text, text)
        conversationId?.let { put(ACNV.conversationId, it) }
    }

    /** An edit adding or replacing the workflow entry [data]. */
    fun workflowEdit(data: Map<String, Any?>): Map<String, Any?> =
        mapOf(CFEP.slot to CCT.workflowDef, GED.action to GedraEditAction.addOrReplace.name, GE.data to data)

    /** A workflow entry's [data] with its id -- the entry's `workflowId` and its definition's -- now [to]. */
    fun renamed(data: Map<String, Any?>, from: String, to: String): Map<String, Any?> = data.mapValues { (key, value) ->
        when {
            key == CCT.workflowId && value == from -> to
            value is Map<*, *> -> renamed(value.toJsonMapOrEmpty(), from, to)
            else -> value
        }
    }

    /** Sends [text] as [admin] and waits for its turn to end; the conversation id. */
    fun send(admin: TestUser, text: String, conversationId: String? = null, on: KdrCxt = cxt): String {
        val started = admin.postData(
            ACNV.messagePath,
            buildMap {
                put(ACNV.clientMessageId, "m${++messageIds}")
                put(ACNV.text, text)
                conversationId?.let { put(ACNV.conversationId, it) }
            },
        )
        AssistantService.get(on).awaitTurn(started[ACNV.turnId].toString())
        return started[ACNV.conversationId].toString()
    }

    fun conversation(admin: TestUser, conversationId: String): Map<String, Any?> =
        admin.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to conversationId))

    fun messagesOf(admin: TestUser, conversationId: String): List<Map<String, Any?>> = conversation(admin, conversationId)[ACNV.messages]
        .toJsonListOfMaps().map { mapOf(MSG.role to it[ACNV.role], MSG.content to it[ACNV.content]) }

    fun lastTurn(admin: TestUser, conversationId: String): Map<String, Any?> =
        conversation(admin, conversationId)[ACNV.turns].toJsonListOfMaps().last()

    /** The tool results of a message, in order. */
    fun results(message: Map<String, Any?>): List<Map<String, Any?>> =
        message[MSG.content].toJsonListOfMaps().filter { it[MSG.type] == TOOL.toolResult }

    /** The history is sound as the next request would send it, and was sound for every request the model was sent. */
    fun historyHolds(messages: List<Map<String, Any?>>) {
        val next = mapOf(MSG.role to MSG.user, MSG.content to listOf(mapOf(MSG.type to MSG.text, MSG.text to "next")))
        AssistantHistory.historyProblems(messages + next).shouldBeEmpty()
        // A request continuing a paused answer ends with that answer.
        model.requests.forEach {
            AssistantHistory.historyProblems(it.messages, continuing = it.messages.last()[MSG.role] == MSG.assistant).shouldBeEmpty()
        }
    }

    "a turn runs every tool call the model makes, answers them in one message, and records what it cost" {
        val admin = clientWithAssistant(cxt, "asstrun")
        val usedBefore = service.usedThisMonth(cxt, "asstrun")
        model.add(
            { ScriptedModel.toolResponse(TOOL.listConfigs to emptyMap(), TOOL.listWorkflows to emptyMap()) },
            { ScriptedModel.textResponse("You have one workflow, review.") },
        )
        val conversationId = send(admin, "What workflows do I have?")
        val messages = messagesOf(admin, conversationId)
        messages.map { it[MSG.role] } shouldBe listOf(MSG.user, MSG.assistant, MSG.user, MSG.assistant)
        val answered = results(messages[2])
        answered shouldHaveSize 2
        answered.forEach { it[TOOL.isError].shouldBeNull() }
        answered[1][TOOL.content].toString() shouldContain "review"
        historyHolds(messages)

        val turn = lastTurn(admin, conversationId)
        turn[ACNV.status] shouldBe ACNV.statusDone
        turn[ACNV.steps] shouldBe 2
        turn[ACNV.weighted].toOptLong().shouldNotBeNull() shouldBeGreaterThan 0
        service.usedThisMonth(cxt, "asstrun") shouldBe usedBefore + turn[ACNV.weighted].toOptLong()!!
        conversation(admin, conversationId)[ACNV.conversation].toJsonMapOrEmpty()[ACNV.running] shouldBe false

        // The same message sent again -- a retry after a lost answer -- is the same turn, not a second one.
        val again = admin.postData(ACNV.messagePath, message("m$messageIds", "What workflows do I have?"))
        again[ACNV.conversationId] shouldBe conversationId
        again[ACNV.turnId] shouldBe turn[ACNV.turnId]
    }

    "a scripted patch lands as a draft the sandbox runs, and leaves the published revision as it was" {
        val client = "asstdraft"
        val admin = clientWithAssistant(cxt, client)
        val review = admin.getItem(CFEP.bundle, mapOf(CFEP.name to "flows"))[CFEP.slots].toJsonMapOrEmpty()[CCT.workflowDef]
            .toJsonListOfMaps().single()
        val newFlow = renamed(review, "review", "rsvp")
        val configs = listOf(
            mapOf(
                CFEP.name to "flows",
                CFEP.edits to listOf(workflowEdit(newFlow)),
            ),
        )
        model.add(
            // A write before its dry run is refused by the tool itself.
            { ScriptedModel.toolResponse(TOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to false)) },
            { ScriptedModel.toolResponse(TOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to true)) },
            { ScriptedModel.toolResponse(TOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to false)) },
            { ScriptedModel.toolResponse(TOOL.viewWorkflow to mapOf(GDF.workflowId to "rsvp")) },
        )
        val conversationId = send(admin, "Add an RSVP workflow.")
        val toolResults = messagesOf(admin, conversationId).filter { it[MSG.role] == MSG.user }.drop(1).map { results(it).single() }
        toolResults[0][TOOL.isError] shouldBe true
        toolResults[0][TOOL.content].toString() shouldContain "dry run"
        toolResults[1][TOOL.isError].shouldBeNull()
        toolResults[2][TOOL.isError].shouldBeNull()
        // The sandbox runs the draft at once.
        toolResults[3][TOOL.isError].shouldBeNull()
        toolResults[3][TOOL.content].toString().jsonMap().shouldNotBeNull()["found"] shouldBe true

        fun workflowIds(revision: String) = admin.getItem(CFEP.bundle, mapOf(CFEP.name to "flows", CFEP.revision to revision))[CFEP.slots]
            .toJsonMapOrEmpty()[CCT.workflowDef].toJsonListOfMaps().map { it[CCT.workflowId] }.toSet()
        workflowIds("latest") shouldBe setOf("review", "rsvp")
        workflowIds("published") shouldBe setOf("review")
        // The draft is the parent's, written as the administrator who stands behind the sandbox user.
        admin.getItems(CFEP.slotEntries, mapOf(CFEP.name to "flows")).first { it[GE.data].toJsonMapOrEmpty()[CCT.workflowId] == "rsvp" }[
            PF.updatedBy,
        ] shouldBe admin.userId
        // Four steps, the step limit: the turn stopped there, its last tool call answered.
        lastTurn(admin, conversationId)[ACNV.status] shouldBe ACNV.statusStopped
        historyHolds(messagesOf(admin, conversationId))
    }

    "the client's plan refuses the assistant's change as it would a person's" {
        val client = "${CUST.free}_asst1526"
        val admin = clientWithAssistant(cxt, client, workflows = 4)
        val flows = admin.getItem(CFEP.bundle, mapOf(CFEP.name to "flows"))[CFEP.slots].toJsonMapOrEmpty()[CCT.workflowDef]
            .toJsonListOfMaps()
        val fifth = renamed(flows.first(), "review", "review5")
        val configs = listOf(
            mapOf(
                CFEP.name to "flows",
                CFEP.edits to listOf(workflowEdit(fifth)),
            ),
        )
        model.add(
            { ScriptedModel.toolResponse(TOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to true)) },
            { ScriptedModel.textResponse("Your plan allows four workflows.") },
        )
        val conversationId = send(admin, "Add a fifth workflow.")
        val refused = results(messagesOf(admin, conversationId)[2]).single()
        refused[TOOL.isError] shouldBe true
        refused[TOOL.content].toString() shouldContain "free plan"
    }

    "a reply cut off, a refusal, and a pause each end or go on as they should" {
        val admin = clientWithAssistant(cxt, "asststops")
        // Cut off with a tool call in it: the call is answered as not run, and the turn stops.
        model.add({ ScriptedModel.toolResponse(TOOL.listConfigs to emptyMap(), stopReason = MSG.maxTokens) })
        val cut = send(admin, "Tell me everything.")
        results(messagesOf(admin, cut).last()).single()[TOOL.content].toString() shouldContain "cut off"
        lastTurn(admin, cut)[ACNV.status] shouldBe ACNV.statusStopped

        // A refusal with nothing in it is not stored: it could not be sent back.
        model.add({ ScriptedModel.response(emptyList(), MSG.refusal) })
        val declined = send(admin, "Something else.")
        messagesOf(admin, declined).map { it[MSG.role] } shouldBe listOf(MSG.user)
        lastTurn(admin, declined)[ACNV.note].toString() shouldContain "declined"

        // A pause is asked again.
        model.add(
            { ScriptedModel.response(listOf(mapOf(MSG.type to MSG.text, MSG.text to "Working.")), MSG.pauseTurn) },
            { ScriptedModel.textResponse("Done.") },
        )
        val paused = send(admin, "Take your time.", conversationId = declined)
        lastTurn(admin, paused)[ACNV.status] shouldBe ACNV.statusDone
        lastTurn(admin, paused)[ACNV.steps] shouldBe 2
        historyHolds(messagesOf(admin, paused))
    }

    "a cancel stops the turn at its next step, answering the calls it had not run" {
        val admin = clientWithAssistant(cxt, "asstcancel")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        model.add({
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
            ScriptedModel.toolResponse(TOOL.listConfigs to emptyMap(), TOOL.listTraits to emptyMap())
        })
        val started = admin.postData(ACNV.messagePath, mapOf(ACNV.clientMessageId to "cancel1", ACNV.text to "Look around."))
        val conversationId = started[ACNV.conversationId].toString()
        entered.await(20, TimeUnit.SECONDS) shouldBe true
        // A second message while the first runs is refused.
        admin.expectError(EXC.conflict, ACNV.messagePath, message("cancel2", "And?", conversationId))[EP.errorCode] shouldBe ACNV.busyCode
        admin.postData(ACNV.cancelPath, mapOf(ACNV.conversationId to conversationId))[ACNV.canceled] shouldBe true
        release.countDown()
        service.awaitTurn(started[ACNV.turnId].toString())
        val answered = results(messagesOf(admin, conversationId).last())
        answered shouldHaveSize 2
        answered.forEach { it[TOOL.content].toString() shouldContain "stopped the assistant" }
        lastTurn(admin, conversationId)[ACNV.status] shouldBe ACNV.statusCanceled
        admin.postData(ACNV.cancelPath, mapOf(ACNV.conversationId to conversationId))[ACNV.canceled] shouldBe false
        historyHolds(messagesOf(admin, conversationId))
    }

    "a spent budget refuses a turn at its start, and stops one at a step" {
        val client = "asstbudget"
        val admin = clientWithAssistant(cxt, client)
        service.setClient(cxt, client, enabled = null, monthlyBudget = 1_000, useDefaultBudget = false, acknowledgedNotice = false)
        // A step costing more than the whole budget: the turn runs it, then stops before the next.
        model.add({ ScriptedModel.toolResponse(TOOL.listConfigs to emptyMap(), usage = ModelUsage(inputTokens = 2_000)) })
        val conversationId = send(admin, "Look.")
        lastTurn(admin, conversationId).let {
            it[ACNV.status] shouldBe ACNV.statusStopped
            it[ACNV.note].toString() shouldContain "allowance"
        }
        admin.expectError(EXC.tooManyRequests, ACNV.messagePath, mapOf(ACNV.clientMessageId to "budget2", ACNV.text to "More."))[
            EP.errorCode,
        ] shouldBe ACNV.budgetSpentCode
    }

    "a message is refused when the assistant is off, with no sandbox, past the hour's limit, or in an older conversation" {
        val noSandbox = clientWithAssistant(cxt, "asstnosbx", sandbox = false)
        noSandbox.expectError(EXC.conflict, ACNV.messagePath, message("x1", "Hi."))[EP.errorCode] shouldBe
            ACNV.noSandboxCode

        val client = "asstlimits"
        val admin = clientWithAssistant(cxt, client)
        service.setClient(cxt, client, enabled = false, monthlyBudget = null, useDefaultBudget = false, acknowledgedNotice = false)
        admin.expectError(EXC.notAuthorized, ACNV.messagePath, message("x2", "Hi."))[EP.errorCode] shouldBe
            ACNV.notOnCode
        service.setClient(cxt, client, enabled = true, monthlyBudget = null, useDefaultBudget = false, acknowledgedNotice = true)

        // A conversation begun under another version of the prompt and tools cannot go on.
        AssistantStore.insertConversation(
            cxt,
            mapOf(
                ACNV.conversationId to "old1526", "client" to client, PF.userId to admin.userId, ACNV.title to "Old",
                ATBL.toolsetVersion to "an-earlier-one",
            ),
        )
        admin.getItems(ACNV.conversationsPath).single { it[ACNV.conversationId] == "old1526" }[ACNV.readOnly] shouldBe true
        admin.expectError(
            EXC.conflict, ACNV.messagePath, mapOf(ACNV.clientMessageId to "x3", ACNV.text to "Hi.", ACNV.conversationId to "old1526"),
        )[EP.errorCode] shouldBe ACNV.readOnlyCode

        // Six turns an hour on this instance.
        repeat(6) {
            model.add({ ScriptedModel.textResponse("Hello.") })
            send(admin, "Hello $it.")
        }
        admin.expectError(EXC.tooManyRequests, ACNV.messagePath, message("x4", "Hi."))[EP.errorCode] shouldBe
            ACNV.turnLimitCode
        // Another administrator's conversation is not this one's to read.
        val other = TestUser.create(cxt, "deputy@$client.test", level = ROLE.admin, userClient = client)
        other.expectError(EXC.notFound, ACNV.conversationPath, args = mapOf(ACNV.conversationId to "old1526"))
    }

    "the tools reach exactly their endpoints: everything left out is refused through the seam" {
        val client = "asstseam"
        val admin = clientWithAssistant(cxt, client)
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        admin.postData(AEP.leaveSandbox, mapOf(AFLD.userId to admin.userId))
        val row = UserService.get(cxt).queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        val principal = InProcessPrincipal(sandboxUserId, row.identityId, row.client, "assistant:test", AssistantTools.scope)
        fun call(method: HttpMethod, path: String, args: Map<String, Any?>? = null) =
            InProcessCaller.call(cxt.instanceConfig, principal, method, path, args, traceId = "t1526")

        val excluded = listOf(
            HttpMethod.POST to CFEP.bundleWrite, HttpMethod.POST to CFEP.bundlePublish, HttpMethod.POST to CFEP.bundleRevert,
            HttpMethod.POST to CFEP.bundleDelete, HttpMethod.POST to CFEP.publishedOnly, HttpMethod.POST to UADEP.clientSandbox,
            HttpMethod.POST to UADEP.clientWorkflowParams, HttpMethod.POST to ACNV.messagePath, HttpMethod.POST to UADEP.userCreate,
            HttpMethod.POST to ADEP.userCreate, HttpMethod.GET to OENV.envReferencePath, HttpMethod.POST to TEP.becomeUser,
        )
        for ((method, path) in excluded) {
            val refused = call(method, path)
            refused.status shouldBe EXC.notAuthorized
            refused.errorMessage.shouldNotBeNull() shouldContain "not open to the workflow assistant"
        }
        for (tool in AssistantTools.all.filter { it.method == HttpMethod.GET }) {
            val answer = call(tool.method!!, tool.path!!, if (CFEP.name in tool.sends) mapOf(CFEP.name to "flows") else null)
            (tool.name to answer.status) shouldBe (tool.name to EXC.ok)
        }
    }

    "each tool's endpoint exists, is open to the seam, and takes what the tool sends; the assistant's own are session-only" {
        val schema = cxt.getGlobalSchema()
        for (tool in AssistantTools.all.filter { it.path != null }) {
            val endpoint = schema.endpoints["${tool.path}:${tool.method!!.name}"].shouldNotBeNull()
            endpoint.sessionOnly shouldBe false
            val input = resolveEndpointInputType(endpoint, schema.types).shouldNotBeNull()
            for (name in tool.sends) input.properties.keys shouldContain name
        }
        AssistantTools.byName.keys shouldBe AssistantTools.all.map { it.name }.toSet()
        for (path in listOf(ACNV.messagePath, ACNV.cancelPath)) schema.endpoints["$path:POST"].shouldNotBeNull().sessionOnly shouldBe true
        for (path in listOf(ACNV.conversationPath, ACNV.conversationsPath)) {
            schema.endpoints["$path:GET"].shouldNotBeNull().sessionOnly shouldBe true
        }
        AssistantTools.readableDoc(cxt, "client-configuration").shouldNotBeNull()
        AssistantTools.readableDoc(cxt, "api-guide").shouldBeNull()
    }

    "a turn whose node stopped is marked interrupted when its lease lapses, and its worker writes nothing more" {
        val lonely = ScriptedModel(emptyList())
        val own = boot("assistantLease1526", lonely)
        val admin = clientWithAssistant(own, "asstlease")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lonely.add({
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
            ScriptedModel.toolResponse(TOOL.listConfigs to emptyMap())
        })
        val started = admin.postData(ACNV.messagePath, mapOf(ACNV.clientMessageId to "lease1", ACNV.text to "Look."))
        val conversationId = started[ACNV.conversationId].toString()
        entered.await(20, TimeUnit.SECONDS) shouldBe true
        own.instanceConfig.clock.advanceBy((TURN.leaseMinutes + 1).minutes)
        val seen = admin.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to conversationId))
        seen[ACNV.turns].toJsonListOfMaps().single()[ACNV.status] shouldBe ACNV.statusInterrupted
        seen[ACNV.conversation].toJsonMapOrEmpty()[ACNV.running] shouldBe false
        release.countDown()
        AssistantService.get(own).awaitTurn(started[ACNV.turnId].toString())
        // The worker's answer came after the turn was given up: nothing of it is stored.
        admin.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to conversationId))[ACNV.messages].toJsonListOfMaps()
            .map { it[ACNV.role] } shouldBe listOf(MSG.user)
    }
})
