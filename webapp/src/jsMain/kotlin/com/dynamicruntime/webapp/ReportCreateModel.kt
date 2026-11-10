package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.report.RCR
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportChartKind
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportPathGroupKind
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/*
 * Making a report from simple choices on the Reports page (issue #1492), its pure half: the paths the server says a
 * column may read, the report being drafted, what is wrong with it, and the create's request -- and removing one. Pure, and covered under
 * `jsNodeTest` (`ReportCreateTest`); the panel is `ReportCreatePanel.kt`.
 *
 * The page never builds a path. A column holds the path the server offered, and naming one entry of a keyed trait is
 * sent as the key's values for the server to write into it.
 */

/** What the Reports page offers for making a report, from the listing's summary. */
class ReportCreateOffer(
    /** Whether a report may be made here. */
    val canCreate: Boolean,
    /** Whether one made here is a draft, as a sandbox's edits are. */
    val createsDraft: Boolean,
    /** The sandbox a report is made from instead, for a client with one. */
    val createFrom: String?,
)

fun parseReportCreateOffer(summary: Map<String, Any?>): ReportCreateOffer = ReportCreateOffer(
    canCreate = summary[RCR.canCreate] == true,
    createsDraft = summary[RCR.createsDraft] == true,
    createFrom = summary[RCR.createFrom].toOptStr(),
)

/** A field of a keyed trait's key, which a column showing one entry gives a value for. */
class ReportKeyFieldView(val field: String, val label: String, val kind: ReportKind)

/** A path a column may read, as the server bound it. */
class ReportPathView(
    val path: String,
    val label: String,
    val kind: ReportKind,
    /** Whether a form may have several values there, so a column says which to show. */
    val multiValued: Boolean,
    /** For a keyed trait, its key fields: a column may show one entry, by its key. */
    val keyFields: List<ReportKeyFieldView>,
)

/** The paths of one source: the form itself, one of its traits, or one workflow. */
class ReportPathGroupView(val source: ReportPathGroupKind, val sourceId: String?, val label: String, val paths: List<ReportPathView>)

/** Every path a column may read, grouped by source. */
class ReportPathCatalogView(val groups: List<ReportPathGroupView>) {
    fun path(path: String): ReportPathView? = groups.firstNotNullOfOrNull { g -> g.paths.firstOrNull { it.path == path } }

    /** The group [path] is in. */
    fun groupOf(path: String): ReportPathGroupView? = groups.firstOrNull { g -> g.paths.any { it.path == path } }
}

private fun kindOf(value: Any?): ReportKind = ReportKind.entries.firstOrNull { it.name == value } ?: ReportKind.string

fun parseReportPathCatalog(raw: Map<String, Any?>): ReportPathCatalogView = ReportPathCatalogView(
    raw[RCR.groups].toJsonListOfMaps().mapNotNull { g ->
        val source = ReportPathGroupKind.entries.firstOrNull { it.name == g[RCR.source] } ?: return@mapNotNull null
        ReportPathGroupView(
            source, g[RCR.sourceId].toOptStr(), g[RRUN.label].toOptStr().orEmpty(),
            g[RCR.paths].toJsonListOfMaps().mapNotNull { p ->
                val path = p[RRUN.path].toOptStr() ?: return@mapNotNull null
                ReportPathView(
                    path, p[RRUN.label].toOptStr() ?: path, kindOf(p[RRUN.kind]), p[RRUN.multiValued] == true,
                    p[RCR.keyFields].toJsonListOfMaps().mapNotNull { k ->
                        k[RCR.field].toOptStr()?.let { ReportKeyFieldView(it, k[RRUN.label].toOptStr() ?: it, kindOf(k[RRUN.kind])) }
                    },
                )
            },
        )
    },
)

/** The two kinds of report the page makes: a row per form, as `formRoster`; or totals by a column, as `expensesByYear`. */
@Suppress("EnumEntryName")
enum class ReportShape(val label: String) {
    perForm("A row per form"),
    totals("Totals by a column"),
}

/**
 * Which of a form's several values a column shows: all of them, how many, the first, or -- for a keyed trait -- one
 * entry's, by its key. Each but the last is the column's combine.
 */
