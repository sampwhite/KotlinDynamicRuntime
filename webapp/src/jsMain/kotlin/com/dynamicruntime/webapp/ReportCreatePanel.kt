package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.report.ReportChartKind
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useState
import web.cssom.ClassName

private val reportCreateScope = MainScope()

external interface ReportCreatePanelProps : Props {
    /** The client named in the page's address, for an administrator who sees every client; null for the caller's own. */
    var client: String?

    /** Whether a report made here is a draft, as the listing said. */
    var createsDraft: Boolean

    /** What the panel's "What is this?" says. */
    var explanation: String

    /** Called with the new report's id once it is made. */
    var onCreated: (String) -> Unit

    var onCancel: () -> Unit
}

/**
 * Making a report from simple choices (issue #1492): a draft panel whose one save is **Create report**. The kind of
 * report, its name, then its columns, picked from the paths the server says bind (`ReportsApi.paths`); a totals report
 * also says what it groups by and how each other column is combined. One-way: a report made here is changed in the
 * bundle editor afterwards. The logic is `ReportCreateModel.kt`'s.
 */
val ReportCreatePanel = FC<ReportCreatePanelProps> { props ->
    var catalog by useState<ReportPathCatalogView?>(null)
    var loadError by useState<DisplayError?>(null)
    var draft by useState(ReportDraft())
    var nextId by useState(1)
    var saving by useState(false)
    var failure by useState<DisplayError?>(null)

    useEffect(props.client) {
        reportCreateScope.launch {
            try {
                catalog = ReportsApi.paths(props.client)
                loadError = null
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            }
        }
    }

    // Every change goes through here, so a chart's measure that can no longer be measured is forgotten, not revived.
    fun change(next: ReportDraft) {
        draft = next.withChartMeasureSettled(reportDraftMeasures(next, catalog))
    }

    // What the chart may measure, worked out once a render for the problems, the Chart choice, and the request.
    val measures = reportDraftMeasures(draft, catalog)
    val problems = reportDraftProblems(draft, measures)
    val dirty = draft != ReportDraft()
    val totals = draft.shape == ReportShape.totals

    fun create() {
        saving = true
        failure = null
        reportCreateScope.launch {
            try {
                val made = ReportsApi.create(
                    reportCreateRequest(props.client, draft, if (props.createsDraft) EDM.draft else EDM.live, measures),
                )
                saving = false
                props.onCreated(made.reportId)
            } catch (e: Throwable) {
                saving = false
                failure = userFacingError(e)
            }
        }
    }

    panel("report-new", "New report", props.explanation, PanelKind.draft, dirty = dirty) {
        p {
            className = ClassName("subtitle")
            +"Made once from these choices. Afterwards it is changed in the client's bundle editor, and removed there or here."
        }
        loadError?.let { errorText("Couldn't load what a report may show.", it) }
        choiceRow("Kind of report") {
            Select {
                value = draft.shape.name
                options = choiceOptions(ReportShape.entries.map { it.label to it.name })
                disabled = saving
                style = js("({ minWidth: 220 })")
                onChange = { v -> ReportShape.entries.firstOrNull { it.name == v }?.let { change(draft.withShape(it)) } }
            }
        }
        p {
            className = ClassName("type-hint")
            +(if (totals) "A row for each value of the column it groups by, with how many forms have it and the other columns combined."
            else "A row for each form, with the columns chosen below.")
        }
        choiceRow("Name") {
            Input {
                value = draft.name
                placeholder = "What the report is called"
                disabled = saving
                style = js("({ maxWidth: 360 })")
                onChange = { e -> change(draft.copy(name = e.target.value as String)) }
            }
        }
        choiceRow("Description") {
            Input {
                value = draft.description
                placeholder = "What it shows, for the person choosing it (optional)"
                disabled = saving
                style = js("({ maxWidth: 560 })")
                onChange = { e -> change(draft.copy(description = e.target.value as String)) }
            }
        }
        h4 { +"Columns" }
        val shown = catalog
        if (draft.columns.isNotEmpty() && shown != null) columnsTable(draft, shown, saving) { change(it) }
        if (shown != null) {
            choiceRow("Add a column") {
                Select {
                    value = null
                    placeholder = "Pick what it shows"
                    options = pathOptions(shown)
                    disabled = saving
                    // Narrowed by what a person reads -- the label -- not the path each option's value is.
                    showSearch = js("({ optionFilterProp: 'label' })")
                    style = js("({ minWidth: 360 })")
                    onChange = { v ->
                        shown.path(v.toString())?.let {
                            change(draft.withColumn(newReportColumn(nextId, it)))
                            nextId += 1
                        }
                    }
                }
            }
        } else if (loadError == null) {
            p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        }
        if (totals) {
            val groupable = groupableColumns(draft)
            choiceRow("Group by") {
                Select {
                    value = draft.groupBy?.toString()
                    placeholder = if (groupable.isEmpty()) "Add a column with one value per form" else "The column it groups by"
                    options = choiceOptions(groupable.map { it.label.ifBlank { "(no heading)" } to it.id.toString() })
                    disabled = saving || groupable.isEmpty()
                    style = js("({ minWidth: 260 })")
                    onChange = { v -> change(draft.copy(groupBy = v.toString().toIntOrNull())) }
                }
            }
            p {
                className = ClassName("type-hint")
                +"Only a column with one value per form can be grouped by: not one showing all of several values."
            }
            p {
                Checkbox {
                    checked = draft.history
                    disabled = saving
                    onChange = { e -> change(draft.copy(history = e.target.checked == true)) }
                    +"Keep a daily snapshot"
                }
            }
            p {
                className = ClassName("type-hint")
                +"Use this when its History view should chart how its totals change from day to day; one is taken each night."
            }
            chartChoice(draft, measures, saving) { change(it) }
        }
        if (dirty && problems.isNotEmpty()) {
            ul {
                className = ClassName("wf-reasons")
                problems.forEachIndexed { i, text ->
                    li {
                        key = i.toString().unsafeCast<Key>()
                        +text
                    }
                }
            }
        }
        SaveBar {
            saveText = "Create report"
            effectText = saveEffectText(props.createsDraft)
            this.saving = saving
            canSave = dirty && problems.isEmpty() && catalog != null
            this.failure = failure
            failureLead = "Couldn't create the report."
            onSave = { create() }
            onDiscard = { props.onCancel() }
        }
    }
}

