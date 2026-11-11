package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.commentRefusedMessage
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A workflow definition read from its stored form (issue #1738 gathered these into one file): the core model, then one
// class per part of a definition -- approval, what an approval shows, eligibility, the rule for who may save, locks,
// singletons, the highlighted start, and function wiring. Each class is the file it used to be, kept whole with its
// own helpers, and each runs on the JVM and under Kotlin/JS alike.

/**
 * The workflow definition model (issue #533), in `commonTest` so the exact source runs on the JVM and under
 * Kotlin/JS: a frontend reading a definition and a backend enforcing one must agree on what it says.
 *
 * Three things are covered that a one-task creation fixture never reaches: the **multi-task** model
 * (`showTaskList`, per-task completeness), the **string-encoded** forms a data-authored definition arrives in,
 * and every **structural refusal** -- each of which is a state a working sample cannot be in.
 */
class WfDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun creation(build: WfTaskBuilder.() -> Unit = { trait("name"); save("create", "Create") }): Map<String, Any?> =
        WfDefBuilder("createForm", WfEntry.creation).apply { task("identify", "Name it", build) }.build()

    private fun entry(traitId: String, data: Any? = mapOf("x" to 1)): Map<String, Any?> =
        mapOf(GE.traitId to traitId, GE.data to data)

    @Test
    fun builderRoundTripsThroughTheSchema() {
        val def = parseWfDef(cxt, creation { trait("name"); trait("expenseReport", required = false); save("create", "Create") })
        assertEquals("createForm", def.workflowId)
        assertEquals(WfEntry.creation, def.entry)
        val task = def.tasks.single()
        assertEquals(listOf("name"), task.requiredTraitIds)
        assertEquals(listOf("name", "expenseReport"), task.displayOrder)
        assertEquals(WfSaveKind.create, task.saves.single().kind)
        assertFalse(def.showTaskList)
    }

    @Test
    fun stringEncodedValuesAreCoercedOnTheWayIn() {
        // Decision 5: a definition from data may spell a boolean, an enum and a list as strings.
        val raw = mapOf(
            WFD.workflowId to "createForm",
            WFD.entry to "creation",
            WFD.tasks to listOf(
                mapOf(
                    WFD.id to "identify", WFD.label to "Name it",
                    WFD.traits to listOf(
                        mapOf(WFD.traitId to "name", WFD.required to "true"),
                        mapOf(WFD.traitId to "notes", WFD.required to "false"),
                    ),
                    WFD.saves to listOf(mapOf(WFD.id to "create", WFD.label to "Create", WFD.kind to "create")),
                    WFD.layout to mapOf(WFD.order to "notes,name", WFD.edit to "inline"),
                ),
            ),
        )
        val def = parseWfDef(cxt, raw)
        val task = def.tasks.single()
        assertEquals(listOf("name"), task.requiredTraitIds)
        assertEquals(listOf("notes", "name"), task.displayOrder)
        assertEquals(WfEditMode.inline, task.layout?.edit)
    }

    @Test
    fun schemaRefusesWhatItCannotRead() {
        // A missing required field and an unknown enum value are schema failures, named by path.
        val noTasks = mapOf(WFD.workflowId to "w", WFD.entry to "creation")
        val e1 = assertFailsWith<KdrException> { parseWfDef(cxt, noTasks) }
        assertTrue(e1.message!!.contains(WFD.tasks), e1.message)
        val badEntry = creation().toMutableMap().also { it[WFD.entry] = "sometimes" }
        val e2 = assertFailsWith<KdrException> { parseWfDef(cxt, badEntry) }
        assertTrue(e2.message!!.contains(WFD.entry), e2.message)
    }

    @Test
    fun commentsRoundTripWhereDeclaredAndAreRefusedElsewhere() {
        // Issue #1135: a workflow, a task and an eligibility test take a `$comment`; it survives the builder, the
        // parse and the JSON form a stored definition is written back in, and changes nothing about the definition.
        val built = WfDefBuilder("review", WfEntry.normal).apply {
            comment = "Committee review."
            eligibility("coreDetailsDone", "coreDetailsComplete", "Finish the core details workflow first.",
                comment = "Reviewers need the track.")
            task("score", "Score it") {
                comment = "The committee's score."
                trait("talkScore")
                save("save", "Save", WfSaveKind.edit)
            }
        }.build()
        val def = parseWfDef(cxt, built)
        assertEquals("Committee review.", def.comment)
        assertEquals("The committee's score.", def.tasks.single().comment)
        assertEquals("Reviewers need the track.", def.eligibility.single().comment)
        assertEquals(built, def.toJsonMap())
        assertEquals(def.toJsonMap(), parseWfDef(cxt, def.toJsonMap()).toJsonMap())

        // Absent, nothing is written: a definition without comments keeps the JSON form it always had.
        assertFalse(SCH.dComment in parseWfDef(cxt, creation()).toJsonMap())

        // A save is not one of the places, so its comment is refused, with the message that says where one goes.
        val onSave = creation().toMutableMap().also { m ->
            val task = m[WFD.tasks].toJsonListOfMaps().single().toMutableMap()
            task[WFD.saves] = listOf(mapOf(WFD.id to "create", WFD.label to "Create", WFD.kind to "create", SCH.dComment to "no"))
            m[WFD.tasks] = listOf(task)
        }
        val e = assertFailsWith<KdrException> { parseWfDef(cxt, onSave) }
        assertTrue(e.message!!.contains(commentRefusedMessage), e.message)
    }

    @Test
    fun creationWorkflowHasExactlyOneTaskAndOneCreateSave() {
        val two = WfDefBuilder("w", WfEntry.creation).apply {
            task("a", "A") { trait("name"); save("s", "S") }
            task("b", "B") { trait("name"); save("s", "S") }
        }.build()
        assertFailsWith<KdrException> { parseWfDef(cxt, two) }
        val noSave = creation { trait("name") }
        assertFailsWith<KdrException> { parseWfDef(cxt, noSave) }
        val twoSaves = creation { trait("name"); save("s1", "One"); save("s2", "Two") }
        assertFailsWith<KdrException> { parseWfDef(cxt, twoSaves) }
    }

    @Test
    fun structuralMistakesAreRefusedWhereWritten() {
        assertFailsWith<KdrException> { WfDef("bad id", WfEntry.creation, listOf(task("t"))) }
        assertFailsWith<KdrException> { WfDef("w", WfEntry.normal, emptyList()) }
        assertFailsWith<KdrException> { WfDef("w", WfEntry.normal, listOf(task("t"), task("t"))) }
        assertFailsWith<KdrException> { WfTask("t", "T", listOf(WfTraitRef("a"), WfTraitRef("a")), emptyList()) }
        assertFailsWith<KdrException> {
            WfTask("t", "T", listOf(WfTraitRef("a")), listOf(WfSave("s", "S", WfSaveKind.create), WfSave("s", "S", WfSaveKind.create)))
        }
        assertFailsWith<KdrException> {
            WfTask("t", "T", listOf(WfTraitRef("a")), emptyList(), WfLayout(listOf("a", "zzz")))
        }
    }

    /**
     * A task's search keyword (issue #1594): it round-trips, an approval task carries `review` without saying so, and
     * a keyword the closed list does not hold, or one on a workflow with no call to action, is refused where written.
     */
    @Test
    fun aTasksKeywordRoundTripsAndIsHeldToTheList() {
        val def = parseWfDef(
            cxt,
            WfDefBuilder("review", WfEntry.normal).apply {
                task("describe", "Describe it") { trait("notes"); save("s", "Save", WfSaveKind.edit); keyword(CTAK.review) }
                task("approve", "Approve it") { approval("approved", "Approve?", "Approve") }
                task("plan", "Plan it") { trait("plan"); save("p", "Save", WfSaveKind.edit) }
            }.build(),
        )
        assertEquals(CTAK.review, def.tasksById.getValue("describe").keyword)
        assertEquals(CTAK.review, parseWfDef(cxt, def.toJsonMap()).tasksById.getValue("describe").keyword)
        // An approval task waits on a review by definition; a plain task with none declared carries none.
        assertNull(def.tasksById.getValue("approve").keyword)
        assertEquals(CTAK.review, def.tasksById.getValue("approve").ctaKeyword)
        assertNull(def.tasksById.getValue("plan").ctaKeyword)
        assertFailsWith<KdrException> {
            WfTask("t", "T", listOf(WfTraitRef("a")), listOf(WfSave("s", "S", WfSaveKind.edit)), keyword = "sign")
        }
        assertFailsWith<KdrException> { parseWfDef(cxt, creation { trait("name"); save("create", "Create"); keyword(CTAK.review) }) }
    }

    @Test
    fun coreDetailsWorkflowRules() {
        fun coreDetailsTask(id: String) = WfTask(id, id, listOf(WfTraitRef("a")), listOf(WfSave("s", "S", WfSaveKind.edit)))

        // A core details workflow may have several tasks (a creation workflow may not), up to the ceiling, with `edit`
        // saves.
        val ok = WfDef("review", WfEntry.coreDetails, (1..WfDef.coreDetailsMaxTasks).map { coreDetailsTask("t$it") })
        assertEquals(WfDef.coreDetailsMaxTasks, ok.tasks.size)
        assertTrue(ok.showTaskList)

        // More than the ceiling, a `create` save, and a save-less task are each refused.
        assertFailsWith<KdrException> {
            WfDef("review", WfEntry.coreDetails, (1..WfDef.coreDetailsMaxTasks + 1).map { coreDetailsTask("t$it") })
        }
        assertFailsWith<KdrException> {
            WfDef("review", WfEntry.coreDetails, listOf(WfTask("t", "T", listOf(WfTraitRef("a")), listOf(WfSave("s", "S",
                WfSaveKind.create)))))
        }
        assertFailsWith<KdrException> {
            WfDef("review", WfEntry.coreDetails, listOf(WfTask("t", "T", listOf(WfTraitRef("a")), emptyList())))
        }

        // The full data path: a core details workflow built in source, its `edit` save admitted by the definition
        // schema.
        val built = WfDefBuilder("review", WfEntry.coreDetails).apply {
            task("details", "Details") { trait("expenseReport"); save("save", "Save changes", WfSaveKind.edit) }
            task("extra", "Extra") { trait("questionnaire", required = false); save("save2", "Save", WfSaveKind.edit) }
        }.build()
        val def = parseWfDef(cxt, built)
        assertEquals(WfEntry.coreDetails, def.entry)
        assertEquals(WfSaveKind.edit, def.tasks.first().saves.single().kind)
    }

    @Test
    fun multiTaskModelDerivesStatusPerTask() {
        // Not a creation workflow -- the multi-task branch a one-task fixture never reaches. A core details workflow's saves
        // are `edit`, which its structural check requires.
        val income = task("income", "income", saveKind = WfSaveKind.edit)
        val assets = task("assets", "assets", "assetNotes" to false, saveKind = WfSaveKind.edit)
        val def = WfDef("coreDetails", WfEntry.coreDetails, listOf(income, assets))
        assertTrue(def.showTaskList)
        assertEquals(assets, def.task("assets"))
        assertNull(def.task("missing"))

        val entries = listOf(entry("income"), entry("assets"))
        assertTrue(WfEngine.taskComplete(income, entries))
        assertTrue(WfEngine.taskComplete(assets, entries)) // the optional trait is not required
        assertEquals(listOf("income"), WfEngine.missingTraits(income.requiredTraitIds, emptyList()))
        // Presence, not content: an empty map is a present entry; a null data is not.
        assertTrue(WfEngine.taskComplete(income, listOf(entry("income", emptyMap<String, Any?>()))))
        assertFalse(WfEngine.taskComplete(income, listOf(entry("income", null))))
    }

    @Test
    fun taskFactsReportCompletenessAndAlwaysAvailability() {
        val t = task("income", "income")
        assertEquals(setOf(WFC.taskAvailable), WfTaskFacts.of(t, emptyList()))
        assertEquals(setOf(WFC.taskAvailable, WFC.taskComplete), WfTaskFacts.of(t, listOf(entry("income"))))
        // Whether the task is the CTA is the caller's judgment over the whole list (issue #785); said, it is a fact.
        assertEquals(setOf(WFC.taskAvailable, WFC.isCta), WfTaskFacts.of(t, emptyList(), isCta = true))
    }

    @Test
    fun wfRefRoundTripsAndRefusesNonReferences() {
        val bundle = GedraId.of(GedraConfigType.configDoc, "acme", "acmeForms", "2")
        val ref = WfRef(bundle, "createForm")
        assertEquals("gc.cd.acme.acmeForms~2#createForm", ref.text)
        assertEquals(ref, WfRef.parse(ref.text))
        assertEquals(ref.hashCode(), WfRef.parse(ref.text).hashCode())
        assertFailsWith<KdrException> { WfRef.parse("gc.cd.acme.acmeForms~2") }
        assertFailsWith<KdrException> { WfRef.parse("#createForm") }
        assertFailsWith<KdrException> { WfRef.parse("gc.cd.acme.acmeForms#") }
        assertFailsWith<KdrException> { WfRef(bundle, "not a name") }
        assertNull(WfRef.parseOrNull(null))
        assertNull(WfRef.parseOrNull("nonsense"))
        assertEquals(ref, WfRef.parseOrNull(ref.text))
    }

    // A global workflow's id is rooted (issue #953), and a reference carries it as text: the colon is part of the id,
    // after the one `#`, so the reference still reads back to the same pair.
    @Test
    fun wfRefCarriesARootedWorkflowId() {
        val bundle = GedraId.of(GedraConfigType.configDoc, "global", "flows", "1")
        val ref = WfRef(bundle, "kdr:review")
        assertEquals("gc.cd.global.flows~1#kdr:review", ref.text)
        assertEquals("kdr:review", WfRef.parse(ref.text).workflowId)
        assertEquals(bundle, WfRef.parse(ref.text).bundleId)
        assertFailsWith<KdrException> { WfRef(bundle, "kdr:review:again") }
        assertFailsWith<KdrException> { WfRef(bundle, "kdr:not a name") }
        assertNull(WfRef.parseOrNull("gc.cd.global.flows~1#:review"))
    }

    private fun task(id: String, vararg required: String, saveKind: WfSaveKind = WfSaveKind.create): WfTask =
        WfTask(id, id, required.map { WfTraitRef(it) }, listOf(WfSave("save", "Save", saveKind)))

    private fun task(id: String, required: String, optional: Pair<String, Boolean>, saveKind: WfSaveKind = WfSaveKind.create): WfTask =
        WfTask(
            id, id,
            listOf(WfTraitRef(required), WfTraitRef(optional.first, optional.second)),
            listOf(WfSave("save", "Save", saveKind)),
        )
}

