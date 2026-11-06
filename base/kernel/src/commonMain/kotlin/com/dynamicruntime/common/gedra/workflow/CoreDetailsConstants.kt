package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The names the core details workflow's state and cfacts are stored and reported under (issue #657). Kept apart from
 * [WFC]: those are per-**task** target facts computed at render time; these are **form-singleton** facts about the
 * whole form, stored as `derived` state and read back through the state->cfact bridge.
 *
 * In the kernel (transpile-clean, alongside [WVF]/[WFD]/[WSF]) because both sides of the wire share it: the
 * backend writes the `coreDetailsCompletion` state and the two cfacts under these names, and the **frontend** reads
 * them back to render the forms-list status column (issue #694). The producer -- the state config and
 * the deriver -- stays in `base:common` (`CoreDetailsState.kt`); only these shared names live here.
 *
 * **The core details workflow gates nothing on its own** (issue #829). Complete core details surface as
 * [coreDetailsComplete], valid ones as [coreDetailsValid] -- form-singleton cfacts, and that is all. Whether a normal
 * workflow requires them is that workflow's own choice, made in its eligibility tests (the sample's audit review does);
 * a workflow whose tests do not name them may engage a form whose core details are unfinished.
 */
@Suppress("ConstPropertyName")
object CDF {
    /**
     * A form's core details have an entry for every trait the core details workflow requires. Positive on purpose (present == good): a form
     * with no computed state yet carries neither core details fact, which reads correctly as "not ready" rather than
     * -- as a negated `coreDetailsIncomplete` would -- falsely reading as complete. See the core details design doc.
     */
    const val coreDetailsComplete = "kdr:coreDetailsComplete"

    /** A form's present core details trait data passes its schema, ignoring missing required values. Positive, as [coreDetailsComplete]. */
    const val coreDetailsValid = "kdr:coreDetailsValid"

    /** The friendly group the two core details cfacts present under. */
    const val group = "Core details"

    /** The `coreDetailsCompletion` state trait's entry type: `kdr.core.CoreDetailsCompletionEntry`. */
    const val coreDetailsCompletionEntry = "CoreDetailsCompletionEntry"

    /** The `coreDetailsCompletion` state trait id -- a derived, form-singleton (unkeyed) projection. */
    const val coreDetailsCompletion = "kdr:coreDetailsCompletion"

    const val complete = "complete"
    const val valid = "valid"
    const val missingTraits = "missingTraits"
    const val invalidTraits = "invalidTraits"

    /** Under a [coreDetailsCompletion] entry: the core details revision the projection was computed against, as `WfRef` text. */
    const val computedAgainstCoreDetailsRef = "computedAgainstCoreDetailsRef"

    /** The config bundle the core details state trait is declared in. */
    const val stateBundle = "coreDetailsState"
}

/**
 * The statuses a form reads as in the forms list (issues #694, #789), as its chip shows them and as its filter takes
 * them (issue #695) -- one vocabulary on both sides of the wire. The first three are the core details workflow's, read
 * off its `coreDetailsCompletion` state ([coreDetailsStatusOf]); the last two are the framework singletons an engaged
 * workflow contributes, which take the place of [valid] ([formStatusOf], the one rule the chip and the filter share).
 */
@Suppress("ConstPropertyName")
object FSV {
    /** Every required core details trait present, the present data passes its schema, and nothing is pending or finished. */
    const val valid = "valid"

    /** Present data passes, but a required core details trait is missing. */
    const val needsInfo = "needsInfo"

    /** Present data fails its schema, whether or not something is also missing. */
    const val invalid = "invalid"

    /**
     * Valid, and a workflow the form is engaged with is waiting on a review (issue #789) -- the status the
     * [WSC.needsReview] cfact reads as. A status, so bare like its neighbors: the cfact is rooted (issue #952), and
     * [formStatusOf] maps one onto the other.
     */
    const val needsReview = "needsReview"

    /** Valid, and a workflow the form is engaged with has finished (issue #789) -- what [WSC.finished] reads as. */
    const val finished = "finished"
}

/**
 * A form's core details status from its state entries (issues #694, #695), or null when the form has no core details
 * state -- a client with no core details workflow, or a row not yet computed -- in which case a column shows nothing
 * and a filter matches nothing. Reads the [CDF.coreDetailsCompletion] entry's `complete` / `valid` booleans: **invalid
 * trumps incomplete** (`!valid` -> [FSV.invalid], else `!complete` -> [FSV.needsInfo], else [FSV.valid]), so data that
 * fails schema reads as invalid even when a required trait is also missing. Pure over maps, so the frontend's chip and
 * the backend's filter run the same code.
 */
fun coreDetailsStatusOf(states: List<Map<String, Any?>>): String? {
    val entry = states.firstOrNull { it[GE.traitId] == CDF.coreDetailsCompletion } ?: return null
    val data = entry[GE.data].toJsonMapOrEmpty()
    val valid = data[CDF.valid] as? Boolean ?: return null
    val complete = data[CDF.complete] as? Boolean ?: return null
    return when {
        !valid -> FSV.invalid
        !complete -> FSV.needsInfo
        else -> FSV.valid
    }
}

/**
 * The status a form's chip shows and its filter matches (issue #789): the core details workflow's
 * ([coreDetailsStatusOf]), except that where the core details workflow would read **Valid** the form's merged cfacts
 * may say more -- [FSV.needsReview] when some engaged workflow is waiting on a review (its call to action carries the
 * `review` keyword, the one thing that sets [WSC.needsReview], issue #1596), else [FSV.finished] when some has
 * finished. Only Valid gives way: Needs Info and Invalid still describe work the owner has to do, which trumps a
 * review or a finish. Needs Review beats Finished, since it is something waiting on someone. Null when the form has no
 * core details status, as [coreDetailsStatusOf] is.
 *
 * Pure over the state entries -- the form's cfacts are its [GT.cfacts] entry -- so the frontend's chip and the
 * backend's filter run the same code and cannot disagree.
 */
fun formStatusOf(states: List<Map<String, Any?>>): String? {
    val coreDetails = coreDetailsStatusOf(states) ?: return null
    if (coreDetails != FSV.valid) {
        return coreDetails
    }
    val facts = states.filter { it[GE.traitId] == GT.cfacts }
        .flatMap { it[GE.data].toJsonMapOrEmpty()[GT.facts].toJsonListOrEmpty() }
        .mapNotNull { it?.toString() }
        .toSet()
    return when {
        WSC.needsReview in facts -> FSV.needsReview
        WSC.finished in facts -> FSV.finished
        else -> FSV.valid
    }
}
