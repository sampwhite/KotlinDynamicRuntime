package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.UNQ
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.failure
import com.dynamicruntime.common.schema.fieldLabelOf
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/*
 * Naming a failure's field where it is shown away from it (issue #1918): a validation message names no field, since it is
 * read under one, so a place that lists failures puts in front what its own form calls each.
 */

/** [message] with [label] in front, as a list of failures shows it; the message alone when there is no label. */
fun labeledMessage(label: String, message: String): String = if (label.isEmpty()) message else "$label: $message"

private val pathStep = Regex("""([^.\[\]]+)|\[(\d+)\]""")

/** Where a failure is on a form (issue #1918): the way to its [label], the field, as the form draws it, and what it holds. */
private class FailurePlace(
    val label: String,
    val name: String,
    val prop: SchProperty?,
    val copy: LayoutCopy?,
    /** The type of the value at fault: the field's, or a list's element's. */
    val type: SchType?,
    val value: Any?,
)

private fun placeOf(type: SchType, values: Map<String, Any?>, path: String, opts: FormOpts): FailurePlace {
    val steps = pathStep.findAll(path).map { it.groupValues[1].ifEmpty { it.groupValues[2].toInt() } }.toList()
    val labels = mutableListOf<String>()
    var at: SchType? = type
    var value: Any? = values
    var name = ""
    var prop: SchProperty? = null
    var copy: LayoutCopy? = null
    var i = 0
    while (i < steps.size) {
        val step = steps[i++]
        if (step is Int) {
            labels.add("${step + 1}")
            at = at?.itemType
            value = value.toJsonListOrEmpty().getOrNull(step)
            continue
        }
        name = step.toString()
        val data = value.toJsonMapOrEmpty()
        val owner = at?.variants?.let { it.select(data[it.discriminator].toOptStr()) } ?: at
        prop = owner?.properties?.get(name)
        copy = owner?.let { layoutCopy(it, name, data, opts) }
        // A key of a map is data, shown as it is.
        val label = if (prop == null && owner?.additionalValueType != null) name else fieldLabelOf(name, prop, copy?.label)
        val fieldType = prop?.valueType ?: owner?.additionalValueType
        value = data[name]
        val elementType = fieldType?.itemType
        val index = steps.getOrNull(i) as? Int
        if (elementType == null || index == null) {
            labels.add(label)
            at = fieldType
            continue
        }
        i++
        val elements = value.toJsonListOrEmpty()
        val entryLabel = entryLabelFor(copy, opts)
        val headings = elements.mapIndexed { n, element ->
            elementRowHeader(elementType, element.toJsonMapOrEmpty(), n, true, opts.fieldLayouts, entryLabel)
        }
        val heading = headings.getOrNull(index)
        if (heading == null || copy?.entry?.entryLabel == null && heading == entryHeading(opts.entryLabel, index)) labels.add(label)
        labels.add(heading?.let { distinctHeadings(headings)[index] } ?: entryHeading(entryLabel, index))
        at = elementType
        value = elements.getOrNull(index)
    }
    return FailurePlace(labels.joinToString(", "), name, prop, copy, at, value)
}

/**
 * The way to the field at [path] of a form of [type] holding [values], as the form draws it with [opts]: each step's label
 * ("Venue, City"), and an element of a list by the form's heading for it ("Co-speaker 2, Email"). The list's own label goes
 * before a heading that is only the form's default ("Lines, Entry 3"), which does not say what it is an entry of. Empty for
 * the whole form. Pure, and covered under `jsNodeTest`.
 */
fun failureLabel(type: SchType, values: Map<String, Any?>, path: String, opts: FormOpts): String =
    placeOf(type, values, path, opts).label

/**
 * What the server said is wrong with an entry (issue #1918), in a refused write or a workflow's problems: the trait, where
 * in its data, the failure's code, the platform's [message] and the schema author's, the choices there are, and the entry's
 * key when its trait may have several. [value] is the one refused, when it says.
 */
data class RefusedFailure(
    val traitId: String,
    val path: String,
    val code: String,
    val message: String,
    val userMessage: String? = null,
    val options: List<SchOption>? = null,
    val value: String? = null,
    val entryKey: Map<String, Any?> = emptyMap(),
)

/** [f], a failure of an entry as the wire carries it. */
fun refusedFailure(traitId: String, f: Map<String, Any?>): RefusedFailure = RefusedFailure(
    traitId = traitId,
    path = pathInData(f[EP.failurePath].toOptStr().orEmpty()),
    code = f[EP.failureCode].toOptStr().orEmpty(),
    message = f[EP.failureMessage].toOptStr().orEmpty(),
    userMessage = f[EP.failureUserMessage].toOptStr(),
    options = f[EP.failureOptions].toJsonListOfMaps().ifEmpty { null }?.map {
        val value = it[SCH.value].toOptStr().orEmpty()
        SchOption(value, it[SCH.label].toOptStr() ?: value)
    },
    value = f[UNQ.value].toOptStr(),
    entryKey = f[EP.failureEntryKey].toJsonMapOrEmpty(),
)

/** The failures of entries [e] carries, when it is a write's refusal that says; else none. */
fun refusedFailures(e: Throwable): List<RefusedFailure> =
    (e as? ApiError)?.extraData?.get(EP.failures).toJsonListOfMaps().mapNotNull { f ->
        f[GE.traitId].toOptStr()?.let { refusedFailure(it, f) }
    }

