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
}

val UserTable = FC<UserTableProps> { props ->
    Table {
        size = "small"
        pagination = false
        rowKey = "key"
        columns = arrayOf(
            column("Id", "userId", 70),
            column("Email", "primaryId", 220),
            column("Username", "username", 160),
            column("Roles", "roles", 140),
            column("Status", "status", null),
        )
        dataSource = props.users.map { user ->
            val row: dynamic = js("({})")
            row.key = user.userId.toString()
            row.userId = user.userId.toString()
            row.primaryId = user.primaryId
            // A placeholder username ("@<email>") means they have not chosen one; showing it verbatim is
            // noisier than saying so.
            row.username = if (user.username.startsWith("@")) "—" else user.username
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
