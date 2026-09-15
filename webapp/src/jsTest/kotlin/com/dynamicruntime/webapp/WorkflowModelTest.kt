package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #536) for the create page's model: a `/gedra/workflow/view` response mapped into
 * a render plan with each trait's schema resolved from the view's own `$defs`, and the save body built from
 * collected values. Maps in, model out -- no React, no DOM, no server.
 */
class WorkflowModelTest {
    // A minimal view: one task, one required `name` trait, resolving against a `$defs` the view carries.
    private fun view(found: Boolean = true): Map<String, Any?> = mapOf(
        WVF.found to found,
        WFD.workflowId to "createForm",
        WFD.entry to "creation",
        WVF.showTaskList to false,
        // The caller's delivered cfacts (issue #569): the whole frontend vocabulary, present-mapped.
        WVF.cfacts to mapOf("hasAdminLevel" to true, "hasEnvAuth" to false),
        // The per-type layouts (issue #585), keyed like `$defs`: the one type here declares a label override.
        WVF.layouts to mapOf(
            "globalconfig.NameData" to mapOf(
                SL.schemaFields to listOf(mapOf(SL.field to "name", SL.label to "What is it called?")),
            ),
        ),
        SCH.dDefs to mapOf(
            "globalconfig.NameData" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)),
            ),
        ),
        WFD.tasks to listOf(
            mapOf(
                WFD.id to "identify",
                WFD.label to "Name it",
                WFD.traits to listOf(
                    mapOf(WFD.traitId to "name", WFD.required to true, WVF.schemaRef to "#/${SCH.dDefs}/globalconfig.NameData"),
                ),
                WFD.saves to listOf(mapOf(WFD.id to "create", WFD.label to "Create form", WFD.kind to "create")),
            ),
        ),
    )

    @Test
    fun foundFalseParsesToNull() {
        assertNull(parseWorkflowView(view(found = false)))
        assertNull(parseWorkflowView(emptyMap()))
    }

    @Test
    fun parsesTasksTraitsSavesAndResolvesTheTraitTypeFromTheViewsOwnDefs() {
        val wf = parseWorkflowView(view())!!
        assertEquals("createForm", wf.workflowId)
        assertEquals("creation", wf.entry)
        assertTrue(!wf.showTaskList)
        val task = wf.tasks.single()
        assertEquals("identify", task.id)
        assertEquals("Name it", task.label)
        val trait = task.traits.single()
        assertEquals("name", trait.traitId)
        assertTrue(trait.required)
        // The type resolved out of the view's $defs, with its own property.
        assertEquals(SCT.kObject, trait.type.jsonType)
        assertTrue(trait.type.properties.containsKey("name"))
        val save = task.saves.single()
        assertEquals("create", save.id)
        assertEquals("Create form", save.label)
    }

    @Test
    fun carriesTheDeliveredCfactsForTheVisibleWhenGate() {
        // The map the rendered SchemaForm evaluates each property's g-visibleWhen against (issue #569): present
        // and absent cfacts both, so a gate naming an absent one still parses.
        val wf = parseWorkflowView(view())!!
        assertEquals(true, wf.cfacts["hasAdminLevel"])
        assertEquals(false, wf.cfacts["hasEnvAuth"])
        // A view with no cfacts key parses to an empty map, not a failure.
        val noCfacts = parseWorkflowView(view() - WVF.cfacts)!!
        assertTrue(noCfacts.cfacts.isEmpty())
    }

    @Test
    fun joinsTheDeliveredLayoutToEachTraitByTypeName() {
        // The third closure (issue #585): the trait's data type declares a layout, so the trait carries it,
        // joined by the very name its schemaRef resolved under.
        val wf = parseWorkflowView(view())!!
        val trait = wf.tasks.single().traits.single()
        assertEquals("globalconfig.NameData", trait.typeName)
        assertEquals("What is it called?", trait.layout?.fields?.single()?.label)
        assertEquals(setOf("globalconfig.NameData"), wf.layouts.keys)
        // A view with no layouts key parses to no layout on the trait, not a failure -- the type renders alone.
        val bare = parseWorkflowView(view() - WVF.layouts)!!
        assertNull(bare.tasks.single().traits.single().layout)
        assertTrue(bare.layouts.isEmpty())
    }

    @Test
    fun buildsTheSaveEntriesAndBody() {
        val entries = workflowSaveEntries(mapOf("name" to mapOf("name" to "My form")))
        assertEquals(1, entries.size)
        assertEquals("name", entries.single()[GE.traitId])
        assertEquals(mapOf("name" to "My form"), entries.single()[GE.data])

        // A create save carries no gedraId.
        val body = workflowSaveBody("createForm", "identify", "create", entries)
        assertEquals("createForm", body[GDF.workflowId])
        assertEquals("identify", body[GDF.taskId])
        assertEquals("create", body[GDF.saveId])
        assertEquals(entries, body[GDF.entries])
        assertTrue(!body.containsKey(GDF.gedraId))

        // A survey edit save carries the form's gedraId (issue #659).
        val editBody = workflowSaveBody("reviewForm", "only", "save", entries, gedraId = "gd.fd.acme.u7")
        assertEquals("gd.fd.acme.u7", editBody[GDF.gedraId])
    }

    @Test
    fun parsesASurveyViewWithSeededEntriesAndItsKind() {
        // A survey view (issue #659): the same shape plus WFD.entry="survey" and each task's current entries.
        val surveyView = view().toMutableMap().apply {
            put(WFD.entry, "survey")
            put(
                WFD.tasks,
                listOf(
                    mapOf(
                        WFD.id to "only",
                        WFD.label to "Review",
                        WFD.traits to listOf(
                            mapOf(WFD.traitId to "name", WFD.required to true, WVF.schemaRef to "#/${SCH.dDefs}/globalconfig.NameData"),
                        ),
                        WFD.saves to listOf(mapOf(WFD.id to "save", WFD.label to "Save changes", WFD.kind to "edit")),
                        // The form's current entry for this task -- the seed source.
                        WVF.entries to listOf(mapOf(GE.traitId to "name", GE.data to mapOf("name" to "Stored name"))),
                    ),
                ),
            )
        }
        val wf = parseWorkflowView(surveyView)!!
        assertEquals("survey", wf.entry)
        val task = wf.tasks.single()
        assertEquals("edit", task.saves.single().kind)
        // seedValuesFromEntries is the inverse of workflowSaveEntries: {traitId -> data}; seedValuesOf is the same
        // over every task of the view -- what the form seeds from and re-snapshots from after a save.
        assertEquals(mapOf("name" to mapOf("name" to "Stored name")), seedValuesFromEntries(task.entries))
        assertEquals(mapOf("name" to mapOf("name" to "Stored name")), seedValuesOf(wf))
    }

    @Test
    fun readsTheSaveOutcomeBothWays() {
        val refused = parseSaveOutcome(mapOf(WSF.saved to false, WSF.unmetTraits to listOf("name")))
        assertTrue(!refused.saved)
        assertEquals(listOf("name"), refused.unmetTraits)

        val ok = parseSaveOutcome(mapOf(WSF.saved to true, WSF.item to mapOf(GDF.gedraId to "gd.fd.acme.u1")))
        assertTrue(ok.saved)
        assertEquals("gd.fd.acme.u1", ok.item[GDF.gedraId])
        // No refreshed view on a create save (issue #700); a survey edit carries one, parsed like the view call's.
        assertNull(ok.view)
        val edited = parseSaveOutcome(mapOf(WSF.saved to true, WSF.item to emptyMap<String, Any?>(), WSF.view to view()))
        assertEquals("createForm", edited.view?.workflowId)
    }

    // --- the task rail (issue #700) ---------------------------------------------------------------------

    /** A task's [WVF.status] map, as the view computes it. */
    private fun status(
        complete: Boolean,
        valid: Boolean,
        missing: List<String> = emptyList(),
        problems: List<Pair<String, String>> = emptyList(),
        userMessage: String? = null,
    ) = mapOf(
        SVY.complete to complete, SVY.valid to valid, SVY.missingTraits to missing,
        SVY.invalidTraits to problems.map { it.first }.distinct(),
        // The kernel's failure wire shape (SchFailure.toWireMap) plus the trait, as the view emits it -- with the
        // schema author's own wording beside the validator's when a field declares one.
        WVF.problems to problems.map { (t, m) ->
            buildMap<String, Any?> {
                put(GE.traitId, t)
                put(EP.failurePath, "text")
                put(EP.failureCode, "badValue")
                put(EP.failureMessage, m)
                userMessage?.let { put(EP.failureUserMessage, it) }
            }
        },
    )

    /** A two-task survey view, each task collecting `name`, with the given statuses and focus task. */
    private fun surveyView(aStatus: Map<String, Any?>?, bStatus: Map<String, Any?>?, focus: String?): Map<String, Any?> {
        fun task(id: String, st: Map<String, Any?>?): Map<String, Any?> = buildMap {
            put(WFD.id, id)
            put(WFD.label, id.uppercase())
            put(WFD.traits, listOf(mapOf(WFD.traitId to "name", WFD.required to true, WVF.schemaRef to "#/${SCH.dDefs}/globalconfig.NameData")))
            put(WFD.saves, listOf(mapOf(WFD.id to "save", WFD.label to "Save", WFD.kind to "edit")))
            st?.let { put(WVF.status, it) }
        }
        return view().toMutableMap().apply {
            put(WFD.entry, "survey")
            put(WFD.tasks, listOf(task("a", aStatus), task("b", bStatus)))
            focus?.let { put(WVF.focusTask, it) }
        }
    }

    /** The view's per-task status and its focus task parse through; a task carrying none has a null status. */
    @Test
    fun parsesTaskStatusAndTheFocusTask() {
        val wf = parseWorkflowView(surveyView(status(true, true), status(true, false, problems = listOf("name" to "Too long.")), "b"))!!
        assertEquals("b", wf.focusTask)
        val a = wf.tasks[0].status!!
        assertTrue(a.complete && a.valid && a.problems.isEmpty())
        val b = wf.tasks[1].status!!
        assertTrue(b.complete && !b.valid)
        assertEquals(listOf("name"), b.invalidTraits)
        assertEquals("Too long.", b.problems.single().message)
        assertEquals("name", b.problems.single().traitId)
        assertEquals("text", b.problems.single().path)
        // The schema author's wording wins over the validator's when the failure carries one.
        val worded = parseWorkflowView(
            surveyView(status(true, false, problems = listOf("name" to "raw"), userMessage = "Keep it short."), null, null),
        )!!
        assertEquals("Keep it short.", worded.tasks[0].status!!.problems.single().message)
        // The creation fixture carries no status and no focus task.
        val creation = parseWorkflowView(view())!!
        assertNull(creation.tasks.single().status)
        assertNull(creation.focusTask)
    }

    /** The mark: absence wins over invalidity (an orange check says "complete"), and no status draws as not started. */
    @Test
    fun marksATaskByItsStatus() {
        val wf = parseWorkflowView(surveyView(status(true, true), status(true, false, problems = listOf("name" to "x")), null))!!
        assertEquals(RailMark.complete, railMark(wf.tasks[0].status))
        assertEquals(RailMark.invalid, railMark(wf.tasks[1].status))
        assertEquals(RailMark.incomplete, railMark(null))
        // Incomplete AND invalid: the mark says incomplete; the problem still reaches the tooltip via the status.
        val both = parseWorkflowView(surveyView(status(false, false, listOf("name"), listOf("name" to "x")), null, null))!!.tasks[0].status
        assertEquals(RailMark.incomplete, railMark(both))
        assertEquals(1, both!!.problems.size)
    }

    /** The task the page opens on: the URL's task if it is one of the view's, else the focus task, else the first. */
    @Test
    fun choosesTheInitialTask() {
        val wf = parseWorkflowView(surveyView(status(true, true), status(false, true, listOf("name")), "b"))!!
        assertEquals("a", initialTaskFor(wf, "a"))
        assertEquals("b", initialTaskFor(wf, null))
        assertEquals("b", initialTaskFor(wf, "not-a-task"))
        val allDone = parseWorkflowView(surveyView(status(true, true), status(true, true), null))!!
        assertEquals("a", initialTaskFor(allDone, null))
    }

    /** Unsaved is per task and client-side: a trait's working value differing from its stored one. */
    @Test
    fun detectsUnsavedEditsPerTask() {
        val wf = parseWorkflowView(surveyView(status(true, true), status(true, true), null))!!
        val stored = mapOf("name" to mapOf<String, Any?>("name" to "kept"))
        assertTrue(!taskUnsaved(wf.tasks[0], stored, stored))
        assertTrue(taskUnsaved(wf.tasks[0], mapOf("name" to mapOf<String, Any?>("name" to "changed")), stored))
        // A trait with no working value and no stored value is not an edit; one side empty and the other not is.
        assertTrue(!taskUnsaved(wf.tasks[0], emptyMap(), emptyMap()))
        assertTrue(taskUnsaved(wf.tasks[0], emptyMap(), stored))
    }

    /** The tooltip / screen-reader wording: one line per thing to know, friendly trait names, else "Complete". */
    @Test
    fun explainsARailMarkInWords() {
        val done = parseWorkflowView(surveyView(status(true, true), null, null))!!.tasks[0].status
        assertEquals("Complete", railExplanation(done, unsaved = false))
        assertEquals("Unsaved changes", railExplanation(done, unsaved = true))
        val needs = parseWorkflowView(surveyView(status(false, true, listOf("expenseReport")), null, null))!!.tasks[0].status
        assertEquals("Needs information: Expense report", railExplanation(needs, unsaved = false))
        // A caller names the traits the way its headings do, so tooltip and heading cannot disagree.
        assertEquals("Needs information: Site audit", railExplanation(needs, unsaved = false) { if (it == "expenseReport") "Site audit" else it })
        val bad = parseWorkflowView(surveyView(status(true, false, problems = listOf("name" to "Too long.")), null, null))!!.tasks[0].status
        assertEquals("Too long.", railExplanation(bad, unsaved = false))
        // Unsaved first, then what is missing, then each problem -- and no status at all reads as not started.
        val both = parseWorkflowView(surveyView(status(false, false, listOf("name"), listOf("name" to "Too long.")), null, null))!!.tasks[0].status
        assertEquals("Unsaved changes\nNeeds information: Name\nToo long.", railExplanation(both, unsaved = true))
        assertEquals("Not started", railExplanation(null, unsaved = false))
    }

    /**
     * The comparison shape (issue #718): a value typed back as its original is not an edit, nor is a field
     * cleared that was never set -- a text box holds strings and `""`, the wire holds numbers and absent keys.
     */
    @Test
    fun comparesValuesAsAWidgetAndTheWireWouldAgreeOn() {
        val wire = mapOf<String, Any?>("year" to 2025, "flag" to true, "sub" to mapOf("a" to null, "b" to 1), "tags" to listOf("x", 2))
        val typed = mapOf<String, Any?>("year" to "2025", "flag" to "true", "note" to "", "gone" to null, "sub" to mapOf("b" to "1"), "tags" to listOf("x", "2"))
        assertEquals(comparableValues(wire), comparableValues(typed))
        assertEquals(mapOf("year" to "2025", "flag" to "true", "sub" to mapOf("b" to "1"), "tags" to listOf("x", "2")), comparableValues(wire))
        val wf = parseWorkflowView(surveyView(status(true, true), status(true, true), null))!!
        val stored = mapOf("name" to mapOf<String, Any?>("name" to "kept"))
        assertTrue(!taskUnsaved(wf.tasks[0], mapOf("name" to mapOf<String, Any?>("name" to "kept", "extra" to "")), stored))
        assertTrue(taskUnsaved(wf.tasks[0], mapOf("name" to mapOf<String, Any?>("name" to "kept ")), stored))
    }

    /** The client-side status (issue #718) follows the server's rule: presence by required trait, content by the kernel. */
    @Test
    fun projectsATaskStatusFromWorkingValues() {
        val task = parseWorkflowView(surveyView(null, null, null))!!.tasks[0]
        val empty = localTaskStatus(task, emptyMap())
        assertTrue(!empty.complete && empty.valid)
        assertEquals(listOf("name"), empty.missingTraits)
        // A blank string is absent, not present-and-wrong.
        assertTrue(!localTaskStatus(task, mapOf("name" to mapOf<String, Any?>("name" to " "))).complete)
        val ok = localTaskStatus(task, mapOf("name" to mapOf<String, Any?>("name" to "Ada")))
        assertTrue(ok.complete && ok.valid && ok.problems.isEmpty())
        assertEquals(RailMark.complete, railMark(ok))
        // A number against a string type is a content failure: complete, invalid, one worded problem on the field.
        val bad = localTaskStatus(task, mapOf("name" to mapOf<String, Any?>("name" to 5)))
        assertTrue(bad.complete && !bad.valid)
        assertEquals(listOf("name"), bad.invalidTraits)
        assertEquals("name", bad.problems.single().path)
        assertEquals("name", bad.problems.single().traitId)
        assertEquals(RailMark.invalid, railMark(bad))
    }

    /** The workflow's own label (issue #719) parses through; absent, it is empty and the page titles itself. */
    @Test
    fun carriesTheWorkflowLabel() {
        assertEquals("", parseWorkflowView(view())!!.label)
        val titled = view().toMutableMap().apply { put(WFD.label, "Expense report review") }
        assertEquals("Expense report review", parseWorkflowView(titled)!!.label)
    }

    /** Which failures the panel shows: the committed fields' (and what lies beneath them) until the trait is whole-checked. */
    @Test
    fun showsCommittedFieldsFailuresUntilTheWholeTraitIsChecked() {
        fun f(path: String) = SchFailure(path, SchFailCode.missingRequired, "missing")
        val all = listOf(f("a"), f("b"), f("a[0]"), f("ab"), f("a.x"))
        assertEquals(listOf("a", "a[0]", "a.x"), shownFailures(all, setOf("a"), wholeTraitChecked = false).map { it.path })
        assertEquals(emptyList(), shownFailures(all, emptySet(), wholeTraitChecked = false))
        assertEquals(all, shownFailures(all, emptySet(), wholeTraitChecked = true))
    }
}
