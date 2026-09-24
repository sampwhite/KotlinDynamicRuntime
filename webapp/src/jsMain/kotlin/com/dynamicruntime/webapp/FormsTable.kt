package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.strong
import react.dom.html.ReactHTML.ul
import react.useRef
import react.useState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import web.cssom.ClassName
import com.dynamicruntime.common.gedra.GSORT
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.schema.PSTAT

/**
 * The caller's form documents as an antd table: one row per form, most recently written first as the endpoint
 * returns them (issue #562), with a User column for a caller who sees other users' documents.
 * The list is the hub for the whole lifecycle (issue #417): a **row click** is the default open -- the survey's
 * **View Info** where the client has a survey, else the raw read-only view (issue #694) -- and a per-row
 * **Actions** column carries **View Info** (the survey's read-only on-boarding view, with its Edit toggle) and
 * Delete, so neither needs the form opened first. There is no separate "View All" action (issue #726): the raw
 * view is the row click where there is no survey, and the raw *editor* is reached from the survey view's Raw
 * edit and the raw view's Edit form. The survey-status chip on an unfinished row is itself a link straight into
 * the survey's edit mode. Delete arms an inline confirm in the row rather than navigating, since it is the one
 * irreversible action here.
 *
 * Presentational: every value is a [FormSummary] the parent already computed and every action is a callback the
 * parent owns, so the table itself knows nothing about gedra shapes or endpoints. An action a caller's surface
 * cannot perform is not offered ([canDelete]), the same "do not show a control that cannot work" rule
 * the view follows.
 */
external interface FormsTableProps : Props {
    /** Each form's id paired with its summary, in display order. */
    var forms: List<Pair<String, FormSummary>>

    /** Opens the raw read-only view of a form: the row click where the client has no survey (issue #726). */
    var onView: (String) -> Unit

    /** Whether the caller's surface carries the delete endpoint, so a Delete action can work. */
    var canDelete: Boolean

    /** The gedra id of a row to flash briefly -- the form just saved on the edit page (issue #592); null for
     *  none. The flash is a one-shot CSS animation on the row, so it fades on its own. */
    var highlightId: String?

    /**
     * Whether to draw the User column (issue #562): true for a caller who administers other users, whose rows
     * carry an owner. An ordinary caller's rows are all their own, so the column would say one name over and
     * over -- and the backend sends no owner for them anyway.
     */
    var showOwner: Boolean

    /**
     * Whether to draw the Client column (issue #668): true for a caller who administers across clients
     * (`allClients`), whose listing spans clients. Every row already carries its client, so this is purely which
     * callers see the column -- the cross-client counterpart of [showOwner].
     */
    var showClient: Boolean

    /** Opens the survey's read-only "View Info" view for a form (issue #694), from which its Edit toggle edits. */
    var onSurveyView: (String) -> Unit

    /** The `href` of the survey's **edit-mode** page for a form (issue #694) -- what the Needs Info / Invalid
     *  status chip links to, landing straight on the fields and bypassing the read-only view. */
    var surveyEditHref: (String) -> String

    /**
     * The workflow column's summary (issue #791): what it may show, over every form the caller may see. Empty
     * means no column at all, even for rows that would show nothing.
     */
    var workflowSummary: List<WorkflowSummaryEntry>

    /** What a row with no workflows shows (issue #791): the client's Markdown copy, or null for a dash. */
    var noWorkflowsCopy: String?

    /**
     * The `href` that opens normal workflow `workflowId` against form `gedraId` (issue #791), on `task` when given
     * (the current task of an engaged workflow, the last of a finished one).
     */
    var workflowHref: (gedraId: String, workflowId: String, task: String?) -> String

    /** The form whose Delete is armed (showing the inline confirm), or null when none is. */
    var confirmingDeleteId: String?

    /** The form whose delete is in flight, for the confirm button's spinner. */
    var deletingId: String?

    /** Arms the inline delete confirm on a row. */
    var onArmDelete: (String) -> Unit

    /** Dismisses the inline delete confirm without deleting. */
    var onCancelDelete: () -> Unit

    /** Performs the delete of a form, then (the parent) reloads the page. */
    var onConfirmDelete: (String) -> Unit

    /** The column the list is currently sorted by (issue #666) -- a display trait id, or `updated` / `created`
     *  -- or null for the default order. Drives which header shows the sort arrow. */
    var sortColumn: String?

    /** Whether the current sort is descending. */
    var sortDescending: Boolean

    /** Called when a header sort changes: the new column (a trait id, or `updated`/`created`) and direction, or
     *  null when the sort is cleared (back to the default order). The parent re-fetches. */
    var onSort: (column: String?, descending: Boolean) -> Unit
}