/** [path] of a failure of an entry, from the entry's data, which is what a form of one trait draws. */
fun pathInData(path: String): String = if (path == GE.data) "" else path.removePrefix("${GE.data}.")

/**
 * What a form of [type] holding [values] says of [failure], drawn with [opts], as it would under the field: the layout's
 * message for it, else the schema author's with the form's own label, else the platform's. The one the server worded stands
 * when a message needs a value this form does not have. Pure, and covered under `jsNodeTest`.
 */
fun failureWords(type: SchType, values: Map<String, Any?>, failure: RefusedFailure, opts: FormOpts): String {
    val place = placeOf(type, values, failure.path, opts)
    val code = SchFailCode.entries.firstOrNull { it.name == failure.code }
    val told = code?.let { SchFailure(failure.path, it, failure.message, failure.options, userMessage = failure.userMessage) }
        ?: return failure.userMessage ?: failure.message
    val given = failure.value ?: place.value
    // A template that could not be filled in is shown as written, which is no message for a reader.
    fun filled(text: String?): String? = text?.takeUnless { $$"${" in it }
    val prop = place.prop
    if (prop != null) {
        filled(layoutErrorMessage(place.copy, prop, place.name, given, told))?.let { return it }
        val worded = place.type?.failure(failure.path, code, failure.message, failure.options, given)
            ?.called(fieldLabelOf(place.name, prop, place.copy?.label))
        filled(worded?.userMessage)?.let { return it + choicesSuffix(told) }
    }
    return (failure.userMessage ?: failure.message) + choicesSuffix(told)
}

/**
 * What to show for a save that failed with [e] (issue #1918): the failures it says, each as [textOf] has it -- with what this
 * page calls its field in front; as [userFacingError] has it for a failure that says none.
 */
fun saveError(e: Throwable, textOf: (RefusedFailure) -> String): DisplayError {
    val shown = userFacingError(e)
    val refused = refusedFailures(e)
    if (refused.isEmpty()) return shown
    val ref = (e as? ApiError)?.traceId?.let { " (ref: $it)" }.orEmpty()
    return DisplayError(refused.joinToString(" ") { textOf(it) } + ref, DisplayError.Kind.message)
}

/** What a page says beside Save while a refusal is shown under the field it is about ([UniqueChecks.refusedShown]). */
val refusedAtField: DisplayError = DisplayError.expected("See the field marked above.")

/** The index, in [values]' [list] of entries, of the one [failure] is about: its trait's, or the one with its key. */
private fun entryIndexOf(values: Map<String, Any?>, list: String, failure: RefusedFailure): Int =
    values[list].toJsonListOfMaps().indexOfFirst { entry ->
        val data = entry[GE.data].toJsonMapOrEmpty()
        entry[GE.traitId] == failure.traitId && failure.entryKey.all { (k, v) -> data[k].fmt() == v.fmt() }
    }

/**
 * What a form whose [values] hold a [list] of entries says of [failure] (issue #1918): the entry as the form heads its
 * section, its key with it when its trait may have several ("Yearly — 2024"), then the field as it labels it, then the
 * words. [inputType] is the form's, and [opts] how it is drawn. Pure, and covered under `jsNodeTest`.
 */
fun entryFailureText(inputType: SchType?, values: Map<String, Any?>, list: String, failure: RefusedFailure, opts: FormOpts): String {
    val variants = inputType?.properties?.get(list)?.valueType?.itemType?.variants
    val trait = variants?.let { friendlyBranchLabel(it, failure.traitId, opts.fieldLayouts) } ?: failure.traitId
    val key = failure.entryKey.values.joinToString(", ") { displayValue(it) }
    val name = if (key.isEmpty()) trait else "$trait — $key"
    val type = entryDataType(inputType, list, failure.traitId) ?: return labeledMessage(
        listOf(name, failure.path).filter { it.isNotEmpty() }.joinToString(", "), failure.userMessage ?: failure.message,
    )
    val data = values[list].toJsonListOfMaps().getOrNull(entryIndexOf(values, list, failure))?.get(GE.data).toJsonMapOrEmpty()
    val label = listOf(name, failureLabel(type, data, failure.path, opts)).filter { it.isNotEmpty() }.joinToString(", ")
    return labeledMessage(label, failureWords(type, data, failure, opts))
}

/**
 * The path, in a form of [inputType] whose [values] hold a [list] of entries, of [field] of the entry of [traitId], when the
 * form draws it with [opts]; null otherwise. Pure, and covered under `jsNodeTest`.
 */
fun drawnEntryField(inputType: SchType?, values: Map<String, Any?>, list: String, traitId: String, field: String, opts: FormOpts): String? {
    val entries = values[list].toJsonListOfMaps()
    val at = entries.indexOfFirst { it[GE.traitId] == traitId }.takeIf { it >= 0 } ?: return null
    val type = entryDataType(inputType, list, traitId) ?: return null
    return "$list[$at].${GE.data}.$field".takeIf { drawsField(type, entries[at][GE.data].toJsonMapOrEmpty(), field, opts) }
}

/** How a page draws a form of this catalog's types, for naming its fields and asking which it draws (issue #1918). */
fun Catalog.formOpts(entryLabel: String): FormOpts = FormOpts(
    friendly = true, gateAllows = buildCfactGate(cfacts), fieldLayouts = fieldLayouts, countersOnSave = countersOnSave,
    entryLabel = entryLabel,
)