/**
 * An approval task as definition data (issue #787): declared by the builder, parsed, round-tripped, and refused
 * where it has no coherent reading -- traits or saves beside the approval, or an approval outside a normal
 * workflow. Recording and evaluating approvals is `base:common`'s.
 */
class WfApprovalDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun normalWithApproval(extra: WfTaskBuilder.() -> Unit = {}) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("record", "Record") { trait("a"); save("s", "S", WfSaveKind.edit) }
        task("approve", "Approve") {
            approval("auditApproved", "Approve the audit once you have read it.", "Approve")
            extra()
        }
    }.build()

    @Test
    fun anApprovalTaskParsesAndRoundTrips() {
        val def = parseWfDef(cxt, normalWithApproval())
        val approval = def.task("approve")?.approval
        assertEquals("auditApproved", approval?.cfact)
        assertEquals("Approve", approval?.button)
        // An ordinary task is not an approval task.
        assertNull(def.task("record")?.approval)

        val again = parseWfDef(cxt, def.toJsonMap()).task("approve")?.approval
        assertEquals(listOf(approval?.cfact, approval?.prompt, approval?.button), listOf(again?.cfact, again?.prompt, again?.button))
    }

    @Test
    fun anApprovalTaskCollectsNothingAndSavesNothing() {
        assertFailsWith<KdrException> { parseWfDef(cxt, normalWithApproval { trait("a") }) }
        assertFailsWith<KdrException> { parseWfDef(cxt, normalWithApproval { save("s", "S", WfSaveKind.edit) }) }
    }

    @Test
    fun onlyANormalWorkflowHasApprovalTasks() {
        val raw = WfDefBuilder("review", WfEntry.coreDetails).apply {
            task("approve", "Approve") { approval("x", "P", "B") }
        }.build()
        assertFailsWith<KdrException> { parseWfDef(cxt, raw) }
    }

    @Test
    fun anApprovalNamesACfactAndCarriesItsCopy() {
        // A blank cfact would be an approval that emits nothing.
        assertFailsWith<KdrException> {
            parseWfDef(cxt, WfDefBuilder("audit", WfEntry.normal).apply {
                task("approve", "Approve") { approval(" ", "P", "B") }
            }.build())
        }
        // Stored config missing the button's copy is refused by the schema, not drawn as an unlabelled button.
        val raw = WfDefBuilder("audit", WfEntry.normal).apply {
            task("approve", "Approve") { approval("x", "P", "B") }
        }.build()
        val tasks = (raw[WFD.tasks] as List<*>).map { (it as Map<*, *>).entries.associate { e -> e.key.toString() to e.value } }
        val stripped = raw + (WFD.tasks to tasks.map { t ->
            t + (WFD.approval to (t[WFD.approval] as Map<*, *>).filterKeys { it != WFD.button })
        })
        assertFailsWith<KdrException> { parseWfDef(cxt, stripped) }
    }

    @Test
    fun anApprovalTasksFactsFollowItsApprovalNotItsTraits() {
        val task = parseWfDef(cxt, normalWithApproval()).task("approve")!!
        // No traits, so presence alone would call it complete; until approved it is not.
        assertEquals(setOf(WFC.taskAvailable, WFC.isCta), WfTaskFacts.of(task, emptyList(), isCta = true))
        // Approved: complete, and carrying its own approval cfact for a display selector to name (issue #788).
        assertEquals(setOf(WFC.taskAvailable, WFC.taskComplete, "auditApproved"), WfTaskFacts.of(task, emptyList(), approved = true))
    }

    /** A two-step approval (issue #1702), or one with [steps] in place of its two. */
    private fun steppedApproval(steps: List<WfApprovalStep>? = null) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("approve", "Approve") {
            approvalSteps(
                "auditApproved",
                steps ?: listOf(
                    WfApprovalStep("first", "First look", "Look first.", "Looked", WFC.reviewer, "Waiting on a reviewer", "looked"),
                    WfApprovalStep("second", "Sign-off", "Sign it off.", "Sign off", null, null, null),
                ),
            )
        }
    }.build()

    @Test
    fun anApprovalInStepsRoundTripsAndIsGivenInOrder() {
        val built = steppedApproval()
        val def = parseWfDef(cxt, built)
        assertEquals(built, def.toJsonMap())
        val task = def.task("approve")!!
        assertEquals(listOf("first", "second"), task.approvalSteps.map { it.id })
        assertEquals(WfApproval.defaultApproveWhen, task.approvalSteps[1].effectiveApproveWhen)
        // The record of each step is keyed by task and step; the current step is the first not given.
        val first = approvalKey("approve", "first")
        assertEquals("approve.first", first)
        assertEquals("first", task.currentStep(emptySet())?.id)
        assertEquals("second", task.currentStep(setOf(first))?.id)
        assertEquals("second", task.declaredCurrentStep(setOf(first))?.id)
        assertFalse(task.isApproved(setOf(first)))
        assertEquals(setOf("looked"), task.givenStepCfacts(setOf(first)))
        val both = setOf(first, approvalKey("approve", "second"))
        assertTrue(task.isApproved(both))
        assertNull(task.currentStep(both))
        // A step given out of order is no progress: the first is still the one waited on.
        assertEquals("first", task.currentStep(setOf(approvalKey("approve", "second")))?.id)
        // A step's cfact holds once it is given, beside the approval's own once all are.
        assertEquals(setOf(WFC.taskAvailable, WFC.taskComplete, "auditApproved", "looked"),
            WfTaskFacts.of(task, emptyList(), approved = true, stepCfacts = task.givenStepCfacts(both)))
    }

    @Test
    fun anApprovalGivenAtOnceIsOneStepKeyedByItsTask() {
        val task = parseWfDef(cxt, normalWithApproval()).task("approve")!!
        assertEquals(listOf("approve"), task.approvalSteps.map { it.id })
        assertEquals("approve", approvalKey("approve", "approve"))
        assertEquals("approve", approvalKey("approve", null))
        assertTrue(task.isApproved(setOf("approve")))
        // Its call to action follows no step: it is as it was before steps.
        assertNull(task.declaredCurrentStep(emptySet()))
    }

    @Test
    fun anApprovalsStepsAreHeldToTheirRules() {
        fun step(id: String, prompt: String = "P", button: String = "B", cfact: String? = null) =
            WfApprovalStep(id, null, prompt, button, null, null, cfact)
        // Two steps of one id; a step with no button; a step asserting the approval's own cfact.
        assertFailsWith<KdrException> { parseWfDef(cxt, steppedApproval(listOf(step("a"), step("a")))) }
        assertFailsWith<KdrException> { parseWfDef(cxt, steppedApproval(listOf(step("a", button = "")))) }
        assertFailsWith<KdrException> { parseWfDef(cxt, steppedApproval(listOf(step("a", cfact = "auditApproved")))) }
        // A step id is a variable name -- no dot to make `<task>.<step>` ambiguous -- and not the task's own id.
        assertFailsWith<KdrException> { parseWfDef(cxt, steppedApproval(listOf(step("b.c")))) }
        assertFailsWith<KdrException> { parseWfDef(cxt, steppedApproval(listOf(step("approve")))) }
        // Steps, and a prompt of the approval's own: with steps, each step has its own.
        val raw = steppedApproval(listOf(step("a")))
        val tasks = raw[WFD.tasks].toJsonListOfMaps()
        val withPrompt = raw + (WFD.tasks to tasks.map { t -> t + (WFD.approval to (t[WFD.approval] as Map<*, *>) + (WFD.prompt to "P")) })
        assertFailsWith<KdrException> { parseWfDef(cxt, withPrompt) }
    }

    @Test
    fun aTaskDisplayIsASelectorThatRoundTrips() {
        val def = parseWfDef(cxt, normalWithApproval {
            display {
                whenCfacts("auditApproved") { text("Approved.") }
                whenCfacts("~${WFC.isCta}") { text("Not yet."); disabled = true }
                whenCfacts(WFC.reviewer) { defaultRendering() }
                otherwise { text("Wait.") }
            }
        })
        val display = def.task("approve")?.display
        // The UiBlock selector shape, branches in declaration order, the last unguarded.
        val branches = display?.get(UIB.select) as List<*>
        assertEquals(4, branches.size)
        assertEquals(mapOf(UIB.cfactExpression to "~${WFC.isCta}", WDSP.mode to WDSP.textMode, WDSP.text to "Not yet.", WDSP.disabled to true), branches[1])
        assertEquals(mapOf(WDSP.mode to WDSP.textMode, WDSP.text to "Wait."), branches[3])
        assertEquals(display, parseWfDef(cxt, def.toJsonMap()).task("approve")?.display)
    }
}

