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
        // Declared widths mean what they say (see `TableProps.tableLayout`): under the default auto layout a
        // long email address grew past its own column and took the space from the date columns, which have a
        // fixed-width content that cannot give way.
        tableLayout = "fixed"
        pagination = false
        rowKey = "key"
        columns = buildList {
            add(column("Id", idColumn, columnWidths[idColumn]))
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
            add(column("Type", typeColumn, columnWidths[typeColumn]))
            add(column("Roles", rolesColumn, columnWidths[rolesColumn]))
            add(column("Status", statusColumn, columnWidths[statusColumn]))
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
 *
 * The `else` returns a marker rather than an empty string, and `UserCellValueTest` asserts every spec field is
 * mapped, so a field added to the spec without a branch here fails the build (a blank column) instead of
 * shipping an empty column silently. Not `private`, so that test can reach it.
 */
fun cellValue(field: String, user: AdminUser): String = when (field) {
    USF.email -> user.primaryId
    // The account's own name; an unnamed account shows the placeholder rather than the username standing in.
    USF.name -> user.name?.takeIf { it.isNotBlank() } ?: "—"
    USF.client -> user.client
    // The three tracked dates the console shows (issue #462). A dash rather than a blank: "never" is a fact
    // about the account -- never logged in, never edited -- and an empty cell reads as a rendering failure.
    USF.lastEdited.at -> user.lastEditedAt?.let { formatTimestamp(it) } ?: "—"
    USF.lastLoggedIn.at -> user.lastLoggedInAt?.let { formatTimestamp(it) } ?: "—"
    USF.activated.at -> user.activatedAt?.let { formatTimestamp(it) } ?: "—"
    else -> unmappedCell
}

/** What [cellValue] returns for a spec field with no display branch -- the tell `UserCellValueTest` catches. */
const val unmappedCell = "(?)"

// The four columns that are not search fields, so they have no spec to take a name from. Named rather than
// repeated as literals, because each appears three times -- the column, the row's cell, and its width.
private const val idColumn = "userId"
private const val typeColumn = "type"
private const val rolesColumn = "roles"
private const val statusColumn = "status"

/**
 * Column widths (a presentation detail, so front-end only) keyed by the spec field name; absent = auto.
 *
 * **Every column declares one now**, because "absent = auto" meant antd divided the leftover space by its own
 * reckoning and gave `Status` -- which holds one short word -- as much as `Roles`, which holds a list. Measured
 * rather than guessed: each figure is the widest content that column actually has to hold, plus its header.
 *
 * The three dates are the ones worth stating a reason for. Their content is **fixed width** --
 * `2026-08-27 19:23 UTC` is the same length forever -- so 175 is not a preference but the number that stops
 * them wrapping, and the previous 150 was 20px short of it. A column whose content cannot vary should never
 * be the one that wraps, which is exactly what it was doing.
 *
 * The total is kept near what a 1440-wide window can show, so nothing is pushed off the right-hand edge and
 * behind a scrollbar. Above that the surplus is shared out; below it the table scrolls inside the card. The
 * columns that absorb the difference are the two whose content has no bound.
 */
private val columnWidths: Map<String, Int> = mapOf(
    idColumn to 50,
    // The two that genuinely vary, and so the two that give way. An address longer than this wraps, which is
    // the right column to spend a second line on: it is the one whose content has no bound, and the row is
    // clickable if the whole of it is wanted.
    USF.email to 190, USF.name to 130, USF.client to 85,
    // Fixed-width content, so these are the figures that must not be shaved: `2026-08-27 19:23 UTC` measures
    // 153px and never varies. Everything else here was sized around them.
    USF.lastEdited.at to 175, USF.lastLoggedIn.at to 175, USF.activated.at to 175,
    // Bounded vocabularies: "Person"/"Business", and "enabled"/"disabled"/"deleted".
    typeColumn to 80, statusColumn to 85,
    // A list, so it is the other one that may wrap. Sized so that it does not at the case that actually
    // occurs -- `user, admin, allClients`, which is what a full administrator holds and measures 159px. The
    // 15px it needed over the obvious figure came from `Client` and `Type`, both of which had headroom.
    rolesColumn to 160,
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
