package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GSRC
import com.dynamicruntime.common.gedra.workflow.CDF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEngine
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SLDM
import com.dynamicruntime.common.schema.SLM
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.parseSchLayout
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        WVF.cfacts to mapOf("kdr:hasAdminLevel" to true, "kdr:hasEnvAuth" to false),
        // The per-type layouts (issue #585), keyed like `$defs`: the one type here declares a label override.
        WVF.fieldLayouts to mapOf(
            "kdr.core.NameData" to mapOf(
                SL.schemaFields to listOf(mapOf(SL.field to "name", SL.label to "What is it called?")),
            ),
        ),
        SCH.dDefs to mapOf(
            "kdr.core.NameData" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)),
            ),
        ),
        WFD.tasks to listOf(
            mapOf(
                WFD.id to "identify",
                WFD.label to "Name it",
                WFD.traits to listOf(
                    mapOf(WFD.traitId to "name", WFD.required to true, WVF.schemaRef to "#/${SCH.dDefs}/kdr.core.NameData"),
                ),
                WFD.saves to listOf(mapOf(WFD.id to "create", WFD.label to "Create form", WFD.kind to "create")),
            ),
        ),
    )

    // A core details view carrying a `userInfo` trait (name, email) whose entry [source] is set: a layout marks `name`
    // filled and `email` offer, so the two modes can be told apart (issue #710).
    private fun prefillView(source: String): Map<String, Any?> = mapOf(
        WVF.found to true,
        WFD.workflowId to "reviewForm",
        WFD.entry to "coreDetails",
        WVF.showTaskList to false,
        WVF.fieldLayouts to mapOf(
            "ns.UserInfoData" to mapOf(
                SL.schemaFields to listOf(
                    mapOf(SL.field to "name", SL.defaultMode to SLDM.filled),
                    mapOf(SL.field to "email", SL.defaultMode to SLDM.offer),
                ),
            ),
        ),
        SCH.dDefs to mapOf(
            "ns.UserInfoData" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "name" to mapOf(SCH.type to SCT.string),
                    "email" to mapOf(SCH.type to SCT.string),
                ),
            ),
        ),
        WFD.tasks to listOf(
            mapOf(
                WFD.id to "profile",
                WFD.label to "Profile",
                WFD.traits to listOf(
                    mapOf(WFD.traitId to "userInfo", WFD.required to false, WVF.schemaRef to "#/${SCH.dDefs}/ns.UserInfoData"),
                ),
                WFD.saves to listOf(mapOf(WFD.id to "save", WFD.label to "Save", WFD.kind to "edit")),
                WVF.entries to listOf(
                    mapOf(GE.traitId to "userInfo", GE.data to mapOf("name" to "Jane", "email" to "j@x.com"), GE.source to source),
                ),
            ),
        ),
    )

    @Test
    fun prefillFilledSeedsButOfferDoesNot() {
        val p = prefillPresentationOf(parseWorkflowView(prefillView(GSRC.prefill))!!)
        // A filled default seeds the working value; an offer one does not (it is held aside for the link).
        assertEquals(mapOf("name" to "Jane"), p.working["userInfo"])
        assertFalse(p.working.getValue("userInfo").containsKey("email"))
        assertEquals(mapOf("email" to "j@x.com"), p.offered["userInfo"])
        // Both defaulted fields carry their mode, for the markers.
        assertEquals(SLDM.filled, p.modes.getValue("userInfo")["name"])
        assertEquals(SLDM.offer, p.modes.getValue("userInfo")["email"])
    }

    @Test
    fun seedValuesOfIsTheWorkingSplit() {
        val wf = parseWorkflowView(prefillView(GSRC.prefill))!!
        assertEquals(prefillPresentationOf(wf).working, seedValuesOf(wf))
    }

    @Test
    fun enteredEntriesAreNotDefaults() {
        // A real (source=user) entry goes whole into working, with no offered values and no modes.
        val p = prefillPresentationOf(parseWorkflowView(prefillView(GSRC.user))!!)
        assertEquals(mapOf("name" to "Jane", "email" to "j@x.com"), p.working["userInfo"])
        assertTrue(p.offered.isEmpty())
        assertTrue(p.modes.isEmpty())
    }

    @Test
    fun defaultModeDefaultsToFilledWithoutALayout() {
        // With no layout, a prefilled field defaults to filled, so both fields seed and none is offered.
        val p = prefillPresentationOf(parseWorkflowView(prefillView(GSRC.prefill) - WVF.fieldLayouts)!!)
        assertEquals(mapOf("name" to "Jane", "email" to "j@x.com"), p.working["userInfo"])
        assertTrue(p.offered.isEmpty())
        assertEquals(SLDM.filled, p.modes.getValue("userInfo")["email"])
    }

    @Test
    fun suggestedFilledFieldsAreTheFilledDefaultsOnly() {
        // Only the filled default (name) starts suggested; the offer default (email) is not shown, so not it.
        val p = prefillPresentationOf(parseWorkflowView(prefillView(GSRC.prefill))!!)
        assertEquals(setOf("name"), suggestedFilledFields(p, "userInfo"))
    }

    @Test
    fun pendingDefaultCountFallsAsDefaultsAreAcceptedOrCleared() {
        val wf = parseWorkflowView(prefillView(GSRC.prefill))!!
        val p = prefillPresentationOf(wf)
        val task = wf.tasks.single()
        // Fresh: name is a still-suggested filled default (1) and email is an unapplied offer (1).
        assertEquals(2, pendingDefaultCount(task, p, mapOf("userInfo" to setOf("name")), emptyMap()))
        // Touch name (no longer suggested) and apply email (now a value): nothing pending.
        assertEquals(
            0,
            pendingDefaultCount(task, p, mapOf("userInfo" to emptySet()), mapOf("userInfo" to mapOf("email" to "j@x.com"))),
        )
        // A view with no prefill at all has nothing pending regardless of state.
        val plain = parseWorkflowView(prefillView(GSRC.user))!!
        assertEquals(0, pendingDefaultCount(plain.tasks.single(), prefillPresentationOf(plain), emptyMap(), emptyMap()))
        // A resolved (saved) trait counts nothing, even with an offer left blank -- it is now stored data.
        assertEquals(
            0,
            pendingDefaultCount(task, p, mapOf("userInfo" to setOf("name")), emptyMap(), resolvedTraits = setOf("userInfo")),
        )
    }

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
        assertEquals(true, wf.cfacts["kdr:hasAdminLevel"])
        assertEquals(false, wf.cfacts["kdr:hasEnvAuth"])
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
        assertEquals("kdr.core.NameData", trait.typeName)
        assertEquals("What is it called?", trait.fieldLayout?.fields?.single()?.label)
        assertEquals(setOf("kdr.core.NameData"), wf.fieldLayouts.keys)
        // A view with no `fieldLayouts` key parses to no layout on the trait, not a failure -- the type renders alone.
        val bare = parseWorkflowView(view() - WVF.fieldLayouts)!!
        assertNull(bare.tasks.single().traits.single().fieldLayout)
        assertTrue(bare.fieldLayouts.isEmpty())
    }

    @Test
    fun anEditUnderALayoutThatChoosesItsFieldsSendsOnlyThose() {
        // Issue #1071: the page is seeded with the whole stored entry, hidden answers included; only the shown go back.
        val payload = mapOf<String, Any?>("q1" to "a", "q2" to "b", "q3" to "hidden")
        val chooses = parseSchLayout("t", mapOf(SL.mode to SLM.authoritative, SL.schemaFields to listOf(mapOf(SL.field to "q1"), mapOf(SL.field to "q2"))))
        assertEquals(mapOf("q1" to "a", "q2" to "b"), shownPayload(payload, chooses))
        // A layout that only annotates, or none, sends it all.
        assertEquals(payload, shownPayload(payload, parseSchLayout("t", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "q1"))))))
        assertEquals(payload, shownPayload(payload, null))
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

        // A core details edit save carries the form's gedraId (issue #659).
        val editBody = workflowSaveBody("reviewForm", "only", "save", entries, gedraId = "gd.fd.acme.u7")
        assertEquals("gd.fd.acme.u7", editBody[GDF.gedraId])

        // A create-for-user save carries the chosen user (issue #727); a blank ref is dropped, so the ordinary
        // self-create sends none.
        val forUserBody = workflowSaveBody("createForm", "identify", "create", entries, forUserRef = "ada@x.test")
        assertEquals("ada@x.test", forUserBody[EI.user])
        assertTrue(!workflowSaveBody("createForm", "identify", "create", entries).containsKey(EI.user))
        assertTrue(!workflowSaveBody("createForm", "identify", "create", entries, forUserRef = "  ").containsKey(EI.user))
    }

    @Test
    fun parsesACoreDetailsViewWithSeededEntriesAndItsKind() {
        // A core details view (issue #659): the same shape plus WFD.entry="coreDetails" and each task's current
        // entries.
        val coreDetailsView = view().toMutableMap().apply {
            put(WFD.entry, "coreDetails")
            put(
                WFD.tasks,
                listOf(
                    mapOf(
                        WFD.id to "only",
                        WFD.label to "Review",
                        WFD.traits to listOf(
                            mapOf(WFD.traitId to "name", WFD.required to true, WVF.schemaRef to "#/${SCH.dDefs}/kdr.core.NameData"),
                        ),
                        WFD.saves to listOf(mapOf(WFD.id to "save", WFD.label to "Save changes", WFD.kind to "edit")),
                        // The form's current entry for this task -- the seed source.
                        WVF.entries to listOf(mapOf(GE.traitId to "name", GE.data to mapOf("name" to "Stored name"))),
                    ),
                ),
            )
        }
        val wf = parseWorkflowView(coreDetailsView)!!
        assertEquals("coreDetails", wf.entry)
        val task = wf.tasks.single()
        assertEquals("edit", task.saves.single().kind)
        // seedValuesOf is the inverse of workflowSaveEntries over every task of the view, {traitId -> data} -- what
        // the form seeds from, and re-snapshots from after a save re-reads the view (issue #827).
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
        // An edit answers with the form's id alone (issue #827): no item, and nothing of the form to render from.
        val edited = parseSaveOutcome(mapOf(WSF.saved to true, GDF.gedraId to "gd.fd.acme.u1"))
        assertTrue(edited.saved)
        assertTrue(edited.item.isEmpty())
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
        CDF.complete to complete, CDF.valid to valid, CDF.missingTraits to missing,
        CDF.invalidTraits to problems.map { it.first }.distinct(),
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

    /** A two-task core details view, each task collecting `name`, with the given statuses and focus task. */
    private fun coreDetailsView(aStatus: Map<String, Any?>?, bStatus: Map<String, Any?>?, focus: String?): Map<String, Any?> {
        fun task(id: String, st: Map<String, Any?>?): Map<String, Any?> = buildMap {
            put(WFD.id, id)
            put(WFD.label, id.uppercase())
            put(WFD.traits, listOf(mapOf(WFD.traitId to "name", WFD.required to true, WVF.schemaRef to "#/${SCH.dDefs}/kdr.core.NameData")))
            put(WFD.saves, listOf(mapOf(WFD.id to "save", WFD.label to "Save", WFD.kind to "edit")))
            st?.let { put(WVF.status, it) }
        }
        return view().toMutableMap().apply {
            put(WFD.entry, "coreDetails")
            put(WFD.tasks, listOf(task("a", aStatus), task("b", bStatus)))
            focus?.let { put(WVF.focusTask, it) }
        }
    }

    /** Who a task waits on (issue #1595) parses through when the view gives it, and is null when it does not. */
    @Test
    fun parsesWhoATaskWaitsOn() {
        val view = coreDetailsView(null, null, null)
        val tasks = view[WFD.tasks].toJsonListOfMaps()
        val waiting = view + (WFD.tasks to listOf(tasks[0] + (WVF.waitingOn to "Waiting on a Northwest reviewer"), tasks[1]))
        val wf = parseWorkflowView(waiting)!!
        assertEquals("Waiting on a Northwest reviewer", wf.tasks[0].waitingOn)
        assertNull(wf.tasks[1].waitingOn)
    }

    /** Whether the caller may approve a task (issue #1595) parses through, and is false when the view says nothing. */
    @Test
    fun parsesWhetherATaskMayBeApproved() {
        val view = coreDetailsView(null, null, null)
        val tasks = view[WFD.tasks].toJsonListOfMaps()
        val wf = parseWorkflowView(view + (WFD.tasks to listOf(tasks[0] + (WVF.canApprove to true), tasks[1])))!!
        assertTrue(wf.tasks[0].canApprove)
        assertFalse(wf.tasks[1].canApprove)
    }

    /** The view's per-task status and its focus task parse through; a task carrying none has a null status. */
    @Test
    fun parsesTaskStatusAndTheFocusTask() {
        val wf = parseWorkflowView(coreDetailsView(status(true, true), status(true, false, problems = listOf("name" to "Too long.")),
            "b",
        ))!!
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
            coreDetailsView(status(true, false, problems = listOf("name" to "raw"), userMessage = "Keep it short."), null, null),
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
        val wf = parseWorkflowView(coreDetailsView(status(true, true), status(true, false, problems = listOf("name" to "x")), null))!!
        assertEquals(RailMark.complete, railMark(wf.tasks[0].status))
        assertEquals(RailMark.invalid, railMark(wf.tasks[1].status))
        assertEquals(RailMark.incomplete, railMark(null))
        // Incomplete AND invalid: the mark says incomplete; the problem still reaches the tooltip via the status.
        val both = parseWorkflowView(coreDetailsView(status(false, false, listOf("name"), listOf("name" to "x")), null,
            null))!!.tasks[0].status
        assertEquals(RailMark.incomplete, railMark(both))
        assertEquals(1, both!!.problems.size)
    }

    /** The task the page opens on: the URL's task if it is one of the view's, else the focus task, else the first. */
    @Test
    fun choosesTheInitialTask() {
        val wf = parseWorkflowView(coreDetailsView(status(true, true), status(false, true, listOf("name")), "b"))!!
        assertEquals("a", initialTaskFor(wf, "a"))
        assertEquals("b", initialTaskFor(wf, null))
        assertEquals("b", initialTaskFor(wf, "not-a-task"))
        val allDone = parseWorkflowView(coreDetailsView(status(true, true), status(true, true), null))!!
        assertEquals("a", initialTaskFor(allDone, null))
    }

    /** Unsaved is per task and client-side: a trait's working value differing from its stored one. */
    @Test
    fun detectsUnsavedEditsPerTask() {
        val wf = parseWorkflowView(coreDetailsView(status(true, true), status(true, true), null))!!
        val stored = mapOf("name" to mapOf<String, Any?>("name" to "kept"))
        assertTrue(!taskUnsaved(wf.tasks[0], stored, stored))
        assertTrue(taskUnsaved(wf.tasks[0], mapOf("name" to mapOf<String, Any?>("name" to "changed")), stored))
        // A trait with no working value and no stored value is not an edit; one side empty and the other not is.
        assertTrue(!taskUnsaved(wf.tasks[0], emptyMap(), emptyMap()))
        assertTrue(taskUnsaved(wf.tasks[0], emptyMap(), stored))
    }

    /** The tooltip / screen-reader wording: one line per thing to know, friendly trait names, else that it needs nothing more. */
    @Test
    fun explainsARailMarkInWords() {
        val done = parseWorkflowView(coreDetailsView(status(true, true), null, null))!!.tasks[0].status
        // Never "Complete" (issue #1694): the step can still be changed, so it says the step asks nothing more.
        assertEquals("Nothing more is needed on this step.", railExplanation(done, unsaved = false))
        assertEquals("All set.", railExplanation(done, unsaved = false, satisfied = "All set."))
        assertEquals("Unsaved changes", railExplanation(done, unsaved = true))
        val needs = parseWorkflowView(coreDetailsView(status(false, true, listOf("expenseReport")), null, null))!!.tasks[0].status
        assertEquals("Needs information: Expense report", railExplanation(needs, unsaved = false))
        // A caller names the traits the way its headings do, so tooltip and heading cannot disagree.
        assertEquals("Needs information: Site audit", railExplanation(needs, unsaved = false) { if (it == "expenseReport") "Site audit" else it })
        val bad = parseWorkflowView(coreDetailsView(status(true, false, problems = listOf("name" to "Too long.")), null,
            null))!!.tasks[0].status
        assertEquals("Too long.", railExplanation(bad, unsaved = false))
        // Unsaved first, then what is missing, then each problem -- and no status at all reads as not started.
        val both = parseWorkflowView(coreDetailsView(status(false, false, listOf("name"), listOf("name" to "Too long.")), null,
            null))!!.tasks[0].status
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
        val wf = parseWorkflowView(coreDetailsView(status(true, true), status(true, true), null))!!
        val stored = mapOf("name" to mapOf<String, Any?>("name" to "kept"))
        assertTrue(!taskUnsaved(wf.tasks[0], mapOf("name" to mapOf<String, Any?>("name" to "kept", "extra" to "")), stored))
        assertTrue(taskUnsaved(wf.tasks[0], mapOf("name" to mapOf<String, Any?>("name" to "kept ")), stored))
    }

    /**
     * The client-side status (issue #718) follows the server's rule: presence by entry, content by the kernel. A
     * required trait left empty is **present** -- the save sends it as `{}` -- so it is complete before the save
     * exactly as the server says after it (issue #826); `NameData` requires no field of its content.
     */
    @Test
    fun projectsATaskStatusFromWorkingValues() {
        val task = parseWorkflowView(coreDetailsView(null, null, null))!!.tasks[0]
        val untouched = mapOf("name" to emptyMap<String, Any?>())
        val empty = localTaskStatus(task, untouched)
        assertTrue(empty.complete && empty.valid && empty.missingTraits.isEmpty())
        // The same verdict the engine gives the entries that save would send.
        assertEquals(emptyList(), WfEngine.missingTraits(listOf("name"), workflowSaveEntries(untouched)))
        assertEquals(RailMark.complete, railMark(empty))
        // A blank string is absent, so the entry is present-but-empty: complete too.
        assertTrue(localTaskStatus(task, mapOf("name" to mapOf<String, Any?>("name" to " "))).complete)
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

    /**
     * What a trait needs of its *content* is its schema's required fields (issue #826): with `name` required, the
     * trait left empty is "needs information" -- the save would refuse it, so the server never sees that state.
     */
    @Test
    fun aRequiredFieldLeftEmptyIsMissing() {
        val strict = coreDetailsView(null, null, null).toMutableMap().apply {
            put(
                SCH.dDefs,
                mapOf(
                    "kdr.core.NameData" to mapOf(
                        SCH.type to SCT.kObject,
                        SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)),
                        SCH.required to listOf("name"),
                    ),
                ),
            )
        }
        val task = parseWorkflowView(strict)!!.tasks[0]
        val empty = localTaskStatus(task, mapOf("name" to emptyMap()))
        assertTrue(!empty.complete)
        assertEquals(listOf("name"), empty.missingTraits)
        assertTrue(localTaskStatus(task, mapOf("name" to mapOf<String, Any?>("name" to "Ada"))).complete)
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

    /** Issue #817: a save of the page's own kind, or none -- never another kind in its place, never an index into nothing. */
    @Test
    fun aTaskOffersOnlyASaveOfThePagesKind() {
        val create = WfSaveView("create", "Create", "create")
        val edit = WfSaveView("saveEdit", "Save", "edit")
        fun task(vararg saves: WfSaveView) = WfTaskView("t", "T", traits = emptyList(), saves = saves.toList())
        assertEquals("saveEdit", saveOfKind(task(create, edit), isEdit = true)?.id)
        assertEquals("create", saveOfKind(task(create, edit), isEdit = false)?.id)
        // A creation task opened against a form: no edit save, so no Save -- not its create, which would make a second form.
        assertNull(saveOfKind(task(create), isEdit = true))
        // A normal task that declares no save.
        assertNull(saveOfKind(task(), isEdit = true))
        // And editability follows the same rule: a creation task is editable on a create page, not against a form.
        val view = WorkflowView(workflowId = "createForm", entry = "creation", showTaskList = false, tasks = listOf(task(create)), cfacts = emptyMap())
        assertTrue(view.isEditable(view.tasks.single(), isEdit = false))
        assertFalse(view.isEditable(view.tasks.single(), isEdit = true))
        // Nor is Edit offered over it, with its one task on the page -- the browser check that found it offered.
        assertFalse(view.offersEdit(null))
        // A core details workflow, whose task has an edit save, is.
        val coreDetails = WorkflowView(workflowId = "review", entry = "coreDetails", showTaskList = false,
            tasks = listOf(task(edit)), cfacts = emptyMap())
        assertTrue(coreDetails.offersEdit(null))
    }

    /**
     * A trait section's heading on a workflow form (issue #1541): the layout `label` when it says something, its heading
     * marks taken off and a `${…}` template kept for the form to fill in; none when it is blank or marks alone, so the
     * trait's name stands in, as on every other page. Each block carries an introduction, since a block holding only a
     * blank label is refused at parse: it says nothing.
     */
    @Test
    fun aBlankLabelGivesNoSectionHeading() {
        val type = parseSchemaTypes(mapOf("t.Note" to mapOf(SCH.type to SCT.kObject))).getValue("t.Note")
        fun sectionLabel(label: String?) = traitSectionLabel(
            WfTraitView("note", false, type, "t.Note", label?.let { parseSchLayout("t.Note", mapOf(SL.label to it, SL.description to "Copy.")) }),
        )
        assertEquals("**Site** visit", sectionLabel("## **Site** visit"))
        assertEquals($$"Notes for ${owner.name}", sectionLabel($$"## Notes for ${owner.name}"))
        assertNull(sectionLabel(""))
        assertNull(sectionLabel("##"))
        assertNull(sectionLabel("## **"))
        assertNull(sectionLabel(null))
    }

    @Test
    fun aCompleteTaskOffersTheNextOneWhenThereIsOneAvailable() {
        // Issue #1694: Next opens the task after the one shown, once the shown one is complete.
        fun task(id: String, disabled: Boolean = false) = WfTaskView(
            id, "Label of $id", traits = emptyList(), saves = emptyList(),
            display = if (disabled) WfTaskDisplay("text", "Not yet.", disabled = true) else null,
        )
        val tasks = listOf(task("record"), task("accept"), task("thank"))
        assertEquals("accept", nextTaskAfter(tasks, "record", RailMark.complete)?.id)
        // Whether the viewer may act on the next task is not asked: it opens as the rail opens it. So a workflow whose
        // tasks are all complete steps forward one at a time.
        assertEquals("thank", nextTaskAfter(tasks, "accept", RailMark.complete)?.id)
        // None after the last task, none while the shown task is incomplete or complete with problems, none for a task
        // the view does not have.
        assertNull(nextTaskAfter(tasks, "thank", RailMark.complete))
        assertNull(nextTaskAfter(tasks, "record", RailMark.incomplete))
        assertNull(nextTaskAfter(tasks, "record", RailMark.invalid))
        assertNull(nextTaskAfter(tasks, "gone", RailMark.complete))
        // The step says it asks nothing more only when its rail marks it complete and nothing in it is unsaved.
        assertTrue(stepSatisfied(RailMark.complete, unsaved = false))
        assertFalse(stepSatisfied(RailMark.complete, unsaved = true))
        assertFalse(stepSatisfied(RailMark.invalid, unsaved = false))
        assertFalse(stepSatisfied(RailMark.incomplete, unsaved = false))
        // Next says where it goes, in the copy's words.
        assertEquals("Next: Accept the talk →", WorkflowCellWords().nextStepText("Accept the talk"))
        // A next task its display greys until something is done is not available, as its rail entry is inert.
        assertNull(nextTaskAfter(listOf(task("record"), task("accept", disabled = true)), "record", RailMark.complete))
    }

    @Test
    fun anApprovalShowsTheTraitsOfTheTasksItApproves() {
        // Issue #1695: the backend resolves which tasks an approval shows; the page draws their traits, read-only.
        val type = parseSchemaTypes(mapOf("t.Note" to mapOf("type" to "object"))).getValue("t.Note")
        fun trait(id: String) = WfTraitView(id, false, type, "t.Note", null)
        val approval = parseApproval(
            mapOf(WFD.prompt to "Read it, then accept.", WFD.button to "Accept", WVF.approved to false, WFD.showTasks to listOf("record")),
        )!!
        assertEquals(listOf("record"), approval.showTasks)
        val record = WfTaskView("record", "Record the review", traits = listOf(trait("review")), saves = emptyList())
        val accept = WfTaskView("accept", "Accept the talk", traits = emptyList(), saves = emptyList(), approval = approval)
        val view = WorkflowView(
            workflowId = "review", entry = WfEntry.normal.name, showTaskList = true, tasks = listOf(record, accept),
            cfacts = emptyMap(),
        )
        assertEquals(listOf("review"), approvalShownTraits(view, accept).map { it.traitId })
        // A task that is no approval shows nothing more; nor does an approval its display greys until something is done.
        assertEquals(emptyList(), approvalShownTraits(view, record))
        val greyed = WfTaskView(
            "accept", "Accept the talk", traits = emptyList(), saves = emptyList(), approval = approval,
            display = WfTaskDisplay("text", "Record the review first.", disabled = true),
        )
        assertEquals(emptyList(), approvalShownTraits(view, greyed))
        // An approval delivered without the list (an older node) shows nothing more.
        assertEquals(emptyList(), parseApproval(mapOf(WFD.prompt to "p", WFD.button to "b"))!!.showTasks)
    }
}
