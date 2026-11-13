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

    /**
     * An approver with no name on their profile arrives as their email (issue #1430). The default line leaves it out;
     * a workflow's text decides for itself, `contains` telling an email from a name.
     */
    @Test
    fun anEmailIsLeftOutOfTheDefaultLineAndTheTextDecides() {
        val byEmail = approvedBlock + (WVF.approvedByName to "rex@acme.test")
        val text = $$"""Approved by ${contains(approvedByName, "@") ? "a reviewer" : approvedByName}."""
        val task = view(approvalTask(byEmail, mapOf(WDSP.mode to WDSP.textMode, WDSP.text to text))).tasks.single()
        assertEquals("Approved on 2026-09-24.", approvedLine(task.approval!!))
        assertEquals("Approved by a reviewer.", displayTextOf(task))
        val named = view(approvalTask(approvedBlock, mapOf(WDSP.mode to WDSP.textMode, WDSP.text to text))).tasks.single()
        assertEquals("Approved by Rex Reviewer.", displayTextOf(named))
    }

    /** An approval in steps (issue #1702): the step waited on, and a line for each step given, by its label. */
    @Test
    fun anApprovalInStepsReadsItsCurrentStepAndListsTheStepsGiven() {
        val step = { id: String, label: String, given: Boolean ->
            buildMap {
                put(WFD.id, id); put(WFD.label, label); put(WVF.approved, given)
                if (given) { put(WVF.approvedByName, "Rex Reviewer"); put(WFS.approvedAt, "2026-09-24T19:18:34.577Z") }
            }
        }
        val waiting = mapOf(
            WFD.prompt to "Sign it off.", WFD.button to "Sign off", WVF.approved to false, WVF.currentStep to "admin",
            WFD.waiting to "Waiting on an administrator",
            WVF.steps to listOf(step("review", "Review", true), step("admin", "Sign-off", false)),
        )
        val approval = view(approvalTask(waiting)).tasks.single().approval!!
        assertEquals("admin", approval.currentStep)
        assertEquals("Waiting on an administrator", approval.waiting)
        assertEquals(listOf("Review", "Sign-off"), approval.steps.map { it.label })
        assertEquals(listOf("Review by Rex Reviewer on 2026-09-24."), givenStepLines(approval))
        // An approval given at once has one step and no label: nothing to list beside its approved line.
        val once = view(approvalTask(approvedBlock + (WVF.steps to listOf(mapOf(WFD.id to "approve", WVF.approved to true)))))
        assertEquals(emptyList(), givenStepLines(once.tasks.single().approval!!))
        assertNull(once.tasks.single().approval!!.currentStep)
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

    /** A task approved at once ("Approve the notes") and one in two steps ("Sign it off"), as an administrator sees them. */
    private fun reversalView(signGiven: Boolean = true) = view(
        mapOf(
            WFD.id to "a", WFD.label to "Approve the notes",
            WVF.approval to mapOf(
                WVF.approved to true,
                WVF.steps to listOf(mapOf(WFD.id to "a", WVF.approved to true, WVF.canReverse to true)),
            ),
        ),
        mapOf(WFD.id to "record", WFD.label to "Record more"),
        mapOf(
            WFD.id to "b", WFD.label to "Sign it off",
            WVF.approval to mapOf(
                WVF.approved to signGiven,
                WVF.steps to listOf(
                    mapOf(WFD.id to "look", WFD.label to "First look", WVF.approved to true, WVF.canReverse to true),
                    mapOf(WFD.id to "sign", WFD.label to "Sign-off", WVF.approved to signGiven, WVF.canReverse to signGiven),
                ),
            ),
        ),
    )

    @Test
    fun reversingAnApprovalNamesTheLaterApprovalsThatGoWithIt() {
        val view = reversalView()
        assertTrue(view.tasks.first().approval!!.steps.single().canReverse)
        assertEquals(listOf("Sign it off: First look", "Sign it off: Sign-off"), reversalTakesWith(view, "a", "a"))
        assertEquals(
            "Reverse this approval? It has to be given again. " +
                "It also reverses “Sign it off: First look” and “Sign it off: Sign-off”.",
            reversalQuestion(view, "a", "a"),
        )
        assertEquals(
            "Reverse this approval? It has to be given again. It also reverses “Sign it off: Sign-off”.",
            reversalQuestion(view, "b", "look"),
        )
        assertEquals("Reverse this approval? It has to be given again.", reversalQuestion(view, "b", "sign"))
        // A later step not given goes with nothing.
        assertEquals(listOf("Sign it off: First look"), reversalTakesWith(reversalView(signGiven = false), "a", "a"))
    }

    @Test
    fun aReversedStepSaysWhoReversedItWhenAndWhy() {
        fun step(extra: Map<String, Any?>) = view(
            approvalTask(
                approvedBlock + (WVF.approved to false) + (WVF.steps to listOf(mapOf(WFD.id to "approve", WVF.approved to false) + extra)),
            ),
        ).tasks.single().approval!!.steps.single()
        val reversed = mapOf(
            WVF.reversedByName to "Ada Admin", WVF.reversedAt to "2026-11-11T19:18:34.577Z", WVF.reversedReason to "The figures were wrong",
        )
        assertEquals("Approval reversed by Ada Admin on 2026-11-11: The figures were wrong.", reversedLine(step(reversed)))
        // Named by its label, with the approval it went with, an email left out, and the reason's own end kept.
        assertEquals(
            "Sign-off reversed on 2026-11-11, with “Approve the notes”: Why was this signed?",
            reversedLine(
                step(
                    reversed + mapOf(
                        WFD.label to "Sign-off", WVF.reversedByName to "ada@acme.test", WVF.reversedReason to "Why was this signed?",
                        WVF.reversedWith to "Approve the notes",
                    ),
                ),
            ),
        )
        assertNull(reversedLine(step(emptyMap())))
        assertFalse(step(reversed).canReverse)
    }
}
