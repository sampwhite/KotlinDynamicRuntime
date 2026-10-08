package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.toOptStr

/*
 * A form's **requirements** (issue #1022): what one form asks for, beside what the data must be.
 *
 * A workflow does not alter schema. The schema says what stored data must be, everywhere; a form -- through its
 * field layout ([SchLayout]) -- may ask for more on its own pages: a field it requires though the schema does not
 * ([SchLayoutField.required]), and a restated, shorter list of the schema's choices ([SchLayoutField.choices]).
 * These never change what data is valid. They are checked where the form is the one saving or being judged -- the
 * workflow's save, its task status, and the page -- by the one rule here, so the three cannot disagree; a general
 * data edit (the raw editor, a form patch) never applies them.
 *
 * They apply **on top of** the schema's own rules for the data entered so far. Where the schema withdraws a field
 * for the current answers (the `else` side of its `if`/`then`/`else`), the form's `required` is ignored; and the
 * form offers only those of its choices the schema offers ([offeredChoices]). A form narrows what the schema
 * currently allows; it never brings back what the schema has withdrawn.
 */

/**
 * The choices a form offers for a field of type [vt] under its layout entry [field] (issue #1022): the schema's
 * choices when the entry restates none; otherwise the entry's, in its order and with its labels, kept only where the
 * schema offers the value. Null when the field has no closed list of choices.
 *
 * The schema's choices are fixed per field today. When it can offer different choices for different answers, this
 * is the one place the form's list meets the schema's current one, so a page and the backend still agree.
 */
fun offeredChoices(vt: SchType, field: SchLayoutField?): List<SchOption>? {
    val schema = vt.options ?: return null
    val restated = field?.choices ?: return schema
    val byValue = schema.associateBy { it.value }
    return restated.mapNotNull { choice -> byValue[choice.value]?.let { SchOption(choice.value, choice.label ?: it.label) } }
}

/**
 * The form requirements [data] -- a value of [type] -- does not meet, under the field layouts [layouts] (keyed by
 * qualified type name, as a schema store holds them): a field the form requires that is absent, as
 * [SchFailCode.missingRequired], and a choice the schema allows but the form does not offer, as
 * [SchFailCode.invalidOption] carrying the form's choices -- each marked [SchFailure.formRequirement]. Nested objects -- a `$ref` to a named type, an array of
 * them -- are judged under their own type's layout. [path] prefixes each failure's path, as the validator's do.
 *
 * Only what the form adds is reported: a value the schema itself refuses is the validator's failure, not this one.
 * Pure, and in the kernel, so the page runs the rule the backend runs.
 */
fun formRequirementFailures(
    type: SchType,
    layouts: Map<String, SchLayout>,
    data: Map<*, *>,
    path: String = "",
): List<SchFailure> {
    val out = mutableListOf<SchFailure>()
    collectFormFailures(type, layouts, data, path, out, depth = 0)
    return out
}

/** The deepest a value is walked for form requirements; matches the JSON nesting cap. */
private const val maxFormRuleDepth = 50

private fun collectFormFailures(
    type: SchType,
    layouts: Map<String, SchLayout>,
    data: Map<*, *>,
    path: String,
    out: MutableList<SchFailure>,
    depth: Int,
) {
    if (depth >= maxFormRuleDepth) return
    val layout = type.name?.let { layouts[it] }
    // What the schema withdraws for the answers given so far; the form asks nothing of it. And what the schema already
    // requires, whose absence is the validator's failure to report, not a second one here.
    val condition = type.condition
    val holds = condition?.holds(data) == true
    val withdrawn = condition?.forbiddenWhen(holds).orEmpty()
    val schemaRequires = type.required + condition?.requiredWhen(holds).orEmpty()
    for ((name, prop) in type.properties) {
        if (name in withdrawn) continue
        val vt = prop.valueType
        val value = data[name]
        val at = childPath(path, name)
        layout?.fieldFor(name)?.let { entry ->
            if (entry.required && name !in schemaRequires && isAbsentForForm(value)) {
                out.add(vt.failure(at, SchFailCode.missingRequired, "'$name' is required on this form.").copy(formRequirement = true))
            }
            // Blank is no answer, which `required` speaks to; only a given value can be one the form does not offer.
            val given = value.toOptStr()?.takeIf { it.isNotBlank() }
            if (entry.choices != null && given != null) {
                val offered = offeredChoices(vt, entry).orEmpty()
                val schemaAllows = vt.options?.any { it.value == given } == true
                if (schemaAllows && offered.none { it.value == given }) {
                    out.add(
                        vt.failure(at, SchFailCode.invalidOption, "'$given' is not offered on this form.", offered, value = given)
                            .copy(formRequirement = true),
                    )
                }
            }
        }
        collectNestedFormFailures(vt, layouts, value, at, out, depth)
    }
    // A map's entries (issue #1055): each is a value of the map's value type, judged under that type's layout.
    type.additionalValueType?.let { entryType ->
        for ((key, value) in data) {
            if (key !is String || key in type.properties) continue
            collectNestedFormFailures(entryType, layouts, value, childPath(path, key), out, depth)
        }
    }
}

/** The form failures inside [value], a nested object of [vt] or a list of them, reported under [at]. */
private fun collectNestedFormFailures(
    vt: SchType,
    layouts: Map<String, SchLayout>,
    value: Any?,
    at: String,
    out: MutableList<SchFailure>,
    depth: Int,
) {
    when (value) {
        is Map<*, *> if vt.holdsFields() -> collectFormFailures(vt, layouts, value, at, out, depth + 1)

        is List<*> -> vt.itemType?.takeIf { it.holdsFields() }?.let { item ->
            value.forEachIndexed { i, element ->
                if (element is Map<*, *>) collectFormFailures(item, layouts, element, indexPath(at, i), out, depth + 1)
            }
        }
    }
}

/** Whether a form holds nothing for a field: absent, null, or text with nothing in it. */
private fun isAbsentForForm(value: Any?): Boolean = value == null || (value is String && value.isBlank())
