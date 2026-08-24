package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.user.userSearchFieldSpecs
import react.FC
import react.Props

/**
 * The user search results as an antd table (issue #411). The **search columns are rendered from the shared
 * spec** ([userSearchFieldSpecs]): their existence, order, header label, and sortability all come from the
 * kernel description, so a field added to the spec becomes a column here with no edit -- the SDUI extra credit.
 * Clicking a sortable header drives a **server-side** re-sort through [onSort], so the order stays correct even
 * when the match ran past the endpoint's cap. The descriptive columns after them (Type, Roles, Status) are
 * static -- they are not search fields.
 *
 * Presentational: every value and the current sort arrive via props. Mirrors [EndpointTable], which solves the
 * same "pick one from a list" problem.
 */
external interface UserTableProps : Props {
    var users: List<AdminUser>
    var onSelect: (AdminUser) -> Unit

    /**
     * Whether to show which client each user belongs to (issue #352) -- the same rule the create selector and
     * the client filter follow. A spec field marked `allClientsOnly` is shown only when this is true, since the
     * client is one value to anyone who cannot see more than one.
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
            // The search columns, straight from the shared spec -- label, sortability, and order included.
            for (spec in userSearchFieldSpecs) {
                if (spec.allClientsOnly && !props.showClient) continue
                if (spec.sortable) {
                    add(sortableColumn(spec.label, spec.name, columnWidths[spec.name], props.sortBy, props.descending))
                } else {
                    add(column(spec.label, spec.name, columnWidths[spec.name]))
                }
            }
            // Descriptive columns that are not search fields, so they stay static and unsorted.
            add(column("Type", "type", 90))
            add(column("Roles", "roles", 130))
            add(column("Status", "status", null))
        }.toTypedArray()
        dataSource = props.users.map { user ->
            val row: dynamic = js("({})")
            row.key = user.userId.toString()
            row.userId = user.userId.toString()
            // A search column's cell is filed under the spec's field name (which is also its `sorter.field`), so
            // the header maps straight to a sort key. The display value per field is the frontend's own reading
            // of the row -- the counterpart to the backend's accessors.
            for (spec in userSearchFieldSpecs) {
                row[spec.name] = cellValue(spec.name, user)
            }
            row.type = if (user.isEntity) "Business" else "Person"
            row.roles = user.roles.joinToString(", ")
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

/**
 * A search column's display value for [user] -- the frontend's reading of the row, the counterpart to the
 * backend registry's `AuthUserRow` accessors. The one per-field front-end touch a new column needs, beside its
 * width; everything else (the column itself, its label, its sortability) comes from the shared spec.
 */
private fun cellValue(field: String, user: AdminUser): String = when (field) {
    USF.email -> user.primaryId
    // The account's own name; an unnamed account shows the placeholder rather than the username standing in.
    USF.name -> user.name?.takeIf { it.isNotBlank() } ?: "—"
    USF.client -> user.client
    USF.updatedAt -> user.updatedAt?.let { formatTimestamp(it) } ?: "—"
    else -> ""
}

/** Column widths (a presentation detail, so front-end only) keyed by the spec field name; absent = auto. */
private val columnWidths: Map<String, Int> = mapOf(
    USF.email to 220, USF.name to 180, USF.client to 110, USF.updatedAt to 150,
)

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
