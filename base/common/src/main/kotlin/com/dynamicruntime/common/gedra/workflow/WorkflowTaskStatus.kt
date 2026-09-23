package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SchFailure

/**
 * One task's general status (issues #700, #785): whether its required traits are all present ([complete]) and
 * whether the present ones pass their schema ([valid]), with the traits behind each answer. [failures] keeps the
 * content failures themselves, for a surface that shows them (the task rail's tooltip).
 */
class WfTaskStatus(
    val complete: Boolean,
    val valid: Boolean,
    val missingTraits: List<String>,
    val invalidTraits: List<String>,
    val failures: Map<String, List<SchFailure>>,
) {
    /** Neither incomplete nor invalid: nothing here for a person to do. */
    val done: Boolean get() = complete && valid

    /** The stored form -- what the workflow state keeps as [WFS.ctaStatus], in the survey's own words. */
    fun toStateMap(): Map<String, Any?> = linkedMapOf(
        SVY.complete to complete,
        SVY.valid to valid,
        SVY.missingTraits to missingTraits,
        SVY.invalidTraits to invalidTraits,
    )
}

/**
 * The **one** computation of task status and of a workflow's **CTA** (call to action, issue #785) -- shared by
 * the per-workflow deriver that stores it, the workflow view's task rail and `focusTask`, and the `wfIsCta` task
 * cfact, so none of them can disagree about where a person's next piece of work is.
 *
 * Presence from the same engine every completeness check uses ([WfEngine]); content from the survey's one
 * validity rule ([surveyContentFailures]), so "valid" means what it means on a save.
 */
object WorkflowTaskStatus {
    /** [task]'s status over the form's [entries] (only the task's own traits' entries matter). */
    fun of(cxt: KdrCxt, client: String, task: WfTask, entries: List<Map<String, Any?>>): WfTaskStatus {
        val missing = WfEngine.missingTraits(task.requiredTraitIds, entries)
        val failures = surveyContentFailures(cxt, client, task.traits.map { it.traitId }.toSet(), entries)
        return WfTaskStatus(missing.isEmpty(), failures.isEmpty(), missing, failures.keys.toList(), failures)
    }

    /**
     * The CTA among [statuses] (in task order): the **earliest** task that is not both complete and valid, or null
     * when every task is done. Earliest rather than, say, the most incomplete, because a workflow's tasks are
     * ordered as the work is done -- the first unfinished one is where to go next.
     *
     * A task with no traits is complete and valid by this rule, so it is never the CTA; an approval task (issue
     * #787) will need its approval, not its traits, to decide that.
     */
    fun <T> cta(statuses: List<Pair<T, WfTaskStatus>>): Pair<T, WfTaskStatus>? = statuses.firstOrNull { !it.second.done }

    /**
     * [def]'s CTA task and its status over the form's [entries], or null when every task is done. Lazy: tasks past
     * the CTA are never validated, since the deriver runs this on every write.
     */
    fun ctaOf(cxt: KdrCxt, client: String, def: WfDef, entries: List<Map<String, Any?>>): Pair<WfTask, WfTaskStatus>? =
        def.tasks.asSequence().map { it to of(cxt, client, it, entries) }.firstOrNull { !it.second.done }
}
