package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.UNQ
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.fieldLabelOf
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

/**
 * The way to the field at [path] of a form of [type] holding [values], as the form draws it with [opts]: each step's label
 * ("Venue, City"), and an element of a list by the form's heading for it ("Co-speaker 2, Email"). The list's own label goes
 * before a heading that is only the form's default ("Lines, Entry 3"), which does not say what it is an entry of. Empty for
 * the whole form. Pure, and covered under `jsNodeTest`.
 */
fun failureLabel(type: SchType, values: Map<String, Any?>, path: String, opts: FormOpts): String {
    val steps = pathStep.findAll(path).map { it.groupValues[1].ifEmpty { it.groupValues[2].toInt() } }.toList()
    val labels = mutableListOf<String>()
    var at: SchType? = type
    var value: Any? = values
    var i = 0
    while (i < steps.size) {
        val step = steps[i++]
        if (step is Int) {
            labels.add("${step + 1}")
            at = at?.itemType
            value = value.toJsonListOrEmpty().getOrNull(step)
            continue
        }
        val name = step.toString()
        val data = value.toJsonMapOrEmpty()
        val owner = at?.variants?.let { it.select(data[it.discriminator].toOptStr()) } ?: at
        val prop = owner?.properties?.get(name)
        val copy = owner?.let { layoutCopy(it, name, data, opts) }
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
        val headings = elements.mapIndexed { n, element ->
            elementRowHeader(elementType, element.toJsonMapOrEmpty(), n, true, opts.fieldLayouts, entryLabelFor(copy, opts))
        }
        val heading = headings.getOrNull(index)
        if (heading == null || copy?.entry?.entryLabel == null && heading == entryHeading(opts.entryLabel, index)) labels.add(label)
        labels.add(heading?.let { distinctHeadings(headings)[index] } ?: entryHeading(opts.entryLabel, index))
        at = elementType
        value = elements.getOrNull(index)
    }
    return labels.joinToString(", ")
}

/**
 * What a refused write said is wrong with an entry (issue #1918): the trait, where in its data, the failure's code, and the
 * message, the schema author's when there is one. [value] is the one refused, when the refusal says.
 */
data class RefusedFailure(val traitId: String, val path: String, val code: String, val message: String, val value: String? = null)

/** The failures of entries [e] carries, when it is a write's refusal that says; else none. */
fun refusedFailures(e: Throwable): List<RefusedFailure> =
    (e as? ApiError)?.extraData?.get(EP.failures).toJsonListOfMaps().mapNotNull { f ->
        RefusedFailure(
            traitId = f[GE.traitId].toOptStr() ?: return@mapNotNull null,
            path = pathInData(f[EP.failurePath].toOptStr().orEmpty()),
            code = f[EP.failureCode].toOptStr().orEmpty(),
            message = f[EP.failureUserMessage].toOptStr() ?: f[EP.failureMessage].toOptStr().orEmpty(),
            value = f[UNQ.value].toOptStr(),
        )
    }

/** [path] of a failure of an entry, from the entry's data, which is what a form of one trait draws. */
fun pathInData(path: String): String = if (path == GE.data) "" else path.removePrefix("${GE.data}.")

/**
 * What to show for a save that failed with [e] (issue #1918): the failures it says, each with [labelOf] -- what this page
 * calls its field -- in front; as [userFacingError] has it for a failure that says none.
 */
fun saveError(e: Throwable, labelOf: (RefusedFailure) -> String): DisplayError {
    val shown = userFacingError(e)
    val refused = refusedFailures(e)
    if (refused.isEmpty()) return shown
    val ref = (e as? ApiError)?.traceId?.let { " (ref: $it)" }.orEmpty()
    return DisplayError(refused.joinToString(" ") { labeledMessage(labelOf(it), it.message) } + ref, DisplayError.Kind.message)
}

/** What a page says beside Save when the refusal is shown under the field it is about. */
val refusedAtField: DisplayError = DisplayError.expected("See the field marked above.")

/**
 * The way to [failure]'s field on a form whose [values] hold a [list] of entries (issue #1918): the trait's name as the form
 * heads its section, and then the field as it labels it. [inputType] is the form's, and [opts] how it is drawn. Pure, and
 * covered under `jsNodeTest`.
 */
fun entryFailureLabel(inputType: SchType?, values: Map<String, Any?>, list: String, failure: RefusedFailure, opts: FormOpts): String {
    val variants = inputType?.properties?.get(list)?.valueType?.itemType?.variants
    val name = variants?.let { friendlyBranchLabel(it, failure.traitId, opts.fieldLayouts) } ?: failure.traitId
    val type = entryDataType(inputType, list, failure.traitId)
    val data = values[list].toJsonListOfMaps().firstOrNull { it[GE.traitId] == failure.traitId }?.get(GE.data).toJsonMapOrEmpty()
    val way = type?.let { failureLabel(it, data, failure.path, opts) } ?: failure.path
    return listOf(name, way).filter { it.isNotEmpty() }.joinToString(", ")
}