/**
 * What an approval task shows, read-only, above its prompt (issue #1695): by default the nearest earlier task that collects
 * traits, else the tasks its approval names, or none; parsed and round-tripped, and refused when it names a task the
 * workflow does not have.
 */
class WfApprovalShowsTest {
    private val cxt: KdrCxtBase = LiteCxt()

    /** A review: a task collecting the review, a note collecting nothing, then the approval, [showTasks] as given. */
    private fun def(showTasks: List<String>? = null) = parseWfDef(
        cxt,
        WfDefBuilder("review", WfEntry.normal).apply {
            task("record", "Record the review") { trait("review"); save("s", "Save", WfSaveKind.edit) }
            task("summary", "Summarize it") { trait("summary"); save("t", "Save", WfSaveKind.edit) }
            task("accept", "Accept the talk") { approval("accepted", "Read it, then accept.", "Accept", showTasks = showTasks) }
        }.build(),
    )

    private fun shows(def: WfDef) = def.approvalShows(def.task("accept")!!)

    @Test
    fun byDefaultAnApprovalShowsTheNearestEarlierTaskThatCollectsTraits() {
        val plain = def()
        assertEquals(listOf("summary"), shows(plain))
        // A task that is no approval shows nothing.
        assertEquals(emptyList(), plain.approvalShows(plain.task("record")!!))
        // The default is not written, so a definition that never names one stores as it did.
        assertFalse(WFD.showTasks in plain.toJsonMap()[WFD.tasks].let { tasks ->
            (tasks as List<*>).filterIsInstance<Map<*, *>>().single { it[WFD.id] == "accept" }[WFD.approval] as Map<*, *>
        })
    }

