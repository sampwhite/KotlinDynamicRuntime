package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException

/**
 * The endpoints' side of a normal workflow's time windows (issue #790): the one place that says what each
 * [WfPhase] refuses and in which words, so engage, view, save and approve cannot drift apart. Outside its
 * lifetime a workflow answers as an unknown one would; each endpoint already says that in its own way, so the
 * refusals here are for the two narrower windows.
 */
object WorkflowPhases {
    /** Where [def] stands for [cxt]'s request -- the moment every check in one request reads. */
    fun of(cxt: KdrCxt, def: WfDef): WfPhase = def.phaseAt(cxt.now())

    /**
     * Refuses [doing] -- "saved", "approved" -- in a workflow that is not being calculated: past its relevancy
     * (frozen, for a form engaged with it) or not yet relevant. A 409, since the request is well-formed and would
     * succeed at another time.
     */
    fun requireCalculated(cxt: KdrCxt, def: WfDef, phase: WfPhase, doing: String) {
        if (phase.calculates) return
        val w = def.windows.effectiveRelevancy
        val start = w.start
        val reason = if (start != null && cxt.now() < start) {
            "it does not open until $start"
        } else {
            "it closed at ${w.end}, and what it recorded then stands"
        }
        throw KdrException("Workflow '${def.workflowId}' cannot be $doing now: $reason.", code = EXC.conflict)
    }

    /** Refuses engaging a form with [def] outside its engagement window; a 400 like the eligibility refusal. */
    fun requireEngageable(cxt: KdrCxt, def: WfDef, phase: WfPhase) {
        if (phase == WfPhase.engageable) return
        val w = def.windows.effectiveEngagement
        val start = w.start
        val reason = if (start != null && cxt.now() < start) "opens at $start" else "closed at ${w.end}"
        throw KdrException.mkInput("Workflow '${def.workflowId}' is not taking new forms: engagement $reason.")
    }
}
