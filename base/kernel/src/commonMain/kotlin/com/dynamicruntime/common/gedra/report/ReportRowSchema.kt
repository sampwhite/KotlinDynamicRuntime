package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.gedra.SBX
import com.dynamicruntime.common.naming.OWNR
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.schema.schemaDefs

/**
 * The names of a report's **generated types** (issue #1086): one row type and one group type per client and report,
 * which the typed report endpoints (issue #1083) answer in.
 *
 * Under `kdr`, not under the client's own namespace, for two reasons. A client's configuration may declare types
 * only beneath `client.<id>` (`clientNamespaceProblem`), so nothing a client writes can take one of these names. And
 * a sandbox has no namespace of its own -- `clientNamespace` gives its parent's -- so beneath `client.acme` a sandbox's
 * types and its parent's would be one name for two things, which a reload of either would then fight over.
 */
@Suppress("ConstPropertyName")
object RGEN {
    const val namespaceRoot = "kdr.report.client"
    const val rowSuffix = "Row"
    const val groupSuffix = "Group"

    /** The namespace [client]'s generated report types live in: a sandbox (`acme:sandbox`) under its own segments. */
    fun namespaceFor(client: String): String = "$namespaceRoot${OWNR.namespaceSep}${client.replace(SBX.roleSep, OWNR.namespaceSep)}"

    /** The qualified name of the type of one **form's row** of [reportId] for [client]. */
    fun rowType(client: String, reportId: String): String = typeName(client, reportId, rowSuffix)

    /** The qualified name of the type of one **group's row** of [reportId] for [client]. */
    fun groupType(client: String, reportId: String): String = typeName(client, reportId, groupSuffix)

    /**
     * A rooted report id (`kdr:formsByStatus`) keeps its root as a segment. Within one client that cannot meet a
     * bare id, which holds no dot. Across clients one overlap is left: a report rooted `sandbox:x` for `acme` and a
     * report `x` for `acme:sandbox` -- which needs a component whose root is the sandbox's own role word, and which
     * the schema refuses to publish rather than serve one type for both.
     */
    private fun typeName(client: String, reportId: String, suffix: String): String =
        "${namespaceFor(client)}${OWNR.namespaceSep}${reportId.replace(OWNR.rootSep, OWNR.namespaceSep)}$suffix"
}

/**
 * The types of [bound]'s rows for [client] (issue #1086), as `$defs` content: [RGEN.rowType], one form's row of a
 * detail run, and [RGEN.groupType], one group's row of a run grouped as the report declares. Generated from the
 * report as it is **bound**, so a column's type is what its path and combine were resolved to, not what its
 * declaration left unsaid.
 *
 * - **Row**: `gedraId`, and `values` holding each column by its id.
 * - **Group**: `group` holding each of the report's own `groupBy` columns, `count`, and `values` holding each column
 *   that declares a rollup -- typed by what the rollup yields.
 *
 * Each column is a text, a number (a whole one for a count), a boolean, or a date -- a day or a timestamp as
 * [BoundColumn.dayOnly] says -- and a list of those when its combine or rollup yields one. No column is required: one
 * with nothing to show is null, which a validator reads as absent.
 *
 * **Strict, and so only true of a run that asks for exact dates.** No value is coerced: a day column refuses a
 * timestamp, a number column text. That is what makes the type a contract rather than a description -- and it holds
 * for the values [detailValues] and [aggregateReport] give with `exactDates`, where without it a day column carries
 * whatever each form stored.
 *
 * Every object is closed and the types refer to nothing but themselves, which is what a generated surface must be
 * to be published (`SchemaService.publishGenerated`).
 */
fun reportRowDefs(cxt: KdrCxtBase, client: String, bound: BoundReport): Map<String, Any?> {
    val named = "'${bound.report.label}' (${bound.reportId})"
    return schemaDefs(cxt, RGEN.namespaceFor(client)) {
        type(RGEN.rowType(client, bound.reportId)) {
            type = SCT.kObject
            description = "One form's row of the report $named."
            additionalProperties = false
            property(RRUN.gedraId, "The form's id.", required = true)
            property(RRUN.values, "The report's columns for the form, by column id. A column with nothing to show is null.", required = true) {
                type = SCT.kObject
                additionalProperties = false
                for (c in bound.columns) {
                    column(c, "${c.column.path} (${c.combine.name})", c.kind, c.yieldsList, whole = c.combine == ReportCombine.count)
                }
            }
        }
        type(RGEN.groupType(client, bound.reportId)) {
            type = SCT.kObject
            description = "One group's row of the report $named, grouped as the report declares."
            additionalProperties = false
            property(RRUN.group, "What the group's forms share: each grouped-by column by its id, null for the forms with no value.", required = true) {
                type = SCT.kObject
                additionalProperties = false
                for (c in bound.defaultGroupBy()) {
                    column(c, "${c.column.path} (${c.combine.name})", c.kind, list = false, whole = c.combine == ReportCombine.count)
                }
            }
            property(RRUN.count, "How many forms are in the group.", required = true) {
                type = SCT.integer
                allowCoerce = false
            }
            property(RRUN.values, "The rolled-up columns, by column id. A rollup with nothing to roll up is null.", required = true) {
                type = SCT.kObject
                additionalProperties = false
                for (c in bound.columns) {
                    val rollup = c.column.rollup ?: continue
                    column(
                        c, "${c.column.path} (${c.combine.name}, rolled up by ${rollup.name})", rollup.resultKind(c.kind),
                        list = rollup.yieldsList,
                        // A count is whole. A sum of counts is too, until it overflows into a fraction's type.
                        whole = rollup == ReportCombine.count,
                    )
                }
            }
        }
    }
}

/** [c] as a property named by its id and titled by its label: a [kind] value, or a [list] of them. */
private fun SchTypeBuilder.column(c: BoundColumn, description: String, kind: ReportKind, list: Boolean, whole: Boolean) {
    property(c.columnId, description) {
        title = c.column.label
        if (list) {
            type = SCT.array
            items { scalar(kind, c.path.dayOnly, whole) }
        } else {
            scalar(kind, c.path.dayOnly, whole)
        }
    }
}

/** One value of [kind], uncoerced: what a report holds is already of its kind, so a value that is not is a fault. */
private fun SchTypeBuilder.scalar(kind: ReportKind, dayOnly: Boolean, whole: Boolean) {
    when (kind) {
        ReportKind.string -> type = SCT.string
        ReportKind.number -> type = if (whole) SCT.integer else SCT.number
        ReportKind.boolean -> type = SCT.boolean
        ReportKind.date -> if (dayOnly) dayOnlyDate() else dateTime()
    }
    allowCoerce = false
}