val FormsTable = FC<FormsTableProps> { props ->
    // The display columns a client's trait-usage rules declared (issue #537): every row carries the same set
    // (the backend attaches all of them), so any row's list gives the columns and their order. A client with
    // no usage rules has none, and `Contains` carries the identity, as it did before this was configurable.
    val displayCols = props.forms.firstOrNull()?.second?.displayValues ?: emptyList()
    // Draw the Status column, and offer "View Info", only when some row carries a survey status (issue #694): a
    // client with no survey has none on any row, so an empty column or a dead-end action would say nothing. (A
    // persistent store's rows written before the survey deriver existed carry none until re-touched or
    // batch-recomputed -- a documented, deferred gap.)
    val anySurveyStatus = props.forms.any { it.second.surveyStatus != null }
    // Whether the Actions column has anything to carry (issue #726 review): View Info needs a survey, Delete needs
    // its endpoint; with neither there is no column, not an empty one.
    val showActions = anySurveyStatus || props.canDelete
    Table {
        size = "small"
        // Declared widths mean what they say (see `TableProps.tableLayout`): under the default auto layout the
        // User column's unbreakable email would take width from the two date columns, whose content is fixed
        // and cannot give way, and wrap them -- the failure the user table met first. `Contains`, the one
        // column with no width, takes what is left -- and the minimum width below guarantees it a real share,
        // so a card too narrow for the whole set scrolls the table rather than crushing that column.
        tableLayout = "fixed"
        pagination = false
        rowKey = "key"
        // Server-side sort (issue #666): antd reports the header the caller clicked and its direction. The
        // dataIndex it reports **is** the endpoint's sort key -- a display column's is namespaced
        // (`display_<traitId>`), which is exactly what keeps a trait named like a fixed column from colliding
        // with it, so it is sent as-is. Order is undefined on the third click, which clears the sort.
        onChange = { _, _, sorter ->
            val order = sorter.order as? String
            val field = sorter.field as? String
            if (order == null || field == null) props.onSort(null, false) else props.onSort(field, order == "descend")
        }
        val cols = buildList {
            // Namespaced key: a usage trait id must not shadow the reserved row key ("key") or a fixed column
            // ("contains"/"owner"/"updated"/"created"/"actions") -- overwriting the row key would break which
            // form a click opens.
            // Sortable (issue #666): a display column orders by its trait, the date columns by their value. The
            // header arrow is antd's, controlled from `sortColumn`/`sortDescending`; the sort key sent to the
            // endpoint is the column's dataIndex -- namespaced for a display column, `updated`/`created` for a date.
            displayCols.forEach {
                add(sortableColumn(it.label, displayColKey(it.traitId), 160, props.sortColumn, props.sortDescending))
            }
            add(sortableColumn("Contains", GSORT.contains, null, props.sortColumn, props.sortDescending))
            if (props.showOwner) add(ownerColumn(props.sortColumn, props.sortDescending))
            // The Client column (issue #668), for a cross-client (`allClients`) caller, reading each row's own
            // `client`. Sortable by the row's client protocol field (`allClients`-only on the backend, as the
            // column is), keyed on `GSORT.client` -- both the row-data key and the endpoint's sort key.
            if (props.showClient) add(sortableColumn("Client", GSORT.client, 140, props.sortColumn, props.sortDescending))
            // The global survey-status column (issue #694): a fixed, non-sortable column (its sort/filter is
            // deferred to #695), rendering a status chip and a CTA on the unfinished rows.
            if (anySurveyStatus) add(statusColumn(props))
            // The workflow column (issue #791): drawn when the summary names any workflow -- decided over every form
            // the caller may see, not this page, so it does not come and go as a search narrows.
            if (props.workflowSummary.isNotEmpty()) add(workflowColumn(props))
            add(sortableColumn("Updated", GSORT.updated, 175, props.sortColumn, props.sortDescending))
            add(sortableColumn("Created", GSORT.created, 175, props.sortColumn, props.sortDescending))
            // View Info (survey clients) and Delete (where its endpoint is on the surface). With "View All" gone
            // (issue #726) the column can have nothing to show -- a no-survey client whose caller cannot delete
            // -- and then it is not drawn at all: an empty column pinned right would be a blank sticky band
            // whose shadow overlays every scrolled row. The row click is the open there.
            if (showActions) add(actionsColumn(props, showSurvey = anySurveyStatus))
        }
        // Pinned while the table scrolls sideways (issue #726 follow-up): the first column -- the client's first
        // display column, or `Contains` when it declares none, either way the row's identity -- stays on the
        // left, and Actions, when drawn, stays on the right, so a row can be told apart and acted on at any
        // scroll position rather than the identity leaving the viewport just as Actions arrives. antd renders a
        // `fixed` column as sticky, which needs only the `scroll.x` set below.
        cols.first().fixed = "left"
        if (showActions) cols.last().fixed = "right"
        columns = cols.toTypedArray()
        scroll = minTableWidth(cols)
        dataSource = props.forms.map { (id, summary) ->
            val row: dynamic = js("({})")
            row.key = id
            // Each declared column's value for this row, under the same namespaced key as its column; a blank
            // cell where the row has none.
            summary.displayValues.forEach { row[displayColKey(it.traitId)] = it.value.ifBlank { "—" } }
            row.contains = summary.traitLabels.joinToString(", ")
            row.updated = summary.updatedAt ?: ""
            row.created = summary.createdAt ?: ""
            row.ownerName = summary.ownerName
            row.ownerEmail = summary.ownerEmail
            // The Client column reads this under its `GSORT.client` dataIndex (issue #668).
            row[GSORT.client] = summary.client.ifBlank { "—" }
            // The workflow cell's items (issue #791), joined to the summary here so the cell only draws.
            row.wfItems = workflowCellOf(summary.states, summary.client, props.workflowSummary)
            // The survey status for the fixed Status column (issue #694): its chip label + colour class, and
            // whether it warrants the CTA (anything but Valid). Absent on a row with no survey state.
            summary.surveyStatus?.let {
                row.svyLabel = it.label
                row.svyPstat = it.pstat
                // The unfinished chip links straight into the survey's edit mode; a workflow's Needs Review or
                // Finished chip (issue #789) opens the workflows behind it instead; Valid is a plain chip.
                val cfact = it.singletonCfact
                when {
                    cfact != null -> {
                        row.svyCfact = cfact
                        row.svyGedraId = id
                    }
                    it != SurveyStatus.valid -> row.svyEditHref = props.surveyEditHref(id)
                }
            }
            row
        }.toTypedArray()
        onRow = { record, _ ->
            val handlers: dynamic = js("({})")
            // The row click is the default open (issue #694): the survey's "View Info" where the client has a
            // survey -- the friendlier on-boarding view, with its Edit toggle and a "Raw edit" escape -- else the
            // raw read-only view. The same signal gates the Status column and the View Info action, so the three
            // can never disagree about whether a survey exists (it is a proxy read off the loaded rows; a
            // persistent store's pre-deriver rows fall back to the raw view until re-touched or batch-recomputed).
            handlers.onClick = {
                val id = record.key as String
                if (anySurveyStatus) props.onSurveyView(id) else props.onView(id)
            }
            handlers.style = js("({ cursor: 'pointer' })")
            // The just-saved form flashes on arrival from the edit page (issue #592).
            if (props.highlightId != null && record.key == props.highlightId) {
                handlers.className = "forms-row-highlight"
            }
            handlers
        }
    }
}