    @Test
    fun anApprovalMayNameTheTasksItShowsOrNone() {
        val named = def(showTasks = listOf("record", "summary"))
        assertEquals(listOf("record", "summary"), shows(named))
        assertEquals(listOf("record", "summary"), shows(parseWfDef(cxt, named.toJsonMap())))
        // An empty list shows none, and round-trips as one rather than as the default.
        val none = def(showTasks = emptyList())
        assertEquals(emptyList(), shows(none))
        assertEquals(emptyList(), shows(parseWfDef(cxt, none.toJsonMap())))
    }

    @Test
    fun anApprovalNamesOnlyOtherTasksOfItsWorkflow() {
        assertFailsWith<KdrException> { def(showTasks = listOf("gone")) }
        assertFailsWith<KdrException> { def(showTasks = listOf("accept")) }
    }
}

/**
 * A normal workflow's eligibility tests as definition data (issue #783): declared in order by the builder,
 * parsed into [WfEligibility], round-tripped through `toJsonMap`, and refused where they have no coherent
 * reading. Evaluating them against a form's cfacts is `base:common`'s, where the cfact registry lives.
 */
class WfEligibilityDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun normal(extra: WfDefBuilder.() -> Unit) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        extra()
    }.build()

    @Test
    fun eligibilityParsesInOrderAndRoundTrips() {
        val def = parseWfDef(
            cxt,
            normal {
                label = "Audit"
                eligibility("coreDetailsDone", "coreDetailsComplete", "Finish the core details workflow first.")
                eligibility("notAudited", "~underAudit", "An audit is already open.")
            },
        )
        assertEquals(listOf("coreDetailsDone", "notAudited"), def.eligibility.map { it.id })
        assertEquals("~underAudit", def.eligibility[1].test)

        val again = parseWfDef(cxt, def.toJsonMap())
        assertEquals(def.eligibility.map { Triple(it.id, it.test, it.explanation) }, again.eligibility.map { Triple(it.id, it.test, it.explanation) })
        // The workflow's own label survives the round trip too -- it used to be dropped on serialization.
        assertEquals("Audit", again.label)
    }

    @Test
    fun aWorkflowWithNoEligibilityIsEligibleByDefinition() {
        assertTrue(parseWfDef(cxt, normal {}).eligibility.isEmpty())
    }

    @Test
    fun twoTestsWithOneIdAreRefused() {
        val e = assertFailsWith<KdrException> {
            parseWfDef(cxt, normal {
                eligibility("dup", "a", "A")
                eligibility("dup", "b", "B")
            })
        }
        assertTrue(e.message.orEmpty().contains("'dup'"))
    }

    @Test
    fun aBlankTestIsRefused() {
        assertFailsWith<KdrException> { parseWfDef(cxt, normal { eligibility("blank", " ", "Never shown.") }) }
    }

    @Test
    fun onlyANormalWorkflowHasEligibility() {
        val raw = WfDefBuilder("review", WfEntry.coreDetails).apply {
            eligibility("x", "a", "A")
            task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        }.build()
        val e = assertFailsWith<KdrException> { parseWfDef(cxt, raw) }
        assertTrue(e.message.orEmpty().contains("only a normal workflow"))
    }
}

