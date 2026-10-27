package com.dynamicruntime.common.gedra

/**
 * The wire names of recalculating a client's form states from its page (issue #1412): bringing the state its forms
 * store -- which workflows they are in, and where -- up to date with the client's configuration now, rather than
 * waiting for the nightly recalculation. Run synchronously, so only for a client holding no more forms than [limit]
 * says.
 */
@Suppress("ConstPropertyName")
object FSR {
    const val resultTypeName = "FormStatesRecalcResult"

    /**
     * On a client's overview row: the most forms a recalculation from its page may take. A client holding more is
     * brought up to date by the nightly recalculation alone.
     */
    const val limit = "formStatesRecalcLimit"

    /**
     * On a client's overview row: how many forms a recalculation would look at -- what [limit] is held against, and
     * so what the page compares with it.
     */
    const val count = "formStatesRecalcCount"

    /** What became of the recalculation: [completed], [stopped], or [busy]. */
    const val outcome = "outcome"

    /** Every form was looked at, and the out-of-date ones recalculated. */
    const val completed = "completed"

    /** The recalculation stopped before it had looked at every form; [reason] says why. */
    const val stopped = "stopped"

    /** Another recalculation of the client's forms was running -- the nightly one, or another's -- so nothing ran. */
    const val busy = "busy"

    /** How many forms had their state recalculated and stored. */
    const val updated = "updated"

    /** How many forms were already current, and were left as they were. */
    const val current = "current"

    /** How many forms' recalculated state could not be stored; the job's exceptions name them. */
    const val failed = "failed"

    /** How many forms the recalculation was to look at. */
    const val total = "total"

    /** Why the recalculation stopped, or why nothing ran; absent when it completed. */
    const val reason = "reason"
}
