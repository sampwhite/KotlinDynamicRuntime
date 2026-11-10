package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.OwnedNameKind
import com.dynamicruntime.common.naming.isOwnedName
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.ConvProblem
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** A report definition's field names (issue #979), and the namespace of the schema it is validated against. */
@Suppress("ConstPropertyName")
object RDEF {
    /** The namespace of the definition schema, beside the workflow definition's `kdr.wfdef`. */
    const val namespace = "kdr.reportdef"
    const val defType = "ReportDef"
    const val columnType = "ReportColumnDef"
    const val chartType = "ReportChartDef"

    const val reportId = "reportId"
    const val label = "label"
    const val description = "description"
    const val columns = "columns"
    const val groupBy = "groupBy"
    const val excludeEmpty = "excludeEmpty"
    const val history = "history"
    const val chart = "chart"

    const val columnId = "columnId"
    const val path = "path"
    const val kind = "kind"
    const val combine = "combine"
    const val rollup = "rollup"

    /** The column a chart measures (issue #1771); its kind is [kind], as a column's is. */
    const val measure = "measure"
}

/** The kinds of chart a report's grouped run may be drawn as (issue #1771): a closed set the page knows how to draw. */
@Suppress("EnumEntryName")
enum class ReportChartKind {
    /** A bar per group, its length the group's measure. */
    bar,

    /** A slice per group, its share of the whole the group's share of the measure; so the measure must add up. */
    pie,
}

/**
 * The chart a report's **grouped** run opens on (issue #1771): a [kind], and the column it [measure]s -- a column with a
 * rollup whose result is a number, not one the report groups by -- or, absent, the count of forms in each group. The
 * page may draw another; this is the report's own. Whether the measure fits the report is the binding's to say
 * (`bindReport`), as for every other default a report names.
 */
class ReportChart(val kind: ReportChartKind, val measure: String? = null) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = buildMap {
        put(RDEF.kind, kind.name)
        measure?.let { put(RDEF.measure, it) }
    }
}

/**
 * One column of a report (issue #979): what it is called ([columnId], [label]), the report path it reads ([path],
 * #977), and optionally how it reads it. [kind] overrides the kind the path's target says; [combine] makes the
 * path's several values one within a form (#978); [rollup] is how the column is combined across the forms of a
 * group when the report is grouped.
 *
 * The path is kept as text: it is parsed and checked against the client's traits when the configuration loads
 * (#980), where one bad path costs its report rather than the configuration holding it.
 */
class ReportColumn(
    val columnId: String,
    val label: String,
    val path: String,
    val kind: ReportKind? = null,
    val combine: ReportCombine? = null,
    val rollup: ReportCombine? = null,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = buildMap {
        put(RDEF.columnId, columnId)
        put(RDEF.label, label)
        put(RDEF.path, path)
        kind?.let { put(RDEF.kind, it.name) }
        combine?.let { put(RDEF.combine, it.name) }
        rollup?.let { put(RDEF.rollup, it.name) }
    }
}

/**
 * A **named report** in a client's configuration (issue #979): the columns it promotes to the top level, which of
 * them it groups by when run grouped ([groupBy]), and which must hold a value for a form to appear
 * ([excludeEmpty]). Both are the defaults a run may override. A report asking for [history] (issue #1033) has a
 * snapshot of its grouped run stored nightly, so what its groups were can be charted over time.
 *
 * Declared in a config bundle, source or stored, as a workflow is, and addressed by [reportId] -- an owned name
 * (#921): a component's is rooted (`kdr:formsByStatus`) and a client's bare, so a release adding a report can never
 * take a name a client uses. A client sees the global reports and its own.
 *
 * Building one checks its **shape** only -- the fields the schema requires, the enum values. Whether its paths mean
 * anything for the client, and whether its columns are consistent, is the registry's (#980).
 */
class ClientReport(
    val reportId: String,
    val label: String,
    val columns: List<ReportColumn>,
    val description: String? = null,
    val groupBy: List<String> = emptyList(),
    val excludeEmpty: List<String> = emptyList(),
    /** Whether the nightly job stores a snapshot of this report's grouped run (issue #1033). */
    val history: Boolean = false,
    /** The chart the report's grouped run opens on (issue #1771), or none: the table alone. */
    val chart: ReportChart? = null,
    /**
     * The author's note on the report (issue #1135): its `$comment`, stored and read back, changing nothing. The
     * [description] is for the person choosing the report; this is for whoever maintains it.
     */
    val comment: String? = null,
) : JsonMappable {
    init {
        if (!isOwnedName(OwnedNameKind.report, reportId)) {
            throw KdrException.mkConv(
                "'$reportId' cannot be a report id: it has to be ${OwnedNameKind.report.localRule}, or a rooted name.",
            )
        }
    }

    /** The definition as its JSON form: what a stored configuration keeps, and what [parseClientReport] reads. */
    override fun toJsonMap(): Map<String, Any?> = buildMap {
        put(RDEF.reportId, reportId)
        comment?.let { put(SCH.dComment, it) }
        put(RDEF.label, label)
        description?.let { put(RDEF.description, it) }
        put(RDEF.columns, columns.map { it.toJsonMap() })
        if (groupBy.isNotEmpty()) put(RDEF.groupBy, groupBy)
        if (excludeEmpty.isNotEmpty()) put(RDEF.excludeEmpty, excludeEmpty)
        // Only when set, so the stored form of every report declared before history existed is unchanged.
        if (history) put(RDEF.history, true)
        chart?.let { put(RDEF.chart, it.toJsonMap()) }
    }

    override fun toString(): String = reportId
}

