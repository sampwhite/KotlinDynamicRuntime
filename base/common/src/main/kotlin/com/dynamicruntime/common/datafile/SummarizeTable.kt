package com.dynamicruntime.common.datafile

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.schema.LogSchema
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SFU
import com.dynamicruntime.common.schema.SchEventType
import com.dynamicruntime.common.schema.SchFunction
import com.dynamicruntime.common.schema.SchFunctionCreation
import com.dynamicruntime.common.schema.SchFunctionUsage
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchWriteFunction
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.isDataTable
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.schema.validatedFunctionSettings
import com.dynamicruntime.common.table.TableColumn
import com.dynamicruntime.common.table.TableSummary
import com.dynamicruntime.common.table.tableColumns
import com.dynamicruntime.common.util.toOptStr

/** The `summarizeTable` function's name, its settings, and its schema names (issue #1883). Each name matches its value. */
@Suppress("ConstPropertyName")
object STF {
    /** The `fn` discriminator this function is declared under. */
    const val fn = "summarizeTable"

    /** The property the summary is stored in -- one the type declares `g-derived`. */
    const val target = "target"

    /** The table field whose table is summed up: a property of the same type holding a CSV table. */
    const val table = "table"

    /** The column summed up: a property of the table's row type. Absent, a count of the rows. */
    const val column = "column"

    /** How: one of `TableSummary.combines` -- count, sum, avg, min, or max. */
    const val combine = "combine"

    /** The schema namespace and type name for this function's settings. */
    const val namespace = "kdr.sfnts"
    const val initDataType = "SummarizeTable"
}

/**
 * The settings of a [STF.fn] usage, for a type's `onWrite(...)` in the schema builder (issue #1883): store [combine] of
 * [column] of table field [table]'s table -- or, with no column, how many rows it has -- in [target]. The same shape a
 * stored definition writes.
 */
fun summarizeTable(target: String, table: String, combine: ReportCombine, column: String? = null, priority: Int? = null): Map<String, Any?> =
    buildMap {
        put(SFU.fn, STF.fn)
        if (priority != null) put(SFU.priority, priority)
        put(STF.target, target)
        put(STF.table, table)
        if (column != null) put(STF.column, column)
        put(STF.combine, combine.name)
    }

/**
 * A table's summary stored in its trait's data (issue #1883): on each write, read the table a table field names and
 * store one value summing up a column -- a count, a total, an average, the least, or the greatest
 * ([TableSummary.summarize], the code a grid's footer runs too) -- in a `g-derived` property, so listings, reports, and
 * workflows can use it as any field.
 *
 * **It clears the target rather than failing the write** when there is no value: no table named yet, a table that does
 * not fit its columns -- whose save the link hook then refuses anyway, rolling this back with it, since the write
 * functions run first in the same transaction -- or one whose bytes cannot be read. A system recompute reads only a file
 * some live form links: a value stored before its field held tables was never checked, and a recompute does not read
 * on a person's behalf.
 *
 * Theme: #DTABL.
 */
class SummarizeTableFn(
    override val priority: Int,
    private val target: String,
    private val table: String,
    private val field: SchType,
    private val column: TableColumn?,
    private val combine: ReportCombine,
    private val targetType: SchType,
) : SchWriteFunction {
    override val fn: String = STF.fn
    override val writes: Set<String> = setOf(target)
    override val reads: Set<String> = setOf(table)

    override fun onWrite(cxt: KdrCxt, data: Map<String, Any?>): Map<String, Any?> {
        val fileId = data[table].toOptStr()?.trim()?.takeIf { it.isNotEmpty() } ?: return data - target
        val system = cxt.userProfile.authId == null
        if (system && !DataFileLinks.linkState(cxt, fileId).live) return data - target
        val read = DataTables.readFor(cxt, fileId, field, cxt.client) ?: return data - target
        if (!read.ok) return data - target
        val value = TableSummary.summarize(read.rows, column, combine) ?: return data - target
        val coerced = coerceAndValidate(targetType, value)
        if (coerced.failures.isNotEmpty()) {
            LogSchema.warn(cxt) {
                "'${STF.fn}' summed up '$table' as a value '$target' does not take; clearing it: " +
                    coerced.failures.joinToString("; ") { it.message }
            }
            return data - target
        }
        return data + (target to coerced.value)
    }
}

/**
 * Builds a [SummarizeTableFn] from a usage (issue #1883), holding it to the type it runs on: the table must be a table
 * field the type declares; the column, when given, one of its row type's columns; the combine one of
 * [TableSummary.combines] that the column allows, and a count when no column is named; and the target a property the
 * type declares `g-derived`, not gated by `g-visibleWhen`, whose type holds what the combine gives -- a number for a
 * count, a sum, or an average (a whole number only for a count, or a sum of whole numbers), and the column's own kind
 * for the least or the greatest. Registered in `CommonComponent` through `SchemaCollector.addSchemaFunction`.
 *
 * Theme: #DTABL.
 */
