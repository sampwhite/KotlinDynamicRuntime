package com.dynamicruntime.webapp

import react.FC
import react.Props

/**
 * The user search results as an antd table: one row per user, and clicking a row selects them for editing.
 * Presentational -- every value arrives via props, and the row cells are plain text, so selecting a user is the
 * only thing this table does. Mirrors [EndpointTable], which solves the same "pick one from a list" problem.
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
}

val UserTable = FC<UserTableProps> { props ->
    Table {
        size = "small"
        pagination = false
        rowKey = "key"
        columns = buildList {
            add(column("Id", "userId", 70))
            add(column("Email", "primaryId", 220))
            add(column("Name", "name", 200))
            add(column("Type", "type", 100))
            if (props.showClient) add(column("Client", "client", 110))
            add(column("Roles", "roles", 140))
            add(column("Status", "status", null))
        }.toTypedArray()
        dataSource = props.users.map { user ->
            val row: dynamic = js("({})")
            row.key = user.userId.toString()
            row.userId = user.userId.toString()
            row.primaryId = user.primaryId
            // The account's own name (a person's or a business's). Unnamed accounts show the placeholder
            // rather than the username standing in for one -- the username is a login id, not a name.
            row.name = user.name?.takeIf { it.isNotBlank() } ?: "—"
            // The name column says *what* it is called; this says which kind of thing it is naming.
            row.type = if (user.isEntity) "Business" else "Person"
            // Always written, even when no column shows it: the row is data, and which columns are drawn is
            // the table's decision rather than something the data should have to anticipate.
            row.client = user.client
            row.roles = user.roles.joinToString(", ")
            row.status = buildList {
                add(if (user.enabled) "enabled" else "disabled")
                if (user.hasPassword) add("password set")
            }.joinToString(", ")
            row
        }.toTypedArray()
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