/**
 * A task's rule for who may save it (issue #856) as definition data: declared, parsed and round-tripped, and refused
 * where it could not mean anything -- outside a normal workflow, blank, or on a task that offers no save.
 */
class WfSaveRuleDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun normal(entry: WfEntry = WfEntry.normal, rule: String = WFC.reviewer, saves: Boolean = true) =
        WfDefBuilder("audit", entry).apply {
            task("record", "Record") {
                trait("a")
                if (saves) save("s", "S", WfSaveKind.edit)
                saveWhen(rule)
            }
            task("other", "Other") { trait("b"); save("s2", "S2", WfSaveKind.edit) }
        }.build()

    @Test
    fun aRuleParsesAndRoundTrips() {
        val def = parseWfDef(cxt, normal())
        assertEquals(WFC.reviewer, def.task("record")?.saveWhen)
        // A task that says nothing admits anyone who can see the form.
        assertNull(def.task("other")?.saveWhen)
        assertEquals(WFC.reviewer, parseWfDef(cxt, def.toJsonMap()).task("record")?.saveWhen)
    }

    @Test
    fun aCoreDetailsTaskMayNotDeclareOne() {
        assertFailsWith<KdrException> { parseWfDef(cxt, normal(entry = WfEntry.coreDetails)) }
    }

    @Test
    fun aBlankRuleReadsAsNone() {
        // The schema layer reads a blank optional string as absent, so a blank rule means what leaving it out does.
        assertNull(parseWfDef(cxt, normal(rule = " ")).task("record")?.saveWhen)
    }

    @Test
    fun aTaskWithNoSaveMayNotDeclareOne() {
        // A rule on a task with nothing to save would guard nothing.
        assertFailsWith<KdrException> {
            parseWfDef(
                cxt,
                WfDefBuilder("audit", WfEntry.normal).apply {
                    task("approve", "Approve") { approval("x", "P", "B"); saveWhen(WFC.reviewer) }
                }.build(),
            )
        }
    }
}

