package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.USF
import react.FC
import react.Props

/**
 * The user search results as an antd table: one row per user, clicking a row selects them for editing, and the
 * sortable columns drive a **server-side** re-sort (issue #411). Presentational -- every value and the current
 * sort arrive via props, and a header click is reported back through [onSort] rather than reordered in place,
 * so the order stays correct even when the match ran past the endpoint's cap.
 *
 * Mirrors [EndpointTable], which solves the same "pick one from a list" problem.
 */
external interface UserTableProps : Props {
    var users: List<AdminUser>
    var onSelect: (AdminUser) -> Unit

    /**
     * Whether to show which client each user belongs to (issue #352).
     *
     * Off for a client-scoped administrator, whose every row carries the same value -- a column that says one
     * thing is a column that says nothing. It follows the same rule as the create form's client selector, and
     * for the same reason: the client is only a distinction to somebody who can see more than one.
     */
    var showClient: Boolean

    /** The field currently sorted on -- a [USF] sort key -- so the active column shows its arrow. */
    var sortBy: String

    /** Whether the current sort is descending, for the active column's arrow direction. */
    var descending: Boolean

    /** Reports a header-click sort: the [USF] sort key and whether it is now descending. */
    var onSort: (field: String, descending: Boolean) -> Unit
}

val UserTable = FC<UserTableProps> { props ->
    Table {
        size = "small"
        pagination = false
        rowKey = "key"
        columns = buildList {
            add(column("Id", "userId", 70))
            add(sortableColumn("Email", USF.email, 220, props.sortBy, props.descending))
            add(sortableColumn("Name", USF.name, 180, props.sortBy, props.descending))
            add(column("Type", "type", 90))
            if (props.showClient) add(sortableColumn("Client", USF.client, 110, props.sortBy, props.descending))
            add(column("Roles", "roles", 130))
            add(sortableColumn("Updated", USF.updatedAt, 150, props.sortBy, props.descending))
            add(column("Status", "status", null))
        }.toTypedArray()
        dataSource = props.users.map { user ->
            val row: dynamic = js("({})")
            row.key = user.userId.toString()
            row.userId = user.userId.toString()
            // The sortable columns' dataIndex is the USF sort key, so a header's `sorter.field` is a key the
            // search accepts directly -- the display value is filed under that key rather than the raw column.
            row[USF.email] = user.primaryId
            // The account's own name (a person's or a business's). Unnamed accounts show the placeholder
            // rather than the username standing in for one -- the username is a login id, not a name.
            row[USF.name] = user.name?.takeIf { it.isNotBlank() } ?: "—"
            // The name column says *what* it is called; this says which kind of thing it is naming.
            row.type = if (user.isEntity) "Business" else "Person"
            // Always written, even when no column shows it: the row is data, and which columns are drawn is
            // the table's decision rather than something the data should have to anticipate.
            row[USF.client] = user.client
            row.roles = user.roles.joinToString(", ")
            row[USF.updatedAt] = user.updatedAt?.let { formatTimestamp(it) } ?: "—"
            row.status = buildList {
                // A permanently-deleted tombstone reads as "deleted", not "disabled" -- it is disabled, but
                // saying only that would hide that it is the irreversible kind and cannot be re-enabled.
                add(if (user.deleted) "deleted" else if (user.enabled) "enabled" else "disabled")
                if (user.hasPassword) add("password set")
            }.joinToString(", ")
            row
        }.toTypedArray()
        // A header click re-sorts on the server. `sortDirections` on each sortable column keeps the cycle to
        // ascend<->descend (never "none"), so `order` is always defined and the field maps straight to a sort key.
        onChange = { _, _, sorter ->
            val field = sorter.field as? String
            val order = sorter.order as? String
            if (field != null && order != null) {
                props.onSort(field, order == "descend")
            }
        }
        // The whole row is the selection target; look the user back up by the key the row carries.
        onRow = { record, _ ->
            val handlers: dynamic = js("({})")
            handlers.onClick = {
                props.users.firstOrNull { it.userId.toString() == record.key }?.let { props.onSelect(it) }
            }
            handlers.style = js("({ cursor: 'pointer' })")
            handlers
        }
    }
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

/**
 * A [column] that sorts on the server: `sorter = true` hands the click to the table's `onChange` rather than
 * reordering locally, `sortOrder` shows the arrow when this is the active column (null otherwise), and
 * `sortDirections` keeps the header cycle to ascend<->descend so the order is never cleared to none.
 */
private fun sortableColumn(
    title: String, sortKey: String, width: Int?, activeSort: String, descending: Boolean,
): dynamic {
    val c = column(title, sortKey, width)
    c.sorter = true
    c.sortDirections = arrayOf("ascend", "descend")
    c.sortOrder = if (activeSort == sortKey) (if (descending) "descend" else "ascend") else null
    return c
}
