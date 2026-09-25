package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.workflow.WDSP
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task displays and approval tasks on the page side (issue #832): reading the display branch and the approval the
 * view delivers, filling a text display's `${'$'}{…}` from the task's own data, and the approved line.
 */
class TaskDisplayViewTest {
    private fun view(vararg tasks: Map<String, Any?>): WorkflowView = parseWorkflowView(
        mapOf(
            WVF.found to true, WFD.workflowId to "auditReview", WFD.entry to WfEntry.normal.name,
            WVF.phase to WfPhase.engageable.name, WVF.engaged to true, WFD.tasks to tasks.toList(),
        ),
    )!!

    private fun approvalTask(approval: Map<String, Any?>, display: Map<String, Any?>? = null, facts: List<String> = emptyList()) =
        buildMap {
            put(WFD.id, "approve"); put(WFD.label, "Approve the audit")
            put(WVF.approval, approval)
            put(WVF.facts, facts)
            display?.let { put(WFD.display, it) }
        }

    private val approvedBlock = mapOf(
        WFD.prompt to "Read it, then approve.", WFD.button to "Approve the audit", WVF.approved to true,
        WVF.approvedByName to "Rex Reviewer", WFS.approvedAt to "2026-09-24T19:18:34.577Z",
    )

    @Test
    fun aTextDisplayIsFilledFromTheApproval() {
        val task = view(
            approvalTask(approvedBlock, mapOf(WDSP.mode to WDSP.textMode, WDSP.text to $$"The form has been approved by ${approvedByName}.")),
        ).tasks.single()
        assertEquals("The form has been approved by Rex Reviewer.", displayTextOf(task))
        assertEquals("Approved by Rex Reviewer on 2026-09-24.", approvedLine(task.approval!!))
    }

    @Test
    fun aPlaceholderIsNeverShownAsTemplateSyntax() {
        // The approver's name is missing (their account since removed): it reads as "a reviewer".
        val nameless = approvedBlock - WVF.approvedByName - WFS.approvedAt
        val byName = view(approvalTask(nameless, mapOf(WDSP.mode to WDSP.textMode, WDSP.text to $$"Approved by ${approvedByName}."))).tasks.single()
        assertEquals("Approved by a reviewer.", displayTextOf(byName))
        assertEquals("Approved.", approvedLine(byName.approval!!))
        // A placeholder nothing can fill is dropped, not shown.
        val unknown = view(approvalTask(approvedBlock, mapOf(WDSP.mode to WDSP.textMode, WDSP.text to $$"Done${nope}."))).tasks.single()
        assertEquals("Done.", displayTextOf(unknown))
    }

    @Test
    fun aDefaultOrAbsentDisplayDrawsTheTaskItself() {
        val default = view(approvalTask(approvedBlock, mapOf(WDSP.mode to WDSP.defaultMode))).tasks.single()
        assertNull(displayTextOf(default))
        val absent = view(approvalTask(approvedBlock)).tasks.single()
        assertNull(absent.display)
        assertNull(displayTextOf(absent))
    }

    @Test
    fun aDisabledStepIsNeitherEditableNorAReviewersUnlessItsFactsSaySo() {
        val disabled = view(
            approvalTask(
                approvedBlock + (WVF.approved to false),
                mapOf(WDSP.mode to WDSP.textMode, WDSP.text to "Previous data entry must be completed before review.", WDSP.disabled to true),
            ),
        )
        val task = disabled.tasks.single()
        assertTrue(task.isDisabled)
        assertFalse(task.isReviewer)
        assertFalse(disabled.isEditable(task, isEdit = true))
        assertFalse(task.approval!!.approved)
        // The reviewer fact is what the button is shown on.
        assertTrue(view(approvalTask(approvedBlock, facts = listOf(WFC.reviewer))).tasks.single().isReviewer)
    }

    @Test
    fun aTaskWithNoApprovalCarriesNone() {
        val task = view(mapOf(WFD.id to "record", WFD.label to "Record")).tasks.single()
        assertNull(task.approval)
        assertNull(task.display)
        assertFalse(task.isDisabled)
    }
}
