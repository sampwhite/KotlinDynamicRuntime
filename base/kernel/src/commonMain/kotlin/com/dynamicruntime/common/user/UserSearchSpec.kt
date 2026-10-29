package com.dynamicruntime.common.user

import com.dynamicruntime.common.http.request.ROLE

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
 * `docs/design/user-search.md` (repo root).
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

    /**
     * A case-insensitive exact match, rendered as a picker: of the spec's own [UserSearchFieldSpec.options] when it
     * has them (the persona, the roles), else of the clients this caller may choose among.
     */
    exact,

    /** A from/to range over an instant, rendered as a pair of date-time pickers (the console's four dates). */
    dateRange,
}

/**
 * Where an [UserFilterKind.exact] field's picker takes its choices from. A closed operational set: each is a different
 * thing the console fetches, or does not.
 */
@Suppress("EnumEntryName")
enum class UserFilterChoices {
    /** The spec's own [UserSearchFieldSpec.options] (the persona, the roles). */
    listed,

    /** The clients this caller may choose among, which the console fetches. */
    clients,

    /**
     * The label suggestions of the client being searched (issues #786, #1481), with any other label typed as well:
     * labels are free-form, so a suggestion list can name only some of them.
     */
    labels,
}

/**
 * One user-search field, described once for both sides (issue #411).
 */
class UserSearchFieldSpec(
    /**
     * The wire/hash key and sort key; matches the backend registry entry and a [USF] constant. It is also the
     * key this field takes in the console's shareable URL, which shares one flat hash namespace with the app's
     * routing keys (`page`, `u`, `g`, `doc`, `tool`, `fault`) -- so do not name a field one of those.
     */
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
     * endpoint reads them, the console sends and encodes them). Null for every other kind.
     *
     * It **is** derived from the field name now (issue #462): `UserDateKeys` generates `at`/`after`/`before`
     * from one root, so a date spec should be built with [dateSpec] rather than by listing the pair. This
     * stays a plain property because a non-date field has none, and because the spec is deliberately flat
     * data that a future server-driven console can serialize without a rule attached.
     */
    val rangeKeys: Pair<String, String>? = null,
    /**
     * For a [UserFilterKind.exact] field, the choices its picker offers (issue #1432), or null for one whose choices
     * the console fetches (see [choices]). Known to the kernel, so both sides name the same values.
     */
    val options: List<UserFilterOption>? = null,
    /** For a [UserFilterKind.exact] field, where its picker's choices come from; [options] when it has them. */
    val choices: UserFilterChoices = if (options != null) UserFilterChoices.listed else UserFilterChoices.clients,
    /**
     * Whether a row holds a list of values here, any one of which the filter matches (the roles). It changes only the
     * words: the chip says `Roles include "Editor"` rather than `is`.
     */
    val listValued: Boolean = false,
)

/** One choice of an exact filter's picker (issue #1432): the value matched, and the label shown for it. */
class UserFilterOption(
    val value: String,
    val label: String,
    /** Whether only a cross-client (`allClients`) caller is offered it, as [UserSearchFieldSpec.allClientsOnly]. */
    val allClientsOnly: Boolean = false,
)

/** The personas a user can have, as the Persona filter offers them (issue #1432): every [PERSONA.defs] entry. */
val personaFilterOptions: List<UserFilterOption> = PERSONA.defs.map { UserFilterOption(it.name, it.label) }

/**
 * The roles a user can hold, as the Roles filter offers them (issue #1432). An operator and an `allClients` holder are
 * the platform's own people, so only a cross-client caller is offered them; within a client the choices are the ones
 * an administrator grants there.
 */
val roleFilterOptions: List<UserFilterOption> = listOf(
    UserFilterOption(ROLE.user, "User"),
    UserFilterOption(ROLE.editor, "Editor"),
    UserFilterOption(ROLE.admin, "Admin"),
    UserFilterOption(ROLE.operator, "Operator", allClientsOnly = true),
    UserFilterOption(ROLE.allClients, "All clients", allClientsOnly = true),
)

