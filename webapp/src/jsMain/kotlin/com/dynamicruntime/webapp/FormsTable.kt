package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.create
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

/**
 * The caller's form documents as an antd table: one row per form, most recently written first as the endpoint
 * returns them (issue #562), with a User column for a caller who sees other users' documents.
 * The list is the hub for the whole lifecycle (issue #417): a **row click** opens the read-only view, and a
 * per-row **Actions** column carries Edit and Delete so neither needs the form to be opened first. Delete arms
 * an inline confirm in the row rather than navigating, since it is the one irreversible action here.
 *
 * Presentational: every value is a [FormSummary] the parent already computed and every action is a callback the
 * parent owns, so the table itself knows nothing about gedra shapes or endpoints. An action a caller's surface
 * cannot perform is not offered ([canEdit]/[canDelete]), the same "do not show a control that cannot work" rule
 * the view follows.
 */
external interface FormsTableProps : Props {
    /** Each form's id paired with its summary, in display order. */
    var forms: List<Pair<String, FormSummary>>

    /** Opens the read-only view of a form -- also what a row click does. */
    var onView: (String) -> Unit

    /** Whether the caller's surface carries the patch endpoint, so an Edit action can work (issue #417). */
    var canEdit: Boolean

    /** Whether the caller's surface carries the delete endpoint, so a Delete action can work. */
    var canDelete: Boolean

    /**
     * Whether to draw the User column (issue #562): true for a caller who administers other users, whose rows
     * carry an owner. An ordinary caller's rows are all their own, so the column would say one name over and
     * over -- and the backend sends no owner for them anyway.
     */
    var showOwner: Boolean

    /** Navigates to the edit page for a form. */
    var onEdit: (String) -> Unit

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
}

val FormsTable = FC<FormsTableProps> { props ->
    // The display columns a client's trait-usage rules declared (issue #537): every row carries the same set
    // (the backend attaches all of them), so any row's list gives the columns and their order. A client with
    // no usage rules has none, and `Contains` carries the identity, as it did before this was configurable.
    val displayCols = props.forms.firstOrNull()?.second?.displayValues ?: emptyList()
    // The Actions column exists only when at least one action can be performed, so a read-only surface carries
    // no empty column.
    val anyActions = props.canEdit || props.canDelete
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
        val cols = buildList {
            // Namespaced key: a usage trait id must not shadow the reserved row key ("key") or a fixed column
            // ("contains"/"owner"/"updated"/"created"/"actions") -- overwriting the row key would break which
            // form a click opens.
            displayCols.forEach { add(column(it.label, displayColKey(it.traitId), 160)) }
            add(column("Contains", "contains", null))
            if (props.showOwner) add(ownerColumn())
            // The order is most recently written first (issue #562); the arrow says so without offering a
            // sort the endpoint does not take.
            add(column("Updated ↓", "updated", 175))
            add(column("Created", "created", 175))
            if (anyActions) add(actionsColumn(props))
        }
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
            row
        }.toTypedArray()
        onRow = { record, _ ->
            val handlers: dynamic = js("({})")
            handlers.onClick = { props.onView(record.key as String) }
            handlers.style = js("({ cursor: 'pointer' })")
            handlers
        }
    }
}

/**
 * The per-row Actions column (issue #417): Edit and Delete, each shown only when its endpoint is on the surface.
 * Delete arms an inline confirm on the row it belongs to rather than acting on the first click. `onCell` stops
 * a click anywhere in this cell from bubbling to the row's own click handler, so using an action never also
 * opens the view. The cell content is a component ([FormRowActions]) rendered per row, since the render callback
 * must return a React node.
 */
private fun actionsColumn(props: FormsTableProps): dynamic {
    val c = column("Actions", "actions", 180)
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
            this.canEdit = props.canEdit
            this.canDelete = props.canDelete
            this.onEdit = props.onEdit
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
 * The User column (issue #562), for a caller who sees other users' documents: the owner's name with the email
 * in small type beneath, or the email alone. The backend sends `ownerName` only when the account has a name
 * that is not its email, so the cell renders what arrives rather than comparing the two.
 */
private fun ownerColumn(): dynamic {
    val c = column("User", "owner", 200)
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
    var canEdit: Boolean
    var canDelete: Boolean
    var onEdit: (String) -> Unit
    var confirming: Boolean
    var deleting: Boolean
    var onArmDelete: (String) -> Unit
    var onCancelDelete: () -> Unit
    var onConfirmDelete: (String) -> Unit
}

private val FormRowActions = FC<FormRowActionsProps> { props ->
    span {
        className = ClassName("row-actions")
        if (props.canEdit) {
            Button {
                type = "link"
                size = "small"
                onClick = { props.onEdit(props.id) }
                +"Edit"
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
private fun displayColKey(traitId: String): String = "display_$traitId"

/** Builds an antd column config `{ title, dataIndex, key, width? }`. */
private fun column(title: String, dataIndex: String, width: Int?): dynamic {
    val c: dynamic = js("({})")
    c.title = title
    c.dataIndex = dataIndex
    c.key = dataIndex
    if (width != null) c.width = width
    return c
}
