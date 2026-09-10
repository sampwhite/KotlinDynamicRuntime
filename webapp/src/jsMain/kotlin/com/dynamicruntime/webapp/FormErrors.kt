package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchType
import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import web.cssom.ClassName

/**
 * The trait-data-type layouts a form-doc form actually renders (issue #641), in the union's declared order. A
 * `g-layout` -- and so a form-level string override -- can be declared only on an **object** type, and for a
 * form that means a trait's data type (`layout {}` sits inside `trait(...)`); the form's own type is a generated
 * envelope (`PatchTarget` on edit, the create-input on create) that carries none. So the overridable strings are
 * reached through the traits the form renders, not the envelope. Navigated structurally: the envelope's one
 * array-of-union property is the entries/edits list, each branch a trait entry/edit whose [GE.data] is the trait
 * data type the layout is keyed by. Empty when the type is not shaped this way or no trait carries a layout.
 */
fun formTraitLayouts(rootType: SchType?, layouts: Map<String, SchLayout>): List<SchLayout> {
    val union = rootType?.properties?.values
        ?.firstNotNullOfOrNull { p -> p.valueType.itemType?.takeIf { it.variants != null } }
        ?: return emptyList()
    return union.variants?.branches.orEmpty().mapNotNull { branch ->
        branch.properties[GE.data]?.valueType?.name?.let { layouts[it] }
    }
}

/**
 * The two form-level error strings a consumer-facing form-doc form shows (issue #641): the
 * [LAYSTR.formErrorSummary] heading over the internal failure list and the [LAYSTR.formErrorHint] one-liner
 * shown in its place off debug. Each is the first override any of the form's [layouts] sets (see
 * [formTraitLayouts]), else the surface's own default -- so a client alters the wording by setting it in one of
 * their traits' `g-layout`, the only layout a form's rendered types actually carry. Pure, so a jsNodeTest can
 * pin the override-vs-default rule and the reach through the type tree that the render below rests on.
 */
fun formErrorStrings(layouts: List<SchLayout>, defaultSummary: String, defaultHint: String): Pair<String, String> {
    val summary = layouts.firstNotNullOfOrNull { it.strings[LAYSTR.formErrorSummary] } ?: defaultSummary
    val hint = layouts.firstNotNullOfOrNull { it.strings[LAYSTR.formErrorHint] } ?: defaultHint
    return summary to hint
}

/**
 * Renders the validation-failure summary at the foot of a consumer-facing form-doc form (issue #641). The bad
 * *fields* are already marked inline -- `SchemaForm` colours them from the same [failures] -- so off [debug] a
 * field-level failure collapses to a one-line prompt pointing the consumer at those marks. A **whole-form**
 * failure (empty path) names no field and is marked nowhere, so its own message is always shown, in or out of
 * debug -- otherwise a cross-field or schema-level failure would leave the consumer stuck with nothing to fix.
 * In debug the full internal list (each failure's path and message) is shown, as before. Both strings come from
 * the form's rendered trait [layouts] when one overrides them, else the [defaultSummary] / [defaultHint] the
 * page passes. A no-op when there are no failures, so a caller may hand it the raw list.
 */
fun ChildrenBuilder.formFailureSummary(
    failures: List<SchFailure>,
    debug: Boolean,
    layouts: List<SchLayout>,
    defaultSummary: String,
    defaultHint: String,
) {
    if (failures.isEmpty()) return
    val (summary, hint) = formErrorStrings(layouts, defaultSummary, defaultHint)
    if (!debug) {
        val (wholeForm, fieldLevel) = failures.partition { it.path.isEmpty() }
        // A field-level failure is already flagged inline; the consumer needs the nudge, not the validator's
        // internals. Only shown when there is such a failure, so a whole-form-only case is not told to look for a
        // highlight that is not there.
        if (fieldLevel.isNotEmpty()) {
            p {
                className = ClassName("error-text")
                +hint
            }
        }
        // A whole-form failure has no field to mark, so its message is the only account the consumer gets.
        wholeForm.forEach { f ->
            p {
                className = ClassName("error-text")
                +"${f.message}${choicesSuffix(f)}"
            }
        }
        return
    }
    // Debug: the internal list, unchanged -- each field failure jumps to its field, whole-form ones read as text.
    h2 { +summary }
    failures.forEach { f ->
        p {
            className = ClassName("error-text")
            val text = "${f.path.ifEmpty { "(whole form)" }}: ${f.message}${choicesSuffix(f)}"
            if (f.path.isEmpty()) {
                +text
            } else {
                button {
                    className = ClassName("failure-jump")
                    asDynamic()["type"] = "button"
                    onClick = { focusField(f.path) }
                    +text
                }
            }
        }
    }
}