/**
 * A [UserSearchFieldSpec] for a date attribute, built from its [keys] (issue #462).
 *
 * The convenience is the point: a date's sort key *and* its two range bounds all come from one root, so
 * spelling them out at the call site is three chances to disagree with the endpoint that reads them. With five
 * dates that is fifteen strings; with this it is five roots.
 */
fun dateSpec(
    keys: UserDateKeys,
    label: String,
    filterKind: UserFilterKind? = UserFilterKind.dateRange,
    sortable: Boolean = true,
): UserSearchFieldSpec = UserSearchFieldSpec(
    keys.at, label, filterKind, sortable, rangeKeys = keys.after to keys.before,
)

/**
 * The user-search fields the **console** presents, in the order it lays them out (issue #411). The backend
 * registry is a superset -- it also carries `publicName`, an API-only axis the console does not surface because
 * it hides the username (the `name` field already matches the username, see `admin-console.md` in the
 * `kdr-webapp-pages` skill).
 *
 * Add a field here (and its accessors to the backend registry) and it becomes a filter input and, if
 * [UserSearchFieldSpec.sortable], a sortable column -- with no further front-end change.
 */
val userSearchFieldSpecs: List<UserSearchFieldSpec> = listOf(
    UserSearchFieldSpec(USF.email, "Email", UserFilterKind.substring, sortable = true),
    UserSearchFieldSpec(USF.name, "Name", UserFilterKind.substring, sortable = true),
    UserSearchFieldSpec(USF.client, "Client", UserFilterKind.exact, sortable = true, allClientsOnly = true),
    // Which of a person's users this is (issue #750), picked from the personas (issue #1432). The personaSuffix that
    // tells a batch apart is found by the free-text term instead.
    UserSearchFieldSpec(USF.persona, "Persona", UserFilterKind.exact, sortable = true, options = personaFilterOptions),
    // The roles a user holds (issue #1432), so Editor finds the editors whatever their persona says -- a member made
    // an editor keeps the persona they had. Not sortable: a list orders by nothing anyone asks for.
    UserSearchFieldSpec(
        USF.roles, "Roles", UserFilterKind.exact, sortable = false, options = roleFilterOptions, listValued = true,
    ),
    // The user's free-form labels (issues #786, #1481), so `reviewer` finds the reviewers. Picked from the client's
    // suggestions or typed, since a label need not be one of them. Not sortable, as the roles are not.
    UserSearchFieldSpec(
        USF.labels, "Labels", UserFilterKind.exact, sortable = false, choices = UserFilterChoices.labels,
        listValued = true,
    ),
    // The four dates the console shows (issues #462, #750). `updatedAt` is deliberately absent: the endpoint
    // still sorts on it, but it moves on a login and so answers a question nobody is asking here. `registered`
    // joined once it meant *claimed* -- when the person proved the user is theirs -- which an administrator
    // provisioning users for others wants to see; it is shown ahead of `activated`, which is stamped at creation.
    dateSpec(USF.lastEdited, "Edited"),
    dateSpec(USF.lastLoggedIn, "Last login"),
    dateSpec(USF.registered, "Registered"),
    dateSpec(USF.activated, "Activated"),
)

/** [userSearchFieldSpecs] by name, for resolving a field the console or a shared URL names. */
val userSearchFieldSpecsByName: Map<String, UserSearchFieldSpec> = userSearchFieldSpecs.associateBy { it.name }

/**
 * The sort keys the console offers -- the sortable specs' names. A shared/bookmarked URL naming anything else
 * (a hand-edited or stale link) falls back to the default rather than asking the endpoint for a field it would
 * reject. Shared so the front end need not re-list it.
 */
val userSortKeys: Set<String> = userSearchFieldSpecs.filter { it.sortable }.map { it.name }.toSet()