/**
 * The per-row Actions column (issue #417): **View Info** (the survey's read-only view; offered when the client
 * has a survey, issue #694) and Delete when its endpoint is on the surface. The former "View All" is gone
 * (issue #726): it duplicated the row click. Delete arms an inline confirm on the row it belongs to
 * rather than acting on the first click. `onCell` stops a click anywhere in this cell from bubbling to the row's
 * own click handler, so using an action never also opens the view. The cell content is a component
 * ([FormRowActions]) rendered per row, since the render callback must return a React node.
 */
private fun actionsColumn(props: FormsTableProps, showSurvey: Boolean): dynamic {
    val c = column("Actions", "actions", 230)
    // Any click inside the actions cell is for an action, not for opening the row -- keep it from reaching the
    // row's onClick.
    c.onCell = {
        val cellProps: dynamic = js("({})")
        cellProps.onClick = { event: dynamic -> event.stopPropagation() }
        cellProps
    }
    c.render = fun(_: dynamic, record: dynamic, _: dynamic): dynamic {
        val id = record.key as String
        return FormRowActions.create {
            this.id = id
            this.showSurvey = showSurvey
            this.onSurveyView = props.onSurveyView
            this.canDelete = props.canDelete
            this.confirming = props.confirmingDeleteId == id
            this.deleting = props.deletingId == id
            this.onArmDelete = props.onArmDelete
            this.onCancelDelete = props.onCancelDelete
            this.onConfirmDelete = props.onConfirmDelete
        }
    }
    return c
}

