package com.dynamicruntime.common.user

/**
 * The **shared, declarative description of the user-search fields** (issue #411, the SDUI extra credit).
 *
 * This is `base/kernel`, so the *same* value compiles into both the JVM backend and the Kotlin/JS front end --
 * there is no JSON on the wire here. The backend's `userSearchFields` registry (in `base/common`) attaches the
 * `AuthUserRow` accessors that do the actual filtering and sorting, keyed by [UserSearchFieldSpec.name]; the
 * console renders its filter inputs and its sortable columns by iterating these specs. So a field added here
 * flows to both sides at once, and a rename or a mismatch is a **compile error**, not a runtime surprise --
 * the same property `ROLE`/`RoleLadder`/`USF` rely on.
 *
 * It is deliberately plain data (strings, an enum, booleans), so serving it as JSON for a fully runtime
 * server-driven console -- the natural next step -- is a matter of rendering these, not redesigning them. See
 * `user-search.md` (repo root).
 *
 * **Why the accessors are not here:** they take an `AuthUserRow`, a JVM type the front end never sees, so they
 * cannot be shared. The split is deliberate -- this says *what* a field is and how it is shown; the backend
 * registry says *how to read it off a row*.
 */

/** How a search field is filtered, which decides the input the console renders for it. A closed operational set. */
@Suppress("EnumEntryName")
enum class UserFilterKind {
    /** A case-insensitive substring, rendered as a text box. */
    substring,

    /** A case-insensitive exact match, rendered as a picker (today only the client). */
    exact,

    /** A from/to range over an instant, rendered as a pair of date-time pickers (today only the update time). */
    dateRange,
}

/**
 * One user-search field, described once for both sides (issue #411).
 */
class UserSearchFieldSpec(
    /** The wire/hash key and sort key; matches the backend registry entry and a [USF] constant. */
    val name: String,
    /** The human label shown as the filter's label and the results column header. */
    val label: String,
    /** How this field filters, or null when it is sort-only (shown/sortable but with no filter input). */
    val filterKind: UserFilterKind?,
    /** Whether the results table offers a sortable column for it. */
    val sortable: Boolean,
    /**
     * Whether the field is only meaningful to a cross-client (`allClients`) caller -- the client, which is one
     * value to anyone else. Such a field's filter and column appear only for that caller (issue #352).
     */
    val allClientsOnly: Boolean = false,
    /**
     * For a [UserFilterKind.dateRange] field, the two wire/hash param names its bounds travel under (the
     * endpoint reads them, the console sends and encodes them). Null for every other kind. It is here rather
     * than derived from [name] because the pair does not follow a pattern from it -- `updatedAt`'s bounds are
     * `updatedAfter`/`updatedBefore`.
     */
    val rangeKeys: Pair<String, String>? = null,
)

/**
 * The user-search fields the **console** presents, in the order it lays them out (issue #411). The backend
 * registry is a superset -- it also carries `publicName`, an API-only axis the console does not surface because
 * it hides the username (the `name` field already matches the username, see `webapp/CLAUDE.md`).
 *
 * Add a field here (and its accessors to the backend registry) and it becomes a filter input and, if
 * [UserSearchFieldSpec.sortable], a sortable column -- with no further front-end change.
 */
val userSearchFieldSpecs: List<UserSearchFieldSpec> = listOf(
    UserSearchFieldSpec(USF.email, "Email", UserFilterKind.substring, sortable = true),
    UserSearchFieldSpec(USF.name, "Name", UserFilterKind.substring, sortable = true),
    UserSearchFieldSpec(USF.client, "Client", UserFilterKind.exact, sortable = true, allClientsOnly = true),
    UserSearchFieldSpec(
        USF.updatedAt, "Updated", UserFilterKind.dateRange, sortable = true,
        rangeKeys = USF.updatedAfter to USF.updatedBefore,
    ),
)

/** [userSearchFieldSpecs] by name, for resolving a field the console or a shared URL names. */
val userSearchFieldSpecsByName: Map<String, UserSearchFieldSpec> = userSearchFieldSpecs.associateBy { it.name }

/**
 * The sort keys the console offers -- the sortable specs' names. A shared/bookmarked URL naming anything else
 * (a hand-edited or stale link) falls back to the default rather than asking the endpoint for a field it would
 * reject. Shared so the front end need not re-list it.
 */
val userSortKeys: Set<String> = userSearchFieldSpecs.filter { it.sortable }.map { it.name }.toSet()
