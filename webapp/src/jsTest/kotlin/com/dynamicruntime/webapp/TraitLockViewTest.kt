package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.workflow.TraitLockCopy
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.gedra.workflow.WVF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trait locks on the page side (issue #857): reading the locks the backend reports, and the raw editor's handling of
 * a locked section -- left out when untouched, reported when changed -- and the override on the patch.
 */
class TraitLockViewTest {
    @Test
    fun locksParse() {
        val locks = parseTraitLocks(
            listOf(
                mapOf(WFD.traitId to "acmeSiteAudit", WFD.workflowId to "auditReview", WFD.label to "Audit review", WVF.canOverride to true),
                // No trait: nothing to lock.
                mapOf(WFD.workflowId to "x"),
            ),
        )
        val lock = locks.single()
        assertEquals(listOf("acmeSiteAudit", "auditReview", "Audit review"), listOf(lock.traitId, lock.workflowId, lock.label))
        assertTrue(lock.canOverride)
    }

    @Test
    fun aLockCarriesTheNameTheServerGivesAndFallsBackToTheHumanizedId() {
        val locks = parseTraitLocks(
            listOf(
                mapOf(WFD.traitId to "acmeSiteAudit", WVF.traitName to "Site audit", WFD.label to "Audit review"),
                mapOf(WFD.traitId to "acmeSiteFollowUp", WFD.label to "Site follow-up"),
            ),
        )
        assertEquals(listOf("Site audit", "Acme site follow up"), locks.map { it.name })
        // The page's own refusal reads as the server's does.
        assertEquals(
            "Site audit is locked by Audit review and can't be changed now.",
            TraitLockCopy.changeRefused(listOf(locks.first().names)),
        )
    }

    @Test
    fun aTraitTwoWorkflowsLockKeepsBothLocksOnTheView() {
        val lock = { wf: String -> mapOf(WFD.traitId to "audit", WFD.workflowId to wf, WFD.label to wf, WVF.canOverride to false) }
        val view = parseWorkflowView(mapOf(WVF.found to true, WVF.lockedTraits to listOf(lock("review"), lock("followUp"))))!!
        assertEquals(listOf("review", "followUp"), view.lockedTraits["audit"]?.map { it.workflowId })
    }

    @Test
    fun aSectionIsNotedOnlyWhenItsTraitIsLocked() {
        val locks = parseTraitLocks(
            listOf(
                mapOf(WFD.traitId to "audit", WFD.workflowId to "review", WFD.label to "Audit review"),
                mapOf(WFD.traitId to "audit", WFD.workflowId to "followUp", WFD.label to "Site follow-up"),
            ),
        )
        assertEquals("Locked by Audit review and Site follow-up", lockNoteFor(locks, "audit"))
        assertNull(lockNoteFor(locks, "notes"))
        assertNull(lockNoteFor(locks, null))
        assertNull(lockNoteFor(emptyList(), "audit"))
    }

    @Test
    fun editIsOfferedOnlyOverAStepTheCallerCanEdit() {
        val type = parseSchemaTypes(mapOf("t.Note" to mapOf("type" to "object"))).getValue("t.Note")
        fun task(id: String, traitId: String, canSave: Boolean) = WfTaskView(
            id, id, traits = listOf(WfTraitView(traitId, false, type, "t.Note", null)),
            saves = listOf(WfSaveView("s", "Save", "edit")), canSave = canSave,
        )
        val contact = task("contact", "userInfo", canSave = true)
        val followUp = task("followUp", "siteFollowUp", canSave = false)
        val audit = task("audit", "siteAudit", canSave = true)
        val view = WorkflowView(
            workflowId = "siteFollowUp", entry = WfEntry.normal.name, showTaskList = true,
            tasks = listOf(contact, followUp, audit), cfacts = emptyMap(),
            phase = WfPhase.engageable.name, engaged = true,
            lockedTraits = mapOf("siteAudit" to listOf(TraitLock("siteAudit", "review", "Audit review", false))),
        )
        // Workable, since the contact step is theirs...
        assertTrue(view.canWork)
        assertTrue(view.offersEdit(contact))
        // ...but not over someone else's step, nor one whose only trait is locked for them.
        assertFalse(view.offersEdit(followUp))
        assertFalse(view.offersEdit(audit))
        // Every task on the page at once: Edit whenever any of them is workable.
        assertTrue(view.offersEdit(null))
    }

    private fun edit(traitId: String, value: String) =
        mapOf(GED.action to "addOrReplace", GE.traitId to traitId, GE.data to mapOf("v" to value))

    private fun target(vararg edits: Map<String, Any?>) = mapOf(GDF.gedraId to "g1", GPF.edits to edits.toList())

    @Test
    fun anUntouchedLockedSectionIsLeftOutAndAChangedOneReported() {
        val seeded = target(edit("audit", "a"), edit("notes", "n"))
        // Untouched: dropped, so it cannot trip its lock; the unlocked section rides along as ever.
        val untouched = splitLockedEdits(target(edit("audit", "a"), edit("notes", "n2")), seeded, setOf("audit"))
        assertEquals(listOf(edit("notes", "n2")), untouched.target[GPF.edits])
        assertEquals(emptyList(), untouched.changedLocked)
        // Changed: kept, and named -- only an override may send it.
        val changed = splitLockedEdits(target(edit("audit", "b"), edit("notes", "n")), seeded, setOf("audit"))
        assertEquals(listOf("audit"), changed.changedLocked)
        assertEquals(2, (changed.target[GPF.edits] as List<*>).size)
        // Nothing locked: the target as it was.
        assertEquals(seeded, splitLockedEdits(seeded, seeded, emptySet()).target)
    }

    @Test
    fun anOverrideRidesThePatchOnlyWhenGiven() {
        assertEquals("typo", formDocPatchBody(target(), overrideReason = "typo")[GPF.overrideReason])
        assertFalse(formDocPatchBody(target()).containsKey(GPF.overrideReason))
        assertNull(formDocPatchBody(target())[GPF.overrideReason])
    }
}
