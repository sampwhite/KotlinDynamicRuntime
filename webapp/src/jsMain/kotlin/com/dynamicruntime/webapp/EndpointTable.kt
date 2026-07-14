package com.dynamicruntime.webapp

import react.FC
import react.Props

/**
 * The endpoint catalog as an antd table: one row per endpoint (method, path, description). Clicking a row
 * selects that endpoint (the parent navigates to its page). Presentational — all data comes in via props.
 */
external interface EndpointTableProps : Props {
    var endpoints: List<EndpointInfo>
    var onSelect: (EndpointInfo) -> Unit
}

val EndpointTable = FC<EndpointTableProps> { props ->
    Table {
        size = "small"
        pagination = false
        rowKey = "key"
        columns = arrayOf(
            column("Method", "method", 90),
            column("Endpoint", "path", 220),
            column("Description", "description", null),
        )
        dataSource = props.endpoints.map { ep ->
            val row: dynamic = js("({})")
            row.key = ep.key
            row.method = ep.method
            row.path = ep.path
            row.description = ep.description ?: ""
            row
        }.toTypedArray()
        // Make a whole row clickable; look the endpoint back up by its key.
        onRow = { record, _ ->
            val handlers: dynamic = js("({})")
            handlers.onClick = {
                props.endpoints.firstOrNull { it.key == record.key }?.let { props.onSelect(it) }
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
