package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchProperty
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.resolveDeliveredLayouts
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.ScriptEnv
import com.dynamicruntime.common.util.analyzeTemplate
import com.dynamicruntime.common.util.humanizeFieldName
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Computes a stored row's **display values** from a client's trait-usage rules (issue #537) -- what the list
 * and read endpoints attach under [GDF.displayValues], so the forms table shows the columns a client declared
 * rather than a hardcoded `name`.
 *
 * One entry per [columns] entry, in declaration order (the column order), under the column's name
 * ([ClientTraitUsage.columnKey], issue #1490), whether or not the row carries that trait -- so every row presents the
 * same column set and the table can build its headers from any one of them. A rule whose trait the row does not
 * carry, or whose expression cannot resolve against the data present, yields an **empty** value rather than
 * dropping the column or failing the listing: a blank cell is the honest presentation of "this row has nothing to
 * show here", and one row's missing field must never fail the page for the rest.
 *
 * [columns] are [usageClient]'s. A rule over one of that client's **own** traits applies only to that client's
 * rows (issue #807): another client may declare a trait with the same id and a different shape, so on its row --
 * which an `allClients` caller's listing can hold -- the value is blank rather than read from a different trait.
 * A rule over a global trait applies to every row, since a global trait is the same trait for every client.
 */
fun computeDisplayValues(
    cxt: KdrCxt,
    row: GedraDataRow,
    columns: List<UsageColumn>,
    usageClient: String,
): List<Map<String, Any?>> {
    // Read in the zone of the client whose columns these are, and at this request's moment (issue #1321).
    val env = ClientService.get(cxt).scriptEnvOf(cxt, usageClient)
    return columns.map { column -> displayValue(cxt, row, column, usageClient, env) }
}

private fun displayValue(
    cxt: KdrCxt,
    row: GedraDataRow,
    column: UsageColumn,
    usageClient: String,
    env: ScriptEnv,
): Map<String, Any?> {
    val usage = column.usage
    val applies = row.client == usageClient || SchemaService.get(cxt).isGlobalTrait(usage.traitId)
    val data = row.entries.takeIf { applies }?.firstOrNull { it[GE.traitId].toOptStr() == usage.traitId }
        ?.let { it[GE.data].toJsonMapOrEmpty() }
    // Substitution only (this issue): the expression pulls a field out of the trait's data. A missing field is a
    // problem the report carries, and for a *presentation* value it is not a fault -- it is a blank cell.
    val value = if (data == null) "" else usage.display.analyzeTemplate(evaluateWith = data, env = env).value ?: ""
    return linkedMapOf(
        UF.columnId to usage.columnKey,
        UF.traitId to usage.traitId,
        UF.label to usage.label,
        UF.value to value,
        UF.kind to column.kind.name,
    )
}

/**
 * [usages] as the columns [types] make of them (issue #1508): each resolved against its trait's data as those types
 * declare it ([resolveUsageColumn]) -- a client's own types, narrowed if it narrowed the trait, or the global document's.
 * What a client's listing query type is generated from when its schema is built, and what the listing reads, so the
 * parameters advertised and the search run cannot disagree.
 */
fun resolveColumns(usages: List<ClientTraitUsage>, types: Map<String, SchType>): List<UsageColumn> {
    if (usages.isEmpty()) return emptyList()
    return usages.map { usage -> resolveUsageColumn(usage, formTraitData(types, usage.traitId)?.valueType) }
}

/**
 * The `data` property of form trait [traitId]'s entry as [types] declare it (issues #1508, #1491): what a listing
 * column's `${field}` reads from, read off the form-document entry union. Its `refName` names the data type, which a
 * layout labeling its fields is keyed by. Null when [types] hold no form trait [traitId].
 */
fun formTraitData(types: Map<String, SchType>, traitId: String): SchProperty? =
    types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"]?.variants?.select(traitId)?.properties?.get(GE.data)

/**
 * Form trait [traitId]'s name in [store], by the rule every page names a trait with (`traitNameOf`, issue #1520): its
 * data type's layout heading, then its title, then its id. The heading's backend `%{@t(...)}` pulls are resolved for
 * [cxt] as delivery to a page resolves them, so a server message or a report names the trait as the page does. Only
 * the heading is resolved, not the layout's field copy.
 */
fun formTraitName(cxt: KdrCxt, store: KdrSchemaStore, traitId: String): String {
    val union = store.types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"]
    val dataType = union?.variants?.byValue?.get(traitId)?.properties?.get(GE.data)?.valueType
    val typeName = dataType?.name
    val layout = typeName?.let { store.layouts[it] }
    val heading = layout?.label?.let { label ->
        val headingOnly = listOfNotNull(SL.label to label, layout.fragmentFileId?.let { SL.fragmentFileId to it }).toMap()
        resolveDeliveredLayouts(cxt, mapOf(typeName to headingOnly))[typeName].toJsonMapOrEmpty()[SL.label].toOptStr()
    }
    return traitNameOf(traitId, heading, dataType?.title)
}

/**
 * What a person calls field [key] of a trait's data, [prop] (issues #1491, #1492): its [layout]'s label, unless that is a
 * template, else its title, else its type's, else its key in words. How a listing column and a report column name a
 * field they offer.
 */
fun fieldLabelOf(layout: SchLayout?, key: String, prop: SchProperty): String =
    layout?.fields?.firstOrNull { it.field == key }?.label?.takeIf { "%{" !in it && $$"${" !in it }
        ?: prop.title ?: prop.valueType.title ?: humanizeFieldName(key)
