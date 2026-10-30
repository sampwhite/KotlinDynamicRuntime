package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.isDateFormat
import com.dynamicruntime.common.schema.parseExactBool
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
    /** The choices its field offers ([ColumnSearchShape.choices]), in their declared order; empty when there are none. */
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
        /** The listing column [column] draws. */
        fun of(column: UsageColumn): ListingColumn =
            ListingColumn(column.columnKey, column.traitId, column.label, column.kind, column.usage.presentation, column.choices)

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
 * How a listing column is searched and sorted (issue #1508): as text, a number range, or a date range ([kind]), and,
 * for text, the [choices] its field offers, in their declared order -- what its filter suggests, its cells name, and it
 * sorts by (issue #1500). A number range is a number range whether its field is an integer or not: a bound of 40.5 on
 * an integer field is a harmless search, and typing the bound `integer` would refuse it.
 *
 * Built from a few facts about the field ([fieldSearchShape]), never copied from its schema: most of a field's schema
 * says what may be **stored** -- required, lengths, bounds, patterns, visibility, conditionals -- and none of it
 * belongs in a search, where a value that is not there finds nothing, which is a harmless answer.
 */
class ColumnSearchShape(
    val kind: UsageKind,
    val choices: List<SchOption> = emptyList(),
)

/**
 * The field a column's [display] reads in the trait's [dataType], when the display is a lone `${field}` (issue
 * #1500): a nested path walks the nested objects. Null for a computed display, a field the type does not declare,
 * or no type.
 */
fun columnField(display: String, dataType: SchType?): SchType? {
    if (dataType == null) return null
    val path = display.loneFieldPath() ?: return null
    var field: SchType = dataType
    for (segment in path) {
        field = field.properties[segment]?.valueType ?: return null
    }
    return field
}

/**
 * The search shape [field]'s schema gives (issue #1508): an integer or a number is a number range; a string with a
 * date format a date range; a string text, with its options as choices; and a boolean a yes-or-no. Null for anything
 * else -- an array, an object, a union -- whose column falls back to its declared kind.
 */
fun fieldSearchShape(field: SchType): ColumnSearchShape? = when (field.jsonType) {
    SCT.integer, SCT.number -> ColumnSearchShape(UsageKind.number)
    SCT.boolean -> ColumnSearchShape(UsageKind.boolean)
    SCT.string -> when {
        isDateFormat(field.format) -> ColumnSearchShape(UsageKind.date)
        else -> ColumnSearchShape(UsageKind.string, choices = field.options.orEmpty())
    }
    else -> null
}

/**
 * A listing column as a client runs it (issue #1508): its [usage], and the [shape] it is searched and sorted by. What
 * the listing, its search, its sort, and its value suggestions read, so that all of them agree with the query
 * parameters the client's schema advertises, which are generated from the same shapes. [fieldShape] is what the
 * field alone would give, null for a computed display, kept so a kind contradicting its field can be reported.
 */
class UsageColumn(val usage: ClientTraitUsage, val shape: ColumnSearchShape, val fieldShape: ColumnSearchShape? = null) {
    val columnKey: String get() = usage.columnKey
    val traitId: String get() = usage.traitId
    val label: String get() = usage.label
    val kind: UsageKind get() = shape.kind
    val choices: List<SchOption> get() = shape.choices

    /**
     * Whether the usage declares a kind its field contradicts -- a `string` column over a number, say, which would sort
     * "100" before "9", or a `date` over a boolean, whose range would match nothing. Plain text contradicts nothing:
     * text holding a number or a day may be read as one. Text with choices is not plain text.
     */
    val kindConflict: Boolean
        get() {
            val field = fieldShape ?: return false
            val declared = usage.kind ?: return false
            val plainText = field.kind == UsageKind.string && field.choices.isEmpty()
            return !plainText && field.kind != declared
        }
}

/**
 * [usage] as a client runs it (issue #1508), given the trait's [dataType] as that client declares it. A lone
 * `${field}` display takes the field's shape ([fieldSearchShape]) unless the usage declares another kind, which then
 * stands: over a plain text field that is a reading of its text (a day kept as text, searched as a date), and over any
 * other a contradiction ([UsageColumn.kindConflict]). A computed display, or a field with no shape, is its declared kind,
 * else text.
 */
fun resolveUsageColumn(usage: ClientTraitUsage, dataType: SchType?): UsageColumn {
    val fieldShape = columnField(usage.display, dataType)?.let { fieldSearchShape(it) }
    val declared = usage.kind
    val shape = when {
        fieldShape != null && (declared == null || declared == fieldShape.kind) -> fieldShape
        else -> ColumnSearchShape(declared ?: UsageKind.string)
    }
    return UsageColumn(usage, shape, fieldShape)
}

/**
 * [usages] as columns searched by their declared kind alone, text where none is declared -- what a column is before
 * its trait's schema is known, and what a test that needs no schema builds.
 */
fun declaredColumns(usages: List<ClientTraitUsage>): List<UsageColumn> = usages.map { resolveUsageColumn(it, null) }

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
 * it, and a `boolean` column's as a boolean (issue #1508), so a presentation can choose its own words; any other, and
 * a value that does not read as its kind, as the text.
 */
fun typedColumnValue(value: String, kind: UsageKind): Any = when (kind) {
    UsageKind.number -> value.trim().let { it.toLongOrNull() ?: it.toDoubleOrNull() } ?: value
    UsageKind.boolean -> parseExactBool(value) ?: value
    else -> value
}

/** What a `boolean` column's cell says for [value] with no presentation of its own (issue #1508): Yes or No. */
fun yesNoText(value: String): String? = parseExactBool(value)?.let { if (it) "Yes" else "No" }

/**
 * What a listing cell shows (issue #1323): [value] -- the column's value, as the backend evaluated it -- read through
 * the column's presentation ([columnPresentation]) over the trait's [data], with the value as `value`, in [env]'s zone
 * and against its clock. A column with [choices] and no presentation of its own shows the value's label (issue #1500),
 * as the form does, and a `boolean` one Yes or No (issue #1508); a value that is neither is shown as it is.
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
    if (presentation.isNullOrBlank() && kind == UsageKind.boolean) return yesNoText(value) ?: value
    val script = columnPresentation(kind, presentation) ?: return value
    if (presentation.isNullOrBlank() && isDayText(value)) return value
    return script.analyzeTemplate(evaluateWith = data + ("value" to typedColumnValue(value, kind)), env = env).value ?: value
}

/** Whether [text] is a day alone, `yyyy-MM-dd`. */
internal fun isDayText(text: String): Boolean =
    text.length == 10 && text.withIndex().all { (i, c) -> if (i == 4 || i == 7) c == '-' else c.isDigit() }
