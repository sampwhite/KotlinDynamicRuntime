package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactRegistry
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Which steps of a form are **waiting on the caller** (issue #1637): the engaged workflows whose current task is theirs
 * to take. What the forms listing's `needsMe` keeps, and what Home's "Waiting on you" card lists.
 *
 * ### The rule
 *
 * The save and approve gates' own rule ([WorkflowTaskJudge.maySave], [WorkflowTaskJudge.mayApprove]), asked of the
 * current task -- **narrowed to the person the step names**, since a gate admits more than a step waits on:
 *
 *  - **An approval task** waits on whoever may approve it: its rule (by default a reviewer who does not own the form)
 *    and the CTA's filter rule.
 *  - **A task whose save names someone** (`saveWhen`, such as `kdr:reviewer`) waits on whoever that rule and the filter
 *    admit.
 *  - **A task whose save names nobody** waits on the form's **owner**: its gate admits anyone who may see the form, an
 *    administrator of the whole client among them, who is not who the step is waiting for.
 *
 * ### From stored state alone
 *
 * As [SingletonWorkflows] does, this reads each workflow's stored entry -- its current task, whether that task is
 * complete, and its filter rule as of the last recompute -- and asks of the caller only what needs no form data: the
 * request's cfacts, the task's viewer facts, and the caller's relation to the form, from its owner. So a listing can
 * filter on it before paging, over the states cache. The gates work the filter rule out again from the form's data
 * when the person acts, so a step shown here as theirs is checked again at the moment it is taken.
 *
 * One instance per request and caller: the request's facts and viewer facts are worked out once per client, whether
 * the caller owns a form once per owner -- and only for a form with a step to judge -- and each form's answer once, so a
 * listing that filtered on it reads a row's steps back without judging it again ([remembered]).
 */
class WaitingSteps(private val cxt: KdrCxt) {
    private val fragments = MarkdownFragmentService.get(cxt)
    private val perClient = HashMap<String, ClientRules>()
    private val owns = HashMap<Long, Boolean>()
    private val byForm = HashMap<String, List<Map<String, Any?>>>()

    /** What is worked out once per client: its workflows, its cfacts, the caller's request and viewer facts there. */
    private inner class ClientRules(val client: String) {
        val registry = WorkflowService.get(cxt).forClient(client)
        val cfacts: CFactRegistry = SchemaService.get(cxt).cfactsFor(client)
        val requestFacts: Set<String> = cfacts.assemble(cxt)
        val viewer = ViewerCfacts(cxt, client)
    }

    private fun label(text: String): String = fragments.backendPass(cxt, text)

    /** The caller's relation to a form owned by [ownerUserId]: [RCF.isOwner] for theirs; nothing for an unknown owner. */
    private fun rowFacts(ownerUserId: Long?): Set<String> {
        if (ownerUserId == null) return emptySet()
        return if (owns.getOrPut(ownerUserId) { RowCfacts.isOwner(cxt, ownerUserId) }) setOf(RCF.isOwner) else emptySet()
    }

    /**
     * The steps of form [gedraId], owned by [ownerUserId], with state entries [states], that wait on the caller: each
     * its workflow's id and label, the task's id, and what it asks of them in words ([SWF.actionText]), in the state's
     * order. Empty when none does. Remembered by the form's id ([remembered]).
     */
    fun of(gedraId: GedraId, ownerUserId: Long?, states: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val rules = perClient.getOrPut(gedraId.client) { ClientRules(gedraId.client) }
        // The caller's relation to the form, asked only once a workflow has a step to judge: most forms have none.
        val callerFacts by lazy { rules.requestFacts + rowFacts(ownerUserId) }
        val steps = states
            .filter { it[GE.traitId].toOptStr() == WFS.workflowState }
            .map { it[GE.data].toJsonMapOrEmpty() }
            .mapNotNull { data -> waitingStep(rules, { callerFacts }, data) }
        byForm[gedraId.fullId] = steps
        return steps
    }

    /** What [of] answered for the form with full id [gedraFullId] in this request, or null when it was not asked. */
    fun remembered(gedraFullId: String): List<Map<String, Any?>>? = byForm[gedraFullId]

    /** One workflow's state [data] as a step waiting on the caller, or null when its current task is not theirs. */
    private fun waitingStep(rules: ClientRules, callerFactsOf: () -> Set<String>, data: Map<String, Any?>): Map<String, Any?>? {
        if (data[WFS.tasksDone] == true) return null
        val workflowId = data[WFD.workflowId].toOptStr() ?: return null
        // Live, seen by the caller, and still calculating (issue #790): a frozen workflow's saves are refused.
        val def = WorkflowPhases.liveFor(cxt, rules.registry, workflowId)?.def ?: return null
        if (!WorkflowPhases.of(cxt, rules.client, def).calculates) return null
        val task = data[WFS.ctaTask].toOptStr()?.let { def.task(it) } ?: return null
        // Neither an approval nor a save: nothing for anyone to take.
        if (task.approval == null && task.saves.isEmpty()) return null
        val callerFacts = callerFactsOf()

        val viewerFacts = rules.viewer.forTask(task)
        val passes = passesCtaFilterRule(rules.cfacts, data[WFS.ctaFilterRule].toOptStr(), callerFacts + viewerFacts)
        if (!passes) return null
        val complete = data[WFS.ctaStatus].toJsonMapOrEmpty()[CDF.complete] == true
        // The CTA's task facts from state, as the singleton chips build them: available, the CTA, complete as stored,
        // never yet approved, and passing its filter.
        val taskFacts = buildSet {
            add(WFC.taskAvailable)
            add(WFC.isCta)
            if (complete) add(WFC.taskComplete)
            add(WFC.passesCtaFilter)
        }
        val facts = callerFacts + taskFacts + viewerFacts
        val approval = task.approval
        val saveWhen = task.saveWhen
        val theirs = when {
            approval != null -> rules.cfacts.parse(approval.effectiveApproveWhen).matches(facts)
            task.saves.isEmpty() -> false
            saveWhen != null -> rules.cfacts.parse(saveWhen).matches(facts)
            // A save that names nobody: the owner's step, not every reader's.
            else -> RCF.isOwner in callerFacts
        }
        if (!theirs) return null
        return linkedMapOf(
            WFD.workflowId to workflowId,
            WFD.label to (def.label.takeIf { it.isNotBlank() }?.let { label(it) } ?: workflowId),
            WFS.ctaTask to task.id,
            SWF.actionText to SingletonWorkflows.actionText(task, facts, rules.cfacts::parse, ::label),
        )
    }
}