object SummarizeTableCreation : SchFunctionCreation {
    override val fn: String = STF.fn
    override val event: SchEventType = SchEventType.write
    override val initDataTypeName: String = "${STF.namespace}.${STF.initDataType}"

    override fun initDataDefs(cxt: KdrCxtBase): Map<String, Any?> = schemaDefs(cxt, STF.namespace) {
        type(STF.initDataType) {
            type = SCT.kObject
            description = "Store a summary of a column of a table field's table -- how many rows, a total, an average, " +
                "the least, or the greatest -- in one of the object's derived properties."
            property(SFU.fn, "The function's kind; always '${STF.fn}' here.", required = true) { const = STF.fn }
            property(SFU.priority, "Run order among the type's write functions, lower first.") { type = SCT.integer }
            property(STF.target, "The property the summary is stored in, one the type declares '${SCH.derived}'.", required = true)
            property(STF.table, "The table field whose table is summed up, a property of the same type.", required = true)
            property(STF.column, "The column summed up, a property of the table's row type. Absent, the rows are counted.")
            property(STF.combine, "How the column is summed up.", required = true) {
                TableSummary.combines.forEach { option(it.name) }
            }
        }
    }

    override fun create(cxt: KdrCxt, usage: SchFunctionUsage, dataType: SchType): SchFunction {
        val m = validatedFunctionSettings(initDataType(cxt), usage, STF.fn)
        val target = m[STF.target].toOptStr().orEmpty()
        val table = m[STF.table].toOptStr().orEmpty()
        val columnName = m[STF.column].toOptStr()
        val combine = ReportCombine.entries.first { it.name == m[STF.combine].toOptStr() }
        fun refuse(problem: String): Nothing = throw KdrException.mkConv("'${STF.fn}' of '$target': $problem")
        val field = dataType.properties[table]?.valueType?.takeIf { it.isDataTable() }
            ?: refuse("the type declares no table field '$table' (a '${SFMT.dataFile}' field holding a CSV table).")
        val rowType = field.fileRowType ?: refuse("the table field '$table' has no row type.")
        val column = columnName?.let { name ->
            tableColumns(rowType).firstOrNull { it.name == name }
                ?: refuse("the table in '$table' has no column '$name'. Its columns are: ${tableColumns(rowType).joinToString(", ")}.")
        }
        if (!TableSummary.allows(column, combine)) {
            refuse(
                if (column == null) "a '${combine.name}' needs a column to sum up; only a count counts the rows."
                else "a '${combine.name}' does not sum up the ${column.kind.name} column '${column.name}'.",
            )
        }
        val prop = dataType.properties[target] ?: refuse("the type declares no property '$target' to store the summary in.")
        if (!prop.valueType.derived) {
            refuse("'$target' is not '${SCH.derived}', so a caller could supply it and this would overwrite what they sent.")
        }
        if (prop.visibleWhen != null) {
            refuse("'$target' is gated by '${SCH.visibleWhen}', which decides who may change it; this changes it for everyone.")
        }
        targetProblem(prop.valueType, column, combine)?.let { refuse("'$target' $it") }
        return SummarizeTableFn(usage.priority, target, table, field, column, combine, prop.valueType)
    }

    /** What keeps [type] from holding [combine] of [column], or null when it holds it. */
    private fun targetProblem(type: SchType, column: TableColumn?, combine: ReportCombine): String? {
        val kind = TableSummary.resultKind(column, combine)
        val whole = combine == ReportCombine.count || (column?.whole == true && combine != ReportCombine.avg)
        val fits = when (kind) {
            ReportKind.number -> type.jsonType == SCT.number || (type.jsonType == SCT.integer && whole)
            ReportKind.date -> type.jsonType == SCT.string && type.format == SFMT.date
            ReportKind.string -> type.jsonType == SCT.string && type.format == null
            ReportKind.boolean -> type.jsonType == SCT.boolean
        }
        if (fits) return null
        val needs = when (kind) {
            ReportKind.number -> if (whole) "a number or an integer" else "a number (an average or a sum of decimals has a fraction)"
            ReportKind.date -> "a day ('${SFMT.date}')"
            ReportKind.string -> "a plain string"
            ReportKind.boolean -> "a boolean"
        }
        return "cannot hold a '${combine.name}'${column?.let { " of '${it.name}'" }.orEmpty()}, which needs $needs."
    }

    // Parsed once and kept, as `computeValue` keeps its own.
    @Volatile
    private var parsed: SchType? = null

    private fun initDataType(cxt: KdrCxt): SchType =
        parsed ?: parseSchemaTypes(initDataDefs(cxt)).getValue(initDataTypeName).also { parsed = it }
}