/**
 * A normal workflow's trait locks (issue #857) as definition data: declared, parsed and round-tripped, and refused
 * where they could not mean anything -- outside a normal workflow, locking a trait twice, or naming a task that does
 * not own the trait or says nothing about who may save it (a lock holding for nobody).
 */
class WfLockDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun def(entry: WfEntry = WfEntry.normal, saveRule: String? = WFC.reviewer, locks: WfDefBuilder.() -> Unit) =
        WfDefBuilder("audit", entry).apply {
            task("record", "Record") {
                trait("audit")
                save("s", "S", WfSaveKind.edit)
                saveRule?.let { saveWhen(it) }
            }
            task("other", "Other") { trait("notes"); save("s2", "S2", WfSaveKind.edit) }
            locks()
        }.build()

    @Test
    fun aLockParsesAndRoundTrips() {
        val parsed = parseWfDef(cxt, def { lock("audit", writableVia = "record", overrideWhen = "hasAdminLevel") })
        val lock = parsed.locks.single()
        assertEquals(listOf("audit", "#always", "record", "hasAdminLevel"), listOf(lock.traitId, lock.whenExpr, lock.writableVia, lock.overrideWhen))
        val again = parseWfDef(cxt, parsed.toJsonMap()).locks.single()
        assertEquals(listOf(lock.traitId, lock.whenExpr, lock.writableVia, lock.overrideWhen), listOf(again.traitId, again.whenExpr, again.writableVia, again.overrideWhen))
        // No override rule: nobody may override.
        assertNull(parseWfDef(cxt, def { lock("audit", writableVia = "record") }).locks.single().overrideWhen)
    }

    @Test
    fun aSaveRuleWithoutALockIsFoundForTheStartupWarning() {
        // The record task says who may save it, and nothing locks its trait: the raw editor is still open.
        assertEquals(mapOf("record" to listOf("audit")), parseWfDef(cxt, def {}).unlockedSaveRuleTraits())
        // Locked: nothing to warn about.
        assertEquals(emptyMap(), parseWfDef(cxt, def { lock("audit", writableVia = "record") }).unlockedSaveRuleTraits())
        // No save rule: anyone may save the task, so an unlocked trait is as intended.
        assertEquals(emptyMap(), parseWfDef(cxt, def(saveRule = null) {}).unlockedSaveRuleTraits())
    }

    @Test
    fun aLockMustBeOwnedByATaskThatSaysWhoMaySave() {
        // The named task does not collect the trait.
        assertFailsWith<KdrException> { parseWfDef(cxt, def { lock("audit", writableVia = "other") }) }
        // No such task.
        assertFailsWith<KdrException> { parseWfDef(cxt, def { lock("audit", writableVia = "nope") }) }
        // A task anyone may save exempts everyone, so the lock would hold for nobody.
        assertFailsWith<KdrException> { parseWfDef(cxt, def(saveRule = null) { lock("audit", writableVia = "record") }) }
        // One lock per trait.
        assertFailsWith<KdrException> {
            parseWfDef(cxt, def { lock("audit", writableVia = "record"); lock("audit", writableVia = "record", whenCfacts = "x") })
        }
    }
}

