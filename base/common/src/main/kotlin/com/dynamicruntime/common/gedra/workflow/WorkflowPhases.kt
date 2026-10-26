package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * The endpoints' side of a normal workflow's time windows (issue #790): the one place that says what each
 * [WfPhase] allows and in which words, so engage, view, save, approve and the chip popover cannot drift apart.
 *
 * **Windows are parameters** (issue #1356): a workflow's windows are not in its definition but in its parameters, set
 * per client ([WorkflowParamsService]), so every phase here is asked of a workflow *in a client* -- the registry's, or
 * the form's. This is the one place that reads them for a phase.
 *
 * **One clock.** Every phase is read at `cxt.instanceNow()`, the instance clock -- not the per-context `now()` --
 * because a phase decides what the deriver persists (a frozen entry, a dropped one), and persisted state is
 * stamped on the instance clock throughout; the engagement recorded beside it is.
 */
object WorkflowPhases {
    /**
     * The windows [def] runs with in [client] (issue #1356): its parameters there -- a sandbox's own, else its
     * parent's -- or none for a workflow that is not normal, which has no windows, and on a node that keeps none.
     */
    fun windowsOf(cxt: KdrCxt, client: String?, def: WfDef): WfWindows =
        if (def.entry != WfEntry.normal) WfWindows.none
        else WorkflowParamsService.getOrNull(cxt)?.windowsFor(cxt, client, def.workflowId) ?: WfWindows.none

    /** Where [def] stands in [client] at the moment [at]. */
    fun phaseAt(cxt: KdrCxt, client: String?, def: WfDef, at: Instant): WfPhase = windowsOf(cxt, client, def).phaseAt(at)

    /** Where [def] stands in [client] for [cxt]'s request. */
    fun of(cxt: KdrCxt, client: String?, def: WfDef): WfPhase = phaseAt(cxt, client, def, cxt.instanceNow())

    /**
     * The workflow [id] of [registry] as a caller may address it: null when unknown, and null for a normal
     * workflow outside its lifetime, which is as good as not configured. The lookup every endpoint uses, so a
     * later consumer (the listing's summary, the workflow pages) inherits the rule rather than remembering it.
     */
    fun live(cxt: KdrCxt, registry: WorkflowRegistry, id: String): WfDeclared? = liveAt(cxt, registry, id, cxt.instanceNow())

    /**
     * [live] at the moment [at]: the one statement of what "live" is, so a caller judging another moment -- the
     * publish impact report, at the moment it reads the client's data -- holds the same rule every endpoint does.
     */
    fun liveAt(cxt: KdrCxt, registry: WorkflowRegistry, id: String, at: Instant): WfDeclared? =
        registry.workflow(id)?.takeIf { it.def.entry != WfEntry.normal || phaseAt(cxt, registry.client, it.def, at).exists }

    /**
     * [live], and seen by the person [cxt] acts as (issue #1384, [WorkflowAudience]): the lookup of every endpoint a
     * person meets a workflow through. A workflow they do not see is as good as not configured for them, as one past its
     * lifetime is for everybody -- they cannot view it, save in it, engage a form with it, or approve in it, nothing
     * shows a form's part in it, and its locks on a form's data do not hold for them ([TraitLocks]) -- while whoever
     * sees it carries on, and its progress is kept.
     */
    fun liveFor(cxt: KdrCxt, registry: WorkflowRegistry, id: String): WfDeclared? =
        live(cxt, registry, id)?.takeIf { WorkflowAudience.of(cxt).sees(registry.client, id) }

    /** [declared] -- a client's creation or survey workflow -- when the person [cxt] acts as sees it ([liveFor]), else null. */
    fun seenOrNull(cxt: KdrCxt, registry: WorkflowRegistry, declared: WfDeclared?): WfDeclared? =
        declared?.takeIf { WorkflowAudience.of(cxt).sees(registry.client, it.def.workflowId) }

    /**
     * [states] -- a form's state entries -- as a caller is shown them (issue #1354): without the per-workflow entries
     * (its engagement, its calculated state, its approvals) of any workflow that is not [liveFor] the caller in
     * [registry]. A workflow past its lifetime is "kept, but nothing shows it" ([WfPhase.outsideLifetime]), one deleted
     * from the configuration is the same, and so is one the caller does not see (issue #1384), so none appears. Nothing stored changes: the entries are only left out, and
     * putting the workflow back, or extending its lifetime, shows them again. The admin raw-state read keeps them all.
     *
     * [alsoShow] is a workflow whose entries are shown whatever it is: the one a call just acted on -- withdrawing a
     * form from a workflow since deleted -- whose caller is owed the result of what they did.
     *
     * A read of stored states goes through `GedraDataService.readShownStates`, which applies this; a write that answers
     * with the states it left calls it directly.
     */
    fun shownStates(
        cxt: KdrCxt,
        registry: WorkflowRegistry,
        states: List<Map<String, Any?>>,
        alsoShow: String? = null,
    ): List<Map<String, Any?>> {
        val retiredOf = retiredJudge(cxt, registry)
        return states.filter { entry -> retiredOf(entry).let { it == null || it == alsoShow } }
    }

    /** [shownStates] against [client]'s workflows. */
    fun shownStates(cxt: KdrCxt, client: String, states: List<Map<String, Any?>>, alsoShow: String? = null): List<Map<String, Any?>> =
        shownStates(cxt, WorkflowService.get(cxt).forClient(client), states, alsoShow)

    /**
     * [states] as a caller reading a form's retired workflows too is answered them (issue #1366): every entry, but those
     * of a workflow that is [live] and the caller does not see (issue #1384). A restricted workflow is not retired --
     * whoever sees it carries on -- so asking for retired workflows reveals neither its entries nor its id.
     */
    fun statesWithRetired(cxt: KdrCxt, registry: WorkflowRegistry, states: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val unseenOf = workflowJudge { id -> live(cxt, registry, id) == null || liveFor(cxt, registry, id) != null }
        return states.filter { unseenOf(it) == null }
    }

    /**
     * The workflows, sorted, whose entries in [states] -- as [statesWithRetired] answers them -- [shownStates] would
     * leave out (issue #1366): those not [live], deleted or past their lifetime. What a caller reading a form's retired
     * workflows too is told, so they can tell an entry that would come back if its workflow were restored from one
     * that is live, without knowing the configuration.
     */
    fun retiredWorkflows(cxt: KdrCxt, client: String, states: List<Map<String, Any?>>): List<String> {
        val registry = WorkflowService.get(cxt).forClient(client)
        val retiredOf = workflowJudge { id -> live(cxt, registry, id) != null }
        return states.mapNotNull(retiredOf).distinct().sorted()
    }

    /**
     * Answers, for one state entry, the workflow it belongs to when that workflow is not [liveFor] the caller in
     * [registry], else null -- null too for an entry that belongs to no workflow.
     */
    private fun retiredJudge(cxt: KdrCxt, registry: WorkflowRegistry): (Map<String, Any?>) -> String? =
        workflowJudge { id -> liveFor(cxt, registry, id) != null }

    /**
     * Answers, for one per-workflow state entry, the workflow it belongs to when [keeps] says no of it, else null --
     * null too for an entry that belongs to no workflow. Each workflow is asked once per judge.
     */
    private fun workflowJudge(keeps: (String) -> Boolean): (Map<String, Any?>) -> String? {
        val kept = HashMap<String, Boolean>()
        return judge@{ entry ->
            if (entry[GE.traitId].toOptStr() !in perWorkflowTraits) return@judge null
            val id = entry[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() ?: return@judge null
            id.takeUnless { kept.getOrPut(id) { keeps(id) } }
        }
    }

    /** The state traits whose entries belong to one workflow, each naming it under [WFD.workflowId]. */
    private val perWorkflowTraits = setOf(WFS.workflowEngagement, WFS.workflowState, WFS.workflowApproval)

    /**
     * Refuses [doing] -- "saved", "approved" -- in a workflow that is not being calculated in [client]: past its relevancy
     * (frozen, for a form engaged with it) or not yet relevant. A 409, since the request is well-formed and would
     * succeed at another time.
     */
    fun requireCalculated(cxt: KdrCxt, client: String?, def: WfDef, doing: String) {
        val now = cxt.instanceNow()
        val windows = windowsOf(cxt, client, def)
        if (windows.phaseAt(now).calculates) return
        val w = windows.effectiveRelevancy
        val start = w.start
        val reason = if (start != null && now < start) {
            "it does not open until $start"
        } else {
            "it closed at ${w.end}, and what it recorded then stands"
        }
        throw KdrException("Workflow '${def.workflowId}' cannot be $doing now: $reason.", code = EXC.conflict)
    }

    /** Refuses engaging a form with [def] in [client] outside its engagement window; a 400 like the eligibility refusal. */
    fun requireEngageable(cxt: KdrCxt, client: String?, def: WfDef) {
        val now = cxt.instanceNow()
        val windows = windowsOf(cxt, client, def)
        if (windows.phaseAt(now) == WfPhase.engageable) return
        val w = windows.effectiveEngagement
        val start = w.start
        val reason = if (start != null && now < start) "opens at $start" else "closed at ${w.end}"
        throw KdrException.mkInput("Workflow '${def.workflowId}' is not taking new forms: engagement $reason.")
    }
}
