package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException

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
    fun live(cxt: KdrCxt, registry: WorkflowRegistry, id: String): WfDeclared? =
        registry.workflow(id)?.takeIf { it.def.entry != WfEntry.normal || of(cxt, it.def).exists }

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
