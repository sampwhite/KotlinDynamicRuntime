package com.dynamicruntime.trials

import com.dynamicruntime.assistant.AENV
import com.dynamicruntime.assistant.AssistantComponent
import com.dynamicruntime.assistant.AssistantService
import com.dynamicruntime.assistant.ScriptedModel
import com.dynamicruntime.common.assistant.ABLK
import com.dynamicruntime.common.assistant.ACNV
import com.dynamicruntime.common.assistant.ATOOL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SCH
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
        outcomes[MethodChecks.readsMakingChanges.name] shouldBe OutcomeKind.met
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
        fun patch(slot: String, dryRun: Boolean = false) = call(
            ATOOL.patchConfig,
            mapOf(CFEP.dryRun to dryRun, CFEP.configs to listOf(mapOf(CFEP.edits to listOf(mapOf(CFEP.slot to slot))))),
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
        ).kind shouldBe OutcomeKind.met
        // A turn that never dry-ran, and a call that never said: both made outright.
        dryRunOutcome(call("t", mapOf(CFEP.dryRun to true)), call("t", mapOf(CFEP.dryRun to false)), call("u", emptyMap())).let {
            it.kind shouldBe OutcomeKind.varied
            it.detail shouldContain "Made without a dry run: ${ATOOL.patchConfig}."
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
