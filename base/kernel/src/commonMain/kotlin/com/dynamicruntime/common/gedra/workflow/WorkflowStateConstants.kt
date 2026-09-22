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