@Suppress("EnumEntryName")
enum class ValuePick(val label: String, val combine: ReportCombine?) {
    all("All of them", ReportCombine.list),
    count("How many", ReportCombine.count),
    first("The first", ReportCombine.first),
    byKey("One, by its key", null),
}

/** One column being drafted: the path it reads, its heading, which value it shows, and how a totals report combines it. */
data class ReportColumnDraft(
    /** The draft's own key for the column, never sent. */
    val id: Int,
    val path: String,
    val label: String,
    /** Which value, for a path with several; null for one with one. */
    val pick: ValuePick?,
    /** For [ValuePick.byKey], a value per key field, in the key's order. */
    val key: List<String> = emptyList(),
    /** How a totals report combines it across a group's forms; null shows it only when grouped by. */
    val rollup: ReportCombine? = null,
    /** Whether a form appears only with a value in it. */
    val mustHaveValue: Boolean = false,
)

/** The report being drafted. */
data class ReportDraft(
    val shape: ReportShape = ReportShape.perForm,
    val name: String = "",
    val description: String = "",
    val columns: List<ReportColumnDraft> = emptyList(),
    /** The column a totals report groups by, by its [ReportColumnDraft.id]. */
    val groupBy: Int? = null,
    /** Whether a totals report keeps a daily snapshot. */
    val history: Boolean = false,
    /** The chart a totals report opens on (issue #1773); null for the table alone. */
    val chart: ReportChartKind? = null,
    /** The column the chart measures, by its [ReportColumnDraft.id]; null for the count of forms. */
    val chartMeasure: Int? = null,
)

/** How [column]'s value reads, after its pick: a count is a number, anything else what its path holds. */
fun reportColumnKind(column: ReportColumnDraft, path: ReportPathView?): ReportKind =
    if (column.pick == ValuePick.count) ReportKind.number else path?.kind ?: ReportKind.string

/** Whether [column]'s value is a list -- every value of several -- which can be neither grouped by nor totalled but by a count. */
fun reportColumnIsList(column: ReportColumnDraft): Boolean = column.pick == ValuePick.all

/** The picks [path] offers: none for a path with one value; for a keyed trait, one entry by its key too. */
fun valuePicks(path: ReportPathView): List<ValuePick> = when {
    !path.multiValued -> emptyList()
    path.keyFields.isNotEmpty() -> ValuePick.entries
    else -> ValuePick.entries - ValuePick.byKey
}

/**
 * How a totals report may combine [column] across a group's forms: only those that suit its value. A list only by a
 * count; otherwise what its kind allows, but neither list form, which a group's cell would show as a list.
 */
fun rollupChoices(column: ReportColumnDraft, path: ReportPathView?): List<ReportCombine> =
    if (reportColumnIsList(column)) listOf(ReportCombine.count)
    else {
        val kind = reportColumnKind(column, path)
        ReportCombine.entries.filter { it.allows(kind) && it != ReportCombine.list && it != ReportCombine.distinct }
    }

/** The rollup a column starts with: a number's total, a date's latest, and nothing for anything else. */
fun defaultRollup(column: ReportColumnDraft, path: ReportPathView?): ReportCombine? = when {
    reportColumnIsList(column) -> null
    else -> when (reportColumnKind(column, path)) {
        ReportKind.number -> ReportCombine.sum
        ReportKind.date -> ReportCombine.max
        else -> null
    }
}

/** What a rollup is called beside a column of [kind]: the latest and earliest of dates, the highest and lowest of the rest. */
fun rollupText(rollup: ReportCombine, kind: ReportKind): String = when (rollup) {
    ReportCombine.sum -> "Total"
    ReportCombine.avg -> "Average"
    ReportCombine.min -> if (kind == ReportKind.date) "Earliest" else "Lowest"
    ReportCombine.max -> if (kind == ReportKind.date) "Latest" else "Highest"
    ReportCombine.count -> "How many"
    ReportCombine.first -> "The first"
    ReportCombine.list -> "All of them"
    ReportCombine.distinct -> "Each once"
}

/** A new column reading [path], headed as the server labels it; a value of several shows all of them to start. */
fun newReportColumn(id: Int, path: ReportPathView): ReportColumnDraft {
    val column = ReportColumnDraft(id, path.path, path.label, if (path.multiValued) ValuePick.all else null)
    return column.copy(rollup = defaultRollup(column, path))
}

