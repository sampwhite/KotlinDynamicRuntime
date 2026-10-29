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
            row.description = catalogRowText(ep.description)
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

/**
 * An endpoint's description as its catalog row shows it (issue #1476): a link as its text alone, since a link in a row
 * would compete with the row's own click, and the endpoint's page draws the description with its links. The rest is as
 * written, as it always was here. Pure, covered under `jsNodeTest`.
 */
fun catalogRowText(description: String?): String = description.orEmpty().replace(markdownLinkRegex, "$1")

// Every bracket escaped: JS reads a regex in Unicode mode, where a bare `]` is an error rather than a character.
private val markdownLinkRegex = Regex("""\[([^\]]*)\]\([^)\s]*\)""")