/**
 * A normal workflow's singleton-cfact rules as definition data (issue #784): parsed, round-tripped, and held
 * to the framework's hardwired list -- a workflow cannot make up a singleton cfact, since each one carries code
 * behavior. Evaluating a rule is `base:common`'s, where the cfact registry lives.
 */
class WfSingletonDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun normal(extra: WfDefBuilder.() -> Unit) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        extra()
    }.build()

    @Test
    fun rulesParseAndRoundTrip() {
        val def = parseWfDef(
            cxt,
            normal {
                singleton(WSC.ownerOnly, "awaitingApproval")
                singleton(WSC.finished, "approved")
            },
        )
        assertEquals(listOf(WSC.ownerOnly, WSC.finished), def.singletons.map { it.cfact })
        val again = parseWfDef(cxt, def.toJsonMap())
        assertEquals(def.singletons.map { it.cfact to it.whenExpr }, again.singletons.map { it.cfact to it.whenExpr })
    }

    @Test
    fun onlyAFrameworkSingletonMayBeEmitted() {
        val e = assertFailsWith<KdrException> { parseWfDef(cxt, normal { singleton("madeUp", "a") }) }
        assertTrue(e.message.orEmpty().contains("'madeUp'"))
    }

    /** Needs Review is the `review` keyword's to set (issue #1596): a workflow's rule may not, and is told what to do instead. */
    @Test
    fun aKeywordsStatusIsNotAWorkflowsToDeclare() {
        val e = assertFailsWith<KdrException> { parseWfDef(cxt, normal { singleton(WSC.needsReview, "a") }) }
        assertTrue(e.message.orEmpty().contains("which the '${CTAK.review}' search keyword sets"), e.message)
    }

    @Test
    fun oneRulePerSingletonAndAConditionEach() {
        assertFailsWith<KdrException> {
            parseWfDef(cxt, normal {
                singleton(WSC.finished, "a")
                singleton(WSC.finished, "b")
            })
        }
        assertFailsWith<KdrException> { parseWfDef(cxt, normal { singleton(WSC.finished, " ") }) }
    }

    @Test
    fun onlyANormalWorkflowEmitsSingletons() {
        val raw = WfDefBuilder("review", WfEntry.coreDetails).apply {
            singleton(WSC.finished, "a")
            task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        }.build()
        assertFailsWith<KdrException> { parseWfDef(cxt, raw) }
    }
}

