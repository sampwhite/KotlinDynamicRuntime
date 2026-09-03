package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Computes a stored row's **display values** from a client's trait-usage rules (issue #537) -- what the list
 * and read endpoints attach under [GDF.displayValues], so the forms table shows the columns a client declared
 * rather than a hardcoded `name`.
 *
 * One entry per declared [usages] rule, in declaration order (the column order), whether or not the row
 * carries that trait -- so every row presents the same column set and the table can build its headers from any
 * one of them. A rule whose trait the row does not carry, or whose expression cannot resolve against the data
 * present, yields an **empty** value rather than dropping the column or failing the listing: a blank cell is
 * the honest presentation of "this row has nothing to show here", and one row's missing field must never fail
 * the page for the rest.
 */
fun computeDisplayValues(
    @Suppress("unused") cxt: KdrCxt,
    row: GedraDataRow,
    usages: List<ClientTraitUsage>,
): List<Map<String, Any?>> = usages.map { usage ->
    val data = row.entries.firstOrNull { it[GE.traitId].toOptStr() == usage.traitId }
        ?.let { it[GE.data].toJsonMapOrEmpty() }
    // Substitution only (this issue): the expression pulls a field out of the trait's data. A missing field
    // throws in the evaluator, which for a *presentation* value is not a fault -- it is a blank cell.
    val value = if (data == null) "" else runCatching { usage.display.evalTemplate(data) }.getOrDefault("")
    linkedMapOf<String, Any?>(
        UF.traitId to usage.traitId,
        UF.label to usage.label,
        UF.value to value,
        UF.kind to usage.kind.name,
    )
}
