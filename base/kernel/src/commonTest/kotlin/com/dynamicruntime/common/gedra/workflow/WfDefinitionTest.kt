package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.http.request.ROLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure workflow engine: the state graph, the role gate on transitions, the tasks that are its steps, and
 * the completeness these are expressed in. Runs on both JVM and JS, which is the point of it living in the
 * kernel -- the frontend will read "which transitions can I take from here?" and "which steps are done?" the
 * same way the backend enforces them (`gedra-workflow.md`).
 */
class WfDefinitionTest {

    /** The reference flow from the design doc: two steps, submit gated on both, advisor can send steps back. */
    private fun reviewFlow(): WfDefinition = WfDefinition(
        workflowId = "loanReview",
        states = listOf(
            WfState("draft", WfHolder.user, initial = true),
            WfState("inReview", WfHolder.advisor),
            WfState("changesRequested", WfHolder.user),
            WfState("approved", WfHolder.none, terminal = true),
        ),
        transitions = listOf(
            WfTransition("submit", "draft", "inReview", ROLE.user, requiresTasks = listOf("income", "assets")),
            WfTransition("approve", "inReview", "approved", WF.advisor),
            WfTransition("return", "inReview", "changesRequested", WF.advisor, reopensTasks = true),
            WfTransition("resubmit", "changesRequested", "inReview", ROLE.user, requiresTasks = listOf("income", "assets")),
        ),
        tasks = listOf(
            WfTask("income", requiredTraitIds = listOf("income")),
            WfTask("assets", requiredTraitIds = listOf("assets")),
        ),
    )

    private fun entry(traitId: String, filled: Boolean) =
        mapOf(GE.traitId to traitId, GE.data to if (filled) mapOf("v" to 1) else emptyMap<String, Any?>())

    @Test
    fun exposesItsInitialStateStatesAndTasks() {
        val wf = reviewFlow()
        assertEquals("draft", wf.initialState.name)
        assertEquals(WfHolder.advisor, wf.state("inReview")?.holder)
        assertTrue(wf.state("approved")!!.terminal)
        assertEquals(listOf("income"), wf.task("income")?.requiredTraitIds)
    }

    @Test
    fun availableTransitionsGateOnTheCallersRole() {
        val wf = reviewFlow()
        assertEquals(listOf("submit"), wf.availableTransitions("draft", setOf(ROLE.user)).map { it.name })
        assertEquals(
            setOf("approve", "return"),
            wf.availableTransitions("inReview", setOf(ROLE.user, WF.advisor)).map { it.name }.toSet(),
        )
        // A plain user sees none of the advisor transitions, though they exist.
        assertEquals(emptyList(), wf.availableTransitions("inReview", setOf(ROLE.user)).map { it.name })
        // A ladder role admits a user transition it ranks above: an admin can resubmit.
        assertEquals(
            listOf("resubmit"),
            wf.availableTransitions("changesRequested", setOf(ROLE.user, ROLE.admin)).map { it.name },
        )
    }

    @Test
    fun completenessRunsPerTaskAndReportsWhichStepsAreUnfinished() {
        val wf = reviewFlow()
        val nothing = emptyList<Map<String, Any?>>()
        val incomeOnly = listOf(entry("income", filled = true))
        val both = listOf(entry("income", true), entry("assets", true))

        // A task is complete only when its required traits are filled.
        assertTrue(wf.task("income")!!.let { WfEngine.taskComplete(it, incomeOnly) })
        assertTrue(!wf.task("assets")!!.let { WfEngine.taskComplete(it, incomeOnly) })

        // The submit gate reports the unfinished steps, in declared order.
        assertEquals(listOf("income", "assets"), WfEngine.incompleteTasks(listOf("income", "assets"), wf, nothing))
        assertEquals(listOf("assets"), WfEngine.incompleteTasks(listOf("income", "assets"), wf, incomeOnly))
        assertEquals(emptyList(), WfEngine.incompleteTasks(listOf("income", "assets"), wf, both))
    }

