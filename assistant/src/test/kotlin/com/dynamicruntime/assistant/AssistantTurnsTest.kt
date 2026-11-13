package com.dynamicruntime.assistant

import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.content.CPAGE
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
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.gedra.CUS
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.WorkflowLayout
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFRAG
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
import com.dynamicruntime.common.util.json
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstantOrNull
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
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
                trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") {
                    property("text", "What it says.")
                    // A choice, for Design View's shared choices (issue #1765).
                    property("kind", "What kind of memo it is.") {
                        option("note", "A note")
                        option("idea", "An idea")
                    }
                }
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

    // A false suppression, IntelliJ has a bug where it does not see that messageIds can be mutated - but the built-in
    // compiler shows an error if you change it to val - so different parts of IntelliJ disagree on this.
    @Suppress("CanBeVal") var messageIds = 0

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

    "a draft brings the forms the sandbox already holds up to date, so a new workflow is offered to them" {
        // Issue #1770: the forms existed before the workflow was drafted, as a real client's would.
        val client = "asstrecalc"
        val admin = clientWithAssistant(cxt, client)
        admin.postData(AEP.openSandbox, emptyMap())
        repeat(2) { n ->
            val memo = mapOf(GE.traitId to "memo", GE.data to mapOf("text" to "Form $n"))
            admin.postData(GEP.formDocCreate, mapOf(GDF.entries to listOf(memo)))
        }
        fun eligibleForRsvp() = admin.getItems(GEP.workflowAggregate).firstOrNull { it[WFD.workflowId] == "rsvp" }?.get(WAGG.eligible)
        admin.postData(AEP.leaveSandbox, mapOf(AFLD.userId to admin.userId))
        val review = admin.getItem(CFEP.bundle, mapOf(CFEP.name to "flows"))[CFEP.slots].toJsonMapOrEmpty()[CCT.workflowDef]
            .toJsonListOfMaps().single()
        val configs = listOf(mapOf(CFEP.name to "flows", CFEP.edits to listOf(workflowEdit(renamed(review, "review", "rsvp")))))
        model.add(
            { ScriptedModel.toolResponse(ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to true)) },
            { ScriptedModel.toolResponse(ATOOL.patchConfig to mapOf(CFEP.configs to configs, CFEP.dryRun to false)) },
            { ScriptedModel.textResponse("Drafted.") },
        )
        val conversationId = send(admin, "Add an RSVP workflow for my existing memos.")
        val write = messagesOf(admin, conversationId).filter { it[MSG.role] == MSG.user }.drop(1).map { results(it).single() }[1]
        write[TOOL.isError].shouldBeNull()
        // The write says what it did to the sandbox's forms, so the model can tell the administrator.
        val auto = write[TOOL.content].toString().jsonMap().shouldNotBeNull()[FSR.auto].toJsonMapOrEmpty()
        auto[FSR.client] shouldBe sandboxOf(client)
        auto[FSR.outcome] shouldBe FSR.completed
        auto[FSR.updated] shouldBe 2
        // Each existing form is offered the drafted workflow at once, with no recalculation asked for.
        admin.postData(AEP.openSandbox, emptyMap())
        eligibleForRsvp() shouldBe 2
        admin.postData(AEP.leaveSandbox, mapOf(AFLD.userId to admin.userId))

        // set_included_traits brings them up to date too: its save recalculates a draft's sandbox (issue #1770's check).
        model.add(
            { ScriptedModel.toolResponse(ATOOL.setIncludedTraits to mapOf(CLD.includedTraits to listOf(CLD.allGlobal))) },
            { ScriptedModel.textResponse("Included.") },
        )
        val set = results(messagesOf(admin, send(admin, "Include every global trait."))[2]).single()
        set[TOOL.content].toString().jsonMap().shouldNotBeNull()[FSR.auto].toJsonMapOrEmpty()[FSR.client] shouldBe sandboxOf(client)
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
        @Suppress("BlockingMethodInNonBlockingContext")
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

    "a person deletes a conversation of their own: its transcript and their words go, what its turns used stays" {
        val admin = clientWithAssistant(cxt, "asstdelete")
        model.add({ ScriptedModel.textResponse("A review of each memo, then.") })
        val conversationId = send(admin, "Plan a review of each memo.")
        val used = service.usedThisMonth(cxt, "asstdelete")
        // Another administrator of the client cannot see it, so cannot delete it.
        val deputy = TestUser.create(cxt, "deputy@asstdelete.test", level = ROLE.admin, userClient = "asstdelete")
        deputy.expectError(EXC.notFound, ACNV.deletePath, mapOf(ACNV.conversationId to conversationId))

        admin.postData(ACNV.deletePath, mapOf(ACNV.conversationId to conversationId))[ACNV.deleted] shouldBe true
        admin.getItems(ACNV.conversationsPath).map { it[ACNV.conversationId] } shouldNotContain conversationId
        admin.expectError(EXC.notFound, ACNV.conversationPath, mapOf(ACNV.conversationId to conversationId))
        admin.expectError(EXC.notFound, ACNV.deletePath, mapOf(ACNV.conversationId to conversationId))
        // Nothing they wrote is left; the turn is, with what it used, so the month's allowance is as it was.
        AssistantStore.messages(cxt, conversationId).shouldBeEmpty()
        AssistantStore.conversation(cxt, conversationId) shouldBe null
        val turn = AssistantStore.turnsOf(cxt, conversationId).single()
        // Blanked: the database may give an empty string back as none.
        turn[ACNV.text].toOptStr().orEmpty() shouldBe ""
        turn[ACNV.weighted].toOptLong().shouldNotBeNull() shouldBeGreaterThan 0L
        service.usedThisMonth(cxt, "asstdelete") shouldBe used
    }

    "a conversation is not deleted while a turn runs in it" {
        val admin = clientWithAssistant(cxt, "asstdeleterun")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        model.add({
            entered.countDown()
            release.await(20, TimeUnit.SECONDS)
            ScriptedModel.textResponse("Done looking.")
        })
        val started = admin.postData(ACNV.messagePath, mapOf(ACNV.clientMessageId to "delrun1", ACNV.text to "Look around."))
        val conversationId = started[ACNV.conversationId].toString()
        @Suppress("BlockingMethodInNonBlockingContext")
        entered.await(20, TimeUnit.SECONDS) shouldBe true
        admin.expectError(EXC.conflict, ACNV.deletePath, mapOf(ACNV.conversationId to conversationId))[EP.errorCode] shouldBe
            ACNV.busyCode
        release.countDown()
        service.awaitTurn(started[ACNV.turnId].toString())
        admin.postData(ACNV.deletePath, mapOf(ACNV.conversationId to conversationId))[ACNV.deleted] shouldBe true
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
            // From the sandbox these answer for its own users and forms, not the client's (issue #1762), so none is a tool.
            HttpMethod.GET to UADEP.userSearch, HttpMethod.GET to GEP.formDocs,
        )
        val more = listOf(
            HttpMethod.POST to CFEP.reload, HttpMethod.GET to CFEP.editor,
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
        // Every read answers from the sandbox, given what it requires.
        val requires = mapOf(
            CFEP.name to "flows", CFEP.names to "flows", DSV.slot to CCT.traitDef, DSV.key to "memo",
        )
        for (tool in AssistantTools.all.filter { it.method == HttpMethod.GET }) {
            val answer = call(tool.method!!, tool.path!!, requires.filterKeys { it in tool.sends }.ifEmpty { null })
            (tool.name to answer.status) shouldBe (tool.name to EXC.ok)
        }
    }

    "the reads answer for the client as its sandbox runs it: its definition, menu, copy, drafts, and where a trait comes from" {
        // Issue #1762: what the client's own configuration does not show, merged from every layer.
        val client = "asstreads"
        val admin = clientWithAssistant(cxt, client)
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        admin.postData(AEP.leaveSandbox, mapOf(AFLD.userId to admin.userId))
        val row = UserService.get(cxt).queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        val principal = InProcessPrincipal(sandboxUserId, row.identityId, row.client, "assistant:test", AssistantTools.scope)
        val run = ToolRun(cxt, principal, "t1762")
        fun read(name: String, input: Map<String, Any?> = emptyMap()): Any? {
            val result = AssistantTools.run(run, name, input)
            result.isError shouldBe false
            return result.text.json()
        }

        // The definition, as the sandbox runs it.
        read(ATOOL.readClient).toJsonMapOrEmpty()[CLD.client].toJsonMapOrEmpty()[CLD.clientId] shouldBe sandboxOf(client)
        // The menu, the platform's items among it: the Workflows entry ships turned off.
        val menu = read(ATOOL.readMenu).toJsonMapOrEmpty()
        val workflows = menu[EP.items].toJsonListOfMaps().single { it[COV.itemId] == HMENU.workflows }
        workflows[MNU.condition] shouldBe CFACT.neverName
        // The audiences are said once, and the platform's condition only where the client's differs: here it does not.
        menu[MNU.audiences].toJsonListOfMaps().shouldNotBeEmpty()
        workflows.keys shouldNotContain MNU.audiences
        workflows.keys shouldNotContain MNU.baseCondition
        // The copy: an index of the pages and the files, then one file's namespace whole.
        val index = read(ATOOL.readCopy).toJsonMapOrEmpty()
        index[CPYIDX.files].toJsonListOfMaps().single { it[COV.fileId] == HFRAG.home }[CPYIDX.namespaces]
            .toJsonMapOrEmpty().keys shouldContain HFRAG.formEntriesNs
        index[CPYIDX.pages].toJsonMapOrEmpty().keys shouldContain CPAGE.home
        // A page's keys, across files, each saying where on the page it is (issue #1799).
        val onHome = read(ATOOL.readCopy, mapOf(ATOOL.page to CPAGE.home)).toJsonListOfMaps()
        onHome.single { it[COV.key] == HFRAG.intro }[CPY.where].toString() shouldStartWith "${CPAGE.home}:"
        read(ATOOL.readCopy, mapOf(ATOOL.page to "Nowhere")).toJsonMapOrEmpty()[CPYIDX.none].toString() shouldContain "The pages are:"
        val entries = read(ATOOL.readCopy, mapOf(ATOOL.file to HFRAG.home, ATOOL.namespace to HFRAG.formEntriesNs)).toJsonListOfMaps()
        entries.single()[COV.key] shouldBe HFRAG.formEntryLabel
        entries.single()[COV.value] shouldBe HFRAG.defaultEntryLabel
        read(ATOOL.readCopy, mapOf(ATOOL.file to "nope")).toJsonMapOrEmpty()[CPYIDX.none].toString() shouldContain "The files are:"
        // What is pending, for the client itself.
        read(ATOOL.listDrafts).toJsonMapOrEmpty()[CFEP.client] shouldBe client
        read(ATOOL.readImpact, mapOf(CFEP.names to listOf("main"))).toJsonMapOrEmpty()[CFEP.client] shouldBe client
        // Where a trait comes from: the client's own configuration.
        val memo = read(ATOOL.readDefinition, mapOf(DSV.slot to CCT.traitDef, DSV.key to "memo")).toJsonMapOrEmpty()
        memo[DSV.config] shouldBe "main"
        // A read refused is the endpoint's answer, for the model to correct from.
        AssistantTools.run(run, ATOOL.readDefinition, mapOf(DSV.slot to "kdr:nope", DSV.key to "memo")).isError shouldBe true
    }

    "the wording and menu writes save drafts from the sandbox: the sandbox shows them, the client's people do not yet" {
        // Issue #1763: the copy and menu editors' saves, as tools, called as the person's user in the sandbox.
        val client = "asstwording"
        val admin = clientWithAssistant(cxt, client)
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        admin.postData(AEP.leaveSandbox, mapOf(AFLD.userId to admin.userId))
        val row = UserService.get(cxt).queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        val run = ToolRun(cxt, InProcessPrincipal(sandboxUserId, row.identityId, row.client, "assistant:test", AssistantTools.scope), "t1763")
        fun write(name: String, input: Map<String, Any?>): Map<String, Any?> {
            val result = AssistantTools.run(run, name, input)
            result.isError shouldBe false
            return result.text.json().toJsonMapOrEmpty()
        }
        val title = mapOf(COV.fileId to HFRAG.home, COV.namespaceField to HFRAG.homeNs, COV.key to HFRAG.title)
        // The title as the sandbox reads it (through the read tool), and as the client's people do.
        fun sandboxTitle(): Any? = AssistantTools.run(run, ATOOL.readCopy, mapOf(ATOOL.file to HFRAG.home, ATOOL.namespace to HFRAG.homeNs))
            .text.json().toJsonListOfMaps().single { it[COV.key] == HFRAG.title }[COV.value]
        fun clientTitle(): Any? = admin.getItems(CPY.keysPath)
            .single { it[COV.fileId] == HFRAG.home && it[COV.namespaceField] == HFRAG.homeNs && it[COV.key] == HFRAG.title }[COV.value]
        val shipped = clientTitle()

        write(ATOOL.setCopy, title + (COV.value to "Welcome to the club"))[CPY.mode] shouldBe EDM.draft
        sandboxTitle() shouldBe "Welcome to the club"
        clientTitle() shouldBe shipped
        // Several keys together, one of them put back.
        val applied = write(
            ATOOL.applyCopy,
            mapOf(
                COV.fileId to HFRAG.home,
                CPY.changes to listOf(
                    mapOf(COV.namespaceField to HFRAG.homeNs, COV.key to HFRAG.title, CPY.reset to true),
                    mapOf(COV.namespaceField to HFRAG.homeNs, COV.key to HFRAG.note, COV.value to "Meetings are on Tuesdays."),
                ),
            ),
        )
        applied[CPY.mode] shouldBe EDM.draft
        sandboxTitle() shouldBe shipped
        write(ATOOL.resetCopy, mapOf(COV.fileId to HFRAG.home, COV.namespaceField to HFRAG.homeNs, COV.key to HFRAG.note))[CPY.mode] shouldBe
            EDM.draft
        // A key the client does not reword cannot be put back: the endpoint's refusal, for the model to correct from.
        AssistantTools.run(run, ATOOL.resetCopy, title).isError shouldBe true

        // The menu: hidden in the sandbox, offered as before to the client's people, then put back.
        fun sandboxDocs(): Any? = AssistantTools.run(run, ATOOL.readMenu, emptyMap()).text.json().toJsonMapOrEmpty()[EP.items]
            .toJsonListOfMaps().single { it[COV.itemId] == HMENU.docs }[MNU.condition]
        fun clientDocs(): Any? = admin.getItems(MNU.itemsPath).single { it[COV.itemId] == HMENU.docs }[MNU.condition]
        val offered = clientDocs()
        write(ATOOL.setMenuItem, mapOf(COV.itemId to HMENU.docs, MNU.visibility to MNU.hide))[CPY.mode] shouldBe EDM.draft
        sandboxDocs() shouldBe CFACT.neverName
        clientDocs() shouldBe offered
        write(ATOOL.resetMenuItem, mapOf(COV.itemId to HMENU.docs))[CPY.mode] shouldBe EDM.draft
        sandboxDocs() shouldBe offered
    }

    "the definition and forms-list writes dry-run first, then save drafts the sandbox runs and the client's people do not yet" {
        // Issue #1764: the definition and listing editors' saves, as tools, called as the person's user in the sandbox.
        val client = "asstdefinition"
        val admin = clientWithAssistant(cxt, client)
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        admin.postData(AEP.leaveSandbox, mapOf(AFLD.userId to admin.userId))
        val row = UserService.get(cxt).queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        val principal = InProcessPrincipal(sandboxUserId, row.identityId, row.client, "assistant:test", AssistantTools.scope)
        val run = ToolRun(cxt, principal, "t1764")
        fun tool(name: String, input: Map<String, Any?>) = AssistantTools.run(run, name, input)
        fun ok(name: String, input: Map<String, Any?>): Map<String, Any?> =
            tool(name, input).also { it.isError shouldBe false }.text.json().toJsonMapOrEmpty()
        val clients = ClientService.get(cxt)

        // The definition: refused unsaved until the same change passed a dry run, which stores nothing.
        val rename = mapOf(CLD.name to "Book Club")
        tool(ATOOL.setClientDefinition, rename + (CFEP.dryRun to false)).text shouldContain "dry run this change first"
        ok(ATOOL.setClientDefinition, rename + (CFEP.dryRun to true))[CFEP.dryRun] shouldBe true
        clients.present(sandboxOf(client)).shouldNotBeNull().name shouldBe "Client $client (sandbox)"
        // A dry run counts once its step is answered: a write in the same step is still refused.
        tool(ATOOL.setClientDefinition, rename + (CFEP.dryRun to false)).isError shouldBe true
        run.endStep()
        ok(ATOOL.setClientDefinition, rename + (CFEP.dryRun to false))[CPY.mode] shouldBe EDM.draft
        clients.present(sandboxOf(client)).shouldNotBeNull().name shouldBe "Book Club (sandbox)"
        clients.present(client).shouldNotBeNull().name shouldBe "Client $client"
        // What only the platform's operator sets is refused, dry run or not.
        tool(ATOOL.setClientDefinition, mapOf(CLD.audience to "internal", CFEP.dryRun to true)).isError shouldBe true

        // The forms list: a column dry-run, then saved as a draft; then its removal dry-run; the layout, which takes none.
        fun columns(of: String) = SchemaService.get(cxt).listingColumnsFor(of).map { it.label }
        val column = mapOf(CUS.column to mapOf(CCT.traitId to "memo", CCT.label to "Memo", CCT.display to $$"${text}"))
        ok(ATOOL.saveFormsListColumn, column + (CFEP.dryRun to true))[CFEP.dryRun] shouldBe true
        columns(sandboxOf(client)) shouldNotContain "Memo"
        run.endStep()
        ok(ATOOL.saveFormsListColumn, column + (CFEP.dryRun to false))[CPY.mode] shouldBe EDM.draft
        columns(sandboxOf(client)) shouldContain "Memo"
        columns(client) shouldNotContain "Memo"
        val removal = mapOf(CCT.traitId to "memo", CCT.label to "Memo")
        ok(ATOOL.removeFormsListColumn, removal + (CFEP.dryRun to true))[CFEP.dryRun] shouldBe true
        columns(sandboxOf(client)) shouldContain "Memo"
        ok(ATOOL.setFormsListLayout, mapOf(CUS.workflowLayout to WorkflowLayout.split.name))[CPY.mode] shouldBe EDM.draft
    }

    "Design View's edits, judged against the basis read with the definition, save drafts the sandbox runs" {
        // Issue #1765: Design View's saves, as tools, called as the person's user in the sandbox.
        val client = "asstdesign"
        val admin = clientWithAssistant(cxt, client)
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        admin.postData(AEP.leaveSandbox, mapOf(AFLD.userId to admin.userId))
        val row = UserService.get(cxt).queryByUserId(cxt, sandboxUserId).shouldNotBeNull()
        val principal = InProcessPrincipal(sandboxUserId, row.identityId, row.client, "assistant:test", AssistantTools.scope)
        val run = ToolRun(cxt, principal, "t1765")
        fun tool(name: String, input: Map<String, Any?>) = AssistantTools.run(run, name, input)
        fun ok(name: String, input: Map<String, Any?>): Map<String, Any?> =
            tool(name, input).also { withClue("$name: ${it.text}") { it.isError shouldBe false } }.text.json().toJsonMapOrEmpty()
        fun read(slot: String, key: String) = ok(ATOOL.readDefinition, mapOf(DSV.slot to slot, DSV.key to key))
        fun label(of: String) = WorkflowService.get(cxt).forClient(of).workflow("review").shouldNotBeNull().def.label
        val view = ok(ATOOL.viewWorkflow, mapOf(GDF.workflowId to "review"))
        val typeName = view[WFD.tasks].toJsonListOfMaps().single()[WFD.traits].toJsonListOfMaps().single()[WVF.schemaRef]
            .toString().removePrefix($$"#/$defs/")

        // A workflow's label: a draft the sandbox runs, the client does not yet, in the workflow's own bundle.
        val basedOn = read(CCT.workflowDef, "review")[DSV.basedOn].shouldNotBeNull()
        val labeled = ok(ATOOL.setWorkflowLabel, mapOf(DSV.workflowId to "review", DSV.label to "Review the memo", DSV.basedOn to basedOn))
        labeled[COV.configName] shouldBe "flows"
        label(sandboxOf(client)) shouldBe "Review the memo"
        label(client) shouldNotBe "Review the memo"
        // The basis it was based on is stale now: refused, to be read again.
        tool(ATOOL.setWorkflowLabel, mapOf(DSV.workflowId to "review", DSV.label to "Again", DSV.basedOn to basedOn)).isError shouldBe true
        val fresh = labeled[DSV.basedOn]
        read(CCT.workflowDef, "review")[DSV.basedOn] shouldBe fresh

        // A field's copy for the workflow, and the fields its form shows: dry-run first, which stores nothing.
        ok(
            ATOOL.setFieldCopy,
            mapOf(DSV.workflowId to "review", DSV.typeName to typeName, DSV.field to "text", DSV.entry to mapOf("label" to "The memo"),
                DSV.basedOn to fresh),
        )
        // An entry, or remove: true -- never a removal by leaving the entry out.
        val bare = mapOf(DSV.workflowId to "review", DSV.typeName to typeName, DSV.field to "text")
        tool(ATOOL.setFieldCopy, bare + (DSV.basedOn to read(CCT.workflowDef, "review")[DSV.basedOn])).isError shouldBe true
        ok(ATOOL.setFieldCopy, bare + (ATOOL.remove to true) + (DSV.basedOn to read(CCT.workflowDef, "review")[DSV.basedOn]))
        val now = read(CCT.workflowDef, "review")[DSV.basedOn]
        val shown = mapOf(DSV.workflowId to "review", DSV.typeName to typeName, DSV.fields to listOf("text"), DSV.basedOn to now)
        tool(ATOOL.setShownFields, shown + (CFEP.dryRun to false)).text shouldContain "dry run this change first"
        ok(ATOOL.setShownFields, shown + (CFEP.dryRun to true))[CFEP.dryRun] shouldBe true
        read(CCT.workflowDef, "review")[DSV.basedOn] shouldBe now
        run.endStep()
        ok(ATOOL.setShownFields, shown + (CFEP.dryRun to false)).containsKey(CFEP.dryRun) shouldBe false

        // Shared: the trait's heading, and a field's copy for every workflow, against the trait's own basis; a change of
        // choices is dry-run first, and a free-text field takes none.
        val sharedBasedOn = read(CCT.traitDef, "memo")[DSV.sharedBasedOn].shouldNotBeNull()
        val headed = ok(ATOOL.setSharedHeading, mapOf(DSV.typeName to typeName, DSV.label to "Memo", DSV.sharedBasedOn to sharedBasedOn))
        headed[COV.configName] shouldBe "main"
        val copied = ok(
            ATOOL.setSharedField,
            mapOf(DSV.typeName to typeName, DSV.field to "text", DSV.entry to mapOf("label" to "What it says"),
                DSV.sharedBasedOn to headed[DSV.sharedBasedOn]),
        )
        val choices = mapOf(
            DSV.typeName to typeName, DSV.field to "text", DSV.options to listOf(mapOf(SCH.value to "a", SCH.label to "A")),
            DSV.sharedBasedOn to copied[DSV.sharedBasedOn],
        )
        tool(ATOOL.setSharedField, choices + (CFEP.dryRun to false)).text shouldContain "dry run this change first"
        tool(ATOOL.setSharedField, choices + (CFEP.dryRun to true)).isError shouldBe true
        // A copy-only change asked as a dry run is one: checked, and nothing saved.
        val copyBasedOn = read(CCT.traitDef, "memo")[DSV.sharedBasedOn]
        val copyOnly = mapOf(DSV.typeName to typeName, DSV.field to "text", DSV.entry to mapOf("label" to "Checked only"),
            DSV.sharedBasedOn to copyBasedOn)
        ok(ATOOL.setSharedField, copyOnly + (CFEP.dryRun to true))[CFEP.dryRun] shouldBe true
        read(CCT.traitDef, "memo")[DSV.sharedBasedOn] shouldBe copyBasedOn

        // A choice a form of the client holds, removed: the dry run says what publishing would do, then it is drafted.
        val held = admin.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "memo", GE.data to mapOf("text" to "Held", "kind" to "idea")))),
        )[GDF.gedraId].shouldNotBeNull()
        val removal = mapOf(
            DSV.typeName to typeName, DSV.field to "kind", DSV.options to listOf(mapOf(SCH.value to "note", SCH.label to "A note")),
            DSV.sharedBasedOn to copyBasedOn,
        )
        val checked = ok(ATOOL.setSharedField, removal + (CFEP.dryRun to true))
        val finding = checked[CIT.draftImpact].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().single()
        finding[IMP.sampleIds].toJsonListOfStrings() shouldContain held
        run.endStep()
        ok(ATOOL.setSharedField, removal + (CFEP.dryRun to false)).containsKey(CFEP.dryRun) shouldBe false
        val schema = read(CCT.traitDef, "memo")[DSV.entry].toJsonMapOrEmpty()[CCT.dataSchema].toJsonMapOrEmpty()
        val options = schema[SCH.properties].toJsonMapOrEmpty()["kind"].toJsonMapOrEmpty()[SCH.options].toJsonListOfMaps()
        options.map { it[SCH.value] } shouldBe listOf("note")
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
        for (path in listOf(ACNV.messagePath, ACNV.cancelPath, ACNV.switchOnPath, ACNV.deletePath)) {
            schema.endpoints["$path:POST"].shouldNotBeNull().sessionOnly shouldBe true
        }
        for (path in listOf(ACNV.statusPath, ACNV.conversationPath, ACNV.conversationsPath)) {
            schema.endpoints["$path:GET"].shouldNotBeNull().sessionOnly shouldBe true
        }
        AssistantTools.readableDoc(cxt, "client-configuration").shouldNotBeNull()
        // The codes a refused value is reported with (issue #1559), generated, are readable too.
        AssistantTools.readableDoc(cxt, "validation-messages").shouldNotBeNull()
        AssistantTools.readableDoc(cxt, "expressions").shouldNotBeNull()
        AssistantTools.readableDoc(cxt, "facts").shouldNotBeNull()
        AssistantTools.readableDoc(cxt, "copy").shouldNotBeNull()
        AssistantTools.readableDoc(cxt, "report-paths").shouldNotBeNull()
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
        @Suppress("BlockingMethodInNonBlockingContext")
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
                ATBL.toolsetVersion to AssistantPrompt.versionOf(own, "asstlease"), ATBL.runningTurnId to "notYetRecorded",
            ),
        )
        fun starting() = admin.getData(ACNV.conversationPath, mapOf(ACNV.conversationId to "starting1526"))[ACNV.conversation]
            .toJsonMapOrEmpty()[ACNV.running]
        starting() shouldBe true
        own.instanceConfig.clock.advanceBy((TURN.leaseMinutes + 1).minutes)
        starting() shouldBe false
    }
})
