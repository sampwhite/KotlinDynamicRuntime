package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.offeredChoices
import com.dynamicruntime.common.schema.formRequirementFailures
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

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

/**
 * [checkInput], then the **form requirements** of a workflow's form (issue #1022) under its field [layouts]: a field
 * its layout requires, a choice it does not offer. Judged over the coerced values, as the backend judges what it
 * receives, by the same kernel rule ([formRequirementFailures]) the workflow's save and task status run -- so a page
 * never sends what its form would refuse, nor refuses what the backend would accept.
 */
fun checkFormInput(type: SchType, values: Map<String, Any?>, layouts: Map<String, SchLayout>): InputCheck {
    val check = checkInput(type, values)
    val form = formRequirementFailures(type, layouts, check.coerced as? Map<*, *> ?: values)
    return if (form.isEmpty()) check else InputCheck(check.failures + form, check.coerced)
}

/**
 * The choices a form's choice control lists for a field of type [vt] under its layout entry [entry] (issue #1022):
 * the choices the form offers ([offeredChoices]), and -- when the field already holds a [value] the schema allows but
 * the form does not offer, saved elsewhere -- that value too, labeled from the schema and marked, so it reads as what
 * it is rather than as a bare value. Null when the field has no closed list. Pure.
 */
fun formChoiceList(vt: SchType, entry: SchLayoutField?, value: Any?): List<SchOption>? {
    val offered = offeredChoices(vt, entry) ?: return null
    val held = value.toOptStr()?.takeIf { it.isNotBlank() } ?: return offered
    if (offered.any { it.value == held }) return offered
    val fromSchema = vt.options?.firstOrNull { it.value == held } ?: return offered
    return offered + SchOption(held, "${fromSchema.label} (not offered on this form)")
}
