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

    /** Schema type name for what the automatic recalculation after a change did (issue #1741), on every result carrying it. */
    const val autoTypeName = "FormStatesAutoRecalc"
    const val autoTypeQualified = "${CLD.overviewNamespace}.$autoTypeName"

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

    // --- the automatic recalculation after a configuration change (issue #1741) ---------------------------------

    /**
     * On a configuration save's or publish's result: what the automatic recalculation of the forms the change reaches
     * did, in these names -- its [outcome] is one of the manual run's, or [tooMany], [manual], [off], or [noForms] when
     * none ran. Absent from the result of a change that does not ask for one (copy, the menu, a report).
     */
    const val auto = "formStatesAuto"

    /** The client holds more forms than [autoLimit]: they are brought up to date by the nightly run, or by hand. */
    const val tooMany = "tooMany"

    /** The client asks to bring its forms up to date only by hand (its definition's `manualFormStates`). */
    const val manual = "manual"

    /** The deployment recalculates no client automatically (its limit is 0). */
    const val off = "off"

    /** The client holds no forms, so there was nothing to bring up to date. */
    const val noForms = "noForms"

    /** The most forms the automatic recalculation takes, beside [total] when it took none for holding more. */
    const val autoLimit = "autoLimit"

    /** Which client's forms it was: the client's own, or for a draft its sandbox's. */
    const val client = "client"
}