/**
 * The global survey-status column (issue #694): a status chip which, on an unfinished (Invalid / Needs Info)
 * row, **is itself the link** straight into the survey's edit mode -- a real `<a href>`, so it is keyboard
 * reachable and can open in a new tab; Valid is a plain chip. Like [actionsColumn], `onCell` stops a click
 * inside the cell from reaching the row's own handler, so following the chip does not also open the raw view.
 * A row with no survey status renders an em dash. The chip label/colour and the edit href are read off the row
 * (set in the dataSource above), so the table stays free of the `SurveyStatus` enum itself.
 */
private fun statusColumn(props: FormsTableProps): dynamic {
    val c = column("Status", "status", 150)
    c.onCell = {
        val cellProps: dynamic = js("({})")
        cellProps.onClick = { event: dynamic -> event.stopPropagation() }
        cellProps
    }
    c.render = fun(_: dynamic, record: dynamic, _: dynamic): dynamic {
        return FormStatusCell.create {
            this.label = record.svyLabel as? String
            this.pstat = record.svyPstat as? String
            this.editHref = record.svyEditHref as? String
            this.cfact = record.svyCfact as? String
            this.gedraId = record.svyGedraId as? String
        }
    }
    return c
}

private external interface FormStatusCellProps : Props {
    /** The status chip's label ("Valid"/"Needs Info"/"Invalid"), or null for a row with no survey status. */
    var label: String?
    /** The [PSTAT] colour class for the chip; null falls back to the neutral colour. */
    var pstat: String?
    /** The survey edit-mode href the chip links to on an unfinished row; null makes it a plain chip. */
    var editHref: String?
    /** On a Needs Review / Finished chip (issue #789): the singleton cfact whose workflows its click lists. */
    var cfact: String?
    /** The form the chip belongs to, for that lookup. */
    var gedraId: String?
}

/** One Status cell: the coloured chip -- a link straight into the survey's edit mode on an unfinished row. */
private val FormStatusCell = FC<FormStatusCellProps> { props ->
    val label = props.label
    val cls = ClassName("op-status " + (props.pstat ?: PSTAT.info))
    val href = props.editHref
    val cfact = props.cfact
    val gedraId = props.gedraId
    when {
        label == null -> +"—"
        cfact != null && gedraId != null -> SingletonChip {
            this.label = label
            this.pstat = props.pstat
            this.cfact = cfact
            this.gedraId = gedraId
        }
        href != null -> a {
            className = cls
            this.href = href
            // Belt and braces with the column's onCell: the chip's own click must not also open the row.
            onClick = { it.stopPropagation() }
            +label
        }
        else -> span {
            className = cls
            +label
        }
    }
}

