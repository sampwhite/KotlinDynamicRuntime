package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.startup.SchemaCollector

/** The friendly label the workflow cfacts group under. */
@Suppress("ConstPropertyName")
object WFGRP {
    const val workflow = "Workflow"
}

/**
 * Declares the two workflow cfacts (issue #533) -- and **only** two, on purpose.
 *
 * Both are **target facts**: facts about the task being rendered, computed by [WfTaskFacts] and passed to the
 * registry's `assemble` beside the request's own facts, so neither has a request-scoped source here.
 * [WFC.taskComplete] has a real producer; [WFC.taskAvailable] is a placeholder that is always present until
 * availability rules exist, and its description says so. Eligibility, validity, "finished" and "reviewer" are
 * not declared: the registry is additive, so each costs nothing when something produces it, and a declared
 * name nothing produces reads as a capability the deployment does not have.
 */
fun addWorkflowCFacts(collector: SchemaCollector) {
    collector.addCFact(
        CFactDef(
            WFC.taskComplete, WFGRP.workflow,
            "True, about the task being rendered, when an entry is present for every trait the task requires. " +
                "Presence, not content: an entry with empty data counts.",
        ),
    )
    collector.addCFact(
        CFactDef(
            WFC.taskAvailable, WFGRP.workflow,
            "True, about the task being rendered, when it may be worked on now. **Always true today**: a " +
                "placeholder holding the shape until availability rules (dates, prior tasks) exist.",
        ),
    )
}
