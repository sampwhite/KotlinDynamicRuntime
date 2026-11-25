package com.dynamicruntime.trials

import com.dynamicruntime.assistant.AENV
import com.dynamicruntime.assistant.AssistantComponent
import com.dynamicruntime.assistant.AssistantService
import com.dynamicruntime.assistant.ScriptedModel
import com.dynamicruntime.common.assistant.ABLK
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.ClientCreate
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.workflow.ULH
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.io.File
import java.nio.file.Files

/** A test's user as a [TrialCaller]: the same calls, in-process. */
class TestUserCaller(private val user: TestUser) : TrialCaller {
    override fun getData(path: String, args: Map<String, Any?>?) = user.getData(path, args)
    override fun getItems(path: String, args: Map<String, Any?>?) = user.getItems(path, args)
    override fun getItem(path: String, args: Map<String, Any?>?) = user.getItem(path, args)
    override fun postData(path: String, data: Map<String, Any?>) = user.postData(path, data)
}

/**
 * The trial harness's own working (issue #1859), without the real model: a trial driven in-process against a scripted
 * one, through the same calls a run makes over HTTP, and the checks a report is made of, on configurations made by hand.
 */
@Suppress("BlockingMethodInNonBlockingContext")
class TrialHarnessTest : StringSpec({
    val model = ScriptedModel(emptyList())
    val cxt: KdrCxt = Startup.mkTestBootCxt(
        "assistantTrials1859", "assistantTrials1859",
        mapOf(AssistantService.modelConfigKey to model, AENV.selfServe.name to "true"),
        additionalComponents = listOf(AssistantComponent()),
    )
    beforeEach { model.clear() }

    val smoke = Trial(
        "smoke", "Rewords Home's introduction.", "Smoke Grants",
        listOf(TrialVariant("explicit", "One request.", listOf(TrialPrompt("Say Riverbend on Home.")))),
        listOf(Expectation("Home's introduction names the program") { r -> Checks.copyMentions(r, HFRAG.home, null, "Riverbend") }),
    )

    "a trial runs through the driver: its client, sandbox, turns, configuration, and report" {
        val intro = mapOf(
            COV.fileId to HFRAG.home, COV.namespaceField to HFRAG.homeNs, COV.key to HFRAG.intro, COV.value to "Riverbend grants.",
        )
        model.add(
            { ScriptedModel.toolResponse(ATOOL.readDoc to mapOf(ATOOL.doc to HDOC.makingChanges)) },
            { ScriptedModel.toolResponse(ATOOL.setCopy to intro) },
            { ScriptedModel.textResponse("Home now names Riverbend.") },
        )
        val person = TestUser.create(cxt, "smoke@trials1859.test")
        val result = TrialDriver(TestUserCaller(person), turnTimeout = 30.seconds, pollEvery = 50.milliseconds, log = {})
            .run(smoke, smoke.variants.single(), "t1")
        result.problems.shouldBeEmpty()
        result.client.shouldNotBeNull()
        result.sandbox.shouldNotBeNull() shouldEndWith ":sandbox"
        result.turns.single().status shouldBe "done"
        result.calls.map { it.name } shouldBe listOf(ATOOL.readDoc, ATOOL.setCopy)
        result.conversations.single().reply(result.turns.single().turnId) shouldBe "Home now names Riverbend."
        result.view.copyKeys["${HFRAG.home}.${HFRAG.homeNs}.${HFRAG.intro}"] shouldBe "Riverbend grants."

        val outcomes = result.outcomes().associate { it.first.name to it.second.kind }
        outcomes["Home's introduction names the program"] shouldBe OutcomeKind.met
        outcomes[MethodChecks.readsMakingChanges.name] shouldBe OutcomeKind.info
        outcomes[MethodChecks.turnsEnded.name] shouldBe OutcomeKind.met

        val info = RunInfo("t1", "http://localhost:0", null, emptyMap(), null, Clock.System.now())
        val report = TrialReport.render(info, listOf(result to "smoke@trials1859.test"))
        report shouldContain "| met | Home's introduction names the program |"
        report shouldContain "1. `read_doc"
        report shouldContain "> Home now names Riverbend."
        // The configuration is in the report whole.
        report shouldContain "\"${CCT.fragmentDef}\""
    }

    "a field check says what it found: met, varied, or not met" {
        fun result(field: Map<String, Any?>, onForm: Boolean): TrialResult {
            val trait = mapOf(CCT.traitId to "ask", CCT.dataSchema to mapOf(SCH.properties to mapOf("amountRequested" to field)))
            val creation = mapOf(
                WFD.entry to WfEntry.creation.name,
                WFD.tasks to listOf(mapOf(WFD.traits to listOf(mapOf(WFD.traitId to if (onForm) "ask" else "other")))),
            )
            val bundle = mapOf(
                CFEP.slots to mapOf(
                    CCT.traitDef to listOf(trait),
                    CCT.workflowDef to listOf(mapOf(CCT.workflowId to "newForm", CCT.definition to creation)),
                ),
            )
            val variant = smoke.variants.single()
            val now = Clock.System.now()
            return TrialResult(smoke, variant, "c", "c:sandbox", emptyList(), mapOf("b" to bundle), emptyList(), now, now)
        }
        fun amount(r: TrialResult) = Checks.newFormField(r, "a number", listOf("amount")) { it.isNumber }.kind
        amount(result(mapOf(SCH.type to "number"), onForm = true)) shouldBe OutcomeKind.met
        amount(result(mapOf(SCH.type to "number"), onForm = false)) shouldBe OutcomeKind.varied
        amount(result(emptyMap(), onForm = true)) shouldBe OutcomeKind.varied
        Checks.newFormField(result(emptyMap(), true), "a date", listOf("start")) { it.isDate }.kind shouldBe OutcomeKind.notMet
    }

    "the core details workflow is held to the client's own traits the New form collects" {
        fun outcome(details: List<String>?): OutcomeKind {
            fun workflow(id: String, template: String, traits: List<String>) = mapOf(
                CCT.workflowId to id,
                CCT.definition to mapOf(
                    WFD.fromTemplate to template,
                    WFD.tasks to listOf(mapOf(WFD.traits to traits.map { mapOf(WFD.traitId to it) })),
                ),
            )
            val workflows = listOfNotNull(
                workflow("newForm", WFT.createForm, listOf("kdr:name", "ask", "plan")),
                details?.let { workflow("formDetails", WFT.coreDetails, it) },
            )
            val bundle = mapOf(CFEP.slots to mapOf(CCT.workflowDef to workflows))
            val now = Clock.System.now()
            return Checks.coreDetailsMatchesNewForm(
                TrialResult(smoke, smoke.variants.single(), "c", null, emptyList(), mapOf("b" to bundle), emptyList(), now, now),
            ).kind
        }
        // The template's own kdr:name is not asked of it.
        outcome(listOf("ask", "plan")) shouldBe OutcomeKind.met
        outcome(listOf("ask")) shouldBe OutcomeKind.varied
        outcome(emptyList()) shouldBe OutcomeKind.notMet
        outcome(null) shouldBe OutcomeKind.notMet
    }

    "the review's approval is held to naming the New form's own traits" {
        fun outcome(approval: Map<String, Any?>, answersTask: Boolean = false): OutcomeKind {
            fun task(id: String, traits: List<String>, extra: Map<String, Any?> = emptyMap()) =
                mapOf(WFD.id to id, WFD.traits to traits.map { mapOf(WFD.traitId to it) }) + extra
            val creation = mapOf(
                CCT.workflowId to "newForm",
                CCT.definition to mapOf(
                    WFD.fromTemplate to WFT.createForm, WFD.tasks to listOf(task("identify", listOf("kdr:name", "ask", "plan"))),
                ),
            )
            val reviewTasks = listOfNotNull(
                if (answersTask) task("answers", listOf("ask", "plan")) else null,
                task("approve", emptyList(), mapOf(WFD.approval to mapOf(WFD.cfact to "approved") + approval)),
            )
            val review = mapOf(
                CCT.workflowId to "review",
                CCT.definition to mapOf(WFD.workflowId to "review", WFD.entry to WfEntry.normal.name, WFD.tasks to reviewTasks),
            )
            val bundle = mapOf(CFEP.slots to mapOf(CCT.workflowDef to listOf(creation, review)))
            val now = Clock.System.now()
            return Checks.approvalShowsNewForm(
                TrialResult(smoke, smoke.variants.single(), "c", null, emptyList(), mapOf("b" to bundle), emptyList(), now, now),
            ).kind
        }
        outcome(mapOf(WFD.showTraits to listOf("ask", "plan"))) shouldBe OutcomeKind.met
        outcome(mapOf(WFD.showTraits to listOf("ask"))) shouldBe OutcomeKind.varied
        // Through a task of its own that only lists them: shown, but by a step that asks nothing of anyone.
        outcome(emptyMap(), answersTask = true) shouldBe OutcomeKind.varied
        outcome(emptyMap()) shouldBe OutcomeKind.notMet
    }

    "a Home card is held to opening the New form, by a button that is drawn or a link" {
        fun outcome(vararg cards: Map<String, Any?>): OutcomeKind {
            val block = mapOf(CCT.blockId to HCARD.block, CCT.content to mapOf(HCARD.cards to cards.toList()))
            val bundle = mapOf(CFEP.slots to mapOf(CCT.uiBlockDef to listOf(block)))
            val now = Clock.System.now()
            return Checks.homeCardStartsForm(
                TrialResult(smoke, smoke.variants.single(), "c", null, emptyList(), mapOf("b" to bundle), emptyList(), now, now),
            ).kind
        }
        fun card(vararg keys: Pair<String, Any?>) = mapOf("id" to "start", HCARD.title to "Start", *keys)
        outcome(card(UIB.action to HMENU.pageNewForm, HCARD.actionLabel to "Start")) shouldBe OutcomeKind.met
        outcome(card(HCARD.links to listOf(mapOf(HCARD.linkLabel to "Start", UIB.action to HMENU.pageNewForm)))) shouldBe OutcomeKind.met
        outcome(card(HCARD.text to "[Start](/wa/forms/new) here.")) shouldBe OutcomeKind.met
        outcome(card(UIB.action to HMENU.pageForms, HCARD.actionLabel to "Forms")) shouldBe OutcomeKind.varied
        // What every run wrote before the guide said how: an address, which opens Home, and no words, so no button either.
        outcome(card(UIB.action to "/forms/new")) shouldBe OutcomeKind.notMet
        outcome(card(UIB.action to "/forms/new", HCARD.actionLabel to "Start")) shouldBe OutcomeKind.notMet
        outcome(card(UIB.action to HMENU.pageNewForm)) shouldBe OutcomeKind.notMet
        outcome() shouldBe OutcomeKind.notMet
    }

    "a review that starts with someone else's step is held to highlighting its Start" {
        fun outcome(extra: Map<String, Any?>, firstTask: Map<String, Any?>? = null): OutcomeKind {
            val approve = mapOf(WFD.id to "approve", WFD.approval to mapOf(WFD.cfact to "approved"))
            val review = mapOf(
                CCT.workflowId to "review",
                CCT.definition to mapOf(
                    WFD.workflowId to "review", WFD.entry to WfEntry.normal.name, WFD.tasks to listOfNotNull(firstTask, approve),
                ) + extra,
            )
            val bundle = mapOf(CFEP.slots to mapOf(CCT.workflowDef to listOf(review)))
            val now = Clock.System.now()
            return Checks.reviewHighlightsStart(
                TrialResult(smoke, smoke.variants.single(), "c", null, emptyList(), mapOf("b" to bundle), emptyList(), now, now),
            ).kind
        }
        val highlighted = mapOf(WFD.highlightStart to true)
        outcome(highlighted) shouldBe OutcomeKind.met
        outcome(emptyMap()) shouldBe OutcomeKind.notMet
        // A first task only a reviewer may save is someone else's step too.
        outcome(emptyMap(), mapOf(WFD.id to "score", WFD.saveWhen to "kdr:reviewer")) shouldBe OutcomeKind.notMet
        // The applicant's own step first: there is nobody else to point at the form.
        outcome(emptyMap(), mapOf(WFD.id to "budget")) shouldBe OutcomeKind.info
    }

    "a review's reply is held to saying a reviewer needs the editor role" {
        fun outcome(reply: String, labeled: Boolean = true): OutcomeKind {
            val function = mapOf(WFD.fn to ULH.fn, WFD.label to "reviewer")
            val approve = mapOf(
                WFD.id to "approve", WFD.approval to mapOf(WFD.cfact to "approved"),
                WFD.functions to if (labeled) listOf(function) else emptyList(),
            )
            val review = mapOf(
                CCT.workflowId to "review",
                CCT.definition to mapOf(WFD.workflowId to "review", WFD.entry to WfEntry.normal.name, WFD.tasks to listOf(approve)),
            )
            val write = mapOf(
                CFEP.dryRun to false, CFEP.configs to listOf(mapOf(CFEP.edits to listOf(mapOf(CFEP.slot to CCT.workflowDef)))),
            )
            fun assistant(vararg blocks: Map<String, Any?>) =
                mapOf(ACNV.role to ABLK.assistant, ACNV.turnId to "t", ACNV.content to blocks.toList())
            val messages = listOf(
                assistant(mapOf(ABLK.type to ABLK.toolUse, ABLK.id to "c1", ABLK.name to ATOOL.patchConfig, ABLK.input to write)),
                assistant(mapOf(ABLK.type to ABLK.text, ABLK.text to reply)),
            )
            val now = Clock.System.now()
            val bundle = mapOf(CFEP.slots to mapOf(CCT.workflowDef to listOf(review)))
            return Checks.reviewersToldRole(
                TrialResult(
                    smoke, smoke.variants.single(), "c", null, listOf(TrialConversation("c", emptyList(), messages)),
                    mapOf("b" to bundle), emptyList(), now, now,
                ),
            ).kind
        }
        outcome("Done.\n- Give your reviewers the label, and the **Editor** role, on the Users page.") shouldBe OutcomeKind.met
        // What every run said before the guidance did.
        outcome("Give the label to your reviewers on the Users page.") shouldBe OutcomeKind.notMet
        // Another editor, which no reviewer needs.
        outcome("You can see it in the bundle editor.") shouldBe OutcomeKind.notMet
        // "sandbox" is not a box to check.
        outcome("Open it in the bundle editor, in the sandbox.") shouldBe OutcomeKind.notMet
        outcome("They don't need the editor role.") shouldBe OutcomeKind.notMet
        // The other ways of saying it.
        for (said in listOf(
            "Tick the **Editor** box under More options.", "Give them the role `editor` too.", "Make them an editor as well.",
            "They need the label and the `editor` role; an administrator does not need it.",
        )) outcome(said) shouldBe OutcomeKind.met
        outcome("Done.", labeled = false) shouldBe OutcomeKind.info
    }

    "a report is held to being made whole by create_report, not patched afterward" {
        @Suppress("CanBeVal") var id = 0
        fun call(name: String, input: Map<String, Any?>, refused: Boolean = false) = listOf(
            mapOf(
                ACNV.role to ABLK.assistant, ACNV.turnId to "t",
                ACNV.content to listOf(mapOf(ABLK.type to ABLK.toolUse, ABLK.id to "c${++id}", ABLK.name to name, ABLK.input to input)),
            ),
            mapOf(
                ACNV.role to ABLK.user, ACNV.turnId to "t",
                ACNV.content to listOf(
                    mapOf(ABLK.type to ABLK.toolResult, ABLK.toolUseId to "c$id", ABLK.content to "ok", ABLK.isError to refused),
                ),
            ),
        )
        fun create(dryRun: Boolean, refused: Boolean = false) = call(ATOOL.createReport, mapOf(CFEP.dryRun to dryRun), refused)
        fun patch(slot: String, dryRun: Boolean = false, refused: Boolean = false) = call(
            ATOOL.patchConfig,
            mapOf(CFEP.dryRun to dryRun, CFEP.configs to listOf(mapOf(CFEP.edits to listOf(mapOf(CFEP.slot to slot))))),
            refused,
        )
        fun outcome(vararg messages: List<Map<String, Any?>>): OutcomeKind {
            val now = Clock.System.now()
            val conversation = TrialConversation("c", emptyList(), messages.flatMap { it })
            return Checks.reportMadeWhole(
                TrialResult(smoke, smoke.variants.single(), "c", null, listOf(conversation), emptyMap(), emptyList(), now, now),
            ).kind
        }
        outcome(create(true), create(false)) shouldBe OutcomeKind.met
        // A patch of something else, or one only dry-run, is not a patch of the report.
        outcome(create(true), create(false), patch(CCT.workflowDef), patch(CCT.reportDef, dryRun = true)) shouldBe OutcomeKind.met
        // What every run did before the tool took a rollup: made it, then patched the total and the chart in.
        outcome(create(true, refused = true), create(true), create(false), patch(CCT.reportDef)) shouldBe OutcomeKind.varied
        outcome(patch(CCT.reportDef)) shouldBe OutcomeKind.varied
        outcome(create(false, refused = true)) shouldBe OutcomeKind.notMet
        // A patch refused changed nothing, and one made before the report was made is of another.
        outcome(create(false), patch(CCT.reportDef, refused = true)) shouldBe OutcomeKind.met
        outcome(patch(CCT.reportDef), create(false)) shouldBe OutcomeKind.met
        outcome(patch(CCT.reportDef, refused = true)) shouldBe OutcomeKind.notMet
    }

    "a change made without saying dryRun, through a tool that offers one, was not dry-run" {
        @Suppress("CanBeVal") var id = 0
        fun call(turn: String, input: Map<String, Any?>) = listOf(
            mapOf(
                ACNV.role to ABLK.assistant, ACNV.turnId to turn,
                ACNV.content to listOf(
                    mapOf(ABLK.type to ABLK.toolUse, ABLK.id to "c${++id}", ABLK.name to ATOOL.patchConfig, ABLK.input to input),
                ),
            ),
            mapOf(
                ACNV.role to ABLK.user, ACNV.turnId to turn,
                ACNV.content to listOf(mapOf(ABLK.type to ABLK.toolResult, ABLK.toolUseId to "c$id", ABLK.content to "ok")),
            ),
        )
        fun dryRunOutcome(vararg messages: List<Map<String, Any?>>): Outcome {
            val now = Clock.System.now()
            val conversation = TrialConversation("c", emptyList(), messages.flatMap { it })
            return MethodChecks.dryRunsFirst.check(
                TrialResult(smoke, smoke.variants.single(), "c", null, listOf(conversation), emptyMap(), emptyList(), now, now),
            )
        }
        dryRunOutcome(call("t", mapOf(CFEP.dryRun to true)), call("t", mapOf(CFEP.dryRun to false))).kind shouldBe OutcomeKind.met
        // A dry run before making-changes is read changes nothing, so the read still came before the first change.
        val read = listOf(
            mapOf(
                ACNV.role to ABLK.assistant, ACNV.turnId to "t",
                ACNV.content to listOf(
                    mapOf(
                        ABLK.type to ABLK.toolUse, ABLK.id to "r1", ABLK.name to ATOOL.readDoc,
                        ABLK.input to mapOf(ATOOL.doc to HDOC.makingChanges),
                    ),
                ),
            ),
        )
        val now = Clock.System.now()
        val messages = buildList {
            addAll(call("t", mapOf(CFEP.dryRun to true)))
            addAll(read)
            addAll(call("t", mapOf(CFEP.dryRun to false)))
        }
        val ordered = TrialConversation("c", emptyList(), messages)
        MethodChecks.readsMakingChanges.check(
            TrialResult(smoke, smoke.variants.single(), "c", null, listOf(ordered), emptyMap(), emptyList(), now, now),
        ).detail shouldBe "All 1 that changed something."
        // A turn that never dry-ran, and a call that never said: both made outright.
        dryRunOutcome(call("t", mapOf(CFEP.dryRun to true)), call("t", mapOf(CFEP.dryRun to false)), call("u", emptyMap())).let {
            it.kind shouldBe OutcomeKind.varied
            it.detail shouldContain "Made without a dry run: ${ATOOL.patchConfig}."
        }
    }

    /** [smoke]'s result with [slots] as its one bundle's, and [conversations]. */
    fun resultOf(
        slots: Map<String, Any?>,
        conversations: List<TrialConversation> = emptyList(),
        variant: TrialVariant = smoke.variants.single(),
    ): TrialResult {
        val now = Clock.System.now()
        return TrialResult(smoke, variant, "c", null, conversations, mapOf("b" to mapOf(CFEP.slots to slots)), emptyList(), now, now)
    }

    /** A conversation of the calls given, each a tool, its input, and whether it was refused. */
    fun conversationOf(vararg calls: Triple<String, Map<String, Any?>, Boolean>): TrialConversation = TrialConversation(
        "c", emptyList(),
        calls.flatMapIndexed { i, (name, input, refused) ->
            listOf(
                mapOf(
                    ACNV.role to ABLK.assistant, ACNV.turnId to "t",
                    ACNV.content to listOf(mapOf(ABLK.type to ABLK.toolUse, ABLK.id to "x$i", ABLK.name to name, ABLK.input to input)),
                ),
                mapOf(
                    ACNV.role to ABLK.user, ACNV.turnId to "t",
                    ACNV.content to listOf(
                        mapOf(ABLK.type to ABLK.toolResult, ABLK.toolUseId to "x$i", ABLK.content to "ok", ABLK.isError to refused),
                    ),
                ),
            )
        },
    )

    "reading making-changes is told of each conversation that changes something, and judged of none" {
        val read = Triple(ATOOL.readDoc, mapOf<String, Any?>(ATOOL.doc to HDOC.makingChanges), false)
        val change = Triple(ATOOL.setCopy, emptyMap<String, Any?>(), false)
        fun said(vararg conversations: TrialConversation): String {
            val outcome = MethodChecks.readsMakingChanges.check(resultOf(emptyMap(), conversations.toList()))
            outcome.kind shouldBe OutcomeKind.info
            return outcome.detail
        }
        said(conversationOf(read, change), conversationOf(read, change)) shouldBe "All 2 that changed something."
        // A conversation has only what was read in it.
        said(conversationOf(read, change), conversationOf(change)) shouldContain "1 of the 2 that changed something. Without it:"
        // A refused read read nothing, and a conversation that changes nothing is not counted.
        said(conversationOf(read.copy(third = true), change)) shouldContain "0 of the 1"
        said(conversationOf(read, change), conversationOf(read)) shouldBe "All 1 that changed something."
        said(conversationOf(read)) shouldBe "It changed nothing."
    }

    "a change is rehearsed by a dry run of the same change that was accepted" {
        fun save(workflow: String, dryRun: Boolean, refused: Boolean = false) =
            Triple(ATOOL.saveTemplateWorkflow, mapOf<String, Any?>(WFD.workflowId to workflow, CFEP.dryRun to dryRun), refused)
        fun outcome(vararg calls: Triple<String, Map<String, Any?>, Boolean>) =
            MethodChecks.dryRunsFirst.check(resultOf(emptyMap(), listOf(conversationOf(*calls)))).kind
        outcome(save("newForm", true), save("details", true), save("newForm", false), save("details", false)) shouldBe OutcomeKind.met
        // Another change's dry run, and one refused, rehearse nothing. Both were met while any dry run of the tool would do.
        outcome(save("newForm", true), save("newForm", false), save("details", false)) shouldBe OutcomeKind.varied
        outcome(save("newForm", true, refused = true), save("newForm", false)) shouldBe OutcomeKind.varied
    }

    "a copy check titled for one line is not met by another" {
        val copy = mapOf(
            CCT.fragmentDef to listOf(
                mapOf(
                    CCT.fileId to HFRAG.home,
                    CCT.content to mapOf(HFRAG.homeNs to mapOf("brand" to "Riverbend", HFRAG.intro to "Hello.")),
                ),
            ),
        )
        fun outcome(namespace: String?, key: String?) = Checks.copyMentions(resultOf(copy), HFRAG.home, namespace, "Riverbend", key).kind
        outcome(null, null) shouldBe OutcomeKind.met
        outcome(HFRAG.homeNs, "brand") shouldBe OutcomeKind.met
        outcome(HFRAG.homeNs, HFRAG.intro) shouldBe OutcomeKind.varied
        outcome(HFRAG.homeNs, "title") shouldBe OutcomeKind.notMet
        // A namespace is matched whole, not by how it begins.
        outcome("hom", null) shouldBe OutcomeKind.notMet
    }

    "a report is held to grouping by the category and totaling the amount, by the fields its columns read" {
        val trait = mapOf(
            CCT.traitId to "ask",
            CCT.dataSchema to mapOf(
                SCH.properties to mapOf(
                    "amount" to mapOf(SCH.type to "number"),
                    "category" to mapOf(SCH.options to listOf(mapOf("value" to "arts", "label" to "Arts"))),
                ),
            ),
        )
        fun report(id: String, groupBy: String?, rollup: String?) = mapOf(
            CCT.reportId to id,
            CCT.definition to buildMap<String, Any?> {
                put(RDEF.reportId, id)
                put(RDEF.label, "By category, with the amount's sum")
                put(
                    RDEF.columns,
                    listOf(
                        mapOf(RDEF.columnId to "cat", RDEF.path to "form.ask.category"),
                        mapOf(RDEF.columnId to "amt", RDEF.path to "form.ask.amount", RDEF.rollup to rollup),
                        mapOf(RDEF.columnId to "made", RDEF.path to "meta.createdAt", RDEF.rollup to "count"),
                    ),
                )
                groupBy?.let { put(RDEF.groupBy, listOf(it)) }
            },
        )
        val starter = report(ClientCreate.starterReport, null, null)
        fun outcome(vararg reports: Map<String, Any?>): OutcomeKind {
            val result = resultOf(mapOf(CCT.traitDef to listOf(trait), CCT.reportDef to listOf(starter) + reports))
            return Checks.reportTotals(result, listOf("category"), listOf("amount")).kind
        }
        outcome(report("r", "cat", "sum")) shouldBe OutcomeKind.met
        outcome(report("r", "cat", "count")) shouldBe OutcomeKind.varied
        // Its words say category and sum, and it groups by neither: met while the check read the report's text.
        outcome(report("r", "made", "sum")) shouldBe OutcomeKind.varied
        outcome(report("r", null, null)) shouldBe OutcomeKind.notMet
        // The starter report, which every client has, kept this from ever being so.
        outcome() shouldBe OutcomeKind.notMet
    }

    "a card an applicant is not shown starts nothing for one" {
        fun outcome(condition: String?): OutcomeKind {
            val card = buildMap<String, Any?> {
                put("id", "start")
                put(HCARD.title, "Start")
                put(UIB.action, HMENU.pageNewForm)
                put(HCARD.actionLabel, "Start")
                condition?.let { put(UIB.cfactExpression, it) }
            }
            val block = mapOf(CCT.blockId to HCARD.block, CCT.content to mapOf(HCARD.cards to listOf(card)))
            return Checks.homeCardStartsForm(resultOf(mapOf(CCT.uiBlockDef to listOf(block)))).kind
        }
        outcome(null) shouldBe OutcomeKind.met
        outcome("${CFACTS.loggedIn},${CFACTS.app}") shouldBe OutcomeKind.met
        outcome("${CFACTS.loggedIn},~${CFACTS.seesClientForms},${CFACTS.app}") shouldBe OutcomeKind.met
        outcome("${CFACTS.administersClient},${CFACTS.app}") shouldBe OutcomeKind.varied
        outcome("${CFACTS.anonymous},${CFACTS.app}") shouldBe OutcomeKind.varied
        outcome(CFACT.neverName) shouldBe OutcomeKind.varied
    }

    "a choice is held to the options named, by their labels or their values" {
        fun names(options: List<Any?>) = FieldView("ask", "category", mapOf(SCH.options to options)).optionNames
        names(listOf(mapOf("value" to "a1", "label" to "Arts"), mapOf("value" to "health"))) shouldBe setOf("arts", "health")
        names(listOf("Arts", "Health")) shouldBe setOf("arts", "health")
        // A field's label is in its type's layout, and counts among its words.
        val trait = mapOf(
            CCT.traitId to "ask",
            CCT.dataSchema to mapOf(
                SCH.properties to mapOf("f1" to emptyMap<String, Any?>()),
                SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "f1", SL.label to "Focus area"))),
            ),
        )
        resultOf(mapOf(CCT.traitDef to listOf(trait))).view.fields.single().mentions(listOf("focus")) shouldBe true
    }

    "a variant's own expectations are held beside its trial's" {
        val own = TrialVariant("own", "One of its own.", emptyList(), listOf(Expectation("Its own") { Outcome.met("yes") }))
        fun names(variant: TrialVariant) = resultOf(emptyMap(), variant = variant).outcomes().map { it.first.name }
        names(own).take(2) shouldBe listOf("Home's introduction names the program", "Its own")
        names(smoke.variants.single()).contains("Its own") shouldBe false
        // Every variant of the first trial is held to the program's name on Home and in its mails, however exactly.
        for (variant in GrantsBasics.trial.variants) {
            variant.expectations.count { Regex("names? the program").containsMatchIn(it.name) } shouldBe 2
        }
    }

    "a run that fails to start is its own problem, and what ran before it is kept and read back" {
        val dir = Files.createTempDirectory("trials1914").toFile()
        try {
            val first = TrialVariant("first", "Runs.", emptyList())
            val second = TrialVariant("second", "Fails to start.", emptyList())
            val third = TrialVariant("third", "Runs after it.", emptyList())
            val trial = Trial("pair", "Several variants.", "Pair", listOf(first, second, third), smoke.expectations)
            val conversation = conversationOf(Triple(ATOOL.setCopy, emptyMap(), false))
            val info = RunInfo("t2", "http://localhost:0", null, emptyMap(), null, Clock.System.now())
            val seen = mutableListOf<Boolean>()
            val (report, results) = TrialRunner.runAll(trial.variants.map { trial to it }, dir, info) { t, v, email ->
                email shouldBe "pair-${v.name}-t2@trials.test"
                // The report is there, with what ran before, when the next begins.
                seen.add(File(dir, "report.md").isFile)
                if (v === second) error("no node")
                val now = Clock.System.now()
                TrialResult(t, v, "c", null, listOf(conversation), mapOf("main" to mapOf("name" to "main")), emptyList(), now, now)
            }
            seen shouldBe listOf(false, true, true)
            results.map { it.first.problems } shouldBe listOf(emptyList(), listOf("The run failed: no node"), emptyList())
            report.readText() shouldContain "- The run failed: no node"
            // Read back from the files alone, as a recheck does: what a check reads is as it was.
            val back = TrialReport.read(dir, listOf(trial))
            back.map { it.variant.name } shouldBe listOf("first", "second", "third")
            back.first().calls.map { it.name } shouldBe listOf(ATOOL.setCopy)
            back.first().config.keys shouldBe setOf("main")
            TrialReport.renderRecheck("t2", back) shouldContain "## pair, first"
        } finally {
            dir.deleteRecursively()
        }
    }

    "copy for one file in two bundles is merged, not replaced" {
        fun bundle(key: String, value: String) = mapOf(
            CFEP.slots to mapOf(
                CCT.fragmentDef to listOf(mapOf(CCT.fileId to HFRAG.home, CCT.content to mapOf(HFRAG.homeNs to mapOf(key to value)))),
            ),
        )
        val view = ConfigView(mapOf("a" to bundle(HFRAG.intro, "Riverbend grants."), "b" to bundle("other", "More.")))
        view.copyKeys["${HFRAG.home}.${HFRAG.homeNs}.${HFRAG.intro}"] shouldBe "Riverbend grants."
        view.copyKeys["${HFRAG.home}.${HFRAG.homeNs}.other"] shouldBe "More."
    }

    "a cost is the weighted tokens at the model's input price" {
        TrialReport.cost(1_500_000, "claude-sonnet-5-5") shouldBe "$3.00"
        TrialReport.cost(1_000, "claude-sonnet-5-5") shouldBe "$0.00"
        TrialReport.cost(1_000, "no-such-model") shouldBe null
    }
})
