package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.util.formatDay
import kotlinx.datetime.LocalDate

/** The field names of a report run's and a report listing's wire forms (issue #981). */
@Suppress("ConstPropertyName")
object RRUN {
    // --- the run's inputs ---
    const val reportId = "reportId"
    const val client = "client"
    const val aggregate = "aggregate"
    const val groupBy = "groupBy"
    const val excludeEmpty = "excludeEmpty"

    // --- a row ---
    const val gedraId = "gedraId"
    const val values = "values"
    const val group = "group"
    const val count = "count"

    // --- the run's summary ---
    const val label = "label"
    const val mode = "mode"
    const val columns = "columns"
    const val scanned = "scanned"
    const val excluded = "excluded"

    // --- a column, as a listing or a summary describes it ---
    const val columnId = "columnId"
    const val path = "path"
    const val kind = "kind"
    const val combine = "combine"
    const val rollup = "rollup"
    const val multiValued = "multiValued"

    // --- a report, as the listing describes it ---
    const val description = "description"
    const val configName = "configName"
    const val origin = "origin"
    const val template = "template"
    const val issues = "issues"
    /** Whether the nightly job stores snapshots of the report (issue #1033). */
    const val history = "history"
}

/** The field names of a report **snapshot** on the wire (issue #1033): a grouped run kept as it was. */
@Suppress("ConstPropertyName")
object RHIS {
    const val snapshotId = "snapshotId"
    const val takenAt = "takenAt"
    const val trigger = "trigger"
    const val launchName = "launchName"
    /** The run's rows: `{group, count, values}` per group, as the run endpoint pages them. */
    const val rows = "rows"
    /** Whether the rows were cut at the stored limit. */
    const val truncated = "truncated"
    /** On the history listing's summary: how many snapshots the report has for the client. */
    const val numSnapshots = "numSnapshots"
}

/** What took a snapshot (issue #1033): the nightly job, or someone pressing the button. */
@Suppress("EnumEntryName")
enum class ReportSnapshotTrigger { scheduled, manual }

/** How a report is run (issue #981): one row per form, or one per group of forms. */
@Suppress("EnumEntryName")
enum class ReportMode {
    detail,
    aggregate,
}

/**
 * Whether [subject] is left out of a run because a column of [excludeEmpty] has no value for it (issue #981) -- the
 * columns that must have one. Empty is [isEmptyReportValue]'s: no value, a blank, an empty list. A count of zero is
 * a value, so a count column never excludes.
 */
fun BoundReport.excludes(subject: ReportSubject, excludeEmpty: List<BoundColumn>): Boolean =
    excludeEmpty.any { isEmptyReportValue(it.valueOf(subject)) }

/** This column's value for [subject], by its own combine. */
fun BoundColumn.valueOf(subject: ReportSubject): Any? = path.valueOf(subject, combine)

/** Each of [columns]' value for [subject], by column id, in column order: a detail row's `values`. */
fun detailValues(subject: ReportSubject, columns: List<BoundColumn>): Map<String, Any?> =
    columns.associate { it.columnId to it.valueOf(subject) }

/**
 * One group of an aggregate run (issue #981): the [key] -- the grouped-by columns' values, in [groupBy] order, null
 * for a form with none -- how many forms are in it, and the rollups of the columns that declare one, by column id.
 */
class ReportGroup(val key: List<Any?>, val count: Int, val rollups: Map<String, Any?>)

/**
 * [subjects] grouped by the values of [groupBy] (issue #981), each group's rollups computed, the groups in key order
 * ([compareReportKeys]: values in order, nulls last) -- a total order, which a cursor over the groups needs. With no
 * [groupBy] the whole set is one group, under the empty key, so an aggregate run without grouping is a total row.
 *
 * A rollup combines the column's value from each form of the group that has one: a `sum` of the forms' totals, the
 * `count` of forms with a value, the `max` of their dates. Two forms are in one group when their keys are equal as
 * report values ([reportValueKey]) -- `2` and `2.0` are one year.
 */
fun aggregateReport(subjects: List<ReportSubject>, groupBy: List<BoundColumn>, rolled: List<BoundColumn>): List<ReportGroup> {
    class Acc(val key: List<Any?>) {
        var count = 0
        val values: Map<String, MutableList<Any>> = rolled.associate { it.columnId to mutableListOf() }
    }
    // Keyed by each value's identity as a report value, then ordered once at the end.
    val groups = LinkedHashMap<List<String>, Acc>()
    for (subject in subjects) {
        val key = groupBy.map { it.valueOf(subject) }
        val acc = groups.getOrPut(key.map(::reportValueKey)) { Acc(key) }
        acc.count++
        for (column in rolled) {
            column.valueOf(subject)?.takeUnless { isEmptyReportValue(it) }?.let { acc.values.getValue(column.columnId).add(it) }
        }
    }
    return groups.values.sortedWith { a, b -> compareReportKeys(a.key, b.key) }.map { acc ->
        ReportGroup(
            acc.key, acc.count,
            rolled.associate { c -> c.columnId to combineReportValues(acc.values.getValue(c.columnId), c.kind, c.column.rollup!!) },
        )
    }
}

/**
 * A report value as its wire form (issue #981): a day as its `yyyy-MM-dd` text -- a `LocalDate` has no JSON form of
 * its own -- a list element by element, and anything else as it is (an `Instant` is written as ISO text by the
 * JSON formatter). [depth] bounds the walk, as a value's nesting is bounded by its path.
 */
fun reportWireValue(value: Any?, depth: Int = 0): Any? = when (value) {
    is LocalDate -> value.formatDay()
    is List<*> -> if (depth > RPT.maxFields) null else value.map { reportWireValue(it, depth + 1) }
    else -> value
}

/** A column as a listing or a run's summary describes it (issue #981): what it reads, and how it was bound. */
fun BoundColumn.describe(): Map<String, Any?> = buildMap {
    put(RRUN.columnId, columnId)
    put(RRUN.label, column.label)
    put(RRUN.path, column.path)
    put(RRUN.kind, kind.name)
    put(RRUN.combine, combine.name)
    column.rollup?.let { put(RRUN.rollup, it.name) }
    put(RRUN.multiValued, path.multiValued)
}
