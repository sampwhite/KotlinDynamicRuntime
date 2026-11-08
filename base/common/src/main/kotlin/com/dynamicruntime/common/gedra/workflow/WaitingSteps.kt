package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactRegistry
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Which steps of a form are **waiting on the caller** (issue #1637): the engaged workflows, not done ([workflowDoneIn], as
 * the cell judges finished), whose current task is theirs to take. What the forms listing's `needsMe` keeps, what Home's "Waiting on you" card lists, and the one answer the
 * classification of a form's workflows ([formWorkflowWeights], issue #1597) cannot read from state -- so a workflow is
 * the caller's turn exactly when its step is waiting on them, and a keyword search's `mine` reads the same rule.
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
 *  - **A task that is neither** asks nothing of anyone.
 *
 * And a save waits on nobody the owner-only rule ([OwnerOnly], issue #1433) keeps out -- an editor, on a form kept to its
 * owner, who is neither its owner nor a reviewer of its current work -- since its gate would refuse them. An approval
 * is not a change to the form, so the rule never keeps anyone from one.
 *
 * **A workflow's Start** waits on the caller too (issue #1662): one that highlights it ([WfDef.highlightStart]), on a form
 * eligible for it, not yet in it, and open to new forms, has its first task as its implicit call to action, judged by
 * the same rule -- a step that engages the form first ([SWF.startsWorkflow]). It only routes: who may engage is the
 * eligibility tests' alone, whoever the Start waits on. No CTA filter applies to it, since the state computes one only for
 * an engaged workflow.
 *
 * The steps waiting on **someone else** are the other half (issue #1598): [reasons] says whom each waits on, by the same
 * rule, so the forms list's muted links can say why.
 *
 * ### From stored state alone
 *
 * As [SingletonWorkflows] does, this reads each workflow's stored entry -- its current task, whether that task is
 * complete, and its filter rule as of the last recompute -- and asks of the caller only what needs no form data: the
 * request's cfacts, the task's viewer facts, and the caller's relation to the form, from its owner. So a listing can
 * filter on it before paging, over the states cache. The gates work the filter rule out again from the form's data
 * when the person acts, so a step shown here as theirs is checked again at the moment it is taken.
 *
 * ### Worked out once
 *
 * One instance per request and caller: the request's facts and viewer facts are worked out once per client, each form's
 * answer once (a page's row reads back what a filter found), and the caller's relation to a form once per owner. On a
 * test instance that relation is a comparison; in production it turns on the owners' identities, which [readOwners]
 * reads for every row of a listing in **one batch** ([readIdentities]) rather than one lookup per owner.
 */
class WaitingSteps(
    private val cxt: KdrCxt,
    /** Reads owners' identities by user id, in one batch: by default through the user cache, whatever the caller's scope. */
    private val readIdentities: (Collection<Long>) -> Map<Long, String?> = { ids -> ownerIdentities(cxt, ids) },
) {
    private val fragments = MarkdownFragmentService.get(cxt)
    private val perClient = HashMap<String, ClientRules>()
    private val identities = HashMap<Long, String?>()
    private val byForm = HashMap<String, List<Map<String, Any?>>>()
    private val formFacts = HashMap<String, FormFacts>()
    private val weighed = HashMap<String, List<FormWorkflowWeight>>()

    /** What is worked out once per client: its workflows, its cfacts, the caller's request and viewer facts there. */
    private inner class ClientRules(val client: String) {
        val registry = WorkflowService.get(cxt).forClient(client)
        val cfacts: CFactRegistry = SchemaService.get(cxt).cfactsFor(client)
        val requestFacts: Set<String> = cfacts.assemble(cxt)
        val viewer = ViewerCfacts(cxt, client)

        /** Resolves the client's copy -- a label, a filter's label -- as its own users read it ([copyResolver]). */
        val resolve: (String) -> String by lazy { copyResolver(cxt, client) }
        private val phases = HashMap<String, WfPhase?>()

        /** Where workflow [id] stands, when it is a normal workflow the caller sees and within its lifetime; else null. */
        fun phaseOf(id: String): WfPhase? = phases.memo(id) {
            WorkflowPhases.liveFor(cxt, registry, id)?.def?.takeIf { it.entry == WfEntry.normal }
                ?.let { WorkflowPhases.of(cxt, client, it) }
        }
    }

    private fun rules(client: String): ClientRules = perClient.getOrPut(client) { ClientRules(client) }

    private fun label(text: String): String = fragments.backendPass(cxt, text)

    /** Whether an owner's identity can decide whether the caller owns their form: in production, for a caller with one. */
    private val identityDecides: Boolean = !cxt.instanceConfig.isTestInstance && cxt.userProfile.identityId != null

    /**
     * Reads the identities of [ownerUserIds], the owners of the forms about to be judged, in one batch -- only those the
     * answer can turn on (another user than the caller, in production) and not already read. Nothing on a test instance.
     */
    fun readOwners(ownerUserIds: Collection<Long?>) {
        if (!identityDecides) {
            return
        }
        val wanted = ownerUserIds.filterNotNull().filter { it != cxt.userProfile.userId && it !in identities }.distinct()
        if (wanted.isEmpty()) {
            return
        }
        val read = readIdentities(wanted)
        for (id in wanted) {
            identities[id] = read[id]
        }
    }

    /**
     * The caller's relation to a form owned by [ownerUserId] ([RowCfacts]): [RCF.isOwner] for theirs; nothing for an
     * unknown owner. From the identities [readOwners] read; an owner it was not given is read on its own, so a forgotten
     * batch costs lookups, never a wrong answer.
     */
    fun rowFacts(ownerUserId: Long?): Set<String> {
        if (ownerUserId == null) return emptySet()
        return RowCfacts.of(cxt, ownerUserId) {
            if (ownerUserId !in identities) readOwners(listOf(ownerUserId))
            identities[ownerUserId]
        }
    }

    /**
     * The steps of form [gedraId], owned by [ownerUserId], with state entries [states], that wait on the caller: each
     * its workflow's id and label, the task's id, and what it asks of them in words ([SWF.actionText]), in the state's
     * order. Empty when none does. Worked out once per form: asked again, it answers what it answered first. A listing's
     * filter hands it all of the form's states and a page's row only those shown; the two differ only by entries of
     * workflows that are not live, which wait on no one either way.
     */
    fun of(gedraId: GedraId, ownerUserId: Long?, states: List<Map<String, Any?>>): List<Map<String, Any?>> =
        byForm.getOrPut(gedraId.fullId) {
            val form = factsOf(gedraId, ownerUserId, states)
            form.datas.mapNotNull { data -> waitingStep(form, data) }
        }

    /**
     * Why each step of form [gedraId] that waits on someone else does (issue #1598), for the workflow cell's muted links:
     * one entry per engaged workflow whose current task, still calculating, has a save or an approval the caller may not
     * take -- its workflow id, whom it waits on ([WCOL.waitsOn], a [WfWaitsOn]), and its CTA filter's label resolved in
     * the form's client ([WFS.ctaFilterLabel]) when that filter is what keeps the caller out -- dropped when it still needs
     * the form's data, which a listing does not have. A highlighted Start (issue #1690) that waits on someone else is
     * among them, by its first task: no CTA filter applies before the form is in, so it waits on whoever that task's
     * approval or save names. In the state's order; the steps [of] answers are not among them.
     *
     * Theme: #TPLPS.
     */
    fun reasons(gedraId: GedraId, ownerUserId: Long?, states: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val theirs = of(gedraId, ownerUserId, states).mapNotNullTo(HashSet()) { it[WFD.workflowId].toOptStr() }
        val form = factsOf(gedraId, ownerUserId, states)
        return form.datas.mapNotNull { data ->
            val workflowId = data[WFD.workflowId].toOptStr()?.takeIf { it !in theirs } ?: return@mapNotNull null
            // A highlighted Start waiting on someone else (issue #1690) gives its first task's reason, as a current task does.
            val task = (currentTask(form.rules, data) ?: startTask(form, data))
                ?.takeIf { it.approval != null || it.saves.isNotEmpty() } ?: return@mapNotNull null
            val keptByFilter = !passesFilter(form.rules, form.callerFacts, task, data)
            val waitsOn = when {
                keptByFilter -> WfWaitsOn.filter
                task.approval != null -> WfWaitsOn.approval
                task.saveWhen == null -> WfWaitsOn.owner
                else -> WfWaitsOn.others
            }
            // The label as the form's client words it; one still needing the form's data is not one a listing can give.
            val label = data[WFS.ctaFilterLabel].toOptStr()?.takeIf { keptByFilter }?.let(form.rules.resolve)
                ?.takeIf { it.isNotBlank() && !SingletonWorkflows.needsFormData(it) }
            linkedMapOf<String, Any?>(WFD.workflowId to workflowId, WCOL.waitsOn to waitsOn.name).apply {
                label?.let { put(WFS.ctaFilterLabel, it) }
            }
        }
    }

    /** Form [gedraId]'s [FormFacts], worked out once per form and shared by [of] and [reasons], as [of]'s answer is. */
    private fun factsOf(gedraId: GedraId, ownerUserId: Long?, states: List<Map<String, Any?>>): FormFacts =
        formFacts.getOrPut(gedraId.fullId) { FormFacts(rules(gedraId.client), ownerUserId, states) }

    /**
     * What is worked out once for a form (owned by [ownerUserId], with state entries [states]) and shared by its steps:
     * its workflows' stored state, the caller's facts there, and whether the owner-only rule keeps the caller out.
     */
    private inner class FormFacts(val rules: ClientRules, ownerUserId: Long?, states: List<Map<String, Any?>>) {
        val datas = states.filter { it[GE.traitId].toOptStr() == WFS.workflowState }.map { it[GE.data].toJsonMapOrEmpty() }

        // The caller's relation to the form, asked only once a workflow has a step to judge: most forms have none.
        val callerFacts by lazy { rules.requestFacts + rowFacts(ownerUserId) }

        // The workflows the form is engaged with, which a Start (issue #1662) is not.
        val engagedIds: Set<String> by lazy {
            states.filter { it[GE.traitId].toOptStr() == WFS.workflowEngagement }.map { it[GE.data].toJsonMapOrEmpty() }
                .filter { it[WFS.engaged] == true }.mapNotNullTo(HashSet()) { it[WFD.workflowId].toOptStr() }
        }

        // The owner-only rule (issue #1433), asked only of an editor, on a form carrying the fact, who does not own it.
        val keptToOwner by lazy {
            AdminRules.editsClient(cxt) && OwnerOnly.isOn(states) && RCF.isOwner !in callerFacts &&
                datas.none { data -> currentTask(rules, data)?.let { reviewsCurrentWork(rules, callerFacts, it, data) } == true }
        }
    }

    /**
     * The workflows of form [gedraId] weighed by what each asks of the caller ([formWorkflowWeights]): its turn exactly
     * where a step of it [of] says waits on them, and a highlighted Start that does not is waiting on someone else (issue
     * #1690). Worked out once per form, as [of] is.
     */
    fun weights(gedraId: GedraId, ownerUserId: Long?, states: List<Map<String, Any?>>): List<FormWorkflowWeight> =
        weighed.getOrPut(gedraId.fullId) {
            val waiting = of(gedraId, ownerUserId, states).mapNotNullTo(HashSet()) { it[WFD.workflowId].toOptStr() }
            val rules = rules(gedraId.client)
            val highlights = { id: String -> rules.registry.workflow(id)?.def?.highlightStart == true }
            formWorkflowWeights(states, rules::phaseOf, highlights) { workflow, _ -> workflow.workflowId in waiting }
        }

    /**
     * The task [data]'s workflow waits on now: live, seen by the caller, still calculating, and not done -- neither every
     * task complete nor the finished fact asserted ([workflowDoneIn]), so a workflow the cell draws as finished never has
     * a step waiting on anyone.
     */
    private fun currentTask(rules: ClientRules, data: Map<String, Any?>): WfTask? {
        if (workflowDoneIn(data)) return null
        val workflowId = data[WFD.workflowId].toOptStr() ?: return null
        // Live, seen by the caller, and still calculating (issue #790): a frozen workflow's saves are refused.
        if (rules.phaseOf(workflowId)?.calculates != true) return null
        return data[WFS.ctaTask].toOptStr()?.let { rules.registry.workflow(workflowId)?.def?.task(it) }
    }

    /**
     * The first task of [data]'s workflow when it is a **Start** (issue #1662): the workflow highlights it, and the form is
     * eligible for it, not yet in it, and may engage now (its phase [WfPhase.engageable]). Null otherwise.
     */
    private fun startTask(form: FormFacts, data: Map<String, Any?>): WfTask? {
        if (data[WFS.eligible] != true) return null
        val workflowId = data[WFD.workflowId].toOptStr() ?: return null
        if (workflowId in form.engagedIds || form.rules.phaseOf(workflowId) != WfPhase.engageable) return null
        return form.rules.registry.workflow(workflowId)?.def?.takeIf { it.highlightStart }?.tasks?.firstOrNull()
    }

    /** Whether the caller passes [task]'s CTA filter rule as [data] stores it. */
    private fun passesFilter(rules: ClientRules, callerFacts: Set<String>, task: WfTask, data: Map<String, Any?>): Boolean =
        passesCtaFilterRule(rules.cfacts, data[WFS.ctaFilterRule].toOptStr(), callerFacts + rules.viewer.forTask(task))

    /** Whether the caller reviews [task], the current work: a reviewer by its functions who passes its filter rule. */
    private fun reviewsCurrentWork(rules: ClientRules, callerFacts: Set<String>, task: WfTask, data: Map<String, Any?>): Boolean =
        WFC.reviewer in rules.viewer.forTask(task) && passesFilter(rules, callerFacts, task, data)

    /**
     * One workflow's state [data] as a step waiting on the caller, or null when its current task -- or, for a Start, its
     * first task (issue #1662) -- is not theirs.
     */
    private fun waitingStep(form: FormFacts, data: Map<String, Any?>): Map<String, Any?>? {
        val rules = form.rules
        val current = currentTask(rules, data)
        val task = current ?: startTask(form, data) ?: return null
        // Neither an approval nor a save: nothing for anyone to take.
        if (task.approval == null && task.saves.isEmpty()) return null
        val callerFacts = form.callerFacts
        if (!passesFilter(rules, callerFacts, task, data)) return null
        // The CTA's task facts from state, as the singleton chips build them, passing its filter.
        val facts = buildSet {
            addAll(callerFacts)
            addAll(stateCtaTaskFacts(data, passesFilter = true))
            addAll(rules.viewer.forTask(task))
        }
        val approval = task.approval
        val saveWhen = task.saveWhen
        val theirs = when {
            approval != null -> rules.cfacts.parse(approval.effectiveApproveWhen).matches(facts)
            // A save the owner-only rule refuses them.
            form.keptToOwner -> false
            saveWhen != null -> rules.cfacts.parse(saveWhen).matches(facts)
            // A save that names nobody: the owner's step, not every reader's.
            else -> RCF.isOwner in callerFacts
        }
        if (!theirs) return null
        val workflowId = data[WFD.workflowId].toOptStr() ?: return null
        val def = rules.registry.workflow(workflowId)?.def ?: return null
        return linkedMapOf<String, Any?>(
            WFD.workflowId to workflowId,
            WFD.label to (def.label.takeIf { it.isNotBlank() }?.let { label(it) } ?: workflowId),
            WFS.ctaTask to task.id,
            SWF.actionText to SingletonWorkflows.actionText(task, facts, rules.cfacts::parse, ::label),
        ).apply { if (current == null) put(SWF.startsWorkflow, true) }
    }

    companion object {
        /**
         * The identities of the users [userIds], in one read through the user cache ([UserService.queryUsersByIds]).
         * Unscoped: an identity is only compared with the caller's, never shown, and a form's owner may have moved out
         * of what the caller sees while the caller still owns the form as the same person.
         */
        fun ownerIdentities(cxt: KdrCxt, userIds: Collection<Long>): Map<Long, String?> =
            UserService.getOrNull(cxt)?.queryUsersByIds(cxt, userIds, ReadScope.unrestricted)?.mapValues { it.value.identityId }
                ?: emptyMap()
    }
}

/**
 * The value for [key], computing it with [compute] only the first time -- a null answer included, which `getOrPut`
 * would compute again on every ask.
 */
private inline fun <K, V> MutableMap<K, V?>.memo(key: K, compute: () -> V?): V? =
    if (containsKey(key)) get(key) else compute().also { put(key, it) }

/**
 * A call to action's task facts as a surface reading state alone has them (issues #789, #1597): available, the CTA,
 * complete as its stored status says, never approved (a CTA never is), [WFC.passesCtaFilter] when the caller passes its
 * filter rule, and the workflow's keyword fact ([CtaKeyword.workflowCfact]) for the keyword the state stores -- as the
 * workflow view's judge gives a task the keyword fact.
 */
internal fun stateCtaTaskFacts(state: Map<String, Any?>, passesFilter: Boolean): Set<String> = buildSet {
    add(WFC.taskAvailable)
    add(WFC.isCta)
    if (state[WFS.ctaStatus].toJsonMapOrEmpty()[CDF.complete] == true) add(WFC.taskComplete)
    if (passesFilter) add(WFC.passesCtaFilter)
    CTAK.of(state[WFS.ctaKeyword].toOptStr())?.let { add(it.workflowCfact) }
}
