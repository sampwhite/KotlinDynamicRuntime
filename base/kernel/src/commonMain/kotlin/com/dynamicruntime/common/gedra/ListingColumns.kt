package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.ScriptEnv
import com.dynamicruntime.common.util.analyzeTemplate
import com.dynamicruntime.common.util.loneFieldPath
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr

/**
 * One column of a forms listing as the listing's summary says it (issue #1323): its name [columnId] (the usage's
 * [ClientTraitUsage.columnKey], issue #1490), the [traitId] it reads, its [label], the [kind] its value sorts and
 * searches by, the [presentation] the cell reads through, if the client gave one, and the [choices] its field offers
 * (issue #1500). Said once for the listing ([GDF.columns]); each row carries only its value, under the same [columnId].
 */
class ListingColumn(
    val columnId: String,
    val traitId: String,
    val label: String,
    val kind: UsageKind,
    val presentation: String?,
    /** The choices its field offers ([deducedColumnChoices]), in their declared order; empty when there are none. */
    val choices: List<SchOption> = emptyList(),
) {
    /** This column on the wire, keyed by [UF]: the presentation and the choices only when there are some. */
    fun toMap(): Map<String, Any?> = buildMap {
        put(UF.columnId, columnId)
        put(UF.traitId, traitId)
        put(UF.label, label)
        put(UF.kind, kind.name)
        presentation?.let { put(UF.presentation, it) }
        if (choices.isNotEmpty()) put(UF.choices, choices.map { mapOf(UF.value to it.value, UF.label to it.label) })
    }

    companion object {
        /** The column [usage] draws, offering [choices]. */
        fun of(usage: ClientTraitUsage, choices: List<SchOption> = emptyList()): ListingColumn =
            ListingColumn(usage.columnKey, usage.traitId, usage.label, usage.kind, usage.presentation, choices)

        /** The columns of a listing's [GDF.columns], in order; a kind the kernel does not know reads as text. */
        fun listFrom(wire: Any?): List<ListingColumn> = wire.toJsonListOfMaps().map { m ->
            ListingColumn(
                columnId = m[UF.columnId].toOptStr().orEmpty(),
                traitId = m[UF.traitId].toOptStr().orEmpty(),
                label = m[UF.label].toOptStr().orEmpty(),
                kind = UsageKind.entries.firstOrNull { it.name == m[UF.kind].toOptStr() } ?: UsageKind.string,
                presentation = m[UF.presentation].toOptStr()?.ifBlank { null },
                choices = m[UF.choices].toJsonListOfMaps().mapNotNull { c ->
                    c[UF.value].toOptStr()?.let { value -> SchOption(value, c[UF.label].toOptStr() ?: value) }
                },
            )
        }
    }
}

/**
 * The choices a column offers (issue #1500), deduced from the trait's [dataType] when the column's [display] is a lone
 * `${field}`: the column's value is then that field's, so its schema says what the value can be -- the field's
 * options, with their labels, or `true` and `false` for a boolean. In their declared order, which is how the column
 * sorts. Empty for a computed display, a field the type does not declare, or one with neither, and for a column that
 * is not text: a number or date column is searched by a range, which choices do not help.
 */
fun deducedColumnChoices(display: String, kind: UsageKind, dataType: SchType?): List<SchOption> {
    if (kind != UsageKind.string || dataType == null) return emptyList()
    val path = display.loneFieldPath() ?: return emptyList()
    var field: SchType = dataType
    for (segment in path) {
        field = field.properties[segment]?.valueType ?: return emptyList()
    }
    return when {
        !field.options.isNullOrEmpty() -> field.options
        field.jsonType == SCT.boolean -> booleanChoices
        else -> emptyList()
    }
}

/** A boolean field's choices: its two values, which read as themselves. */
private val booleanChoices = listOf(SchOption("true", "true"), SchOption("false", "false"))

/** The label [choices] give [value], or null when it is not one of them. */
fun choiceLabel(value: String, choices: List<SchOption>): String? = choices.firstOrNull { it.value == value.trim() }?.label

/** The presentation a `date` column with none of its own reads through (issue #1323): relative to now, as Edited and Created are. */
const val defaultDatePresentation = $$"""${formatDate(value, "relative")}"""

/**
 * The presentation [kind]'s column reads through (issue #1323): its own [presentation], else
 * [defaultDatePresentation] for a `date`, else none -- the value is shown as it is.
 */
fun columnPresentation(kind: UsageKind, presentation: String?): String? =
    presentation?.ifBlank { null } ?: defaultDatePresentation.takeIf { kind == UsageKind.date }

/**
 * [value] as its script reads it (issue #1323): a `number` column's as a number, so `formatNumber` and `currency` take
 * it; any other, and a number that does not read as one, as the text.
 */
fun typedColumnValue(value: String, kind: UsageKind): Any =
    if (kind != UsageKind.number) value else value.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull() } ?: value

/**
 * What a listing cell shows (issue #1323): [value] -- the column's value, as the backend evaluated it -- read through
 * the column's presentation ([columnPresentation]) over the trait's [data], with the value as `value`, in [env]'s zone
 * and against its clock. A column with [choices] and no presentation of its own shows the value's label (issue #1500),
 * as the form does; a value that is not one of them is shown as it is.
 *
 * A blank value is blank: the row has nothing here (or the column is another client's trait, #807), and there is
 * nothing to present. A day alone in a `date` column with no presentation of its own is shown as it is, since a day
 * has no time to be relative from. And a presentation that cannot be evaluated against this row -- a field it reads
 * is missing -- shows the value, as a broken presentation must never blank the cell: what the column holds is still
 * true.
 */
fun presentColumnValue(
    value: String,
    kind: UsageKind,
    presentation: String?,
    data: Map<String, Any?>,
    env: ScriptEnv,
    choices: List<SchOption> = emptyList(),
): String {
    if (value.isBlank()) return value
    if (presentation.isNullOrBlank() && choices.isNotEmpty()) return choiceLabel(value, choices) ?: value
    val script = columnPresentation(kind, presentation) ?: return value
    if (presentation.isNullOrBlank() && isDayText(value)) return value
    return script.analyzeTemplate(evaluateWith = data + ("value" to typedColumnValue(value, kind)), env = env).value ?: value
}

/** Whether [text] is a day alone, `yyyy-MM-dd`. */
internal fun isDayText(text: String): Boolean =
    text.length == 10 && text.withIndex().all { (i, c) -> if (i == 4 || i == 7) c == '-' else c.isDigit() }
