package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.resolveEndpointInputType
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
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
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.InProcessCaller
import com.dynamicruntime.common.http.request.InProcessPrincipal
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.operator.OENV
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstantOrNull
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
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
    // A case that failed part way leaves its unused steps behind; the next case starts with none.
    beforeEach { model.clear() }

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
            { ScriptedModel.toolResponse(ATOOL.listConfigs to emptyMap(), ATOOL.listWorkflows to emptyMap()) },
            { ScriptedModel.textResponse("You have one workflow, review.") },
        )
        val conversationId = send(admin, "What workflows do I have?")
        val firstId = "m$messageIds"
        val messages = messagesOf(admin, conversationId)
        messages.map { it[MSG.role] } shouldBe listOf(MSG.user, MSG.assistant, MSG.user, MSG.assistant)
        val answered = results(messages[2])
        answered shouldHaveSize 2
        answered.forEach { it[TOOL.isError].shouldBeNull() }
        answered[1][TOOL.content].toString() shouldContain "review"
        // The endpoint's envelope is gone: a listing is its items.
        answered[0][TOOL.content].toString() shouldStartWith "{\"${EP.items}\""
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
        // The same message id sent into another conversation is the page's mistake, not that turn.
        model.add({ ScriptedModel.textResponse("Hello.") })
        val other = send(admin, "Another conversation.")
        admin.expectError(EXC.conflict, ACNV.messagePath, message("m$messageIds", "Again?", conversationId)).toString() shouldContain
            "another conversation"
        // The first message's id sent again with other text: a new message given an old id, refused rather than answered
        // with the old message's turn.
        val reused = admin.expectError(EXC.conflict, ACNV.messagePath, message(firstId, "Something new.", conversationId))
        reused[EP.errorCode] shouldBe ACNV.messageIdUsedCode
        reused[EP.errorMessage].toString() shouldContain "another message"
        other shouldNotBe conversationId
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
            { ScriptedModel.toolResponse(ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to false)) },
            { ScriptedModel.toolResponse(ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to true)) },
            { ScriptedModel.toolResponse(ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to false)) },
            { ScriptedModel.toolResponse(ATOOL.viewWorkflow to mapOf(GDF.workflowId to "rsvp")) },
        )
        val conversationId = send(admin, "Add an RSVP workflow.")
        val toolResults = messagesOf(admin, conversationId).filter { it[MSG.role] == MSG.user }.drop(1).map { results(it).single() }
        toolResults[0][TOOL.isError] shouldBe true
        toolResults[0][TOOL.content].toString() shouldContain "dry run"
        toolResults[1][TOOL.isError].shouldBeNull()
        // The dry run's answer is its impact, not the bundles the model sent.
        toolResults[1][TOOL.content].toString().jsonMap().shouldNotBeNull().keys shouldNotContain CFEP.configs
        toolResults[2][TOOL.isError].shouldBeNull()
        // The write's answer keeps the bundles as written: what the model needs for its next edit.
        toolResults[2][TOOL.content].toString().jsonMap().shouldNotBeNull().keys shouldContain CFEP.configs
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

    "a write is refused in the same message as its dry run: the model must read the dry run's answer first" {
        val admin = clientWithAssistant(cxt, "asstsamestep")
        val review = admin.getItem(CFEP.bundle, mapOf(CFEP.name to "flows"))[CFEP.slots].toJsonMapOrEmpty()[CCT.workflowDef]
            .toJsonListOfMaps().single()
        val configs = listOf(mapOf(CFEP.name to "flows", CFEP.edits to listOf(workflowEdit(renamed(review, "review", "early")))))
        model.add(
            {
                ScriptedModel.toolResponse(
                    ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to true),
                    ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to false),
                )
            },
            { ScriptedModel.textResponse("I will write it next.") },
        )
        val conversationId = send(admin, "Add it in one go.")
        val (dryRun, write) = results(messagesOf(admin, conversationId)[2])
        dryRun[TOOL.isError].shouldBeNull()
        write[TOOL.isError] shouldBe true
        write[TOOL.content].toString() shouldContain "later step"
    }

    "the included global traits are set as a draft" {
        val admin = clientWithAssistant(cxt, "assttraits")
        model.add(
            { ScriptedModel.toolResponse(ATOOL.setIncludedTraits to mapOf(CLD.includedTraits to listOf(CLD.allGlobal))) },
            { ScriptedModel.textResponse("Included.") },
        )
        val set = results(messagesOf(admin, send(admin, "Include every global trait."))[2]).single()
        set[TOOL.isError].shouldBeNull()
        set[TOOL.content].toString().jsonMap().shouldNotBeNull()[CPY.mode] shouldBe EDM.draft
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
            { ScriptedModel.toolResponse(ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to true)) },
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
        model.add({ ScriptedModel.toolResponse(ATOOL.listConfigs to emptyMap(), stopReason = MSG.maxTokens) })
        val cut = send(admin, "Tell me everything.")
        results(messagesOf(admin, cut).last()).single()[TOOL.content].toString() shouldContain "cut off"
        lastTurn(admin, cut)[ACNV.status] shouldBe ACNV.statusStopped

        // A refusal with nothing in it is not stored -- it could not be sent back -- so the conversation goes on.
        model.add({ ScriptedModel.response(emptyList(), MSG.refusal) })
        val declinedEmpty = send(admin, "Something else.")
        messagesOf(admin, declinedEmpty).map { it[MSG.role] } shouldBe listOf(MSG.user)
        lastTurn(admin, declinedEmpty)[ACNV.note].toString() shouldContain "declined"
        conversation(admin, declinedEmpty)[ACNV.conversation].toJsonMapOrEmpty()[ACNV.readOnly] shouldBe false

        // A refusal with content stays in the history, and would meet the next request: the conversation ends there.
        model.add({ ScriptedModel.response(listOf(mapOf(MSG.type to MSG.text, MSG.text to "I can't help with that.")), MSG.refusal) })
        val declined = send(admin, "Something else again.", conversationId = declinedEmpty)
        conversation(admin, declined)[ACNV.conversation].toJsonMapOrEmpty()[ACNV.readOnly] shouldBe true
        admin.expectError(EXC.conflict, ACNV.messagePath, message("declined2", "Try again.", declined))[EP.errorCode] shouldBe
            ACNV.readOnlyCode

        // A stop this code does not know, with a call in it: the call is answered, and the conversation goes on.
        model.add(
            { ScriptedModel.toolResponse(ATOOL.listConfigs to emptyMap(), stopReason = "model_context_window_exceeded") },
            { ScriptedModel.textResponse("Still here.") },
        )
        val unknown = send(admin, "Odd stop.")
        results(messagesOf(admin, unknown).last()).single()[TOOL.content].toString() shouldContain "Not run"
        send(admin, "And now?", conversationId = unknown)
        lastTurn(admin, unknown)[ACNV.status] shouldBe ACNV.statusDone

        // A pause is asked again.
        model.add(
            { ScriptedModel.response(listOf(mapOf(MSG.type to MSG.text, MSG.text to "Working.")), MSG.pauseTurn) },
            { ScriptedModel.textResponse("Done.") },
        )
        val paused = send(admin, "Take your time.")
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
            ScriptedModel.toolResponse(ATOOL.listConfigs to emptyMap(), ATOOL.listTraits to emptyMap())
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
        model.add({ ScriptedModel.toolResponse(ATOOL.listConfigs to emptyMap(), usage = ModelUsage(inputTokens = 2_000)) })
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

    "the page's status says whether the assistant can be used, and from the sandbox the person's conversations go on" {
        val client = "asstpage"
        val admin = clientWithAssistant(cxt, client)
        fun menuIds(user: TestUser): List<Any?> =
            user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.menu].toJsonListOfMaps().map { it[HFLD.id] }
        admin.getData(ACNV.statusPath).let {
            it[ASST.client] shouldBe client
            it[ACNV.on] shouldBe true
            it[ACNV.hasSandbox] shouldBe true
            it[ACNV.inSandbox] shouldBe false
            it[ACNV.spent] shouldBe false
            it[ACNV.usedShare].toString().toDouble() shouldBe 0.0
            it[ACNV.resetsAt].toOptInstantOrNull().shouldNotBeNull() shouldBeGreaterThan cxt.now()
            it[ASST.dataNotice].toString() shouldContain "Anthropic"
        }
        // The menu offers the page beside Reports, to an administrator of a client it is on for.
        val menu = menuIds(admin)
        menu shouldContain ASST.menuItem
        menu.indexOf(ASST.menuItem) shouldBe menu.indexOf(HMENU.reports) + 1
        menuIds(TestUser.create(cxt, "member@$client.test", level = ROLE.user, userClient = client)) shouldNotContain ASST.menuItem

        model.add({ ScriptedModel.textResponse("Hello.") })
        val conversationId = send(admin, "Hello.")
        // Signed in to the sandbox -- as "Try it in the sandbox" leaves them -- the person's conversation is still theirs.
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        admin.getData(ACNV.statusPath).let {
            it[ASST.client] shouldBe client
            it[ACNV.inSandbox] shouldBe true
        }
        menuIds(admin) shouldContain ASST.menuItem
        admin.getItems(ACNV.conversationsPath).map { it[ACNV.conversationId] } shouldContain conversationId
        model.add({ ScriptedModel.toolResponse(ATOOL.listWorkflows to emptyMap()) }, { ScriptedModel.textResponse("Still here.") })
        send(admin, "And now?", conversationId)
        val turn = lastTurn(admin, conversationId)
        turn[ACNV.status] shouldBe ACNV.statusDone
        // Its tools act as the user the session has in the sandbox.
        AssistantStore.turn(cxt, turn[ACNV.turnId].toString()).shouldNotBeNull()[ATBL.sandboxUserId].toOptLong() shouldBe sandboxUserId

        // Nobody outside stands behind these two in the sandbox, so the assistant answers neither -- and the menu, asking
        // the same, does not offer them the page: an administrator of every client who opened this client's sandbox from
        // their own, and a sandbox's own administrator whom nobody opened it for.
        val across = TestUser.createFullAdmin(cxt, "across@example.com")
        across.postData(AEP.openSandbox, mapOf(AFLD.client to client))[UPF.client] shouldBe sandboxOf(client)
        // Its admin takes effect through the same person's administrator in the client.
        TestUser.create(cxt, "deputy@$client.test", level = ROLE.admin, userClient = client)
        val own = TestUser.create(cxt, "deputy@$client.test", level = ROLE.admin, userClient = sandboxOf(client))
        for (refused in listOf(across, own)) {
            menuIds(refused) shouldNotContain ASST.menuItem
            refused.expectError(EXC.badInput, ACNV.statusPath)
            refused.expectError(EXC.badInput, ACNV.conversationsPath)
        }

        // A client with no sandbox, or one it is off for, says so; a spent month says when it starts again.
        clientWithAssistant(cxt, "asstpagenosbx", sandbox = false).getData(ACNV.statusPath)[ACNV.hasSandbox] shouldBe false
        val spent = clientWithAssistant(cxt, "asstpagespent")
        service.setClient(cxt, "asstpagespent", enabled = false, monthlyBudget = 1, useDefaultBudget = false, acknowledgedNotice = false)
        service.recordUsage(cxt, "asstpagespent", service.settings.model, ModelUsage(inputTokens = 10))
        spent.getData(ACNV.statusPath).let {
            it[ACNV.on] shouldBe false
            it[ACNV.spent] shouldBe true
            it[ACNV.usedShare].toString().toDouble() shouldBe 10.0
        }
        menuIds(spent) shouldNotContain ASST.menuItem
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
        val more = listOf(
            HttpMethod.POST to CFEP.reload, HttpMethod.GET to CFEP.editor, HttpMethod.GET to CFEP.bundleImpact,
            // A permitted path by another method.
            HttpMethod.POST to CFEP.bundles, HttpMethod.GET to CFEP.bundlePatch,
        )
        for ((method, path) in excluded + more) {
            val refused = call(method, path)
            refused.status shouldBe EXC.notAuthorized
            refused.errorMessage.shouldNotBeNull() shouldContain "not open to the workflow assistant"
        }
        // A client's own copy of a permitted endpoint is not that endpoint: the scope names paths exactly.
        for (path in listOf("/clientOperator/$client/cfacts", "/gedra/$client/workflow/view")) {
            call(HttpMethod.GET, path).isSuccess shouldBe false
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
        // The codes a refused value is reported with (issue #1559), generated, are readable too.
        AssistantTools.readableDoc(cxt, "validation-messages").shouldNotBeNull()
        AssistantTools.readableDoc(cxt, "api-guide").shouldBeNull()
    }

    "the demo provider goes through a real tool round: it lists the workflows, and names them" {
        val demo = Startup.mkTestBootCxt(
            "assistantDemoTurn1526", "assistantDemoTurn1526", mapOf(AENV.provider.name to AENV.fakeProvider),
            additionalComponents = listOf(AssistantComponent()),
        )
        val admin = clientWithAssistant(demo, "asstdemo")
        val conversationId = send(admin, "What do I have?", on = demo)
        val messages = messagesOf(admin, conversationId)
        messages.map { it[MSG.role] } shouldBe listOf(MSG.user, MSG.assistant, MSG.user, MSG.assistant)
        results(messages[2]).single()[TOOL.isError].shouldBeNull()
        messages[3][MSG.content].toJsonListOfMaps().single()[MSG.text].toString() shouldContain "found 1: review"
        lastTurn(admin, conversationId)[ACNV.status] shouldBe ACNV.statusDone

        // Asked to draft, it drafts a copy of a template -- a dry run, then the write -- which the sandbox runs.
        send(admin, "Please draft a workflow.", conversationId, on = demo)
        val drafted = messagesOf(admin, conversationId).drop(4)
        drafted.map { it[MSG.role] } shouldBe List(4) { listOf(MSG.user, MSG.assistant) }.flatten()
        drafted.filter { it[MSG.role] == MSG.user }.drop(1).map { results(it).single() }.forEach { it[TOOL.isError].shouldBeNull() }
        drafted.last()[MSG.content].toJsonListOfMaps().single()[MSG.text].toString() shouldContain "Drafted the workflow"
        admin.getItem(CFEP.bundle, mapOf(CFEP.name to DEMO.bundle))[CFEP.slots].toJsonMapOrEmpty()[CCT.workflowDef]
            .toJsonListOfMaps().map { it[CCT.workflowId] } shouldBe listOf(DEMO.workflowId)

        // A client whose only workflow was made from a template: the copy is written whole, not as the template's overlay.
        val fromTemplate = clientWithAssistant(demo, "asstdemotpl", workflows = 0)
        val slots = WfTemplateSlots(null, WFT.collect, "Collect memos", listOf(WfTaskSlot("collect", "Memo", listOf(WfTraitRef("memo")))))
        fromTemplate.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots.toInfo()))
        val copied = send(fromTemplate, "Draft one like it.", on = demo)
        messagesOf(fromTemplate, copied).last()[MSG.content].toJsonListOfMaps().single()[MSG.text].toString() shouldContain
            "Drafted the workflow"
    }

    "a long result is cut, saying so, and never through a character written as two" {
        val emoji = "\uD83D\uDE00"
        val text = "a".repeat(TOOL.maxResultChars - 1) + emoji + "tail"
        val cut = ToolRun.capped(text)
        cut shouldStartWith "a".repeat(TOOL.maxResultChars - 1)
        cut.substringBefore("\n[Cut") shouldBe "a".repeat(TOOL.maxResultChars - 1)
        cut shouldContain "[Cut: ${TOOL.maxResultChars - 1} of ${text.length} characters shown."
        ToolRun.capped("short") shouldBe "short"
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
            ScriptedModel.toolResponse(ATOOL.listConfigs to emptyMap())
        })
        val started = admin.postData(ACNV.messagePath, mapOf(ACNV.clientMessageId to "lease1", ACNV.text to "Look."))
        val conversationId = started[ACNV.conversationId].toString()
        entered.await(20, TimeUnit.SECONDS) shouldBe true
        own.instanceConfig.clock.advanceBy((TURN.leaseMinutes + 1).minutes)
        // A lapsed turn holds no place in the client's limit, even before anyone looks at its conversation.
        AssistantStore.runningTurns(own, "asstlease", own.now()) shouldBe 0
        val seen = admin.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to conversationId))
        seen[ACNV.turns].toJsonListOfMaps().single()[ACNV.status] shouldBe ACNV.statusInterrupted
        seen[ACNV.conversation].toJsonMapOrEmpty()[ACNV.running] shouldBe false
        release.countDown()
        AssistantService.get(own).awaitTurn(started[ACNV.turnId].toString())
        // The worker's answer came after the turn was given up: nothing of it is stored.
        admin.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to conversationId))[ACNV.messages].toJsonListOfMaps()
            .map { it[ACNV.role] } shouldBe listOf(MSG.user)

        // A conversation claimed by a message still being started -- its turn not yet recorded -- is left alone, until
        // the claim is as old as a lease: then its start failed, and the claim is freed.
        AssistantStore.insertConversation(
            own,
            mapOf(
                ACNV.conversationId to "starting1526", "client" to "asstlease", PF.userId to admin.userId, ACNV.title to "Starting",
                ATBL.toolsetVersion to AssistantPrompt.versionOf("asstlease"), ATBL.runningTurnId to "notYetRecorded",
            ),
        )
        fun starting() = admin.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to "starting1526"))[ACNV.conversation]
            .toJsonMapOrEmpty()[ACNV.running]
        starting() shouldBe true
        own.instanceConfig.clock.advanceBy((TURN.leaseMinutes + 1).minutes)
        starting() shouldBe false
    }
})