/**
 * A totals report's chart (issue #1773): the kind it opens on, as the Grouped view's Chart offers it -- a pie only of a
 * measure that adds up -- and, for a chart, what it measures among [measures], as the Grouped view's Of does. [update]
 * takes the draft as changed.
 */
private fun ChildrenBuilder.chartChoice(draft: ReportDraft, measures: List<ReportMeasure>, busy: Boolean, update: (ReportDraft) -> Unit) {
    val measure = reportDraftMeasure(draft, measures)
    choiceRow("Chart") {
        Select {
            value = draft.chart?.name ?: reportChartTable
            options = arrayOf(
                choiceOption("Table only", reportChartTable),
                choiceOption("Bar chart", ReportChartKind.bar.name),
                choiceOption("Pie chart", ReportChartKind.pie.name, disabled = !measure.additive),
            )
            disabled = busy
            style = js("({ minWidth: 220 })")
            onChange = { v -> update(draft.copy(chart = ReportChartKind.entries.firstOrNull { it.name == v as? String })) }
        }
    }
    if (draft.chart != null) {
        choiceRow("Of") {
            Select {
                value = measure.key
                options = choiceOptions(measures.map { it.label to it.key })
                disabled = busy || measures.size < 2
                style = js("({ minWidth: 260 })")
                onChange = { v -> update(draft.copy(chartMeasure = (v as? String)?.toIntOrNull())) }
            }
        }
    }
    p {
        className = ClassName("type-hint")
        +"Use this when the report should open on a chart of its groups, above its table; a viewer can change it on the page."
    }
}

/** The paths as the Add-a-column choice lists them: grouped by source, each labeled as a person knows it. */
private fun pathOptions(catalog: ReportPathCatalogView): Array<dynamic> = catalog.groups.map { g ->
    val group: dynamic = js("({})")
    group.label = g.label
    group.options = choiceOptions(g.paths.map { it.label to it.path })
    group
}.toTypedArray()