/** This column showing [pick]: the key cleared unless it names one entry, and the rollup reset when it no longer suits. */
fun ReportColumnDraft.withPick(pick: ValuePick, path: ReportPathView?): ReportColumnDraft {
    val next = copy(pick = pick, key = if (pick == ValuePick.byKey) key.ifEmpty { path?.keyFields?.map { "" }.orEmpty() } else emptyList())
    return if (next.rollup != null && next.rollup in rollupChoices(next, path)) next else next.copy(rollup = defaultRollup(next, path))
}

/** The columns a totals report may group by: those with one value per form. */
fun groupableColumns(draft: ReportDraft): List<ReportColumnDraft> = draft.columns.filter { !reportColumnIsList(it) }

/** This draft with [column] added; the first column a totals report can group by is what it groups by, until chosen. */
fun ReportDraft.withColumn(column: ReportColumnDraft): ReportDraft {
    val next = copy(columns = columns + column)
    val groups = next.shape == ReportShape.totals && next.groupBy == null && !reportColumnIsList(column)
    return if (groups) next.copy(groupBy = column.id) else next
}

/** This draft with the column [id] changed by [change]; a grouped-by column that becomes a list is no longer grouped by. */
fun ReportDraft.withColumnChanged(id: Int, change: (ReportColumnDraft) -> ReportColumnDraft): ReportDraft {
    val next = copy(columns = columns.map { if (it.id == id) change(it) else it })
    val ungrouped = next.groupBy == id && next.columns.firstOrNull { it.id == id }?.let(::reportColumnIsList) == true
    return if (ungrouped) next.copy(groupBy = null) else next
}

/** This draft without the column [id]. */
fun ReportDraft.withoutColumn(id: Int): ReportDraft = copy(columns = columns.filter { it.id != id }, groupBy = groupBy.takeIf { it != id })

/** This draft as [shape]; a totals report groups by its first column that can be, until one is chosen. */
fun ReportDraft.withShape(shape: ReportShape): ReportDraft =
    copy(shape = shape, groupBy = if (shape == ReportShape.totals) groupBy ?: groupableColumns(this).firstOrNull()?.id else null)

/**
 * What a totals report's chart may measure (issue #1773), as its Grouped view offers ([reportMeasures]): the count of
 * forms first, keyed [RRUN.count], then each column not grouped by whose rollup gives a number, keyed by its
 * [ReportColumnDraft.id] -- the rule the report's binding holds a chart to (issue #1771). [catalog] says what each path
 * holds.
 */
fun reportDraftMeasures(draft: ReportDraft, catalog: ReportPathCatalogView?): List<ReportMeasure> = buildList {
    add(ReportMeasure(RRUN.count, reportCountLabel, additive = true))
    if (draft.shape != ReportShape.totals) return@buildList
    for (c in draft.columns) {
        val rollup = c.rollup ?: continue
        if (c.id == draft.groupBy) continue
        val kind = reportColumnKind(c, catalog?.path(c.path))
        if (rollup.resultKind(kind) != ReportKind.number) continue
        add(ReportMeasure(c.id.toString(), "${c.label.trim().ifEmpty { "(no heading)" }} (${rollupText(rollup, kind)})", rollup.addsUp))
    }
}

/**
 * The measure [draft]'s chart is of: the column it names while that column may still be measured, else the count of
 * forms -- so removing the column, grouping by it, or rolling it up to a date leaves the chart counting forms.
 */
fun reportDraftMeasure(draft: ReportDraft, catalog: ReportPathCatalogView?): ReportMeasure {
    val measures = reportDraftMeasures(draft, catalog)
    return measures.firstOrNull { it.key == draft.chartMeasure?.toString() } ?: measures.first()
}