/**
 * A workflow's highlighted Start (issue #1662) as definition data: declared, parsed and round-tripped, absent by default,
 * and refused on a workflow no form engages with.
 */
class WfHighlightStartDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun def(entry: WfEntry = WfEntry.normal, highlight: Boolean = true) =
        WfDefBuilder("review", entry).apply {
            highlightStart = highlight
            task("record", "Record") { trait("a"); save("s", "S", WfSaveKind.edit) }
        }.build()

    @Test
    fun itParsesAndRoundTripsAndIsOffByDefault() {
        val on = parseWfDef(cxt, def())
        assertTrue(on.highlightStart)
        assertEquals(true, on.toJsonMap()[WFD.highlightStart])
        assertTrue(parseWfDef(cxt, on.toJsonMap()).highlightStart)
        // Off, it is not written at all, so a definition that never asked for it stores as it did.
        val off = parseWfDef(cxt, def(highlight = false))
        assertFalse(off.highlightStart)
        assertFalse(WFD.highlightStart in off.toJsonMap())
    }

    @Test
    fun onlyANormalWorkflowMayHighlightItsStart() {
        assertFailsWith<KdrException> { parseWfDef(cxt, def(entry = WfEntry.coreDetails)) }
    }
}

/**
 * The kernel wiring of workflow-function **usages** onto a definition (issue #677): a def's global-event
 * usages and a task's task-event usages parse from the builder, order by priority, and round-trip through
 * `toJsonMap` -- all as pure kernel data. Resolving a usage into a runnable function (and the
 * scope-matches-placement check) is a `base:common` second pass, so `resolvedFunctions` is empty here; that is
 * asserted too. In `commonTest` so the model behaves the same on the JVM and under Kotlin/JS.
 */
class WfFunctionWiringTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun fnData(fn: String, priority: Int? = null): Map<String, Any?> = buildMap {
        put(WFD.fn, fn)
        priority?.let { put(WFD.priority, it) }
    }

    @Test
    fun usagesParseFromTheBuilderAndRoundTrip() {
        val raw = WfDefBuilder("wf", WfEntry.normal).apply {
            function(fnData("computeCFactsFromData", priority = 5))
            task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit); function(fnData("prefillFromOwner")) }
        }.build()

        val def = parseWfDef(cxt, raw)
        assertEquals(listOf("computeCFactsFromData"), def.functionUsages.map { it.fn })
        assertEquals(5, def.functionUsages.single().priority)
        assertEquals(listOf("prefillFromOwner"), def.tasks.single().functionUsages.map { it.fn })

        // Resolution is the common second pass; the resolved store is empty until it runs.
        assertTrue(def.resolvedFunctions.isEmpty())
        assertTrue(def.tasks.single().resolvedFunctions.isEmpty())

        // A usage re-emits its own init data, so serialize + reparse yields the same usages.
        val again = parseWfDef(cxt, def.toJsonMap())
        assertEquals(def.functionUsages.map { it.fn }, again.functionUsages.map { it.fn })
        assertEquals(def.tasks.single().functionUsages.map { it.fn }, again.tasks.single().functionUsages.map { it.fn })
    }

    @Test
    fun usagesAreOrderedByPriority() {
        val raw = WfDefBuilder("wf", WfEntry.normal).apply {
            function(fnData("late", priority = 5))
            function(fnData("early", priority = 1))
            task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
        }.build()
        val def = parseWfDef(cxt, raw)
        assertEquals(listOf("early", "late"), def.functionUsages.map { it.fn })
    }

    @Test
    fun aUsageWithoutAnFnIsRefused() {
        assertFailsWith<KdrException> { WfFunctionUsage(mapOf(WFD.priority to 1)) }
    }
}
