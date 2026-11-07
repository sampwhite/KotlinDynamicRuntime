package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.codeOverlayStale
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bundle editor's overlays of the code (issue #1657): a code workflow is edited whole and saved as only what differs
 * from the code's; a change no overlay can state is refused, pointing to Detach from code; a list without keys the change
 * restates is said; and an overlay opened is shown merged, which is no edit.
 */
class CodeOverlayEditTest {
    private fun task(id: String, label: String, functions: List<Map<String, Any?>>? = null) = buildMap {
        put(WFD.id, id)
        put(WFD.label, label)
        put(WFD.saves, listOf(mapOf(WFD.id to "s", WFD.label to "Save", WFD.kind to "edit")))
        functions?.let { put(WFD.functions, it) }
    }

    private val codeFlow = mapOf(
        CCT.workflowId to "review",
        CCT.definition to mapOf(
            WFD.workflowId to "review", WFD.entry to "normal", WFD.label to "Review",
            WFD.tasks to listOf(task("a", "Check it", listOf(mapOf("fn" to "kdr:stamp"))), task("b", "Sign it")),
        ),
    )

    private val view = BundleEditorView(
        client = "acme",
        configs = emptyList(),
        slotKeys = mapOf(CCT.workflowDef to listOf(CCT.workflowId)),
        codeConfigs = listOf(CodeBundle("flows", null, "client.acme", mapOf(CCT.workflowDef to listOf(codeFlow)))),
    )

    /** A new change of the code's workflow, as **Edit** starts it, with its text rewritten by [edit]. */
    private fun changing(edit: (Map<String, Any?>) -> Map<String, Any?>): Pair<BundleEdits, String> {
        // The stored bundle named like the code's, made on the page as the client has none yet.
        val (edits, id) = BundleEdits().addingBundle("flows")
            .adding("flows", CCT.workflowDef, entryText(overlayOf(codeFlow)), "acme", code = codeFlow)
        return edits.with(id, edits.drafts.getValue(id).withText(entryText(edit(overlayOf(codeFlow))))) to id
    }

    private fun withTasks(entry: Map<String, Any?>, tasks: List<Map<String, Any?>>) =
        entry + (CCT.definition to entry[CCT.definition].toJsonMapOrEmpty() + (WFD.tasks to tasks))

    @Test
    fun aChangeOfACodeWorkflowIsSavedAsOnlyWhatDiffers() {
        val (edits, id) = changing {
            withTasks(it, listOf(task("a", "Check it twice", listOf(mapOf("fn" to "kdr:stamp"))), task("b", "Sign it")))
        }
        val check = checkEntry(id, view, edits, emptyMap())
        assertTrue(check.ok, check.problems.toString())
        val request = assertNotNull(bundlePatchRequest("acme", false, view, edits, mapOf(id to check), dryRun = true))
        val edit = request.body[CFEP.configs].toJsonListOfMaps().single()[CFEP.edits].toJsonListOfMaps().single()
        val sent = edit[GE.data].toJsonMapOrEmpty()
        assertEquals(CCT.overridesCodeOverlayValue, sent[CCT.overrides])
        assertEquals(
            mapOf(WFD.workflowId to "review", WFD.tasks to listOf(mapOf(WFD.id to "a", WFD.label to "Check it twice"))),
            sent[CCT.definition],
        )
    }

    @Test
    fun removingATaskIsRefusedPointingToDetach() {
        val (edits, id) = changing { withTasks(it, listOf(task("a", "Check it", listOf(mapOf("fn" to "kdr:stamp"))))) }
        val problem = checkEntry(id, view, edits, emptyMap()).problems.single()
        assertTrue("'b' is gone" in problem && "Detach from code" in problem, problem)

        // Detached, the whole entry is kept, and nothing is refused.
        val text = entryText(detached(withTasks(overlayOf(codeFlow), listOf(task("a", "Check it")))))
        val detachedEdits = edits.with(id, edits.drafts.getValue(id).withText(text))
        val check = checkEntry(id, view, detachedEdits, emptyMap())
        assertTrue(check.ok, check.problems.toString())
        assertEquals(CCT.overridesCodeValue, check.data?.get(CCT.overrides))
        assertEquals(1, check.data?.get(CCT.definition).toJsonMapOrEmpty()[WFD.tasks].toJsonListOfMaps().size)
    }