/**
 * The User column (issue #562), for a caller who sees other users' documents: the owner's name with the email
 * in small type beneath, or the email alone. The backend sends `ownerName` only when the account has a name
 * that is not its email, so the cell renders what arrives rather than comparing the two.
 */
private fun ownerColumn(sortColumn: String?, descending: Boolean): dynamic {
    // Sortable by the owner name (issue #666); the cell still renders the name-over-email block, so the sort key
    // is the column's `owner` dataIndex while the render reads `ownerName`/`ownerEmail` off the row.
    val c = sortableColumn("User", GSORT.owner, 200, sortColumn, descending)
    c.render = fun(_: dynamic, record: dynamic, _: dynamic): dynamic = FormOwnerCell.create {
        name = record.ownerName as? String
        email = record.ownerEmail as? String
    }
    return c
}

private external interface FormOwnerCellProps : Props {
    var name: String?
    var email: String?
}

private val FormOwnerCell = FC<FormOwnerCellProps> { props ->
    val email = props.email
    val name = props.name
    when {
        email == null -> +"—"
        name == null -> +email
        else -> div {
            +name
            span {
                className = ClassName("owner-email")
                +email
            }
        }
    }
}

/** The one row's action buttons, resolved to this row's id -- see [actionsColumn]. */
private external interface FormRowActionsProps : Props {
    var id: String
    var showSurvey: Boolean
    var onSurveyView: (String) -> Unit
    var canDelete: Boolean
    var confirming: Boolean
    var deleting: Boolean
    var onArmDelete: (String) -> Unit
    var onCancelDelete: () -> Unit
    var onConfirmDelete: (String) -> Unit
}

private val FormRowActions = FC<FormRowActionsProps> { props ->
    span {
        className = ClassName("row-actions")
        // The survey's read-only on-boarding view (issue #694) -- only where the client has a survey.
        if (props.showSurvey) {
            Button {
                type = "link"
                size = "small"
                onClick = { props.onSurveyView(props.id) }
                +"View Info"
            }
        }
        if (props.canDelete) {
            if (props.confirming) {
                span {
                    className = ClassName("subtitle")
                    +"Delete?"
                }
                Button {
                    type = "link"
                    size = "small"
                    danger = true
                    loading = props.deleting
                    onClick = { props.onConfirmDelete(props.id) }
                    +"Yes"
                }
                Button {
                    type = "link"
                    size = "small"
                    onClick = { props.onCancelDelete() }
                    +"Cancel"
                }
            } else {
                Button {
                    type = "link"
                    size = "small"
                    danger = true
                    onClick = { props.onArmDelete(props.id) }
                    +"Delete"
                }
            }
        }
    }
}

/** What the width-less `Contains` column is guaranteed under the fixed layout, in px -- see [minTableWidth]. */
private const val containsMinWidth = 200

/**
 * The table's minimum width as an antd `scroll` config: every declared column width plus [containsMinWidth]
 * for the one column declared without one. Above this the width-less column absorbs the extra; below it the
 * table scrolls inside its wrapper, so no card width crushes a column to nothing.
 */
private fun minTableWidth(cols: List<dynamic>): dynamic {
    val declared = cols.sumOf { (it.width as? Int) ?: 0 }
    val scroll: dynamic = js("({})")
    scroll.x = declared + containsMinWidth
    return scroll
}

/** The antd row/column key for a display column: a trait id, namespaced so it cannot shadow the reserved
 *  row key ("key") or the fixed "contains"/"owner"/"updated"/"created"/"actions" columns (issue #537). */
private fun displayColKey(traitId: String): String = GSORT.displayColumnPrefix + traitId

/**
 * An antd column that sorts server-side (issue #666): `sorter = true` with a **controlled** `sortOrder`, so the
 * arrow reflects [sortColumn]/[descending] the parent holds rather than antd sorting the page itself. The
 * [dataIndex] is both the row-data key and the endpoint's sort key -- namespaced (`display_<traitId>`) for a
 * display column, so it cannot be read as a fixed `updated`/`created` column.
 */