/** What is wrong with [draft], said as what to do; empty when it can be made. [catalog] says what each path holds. */
fun reportDraftProblems(draft: ReportDraft, catalog: ReportPathCatalogView? = null): List<String> = buildList {
    if (draft.name.isBlank()) add("Give the report a name.")
    if (draft.columns.isEmpty()) add("Add at least one column.")
    val headings = draft.columns.map { it.label.trim() }
    if (headings.any { it.isEmpty() }) add("Give every column a heading.")
    headings.filter { it.isNotEmpty() }.groupBy { it.lowercase() }.values.filter { it.size > 1 }.forEach {
        add("Two columns are headed “${it.first()}”; give each its own.")
    }
    draft.columns.filter { it.pick == ValuePick.byKey && (it.key.isEmpty() || it.key.any { k -> k.isBlank() }) }.forEach {
        add("Say which entry “${it.label.trim().ifEmpty { "the column" }}” shows: a value for each field of its key.")
    }
    if (draft.shape == ReportShape.totals && draft.columns.isNotEmpty() && draft.columns.none { it.id == draft.groupBy }) {
        add("Choose the column to group by.")
    }
    if (draft.shape == ReportShape.totals && draft.chart == ReportChartKind.pie && !reportDraftMeasure(draft, catalog).additive) {
        add("A pie shows shares of a whole: chart the forms, or a column combined by its total or how many.")
    }
}

/**
 * The create's request for [draft] -- [client] when the caller names one, and how its page told them it takes effect,
 * [expected]. Only a totals report sends a grouping, rollups (but not on the grouped-by column), history, and a chart:
 * its kind, and the column it measures marked [RCR.charted] ([reportDraftMeasure]; none for the count of forms).
 */
fun reportCreateRequest(
    client: String?, draft: ReportDraft, expected: String, catalog: ReportPathCatalogView? = null,
): Map<String, Any?> = buildMap {
    client?.let { put(RRUN.client, it) }
    put(RDEF.label, draft.name.trim())
    draft.description.trim().takeIf { it.isNotEmpty() }?.let { put(RDEF.description, it) }
    val totals = draft.shape == ReportShape.totals
    val chart = draft.chart.takeIf { totals }
    val measured = chart?.let { reportDraftMeasure(draft, catalog).key.toIntOrNull() }
    put(
        RDEF.columns,
        draft.columns.map { c ->
            buildMap {
                put(RDEF.label, c.label.trim())
                put(RDEF.path, c.path)
                c.pick?.combine?.let { put(RDEF.combine, it.name) }
                if (c.pick == ValuePick.byKey) put(RCR.key, c.key.map { it.trim() })
                val grouped = totals && c.id == draft.groupBy
                if (grouped) put(RCR.groupedBy, true)
                if (totals && !grouped) c.rollup?.let { put(RDEF.rollup, it.name) }
                if (c.mustHaveValue) put(RCR.mustHaveValue, true)
                if (c.id == measured) put(RCR.charted, true)
            }
        },
    )
    if (totals && draft.history) put(RDEF.history, true)
    chart?.let { put(RDEF.chart, it.name) }
    put(CPY.mode, expected)
}

/** What a create answered: the report's id, and how it took effect. */
class ReportCreated(val reportId: String, val mode: String)

fun parseReportCreated(envelope: Map<String, Any?>): ReportCreated = envelope[EP.results].toJsonMapOrEmpty().let {
    ReportCreated(it[RRUN.reportId].toOptStr().orEmpty(), it[CPY.mode].toOptStr().orEmpty())
}

/**
 * Whether the page offers to remove [report]: one the client stores, from a client whose configuration is edited here
 * ([offer]) -- not one declared in source code, nor a copy from the client this one is built on.
 */
fun reportRemovable(report: ReportInfo, offer: ReportCreateOffer): Boolean = offer.canCreate && report.stored && report.template == null

/**
 * What removing [report] asks, naming where it acts ([deleteTargetText]): [client]'s live data, or its sandbox, where a
 * removal is a draft until published.
 */
fun reportRemoveQuestion(report: ReportInfo, client: String): String =
    "Remove the report “${report.label}” from ${deleteTargetText(client)}? Its stored snapshots are kept."

/** The removal's request: [reportId], [client] when the caller names one, and how its page told them it takes effect. */
fun reportRemoveRequest(client: String?, reportId: String, expected: String): Map<String, Any?> = buildMap {
    client?.let { put(RRUN.client, it) }
    put(RRUN.reportId, reportId)
    put(CPY.mode, expected)
}
