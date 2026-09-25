package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.SchemaService
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
 *
 * [usages] are [usageClient]'s. A rule over one of that client's **own** traits applies only to that client's
 * rows (issue #807): another client may declare a trait with the same id and a different shape, so on its row --
 * which an `allClients` caller's listing can hold -- the value is blank rather than read from a different trait.
 * A rule over a global trait applies to every row, since a global trait is the same trait for every client.
 */
fun computeDisplayValues(
    cxt: KdrCxt,
    row: GedraDataRow,
    usages: List<ClientTraitUsage>,
    usageClient: String,
): List<Map<String, Any?>> = usages.map { usage ->
    val applies = row.client == usageClient || SchemaService.get(cxt).isGlobalTrait(usage.traitId)
    val data = row.entries.takeIf { applies }?.firstOrNull { it[GE.traitId].toOptStr() == usage.traitId }
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