private fun sortableColumn(
    title: String,
    dataIndex: String,
    width: Int?,
    sortColumn: String?,
    descending: Boolean,
): dynamic {
    val c = column(title, dataIndex, width)
    c.sorter = true
    // The dataIndex is the endpoint's sort key (a display column's is namespaced); the arrow shows only on it.
    c.sortOrder = if (sortColumn == dataIndex) (if (descending) "descend" else "ascend") else null
    return c
}

/** Builds an antd column config `{ title, dataIndex, key, width? }`. */
private fun column(title: String, dataIndex: String, width: Int?): dynamic {
    val c: dynamic = js("({})")
    c.title = title
    c.dataIndex = dataIndex
    c.key = dataIndex
    if (width != null) c.width = width
    return c
}

private external interface SingletonChipProps : Props {
    var label: String
    var pstat: String?
    var cfact: String
    var gedraId: String
}

/**
 * A Needs Review / Finished chip (issue #789): a button-like chip that opens a popover listing the workflows behind
 * it -- each one's name and what its current task asks of *this* caller (the approve button's words for a reviewer,
 * a wait for anyone else). Fetched the first time it opens, not for every row: the answer is per caller and per
 * form, and most chips are never opened.
 */
private val SingletonChip = FC<SingletonChipProps> { props ->
    var open by useState(false)
    var workflows by useState<List<SingletonWorkflow>?>(null)
    var failed by useState<String?>(null)
    // Which load is the latest: a reopen starts a new one, and an older answer arriving after it is dropped.
    val loadSeq = useRef(0)

    // Fetched on every open, not once: the row can stay mounted while a reviewer acts elsewhere, and the status
    // alone does not say so (open findings keep a form at Needs Review). The last answer stays up meanwhile.
    fun load() {
        val seq = (loadSeq.current ?: 0) + 1
        loadSeq.current = seq
        chipScope.launch {
            try {
                val fresh = WorkflowApi.fetchSingletonWorkflows(props.gedraId, props.cfact)
                if (loadSeq.current == seq) {
                    workflows = fresh
                    failed = null
                }
            } catch (e: Throwable) {
                if (loadSeq.current == seq) failed = userFacingError(e).text
            }
        }
    }

    Popover {
        this.open = open
        trigger = "click"
        placement = "bottomLeft"
        title = props.label
        onOpenChange = { next ->
            open = next
            if (next) load()
        }
        content = SingletonChipBody.create {
            this.workflows = workflows
            this.failed = failed
        }
        // A real button, so the chip is reachable and operable from the keyboard like any control; styled as a
        // chip (`button.op-status`). The type is set as a plain attribute, as the catalog does, so the chip can
        // never submit a form it ends up inside.
        button {
            className = ClassName("op-status " + (props.pstat ?: PSTAT.info))
            asDynamic()["type"] = "button"
            onClick = { it.stopPropagation() }
            +props.label
        }
    }
}

private external interface SingletonChipBodyProps : Props {
    var workflows: List<SingletonWorkflow>?
    var failed: String?
}

/** What the chip's popover shows: loading, a failure, or one line per workflow -- its name, then its action. */
private val SingletonChipBody = FC<SingletonChipBodyProps> { props ->
    val workflows = props.workflows
    when {
        props.failed != null -> p { className = ClassName("error-text"); +props.failed!! }
        workflows == null -> p { +"Loading\u2026" }
        workflows.isEmpty() -> p { +"No workflow is behind this status any more." }
        else -> ul {
            className = ClassName("chip-workflows")
            workflows.forEach { wf ->
                li {
                    key = wf.workflowId.unsafeCast<Key>()
                    strong { +wf.label }
                    wf.actionText?.let { +" \u2014 $it" }
                }
            }
        }
    }
}

private val chipScope = MainScope()

/**
 * The workflow column (issue #791): each row's workflows in cell order -- in progress, available, finished, not
 * available -- the first two as links and the rest behind a count. Like [statusColumn], `onCell` stops a click in
 * the cell from also opening the row.
 */