    @Test
    fun aListWithoutKeysTheChangeRestatesIsSaid() {
        val (edits, id) = changing {
            withTasks(it, listOf(task("a", "Check it", listOf(mapOf("fn" to "kdr:other"))), task("b", "Sign it")))
        }
        val check = checkEntry(id, view, edits, emptyMap())
        assertTrue(check.ok, check.problems.toString())
        assertTrue(check.notes.single().contains("tasks[a].functions"), check.notes.toString())
    }

    @Test
    fun aSavesDryRunSaysAnOverlayKeepsTheRestOfTheCodes() {
        val overlay = CodeReplacementView(
            "flows", CCT.workflowDef, mapOf(CCT.workflowId to "review"), "flows", null, listOf(codeFlow), marked = true, overlay = true,
        )
        assertEquals(
            listOf("Workflows review, from the code's flows bundle, in flows — only what is changed; the rest follows the code"),
            replacingText(listOf(overlay)),
        )
        assertEquals("Revert to code later and the code's runs again.", replacingNoteText(listOf(overlay)))
        assertEquals("The code's, in its flows bundle, which this entry changes", replacedHeadingText(overlay))
    }

    @Test
    fun aSaveSaysWhichStalePartsOfTheStoredChangeItDrops() {
        // Stored when the code had a task `gone`, which it has since lost.
        val stored = mapOf(
            CCT.workflowId to "review", CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.definition to mapOf(WFD.workflowId to "review", WFD.tasks to listOf(mapOf(WFD.id to "gone", WFD.label to "Orphan"))),
        )
        assertEquals(1, codeOverlayStale(CCT.workflowDef, codeFlow, stored).size)
        val id = "flows/${CCT.workflowDef}/review"
        val opened = EntryDraft("flows", CCT.workflowDef, stored, null, "", client = "acme", code = codeFlow)
        val edited = opened.withText(entryText(opened.originalShown() + (CCT.definition to
            opened.originalShown()[CCT.definition].toJsonMapOrEmpty() + (WFD.label to "Review, ours"))))
        val check = checkEntry(id, view, BundleEdits().with(id, edited), emptyMap())
        assertTrue(check.ok, check.problems.toString())
        assertTrue(check.notes.any { "Saving drops this part" in it && "gone" in it }, check.notes.toString())
    }

    @Test
    fun theSlotLineCountsWhatIsChangedHereAndBundlesNameTheBundlesShownWithTheirCode() {
        val changed = CodeReplacementView(
            "flows", CCT.workflowDef, mapOf(CCT.workflowId to "review"), "flows", null, listOf(codeFlow), marked = true, overlay = true,
        )
        assertEquals(
            "Workflows — 1 entry · 1 changed here",
            codeSlotLineText("Workflows — 1 entry", listOf(changed), "flows", CCT.workflowDef),
        )
        assertEquals("Traits — 2 entries", codeSlotLineText("Traits — 2 entries", listOf(changed), "flows", CCT.traitDef))

        val storedSlots = mapOf(CCT.workflowDef to listOf(mapOf<String, Any?>(CCT.workflowId to "review")))
        val stored = EditorBundle("flows", EditorRevision(1, true, storedSlots, 0), null)
        val own = EditorBundle("main", EditorRevision(1, true, emptyMap(), 0), null)
        assertEquals(
            "Changes to code bundles are stored in bundles of the same name, shown with them under From code: flows (1 entry).",
            codeNamedBundlesText(listOf(own, stored), view.codeConfigs),
        )
        assertEquals(null, codeNamedBundlesText(listOf(own), view.codeConfigs))
    }

    @Test
    fun aStoredOverlayIsShownMergedAndOpeningItIsNoEdit() {
        val stored = mapOf(
            CCT.workflowId to "review", CCT.overrides to CCT.overridesCodeOverlayValue,
            CCT.definition to mapOf(WFD.workflowId to "review", WFD.tasks to listOf(mapOf(WFD.id to "b", WFD.label to "Sign it now"))),
        )
        val opened = EntryDraft("flows", CCT.workflowDef, stored, null, "", client = "acme", code = codeFlow)
        val draft = opened.withText(entryText(opened.originalShown()))
        assertFalse(draft.isEdit)
        val shown = draft.originalShown()[CCT.definition].toJsonMapOrEmpty()
        assertEquals(listOf("Check it", "Sign it now"), shown[WFD.tasks].toJsonListOfMaps().map { it[WFD.label] })
        assertEquals("Review", shown[WFD.label])
    }
}