/**
 * The drafted columns, one row each: its heading, what it shows, which value of several, how a totals report combines
 * it, whether a form needs a value in it, and Remove. [update] takes the draft as changed.
 */
private fun ChildrenBuilder.columnsTable(draft: ReportDraft, catalog: ReportPathCatalogView, busy: Boolean, update: (ReportDraft) -> Unit) {
    val totals = draft.shape == ReportShape.totals
    div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { +"Heading" }
                    th { +"Shows" }
                    th { +"Which value" }
                    if (totals) th { +"Combined" }
                    th { +"Must have a value" }
                    th {}
                }
            }
            tbody {
                draft.columns.forEach { column ->
                    val path = catalog.path(column.path)
                    fun change(f: (ReportColumnDraft) -> ReportColumnDraft) = update(draft.withColumnChanged(column.id, f))
                    tr {
                        key = column.id.toString().unsafeCast<Key>()
                        td {
                            Input {
                                value = column.label
                                disabled = busy
                                style = js("({ minWidth: 160 })")
                                onChange = { e -> change { it.copy(label = e.target.value as String) } }
                            }
                        }
                        td {
                            +(path?.label ?: column.path)
                            catalog.groupOf(column.path)?.let {
                                span {
                                    className = ClassName("type-hint")
                                    +" · ${it.label}"
                                }
                            }
                        }
                        td { whichValue(column, path, busy, ::change) }
                        if (totals) td {
                            if (column.id == draft.groupBy) {
                                span {
                                    className = ClassName("type-hint")
                                    +"Grouped by"
                                }
                            } else {
                                val kind = reportColumnKind(column, path)
                                Select {
                                    value = column.rollup?.name ?: ""
                                    options = choiceOptions(
                                        listOf("Not shown when grouped" to "") +
                                            rollupChoices(column, path).map { rollupText(it, kind) to it.name },
                                    )
                                    disabled = busy
                                    style = js("({ minWidth: 180 })")
                                    onChange = { v ->
                                        change { c -> c.copy(rollup = rollupChoices(c, path).firstOrNull { it.name == v }) }
                                    }
                                }
                            }
                        }
                        td {
                            Checkbox {
                                checked = column.mustHaveValue
                                disabled = busy
                                onChange = { e -> change { it.copy(mustHaveValue = e.target.checked == true) } }
                            }
                        }
                        td {
                            Button {
                                type = "link"
                                disabled = busy
                                onClick = { update(draft.withoutColumn(column.id)) }
                                +"Remove"
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A column's choice of which value to show, for a path with several, and the key naming one entry when it shows one. */
private fun ChildrenBuilder.whichValue(
    column: ReportColumnDraft,
    path: ReportPathView?,
    busy: Boolean,
    change: ((ReportColumnDraft) -> ReportColumnDraft) -> Unit,
) {
    val picks = path?.let(::valuePicks).orEmpty()
    if (picks.isEmpty()) {
        span {
            className = ClassName("type-hint")
            +"Its one value"
        }
        return
    }
    Select {
        value = column.pick?.name
        options = choiceOptions(picks.map { it.label to it.name })
        disabled = busy
        style = js("({ minWidth: 160 })")
        onChange = { v -> ValuePick.entries.firstOrNull { it.name == v }?.let { pick -> change { it.withPick(pick, path) } } }
    }
    if (column.pick == ValuePick.byKey && path != null) {
        path.keyFields.forEachIndexed { i, field ->
            div {
                key = field.field.unsafeCast<Key>()
                Input {
                    value = column.key.getOrNull(i).orEmpty()
                    placeholder = field.label
                    disabled = busy
                    style = js("({ maxWidth: 160 })")
                    onChange = { e ->
                        val typed = e.target.value as String
                        change { c ->
                            c.copy(key = path.keyFields.indices.map { j -> if (j == i) typed else c.key.getOrNull(j).orEmpty() })
                        }
                    }
                }
            }
        }
    }
}