/**
 * A stored report definition that could not be read (issue #979), kept **as stored**: [definition] verbatim under its
 * slot key [slotKey], and [why] it was refused. Kept, not dropped, because every write of a stored config rebuilds it
 * and serializes it again -- a definition dropped on reading would be deleted from storage by the next edit of
 * anything else in the config (a copy change, a menu label). Kept, it round-trips untouched until someone fixes it.
 */
class UnreadReport(val slotKey: String, val definition: Map<String, Any?>, val why: String)

/**
 * The schema a report definition is validated against before it becomes a [ClientReport] (issue #979): one set of
 * types for the source builder and a definition arriving as data, published so a catalog or a frontend reads the
 * same ones. Array-valued properties declare `allowCoerce`, which defaults off for arrays.
 */
object ReportDefSchema {
    /** The `$defs` of the definition schema, under [RDEF.namespace]. */
    fun defs(cxt: KdrCxtBase): Map<String, Any?> = schemaDefs(cxt, RDEF.namespace) {
        type(RDEF.columnType) {
            type = SCT.kObject
            description = "One column of a report: the value it promotes, and how."
            property(RDEF.columnId, "The column's id within the report: what a run's groupBy and excludeEmpty name.", required = true)
            property(RDEF.label, "The column's heading.", required = true)
            property(RDEF.path, "The report path the column reads, such as form.sample:yearly[2024].note.", required = true)
            property(RDEF.kind, "The value's kind, when it should be read as other than its target says.") { options(ReportKind.entries) }
            property(RDEF.combine, "How the path's several values are made one within a form; absent, the first, or a list for a path with several.") {
                options(ReportCombine.entries)
            }
            property(RDEF.rollup, "How the column is combined across the forms of a group when the report is grouped.") {
                options(ReportCombine.entries)
            }
        }
        type(RDEF.chartType) {
            type = SCT.kObject
            description = "The chart a report's grouped run opens on: a bar or a slice per group."
            property(RDEF.kind, "The kind of chart: a bar per group, or a pie of the groups' shares.", required = true) {
                options(ReportChartKind.entries)
            }
            property(
                RDEF.measure,
                "The column each group is measured by: one with a rollup whose result is a number, not one grouped by, and for " +
                    "a pie one that adds up (a count or a sum). Absent, the count of forms in each group.",
            )
        }
        type(RDEF.defType) {
            type = SCT.kObject
            description = "A named report: the columns a client's forms are shown with, and how they are grouped and filtered by default."
            property(RDEF.reportId, "The report's id: rooted for a component's report, bare for a client's.", required = true)
            commentProperty()
            property(RDEF.label, "What the report is called.", required = true)
            property(RDEF.description, "What the report shows, for the person choosing it.")
            property(RDEF.columns, "The columns, in the order shown.", required = true) {
                type = SCT.array
                allowCoerce = true
                items { ref(RDEF.columnType) }
            }
            property(RDEF.groupBy, "The columns a grouped run groups by, unless the run names others.") {
                type = SCT.array
                allowCoerce = true
                items { type = SCT.string }
            }
            property(RDEF.excludeEmpty, "The columns that must hold a value for a form to appear, unless the run names others.") {
                type = SCT.array
                allowCoerce = true
                items { type = SCT.string }
            }
            property(RDEF.history, "Whether the nightly job stores a snapshot of the report's grouped run, so its groups can be charted over time.") {
                type = SCT.boolean
            }
            property(RDEF.chart, "The chart the report's grouped run opens on; absent, the table alone.") { ref(RDEF.chartType) }
        }
    }

    // Parsed once and kept, as the workflow definition schema is: it is a constant of the runtime.
    private var parsed: Map<String, SchType>? = null

    /** The compiled types, keyed by qualified name. */
    fun types(cxt: KdrCxtBase): Map<String, SchType> = parsed ?: parseSchemaTypes(defs(cxt)).also { parsed = it }

    /** The compiled definition type. */
    fun defType(cxt: KdrCxtBase): SchType = types(cxt).getValue("${RDEF.namespace}.${RDEF.defType}")
}

