package com.dynamicruntime.webapp

import react.FC
import react.Props

/**
 * The caller's form documents as an antd table: one row per form, newest first as the endpoint returns them.
 * Clicking a row opens it (the parent navigates to the read-only view). Presentational -- every value is a
 * [FormSummary] the parent already computed, so the table itself knows nothing about gedra shapes (issue #408).
 */
external interface FormsTableProps : Props {
    /** Each form's id paired with its summary, in display order. */
    var forms: List<Pair<String, FormSummary>>
    var onSelect: (String) -> Unit
}

val FormsTable = FC<FormsTableProps> { props ->
    // A form document has no dedicated name -- a title comes from the `name` trait, which a client may not
    // support (acme does not), so for such a client every row's name is empty. The Name column is shown only
    // when at least one listed form actually has one, rather than standing as a column of dashes; `Contains`
    // carries the identity in its absence.
    val anyNamed = props.forms.any { it.second.title != null }
    Table {
        size = "small"
        pagination = false
        rowKey = "key"
        columns = buildList {
            if (anyNamed) add(column("Name", "name", 220))
            add(column("Contains", "contains", null))
            add(column("Created", "created", 170))
        }.toTypedArray()
        dataSource = props.forms.map { (id, summary) ->
            val row: dynamic = js("({})")
            row.key = id
            // A dash only where a named list has the odd untitled row; an all-untitled list drops the column.
            row.name = summary.title ?: "—"
            row.contains = summary.traitLabels.joinToString(", ")
            row.created = summary.createdAt ?: ""
            row
        }.toTypedArray()
        onRow = { record, _ ->
            val handlers: dynamic = js("({})")
            handlers.onClick = { props.onSelect(record.key as String) }
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
