package com.dynamicruntime.common.gedra.workflow

/**
 * The wire names of a form's **per-workflow** state (issue #794): the computed [workflowState] and the asserted
 * [workflowEngagement] beside it, both keyed by [WFD.workflowId].
 *
 * In `base:kernel` for the reason [SVY] is: a later slice draws these on the forms listing and the workflow
 * column, so the frontend reads them by name and a rename here breaks its compile rather than its runtime.
 *
 * ### Two traits, because they are two different kinds of fact
 *
 * A form's survey state is **form-singleton** -- one "is the survey done?" per form. A normal workflow is
 * **many-per-form**, so its state is keyed by the workflow it is about. That much is just the key. The reason
 * there are *two* traits is the state class:
 *
 *  - [workflowState] is `StateTraitClass.derived` -- a projection of the form's data against a workflow
 *    definition, which every recompute rebuilds from scratch and a batch job may overwrite freely.
 *  - [workflowEngagement] is `StateTraitClass.asserted` -- a person chose to put this form into this workflow,
 *    which is exactly the "human act a batch must never recompute away" the state classes were drawn for.
 *
 * Modelling engagement as its own asserted trait is what makes the recompute rule fall out of machinery that
 * already works: `recomputeDerivedStateUnderLock` preserves asserted entries verbatim and replaces derived
 * ones, so an engagement survives every recompute with no field-level carve-out, and a [workflowState] the
 * deriver stops emitting is implicitly deleted by the same whole-replace.
 */
@Suppress("ConstPropertyName")
object WFS {
    /** The bundle the two state traits are declared in: `gc.cd.global.workflowState`. */
    const val stateBundle = "workflowState"

    // --- the derived per-workflow projection ---

    /** Generated entry type of the [workflowState] trait. */
    const val workflowStateEntry = "WorkflowStateEntry"

    /** Trait id: one derived entry per workflow this form is being evaluated against. */
    const val workflowState = "workflowState"

    /**
     * The workflow revision this entry was computed against, as `WfRef` text -- the same bookkeeping
     * [SVY.computedAgainstSurveyRef] keeps, so a stale entry can be told from a current one.
     */
    const val computedAgainstRef = "computedAgainstRef"

    /**
     * Whether the form meets every eligibility test of the workflow (issue #783) -- true exactly when
     * [eligibilityFailures] is empty. Kept beside the list so a listing can filter on it without reading one.
     */
    const val eligible = "eligible"

    /**
     * The eligibility tests the form fails, in the workflow's declaration order (issue #783): each a
     * [workflowEligibilityFailure] naming the test by id. **Not the explanation text** -- that stays on the
     * definition and is found again by id when shown, so a reworded explanation reads correctly without a
     * recompute, and a stored failure never carries text in someone else's language.
     */
    const val eligibilityFailures = "eligibilityFailures"

    /**
     * The workflow's **own** cfacts, as its `cfactCalc` functions concluded them from the form's data (issue
     * #784) -- per workflow, so kept on its entry rather than in the form's set. Stored so a later evaluation
     * (the needsReview listing, #785) can work from state alone, without reading the form.
     */
    const val cfacts = "cfacts"

    /**
     * The framework singleton cfacts ([WSC]) this workflow contributes to the form (issue #784) -- the
     * **attribution** behind the form's merged set, which is what answers "which workflows need review?". Empty
     * unless the form is engaged with the workflow: only an engaged workflow contributes.
     */
    const val singletonCfacts = "singletonCfacts"

    /**
     * The workflow's **CTA** task id (issue #785): the earliest task, in list order, that is not both complete and
     * valid -- where the listing's link for an engaged workflow points. Absent when [tasksDone].
     */
    const val ctaTask = "ctaTask"

    /** The [ctaTask]'s general status: complete, valid, and the traits behind each ([workflowCtaStatus]). */
    const val ctaStatus = "ctaStatus"

    /** The named type of [ctaStatus]. */
    const val workflowCtaStatus = "WorkflowCtaStatus"

    /**
     * Every task is complete and valid, so there is no [ctaTask] (issue #785). Explicit rather than read from an
     * absent [ctaTask], which would be ambiguous with "not computed" -- the CTA is computed for engaged workflows
     * only, so a non-engaged entry carries neither.
     */
    const val tasksDone = "tasksDone"

    // --- approvals (issue #787) ---

