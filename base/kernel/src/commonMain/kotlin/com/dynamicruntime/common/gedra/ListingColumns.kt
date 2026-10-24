package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.util.ScriptEnv
import com.dynamicruntime.common.util.analyzeTemplate
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr

/**
 * One column of a forms listing as the listing's summary says it (issue #1323): the [traitId] it reads, its [label],
 * the [kind] its value sorts and searches by, and the [presentation] the cell reads through, if the client gave one.
 * Said once for the listing ([GDF.columns]); each row carries only its value.
 */
class ListingColumn(
    val traitId: String,
    val label: String,
    val kind: UsageKind,
    val presentation: String?,
) {
    /** This column on the wire, keyed by [UF]: the presentation only when there is one. */
    fun toMap(): Map<String, Any?> = buildMap {
        put(UF.traitId, traitId)
        put(UF.label, label)
        put(UF.kind, kind.name)
        presentation?.let { put(UF.presentation, it) }
    }

    companion object {
        /** The column [usage] draws. */
        fun of(usage: ClientTraitUsage): ListingColumn = ListingColumn(usage.traitId, usage.label, usage.kind, usage.presentation)

        /** The columns of a listing's [GDF.columns], in order; a kind the kernel does not know reads as text. */
        fun listFrom(wire: Any?): List<ListingColumn> = wire.toJsonListOfMaps().map { m ->
            ListingColumn(
                traitId = m[UF.traitId].toOptStr().orEmpty(),
                label = m[UF.label].toOptStr().orEmpty(),
                kind = UsageKind.entries.firstOrNull { it.name == m[UF.kind].toOptStr() } ?: UsageKind.string,
                presentation = m[UF.presentation].toOptStr()?.ifBlank { null },
            )
        }
    }
}

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
 * and against its clock.
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
): String {
    if (value.isBlank()) return value
    val script = columnPresentation(kind, presentation) ?: return value
    if (presentation.isNullOrBlank() && isDayText(value)) return value
    return script.analyzeTemplate(evaluateWith = data + ("value" to typedColumnValue(value, kind)), env = env).value ?: value
}

/** Whether [text] is a day alone, `yyyy-MM-dd`. */
private fun isDayText(text: String): Boolean =
    text.length == 10 && text.withIndex().all { (i, c) -> if (i == 4 || i == 7) c == '-' else c.isDigit() }
