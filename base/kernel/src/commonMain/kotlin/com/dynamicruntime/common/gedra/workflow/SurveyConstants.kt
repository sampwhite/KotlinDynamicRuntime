package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The names the survey's state and cfacts are stored and reported under (issue #657). Kept apart from [WFC]:
 * those are per-**task** target facts computed at render time; these are **form-singleton** facts about the
 * whole form, stored as `derived` state and read back through the state->cfact bridge.
 *
 * In the kernel (transpile-clean, alongside [WVF]/[WFD]/[WSF]) because both sides of the wire share it: the
 * backend writes the `surveyCompletion` state and the two cfacts under these names, and the **frontend** reads
 * them back to render the forms-list survey-status column (issue #694). The producer -- the state config and
 * the deriver -- stays in `base:common` (`SurveyState.kt`); only these shared names live here.
 */
@Suppress("ConstPropertyName")
object SVY {
    /**
     * A form's survey has an entry for every trait it requires. Positive on purpose (present == good): a form
     * with no computed state yet carries neither survey fact, which reads correctly as "not ready" rather than
     * -- as a negated `surveyIncomplete` would -- falsely reading as complete. See the survey design doc.
     */
    const val surveyComplete = "surveyComplete"

    /** A form's present survey-trait data passes its schema, ignoring missing required values. Positive, as [surveyComplete]. */
    const val surveyValid = "surveyValid"

    /** The friendly group the two survey cfacts present under. */
    const val group = "Survey"

    /** The `surveyCompletion` state trait's entry type: `globalconfig.SurveyCompletionEntry`. */
    const val surveyCompletionEntry = "SurveyCompletionEntry"

    /** The `surveyCompletion` state trait id -- a derived, form-singleton (unkeyed) projection. */
    const val surveyCompletion = "surveyCompletion"

    const val complete = "complete"
    const val valid = "valid"
    const val missingTraits = "missingTraits"
    const val invalidTraits = "invalidTraits"

    /** Under a [surveyCompletion] entry: the survey revision the projection was computed against, as `WfRef` text. */
    const val computedAgainstSurveyRef = "computedAgainstSurveyRef"

    /** The config bundle the survey state trait is declared in. */
    const val stateBundle = "surveyState"

    /**
     * The forms-listing query parameter that filters by a form's survey status (issue #695), taking one of the
     * [SVYS] values. Named for the survey rather than as a generic "status": a later form-singleton status gets
     * a parameter of its own, and the listing composes them.
     */
    const val surveyStatus = "surveyStatus"
}

/**
 * The three survey statuses a form's `surveyCompletion` state reads as (issue #694), as the forms list shows
 * them and as its filter takes them (issue #695) -- one vocabulary on both sides of the wire. Derived by
 * [surveyStatusOf], the one rule the status column and the backend filter share.
 */
@Suppress("ConstPropertyName")
object SVYS {
    /** Every required survey trait present, and the present data passes its schema. */
    const val valid = "valid"

    /** Present data passes, but a required survey trait is missing. */
    const val needsInfo = "needsInfo"

    /** Present data fails its schema, whether or not something is also missing. */
    const val invalid = "invalid"

    /** The three, in the order a control offers them. */
    val all: List<String> = listOf(valid, needsInfo, invalid)
}

/**
 * A form's survey status from its state entries (issues #694, #695), or null when the form has no survey state
 * -- a client with no survey, or a row not yet computed -- in which case a column shows nothing and a filter
 * matches nothing. Reads the [SVY.surveyCompletion] entry's `complete` / `valid` booleans: **invalid trumps
 * incomplete** (`!valid` -> [SVYS.invalid], else `!complete` -> [SVYS.needsInfo], else [SVYS.valid]), so data
 * that fails schema reads as invalid even when a required trait is also missing. Pure over maps, so the
 * frontend's chip and the backend's filter run the same code.
 */
fun surveyStatusOf(states: List<Map<String, Any?>>): String? {
    val entry = states.firstOrNull { it[GE.traitId] == SVY.surveyCompletion } ?: return null
    val data = entry[GE.data].toJsonMapOrEmpty()
    val valid = data[SVY.valid] as? Boolean ?: return null
    val complete = data[SVY.complete] as? Boolean ?: return null
    return when {
        !valid -> SVYS.invalid
        !complete -> SVYS.needsInfo
        else -> SVYS.valid
    }
}
