package com.dynamicruntime.common.gedra.workflow

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
}
