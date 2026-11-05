package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactDef
import com.dynamicruntime.common.startup.SchemaCollector

/** The friendly label the workflow cfacts group under. */
@Suppress("ConstPropertyName")
object WFGRP {
    const val workflow = "Workflow"
}

/**
 * Declares the workflow task cfacts (issues #533, #785, #786) -- each one because something produces it -- and the
 * fact about the caller's relation to the form, [RCF.isOwner] (issue #1595), which [RowCfacts] produces.
 *
 * All five are **target facts**: facts about the task being rendered (or, for `isOwner`, the form), passed to the
 * registry's `assemble` beside the request's own facts, so none has a request-scoped source here. [WFC.taskComplete] and [WFC.isCta] come from
 * the status engine ([WfTaskFacts]); [WFC.reviewer] from a task's `viewerCfacts` functions (`userHasLabel`), which
 * is what earned it a declaration -- it was withheld until #786 gave it a producer; and [WFC.taskAvailable] is a
 * placeholder that is always present until availability rules exist, and its description says so.
 *
 * The rule that kept `reviewer` out still holds for everything else: eligibility and validity are not declared
 * as task facts, because the registry is additive -- a name costs nothing once something produces it, while a
 * declared name nothing produces reads as a capability the deployment does not have. (`finished` and
 * `needsReview` are declared, but as form-level singletons in `WSC`, not here.)
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
    collector.addCFact(
        CFactDef(
            WFC.isCta, WFGRP.workflow,
            "True, about the task being rendered, when it is the workflow's call to action: the earliest task, in " +
                "list order, that is not both complete and valid.",
        ),
    )
    collector.addCFact(
        CFactDef(
            WFC.reviewer, WFGRP.workflow,
            "True, about the task being rendered, when the person viewing it may review it -- concluded as the " +
                "task is shown, by the task's own functions (typically `userHasLabel`), and never stored.",
        ),
    )
    // A fact about the caller's relation to the form (issue #1595), not about a task, declared here beside the facts it
    // is judged with. Kept off the page: a field's `g-visibleWhen` may not name it, since a form's schema is about its
    // data whoever looks.
    collector.addCFact(
        CFactDef(
            RCF.isOwner, WFGRP.workflow,
            "True, about the form being worked on, when the caller owns it: its owning user, or in production another " +
                "user of the same person; on a test instance only the owning user, so one tester's personas can play " +
                "owner and reviewer. Worked out from the form's owner as it is read, and never stored. Named in a task's " +
                "`saveWhen` or `display`, a CTA filter, or an approval's `approveWhen`, and nowhere else.",
        ),
    )
}
