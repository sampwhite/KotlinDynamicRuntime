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
 * **One clock.** Every phase is read at `cxt.instanceNow()`, the instance clock -- not the per-context `now()` --
 * because a phase decides what the deriver persists (a frozen entry, a dropped one), and persisted state is
 * stamped on the instance clock throughout; the engagement recorded beside it is.
 */
object WorkflowPhases {
    /** Where [def] stands for [cxt]'s request. */
    fun of(cxt: KdrCxt, def: WfDef): WfPhase = def.phaseAt(cxt.instanceNow())

    /**
     * The workflow [id] of [registry] as a caller may address it: null when unknown, and null for a normal
     * workflow outside its lifetime, which is as good as not configured. The lookup every endpoint uses, so a
     * later consumer (the listing's summary, the workflow pages) inherits the rule rather than remembering it.
     */
    fun live(cxt: KdrCxt, registry: WorkflowRegistry, id: String): WfDeclared? = liveAt(registry, id, cxt.instanceNow())

    /**
     * [live] at the moment [at]: the one statement of what "live" is, so a caller judging another moment -- the
     * publish impact report, at the moment it reads the client's data -- holds the same rule every endpoint does.
     */
    fun liveAt(registry: WorkflowRegistry, id: String, at: Instant): WfDeclared? =
        registry.workflow(id)?.takeIf { it.def.entry != WfEntry.normal || it.def.phaseAt(at).exists }

    /**
     * [states] -- a form's state entries -- as a caller is shown them (issue #1354): without the per-workflow entries
     * (its engagement, its calculated state, its approvals) of any workflow that is not [live] in [registry]. A
     * workflow past its lifetime is "kept, but nothing shows it" ([WfPhase.outsideLifetime]), and one deleted from the
     * configuration is the same, so neither appears. Nothing stored changes: the entries are only left out, and
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
     * The workflows, sorted, whose entries in [states] [shownStates] would leave out (issue #1366): what a caller
     * reading a form's retired workflows too is told, so they can tell an entry that would come back if its workflow
     * were restored from one that is live, without knowing the configuration.
     */
    fun retiredWorkflows(cxt: KdrCxt, client: String, states: List<Map<String, Any?>>): List<String> {
        val retiredOf = retiredJudge(cxt, WorkflowService.get(cxt).forClient(client))
        return states.mapNotNull(retiredOf).distinct().sorted()
    }

    /**
     * Answers, for one state entry, the workflow it belongs to when that workflow is not [live] in [registry], else
     * null -- null too for an entry that belongs to no workflow. Each workflow is looked up once per judge.
     */
    private fun retiredJudge(cxt: KdrCxt, registry: WorkflowRegistry): (Map<String, Any?>) -> String? {
        val liveIds = HashMap<String, Boolean>()
        return judge@{ entry ->
            if (entry[GE.traitId].toOptStr() !in perWorkflowTraits) return@judge null
            val id = entry[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() ?: return@judge null
            id.takeUnless { liveIds.getOrPut(id) { live(cxt, registry, id) != null } }
        }
    }

    /** The state traits whose entries belong to one workflow, each naming it under [WFD.workflowId]. */
    private val perWorkflowTraits = setOf(WFS.workflowEngagement, WFS.workflowState, WFS.workflowApproval)

    /**
     * Refuses [doing] -- "saved", "approved" -- in a workflow that is not being calculated: past its relevancy
     * (frozen, for a form engaged with it) or not yet relevant. A 409, since the request is well-formed and would
     * succeed at another time.
     */
    fun requireCalculated(cxt: KdrCxt, def: WfDef, doing: String) {
        val now = cxt.instanceNow()
        if (def.phaseAt(now).calculates) return
        val w = def.windows.effectiveRelevancy
        val start = w.start
        val reason = if (start != null && now < start) {
            "it does not open until $start"
        } else {
            "it closed at ${w.end}, and what it recorded then stands"
        }
        throw KdrException("Workflow '${def.workflowId}' cannot be $doing now: $reason.", code = EXC.conflict)
    }

    /** Refuses engaging a form with [def] outside its engagement window; a 400 like the eligibility refusal. */
    fun requireEngageable(cxt: KdrCxt, def: WfDef) {
        val now = cxt.instanceNow()
        if (def.phaseAt(now) == WfPhase.engageable) return
        val w = def.windows.effectiveEngagement
        val start = w.start
        val reason = if (start != null && now < start) "opens at $start" else "closed at ${w.end}"
        throw KdrException.mkInput("Workflow '${def.workflowId}' is not taking new forms: engagement $reason.")
    }
}