/**
 * A report definition from its JSON form, or what is wrong with it (issue #979): [raw] validated and coerced
 * against [ReportDefSchema] -- every failure named -- then built into a [ClientReport], which checks its id.
 */
fun clientReportResult(cxt: KdrCxtBase, raw: Map<String, Any?>): Parsed<ClientReport> {
    val id = raw[RDEF.reportId].toOptStr() ?: "(no id)"
    val result = coerceAndValidate(ReportDefSchema.defType(cxt), raw)
    if (result.failures.isNotEmpty()) {
        return Parsed.failed(
            ConvProblem.badFormat,
            "Report definition '$id' is not valid: " +
                result.failures.joinToString("; ") { "${it.path.ifEmpty { "(root)" }}: ${it.message}" },
        )
    }
    val m = result.value.toJsonMapOrEmpty()
    return try {
        Parsed.Ok(
            ClientReport(
                reportId = m[RDEF.reportId].toOptStr() ?: "",
                label = m[RDEF.label].toOptStr() ?: "",
                description = m[RDEF.description].toOptStr(),
                columns = m[RDEF.columns].toJsonListOfMaps().map { c ->
                    ReportColumn(
                        columnId = c[RDEF.columnId].toOptStr() ?: "",
                        label = c[RDEF.label].toOptStr() ?: "",
                        path = c[RDEF.path].toOptStr() ?: "",
                        kind = enumOrNull(ReportKind.entries, c[RDEF.kind]),
                        combine = enumOrNull(ReportCombine.entries, c[RDEF.combine]),
                        rollup = enumOrNull(ReportCombine.entries, c[RDEF.rollup]),
                    )
                },
                groupBy = m[RDEF.groupBy].toJsonListOrEmpty().mapNotNull { it.toOptStr() },
                excludeEmpty = m[RDEF.excludeEmpty].toJsonListOrEmpty().mapNotNull { it.toOptStr() },
                history = m[RDEF.history] == true,
                // The schema requires a chart's kind and holds it to the closed set, so a chart present has one.
                chart = m[RDEF.chart]?.let { raw ->
                    val c = raw.toJsonMapOrEmpty()
                    enumOrNull(ReportChartKind.entries, c[RDEF.kind])?.let { ReportChart(it, c[RDEF.measure].toOptStr()) }
                },
                comment = m[SCH.dComment].toOptStr(),
            ),
        )
    } catch (e: KdrException) {
        Parsed.failed(ConvProblem.badFormat, e.message ?: "Report definition '$id' is not valid.")
    }
}

/** [clientReportResult], throwing its problem: what a config builder calls, as it calls `parseWfDef`. */
fun parseClientReport(cxt: KdrCxtBase, raw: Map<String, Any?>): ClientReport = clientReportResult(cxt, raw).orThrow()

/** The entry of [entries] named by [value], or null when it is absent. The schema has already refused a wrong name. */
private fun <E : Enum<E>> enumOrNull(entries: List<E>, value: Any?): E? = value.toOptStr()?.let { v -> entries.firstOrNull { it.name == v } }

/**
 * Builds a [ClientReport] in source (issue #979), as `WfDefBuilder` builds a workflow: the result goes through the
 * same JSON form and parser a stored definition does, so the two cannot accept different things.
 *
 * ```kotlin
 * report("auditOverview", "Audit overview") {
 *     column("auditor", "Auditor", "form.acmeSiteAudit.auditor")
 *     column("total", "Total", "form.sample:expenseReport.totalAmount", rollup = ReportCombine.sum)
 *     groupBy = listOf("auditor")
 *     chart(ReportChartKind.bar, measure = "total")
 * }
 * ```
 */
class ReportBuilder(private val reportId: String, private val label: String) {
    var description: String? = null
    var groupBy: List<String> = emptyList()
    var excludeEmpty: List<String> = emptyList()
    /** Whether the nightly job stores a snapshot of the grouped run (issue #1033). */
    var history: Boolean = false
    /** The author's note on the report (issue #1135), stored as its `$comment`; it changes nothing. */
    var comment: String? = null
    /** The chart the grouped run opens on (issue #1771); [chart] sets it. */
    var chart: ReportChart? = null
    private val columns = mutableListOf<ReportColumn>()

    /** Opens the grouped run on a [kind] of chart of [measure] -- a column id -- or, absent, of each group's count of forms. */
    fun chart(kind: ReportChartKind, measure: String? = null) {
        chart = ReportChart(kind, measure)
    }

    fun column(
        columnId: String,
        label: String,
        path: String,
        kind: ReportKind? = null,
        combine: ReportCombine? = null,
        rollup: ReportCombine? = null,
    ) {
        columns.add(ReportColumn(columnId, label, path, kind, combine, rollup))
    }

    /** The definition's JSON form, for the parser. */
    fun build(): Map<String, Any?> =
        ClientReport(reportId, label, columns.toList(), description, groupBy, excludeEmpty, history, chart, comment).toJsonMap()
}
