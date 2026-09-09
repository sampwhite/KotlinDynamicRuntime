package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchLayout
import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import web.cssom.ClassName

/**
 * The two form-level error strings a consumer-facing form-doc form shows (issue #641), each resolved from the
 * edited type's layout when it overrides it ([LAYSTR.formErrorSummary] / [LAYSTR.formErrorHint]), else the
 * surface's own default. [LAYSTR.formErrorSummary] is the heading over the internal failure list; [LAYSTR.formErrorHint] is
 * the one-liner shown in its place when the frontend is not in debug. A component sets either on the base type's
 * `g-layout`, a client overrides it on their variant -- so the wording can be altered without touching the
 * page. Pure, so a jsNodeTest can pin the override-vs-default rule the render below rests on.
 */
fun formErrorStrings(layout: SchLayout?, defaultSummary: String, defaultHint: String): Pair<String, String> {
    val strings = layout?.strings ?: emptyMap()
    return (strings[LAYSTR.formErrorSummary] ?: defaultSummary) to (strings[LAYSTR.formErrorHint] ?: defaultHint)
}

/**
 * Renders the validation-failure summary at the foot of a consumer-facing form-doc form (issue #641). The bad
 * fields are already marked inline -- `SchemaForm` colours them from the same [failures] -- so off [debug] this
 * shows only a one-line prompt pointing the consumer at them; the internal validator detail (each failure's path
 * and message) is developer-facing and appears only when the frontend is in debug. Both strings come from the
 * edited type's [layout] when it overrides them, else the [defaultSummary] / [defaultHint] the page passes (see
 * [formErrorStrings]). A no-op when there are no failures, so a caller may hand it the raw list.
 */
fun ChildrenBuilder.formFailureSummary(
    failures: List<SchFailure>,
    debug: Boolean,
    layout: SchLayout?,
    defaultSummary: String,
    defaultHint: String,
) {
    if (failures.isEmpty()) return
    val (summary, hint) = formErrorStrings(layout, defaultSummary, defaultHint)
    if (!debug) {
        // The fields are already flagged inline; a consumer needs the nudge, not the validator's internals.
        p {
            className = ClassName("error-text")
            +hint
        }
        return
    }
    // Debug: the internal list, unchanged -- each failure jumps to its field, the whole-form ones read as text.
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