    /** Generated entry type of the [workflowApproval] trait. */
    const val workflowApprovalEntry = "WorkflowApprovalEntry"

    /**
     * Trait id: one **asserted** entry per approved approval task, keyed by workflow and task (issue #787). Apart
     * from the derived [workflowState] -- an approval is a person's act, which no recompute may compute away.
     */
    const val workflowApproval = "workflowApproval"

    /** On an approval: the approval task it records; with [WFD.workflowId], the entry's primary key. */
    const val taskId = "taskId"

    /** On an approval: when it was approved. */
    const val approvedAt = "approvedAt"

    /** On an approval: the numeric userId of the reviewer who approved it. */
    const val approvedBy = "approvedBy"

    /** [kind] of the engagement event recording an approval; its [note] names the task. */
    const val approvedEvent = "approved"

    /** The named type of one [eligibilityFailures] element. */
    const val workflowEligibilityFailure = "WorkflowEligibilityFailure"

    /**
     * On a failure: the values captured when the test was evaluated, which its explanation may reference. Empty
     * until ambient capture arrives (the follow-up #783 names); declared now so a stored failure's shape does
     * not change when it does.
     */
    const val captured = "captured"

    // --- the asserted engagement ---

    /** Generated entry type of the [workflowEngagement] trait. */
    const val workflowEngagementEntry = "WorkflowEngagementEntry"

    /** Trait id: one asserted entry per workflow a person has put this form into. */
    const val workflowEngagement = "workflowEngagement"

    /**
     * Whether the form is currently engaged with the workflow. A field rather than mere entry presence, so
     * disengaging leaves the trail behind ([events]) instead of erasing that it ever happened.
     */
    const val engaged = "engaged"

    /**
     * When the form was last engaged with the workflow, and by whom ([lastEngagedBy]). Kept beside [engaged]
     * rather than only in a trail, so "since when, and who" is answerable without walking history.
     */
    const val lastEngagedAt = "lastEngagedAt"

    /** Numeric userId of whoever last engaged the form with the workflow. */
    const val lastEngagedBy = "lastEngagedBy"

    /**
     * The evolution of this form's relationship with this workflow, oldest first: an append-only trail of what
     * happened and who did it. Deliberately **open** -- the entry takes additional properties and an event's
     * [kind] is not a closed set -- because the later slices each add a milestone worth recording (approved,
     * finished) and none of them should need this trait redefined to say so.
     */
    const val events = "events"

    /** The named type of one [events] element. Named rather than inlined, the way `SiteAddress` is. */
    const val workflowEventEntry = "WorkflowEventEntry"

    /** On an event: what happened, e.g. [engagedEvent]. Open, so a later slice names its own milestone. */
    const val kind = "kind"

    /** On an event: when it happened. */
    const val at = "at"

    /** On an event: the numeric userId of whoever did it, when a person did. */
    const val by = "by"

    /** On an event: free text a producer wanted to record beside the milestone. */
    const val note = "note"

    // --- event kinds this slice writes; later slices add their own ---

    /** [kind] of the event recording that a form was put into the workflow. */
    const val engagedEvent = "engaged"

    /** [kind] of the event recording that a form was taken back out of the workflow. */
    const val disengagedEvent = "disengaged"
}

/**
 * The **framework singleton cfacts** a workflow may emit about a form (issue #784): a hardwired list, because
 * each carries code behavior -- a status chip, a listing, a search. A workflow maps its own cfacts onto these
 * (`WfDef.singletons`) and may emit nothing else; a client's cfact extensions are ignored for this purpose, since
 * no code would know what one meant.
 *
 * Emitted by an **engaged** workflow, merged into the form's single `cfacts` state set, and attributed on the
 * workflow's own state entry ([WFS.singletonCfacts]). In `base:kernel` so the frontend names them identically.
 */
@Suppress("ConstPropertyName")
object WSC {
    /** Some workflow the form is engaged with is waiting on a review. Drives the `Needs Review` chip (#789). */
    const val needsReview = "needsReview"

    /** Some workflow the form is engaged with has finished. Drives the `Finished` chip (#789). */
    const val finished = "finished"

    /** The friendly group these present under in the cfact catalog. */
    const val group = "Workflow"

    /** The whole list: what `WfDef.singletons` is checked against. */
    val all: Set<String> = setOf(needsReview, finished)
}