private fun workflowColumn(props: FormsTableProps): dynamic {
    val c = column("Workflows", "workflows", 230)
    c.onCell = {
        val cellProps: dynamic = js("({})")
        cellProps.onClick = { event: dynamic -> event.stopPropagation() }
        cellProps
    }
    c.render = fun(_: dynamic, record: dynamic, _: dynamic): dynamic {
        return WorkflowCell.create {
            gedraId = record.key as String
            items = record.wfItems.unsafeCast<List<WorkflowCellItem>>()
            noWorkflowsCopy = props.noWorkflowsCopy
            workflowHref = props.workflowHref
        }
    }
    return c
}

private external interface WorkflowCellProps : Props {
    var gedraId: String
    var items: List<WorkflowCellItem>
    var noWorkflowsCopy: String?
    var workflowHref: (String, String, String?) -> String
}

/** How many workflows a cell shows before the rest go behind its count (issue #791). */
private const val workflowCellShown = 2

/**
 * One row's workflow cell (issue #791). An ineligible workflow's link opens a dialog with the reasons, since there
 * is nowhere to go; every other link opens the workflow on the form. A row with nothing to show draws the client's
 * copy for that.
 */
private val WorkflowCell = FC<WorkflowCellProps> { props ->
    var reasonsFor by useState<WorkflowCellItem?>(null)
    var allOpen by useState(false)
    val items = props.items

    fun ChildrenBuilder.item(it: WorkflowCellItem) {
        div {
            className = ClassName("wf-cell-item")
            if (it.workflow.category == WfColumnCategory.ineligible) {
                button {
                    className = ClassName("wf-cell-link")
                    asDynamic()["type"] = "button"
                    onClick = { _ -> allOpen = false; reasonsFor = it }
                    +it.entry.label
                }
            } else {
                a {
                    className = ClassName("wf-cell-link")
                    href = props.workflowHref(props.gedraId, it.entry.workflowId, it.linkTask)
                    +it.entry.label
                }
            }
            span {
                className = ClassName("wf-cell-cat wf-cat-" + it.workflow.category.name)
                +(" " + workflowCategoryText(it.workflow.category))
            }
        }
    }

    if (items.isEmpty()) {
        val copy = props.noWorkflowsCopy
        if (copy == null) span { +"\u2014" } else MarkdownInline { source = copy }
        return@FC
    }
    items.take(workflowCellShown).forEach { item(it) }
    if (items.size > workflowCellShown) {
        Popover {
            open = allOpen
            trigger = "click"
            placement = "bottomLeft"
            title = "All workflows"
            onOpenChange = { allOpen = it }
            content = WorkflowCellAll.create {
                this.items = items
                renderItem = { builder, it -> with(builder) { item(it) } }
            }
            button {
                className = ClassName("wf-cell-more")
                asDynamic()["type"] = "button"
                +(workflowCellCounts(items).ifBlank { "${items.size - workflowCellShown} more" } + " \u2026")
            }
        }
    }
    Modal {
        open = reasonsFor != null
        title = reasonsFor?.let { "Why ${it.entry.label} is not available" }
        onCancel = { reasonsFor = null }
        footer = null
        val reasons = reasonsFor?.reasons.orEmpty()
        if (reasons.isEmpty()) {
            p { +"This form does not meet the workflow's conditions." }
        } else {
            ul {
                className = ClassName("wf-reasons")
                reasons.forEachIndexed { i, r ->
                    li {
                        key = i.toString().unsafeCast<Key>()
                        MarkdownInline { source = r }
                    }
                }
            }
        }
    }
}

private external interface WorkflowCellAllProps : Props {
    var items: List<WorkflowCellItem>
    var renderItem: (ChildrenBuilder, WorkflowCellItem) -> Unit
}

/** The full list behind a crowded cell's count (issue #791), in cell order. */
private val WorkflowCellAll = FC<WorkflowCellAllProps> { props ->
    div {
        className = ClassName("wf-cell-all")
        props.items.forEach { props.renderItem(this, it) }
    }
}