    @Test
    fun taskStatusReflectsCompletenessAndReopening() {
        val wf = reviewFlow()
        val both = listOf(entry("income", true), entry("assets", true))
        val income = wf.task("income")!!

        assertEquals(WfTaskStatus.pending, WfEngine.taskStatus(income, emptyList(), emptySet()))
        assertEquals(WfTaskStatus.complete, WfEngine.taskStatus(income, both, emptySet()))
        // Reopened wins over complete: the advisor's ask is not a data-absence, so a filled-but-reopened step
        // reads as changesRequested until it is resubmitted and reviewed afresh.
        assertEquals(WfTaskStatus.changesRequested, WfEngine.taskStatus(income, both, setOf("income")))
    }

    @Test
    fun anAssignmentMatchesRolesGroupsAndUsers() {
        val actor = WfActor(roles = setOf(ROLE.user, WF.advisor), userId = 7L, groups = setOf("riskTeam"))
        // A role assignment reads roles the one way the whole system does (RoleLadder.satisfies).
        assertTrue(WfAssignment.ofRole(WF.advisor).matches(actor))
        assertTrue(!WfAssignment.ofRole("auditor").matches(actor))
        // A user assignment is the single-person claim.
        assertTrue(WfAssignment.ofUser(7L).matches(actor))
        assertTrue(!WfAssignment.ofUser(8L).matches(actor))
        // A group assignment matches anyone in the group.
        assertTrue(WfAssignment.ofGroup("riskTeam").matches(actor))
        assertTrue(!WfAssignment.ofGroup("legal").matches(actor))
        // It round-trips through its stored `{kind, value}` form.
        val restored = WfAssignment.fromMap(WfAssignment.ofUser(7L).toMap())
        assertEquals(WfAssigneeKind.user, restored?.kind)
        assertEquals("7", restored?.value)
        assertNull(WfAssignment.fromMap(mapOf("kind" to "nonsense", "value" to "x")))
    }

    @Test
    fun theDefaultAssignmentFollowsTheStatesHolder() {
        val wf = reviewFlow()
        // A user-held state defaults to the owner...
        val draft = wf.defaultAssignment("draft", 42L)
        assertEquals(WfAssigneeKind.user, draft?.kind)
        assertEquals("42", draft?.value)
        // ...an advisor-held state to the advisor *role* (the pool, not one person)...
        val review = wf.defaultAssignment("inReview", 42L)
        assertEquals(WfAssigneeKind.role, review?.kind)
        assertEquals(WF.advisor, review?.value)
        // ...and a terminal state to nobody.
        assertNull(wf.defaultAssignment("approved", 42L))
    }

    @Test
    fun aDefinitionThatCannotBeACoherentMachineIsRejected() {
        // Two initial states.
        assertFailsWith<KdrException> {
            WfDefinition(
                "bad", listOf(WfState("a", WfHolder.user, initial = true), WfState("b", WfHolder.user, initial = true)),
                emptyList(),
            )
        }
        // No initial state.
        assertFailsWith<KdrException> {
            WfDefinition("bad", listOf(WfState("a", WfHolder.user)), emptyList())
        }
        // A transition into an unknown state.
        assertFailsWith<KdrException> {
            WfDefinition(
                "bad", listOf(WfState("a", WfHolder.user, initial = true)),
                listOf(WfTransition("go", "a", "nowhere", ROLE.user)),
            )
        }
        // A transition requiring an unknown task.
        assertFailsWith<KdrException> {
            WfDefinition(
                "bad",
                listOf(WfState("a", WfHolder.user, initial = true), WfState("b", WfHolder.advisor)),
                listOf(WfTransition("go", "a", "b", ROLE.user, requiresTasks = listOf("ghost"))),
            )
        }
        // A reopening transition that does not land in a user-held state -- you cannot reopen a task into review.
        assertFailsWith<KdrException> {
            WfDefinition(
                "bad",
                listOf(WfState("a", WfHolder.advisor, initial = true), WfState("b", WfHolder.advisor)),
                listOf(WfTransition("go", "a", "b", WF.advisor, reopensTasks = true)),
                listOf(WfTask("t")),
            )
        }
    }
}
