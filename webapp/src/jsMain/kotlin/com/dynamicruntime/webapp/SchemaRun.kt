package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * The outcome of checking a set of form values against an endpoint's **input** type: the [failures] to show,
 * and the [coerced] payload the coercion produced.
 *
 * The coerced value is kept whether or not it validated, because the two callers want it in both states: a
 * clean check hands [payload] to the wire, while a failing one still renders the coerced text in a panel so the
 * complaint sits on the payload as it would actually be sent. [payload] is that same coerced value as a map,
 * but only when nothing failed -- a caller reads it and knows the read is safe to send.
 */
class InputCheck(val failures: List<SchFailure>, val coerced: Any?) {
    /** No failures: the form is valid against the endpoint's input schema. */
    val isValid: Boolean get() = failures.isEmpty()

    /** The coerced payload to send, or null when something failed (so it is never sent). */
    val payload: Map<String, Any?>? get() = if (isValid) coerced.toJsonMapOrEmpty() else null
}

/**
 * Coerces and validates [values] against [type] as a **request** (issue #254): a `g-derived` field is neither
 * demanded of the person filling the form in nor taken from them, which is what `forInput` selects -- the same
 * kernel validates responses elsewhere, where those fields are ordinary values, so the direction is passed
 * rather than inferred.
 *
 * `keepAdditionalProperties` keeps an undeclared key rather than dropping it: it is a failure either way, but a
 * form has to keep showing the key its error names, or the complaint points at something no longer on screen.
 * It never reaches the wire, because a failure stops the send.
 *
 * Pure -- the shared kernel does the work -- so both the endpoint catalog and the new-form page check input
 * identically, and the rule is covered by `jsNodeTest` rather than only by driving a browser.
 */
fun checkInput(type: SchType, values: Map<String, Any?>): InputCheck {
    val result = coerceAndValidate(type, values, SchOpts(keepAdditionalProperties = true, forInput = true))
    return InputCheck(result.failures, result.value)
}
