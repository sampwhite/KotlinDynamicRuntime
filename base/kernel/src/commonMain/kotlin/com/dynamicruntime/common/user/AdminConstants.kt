package com.dynamicruntime.common.user

// Admin (user-management) constants shared with the *frontend*, alongside the auth constants in
// AuthConstants.kt and for the same reason: an admin console is a Kotlin/JS widget-group waiting to be written,
// and it should build its calls from the same strings the backend serves them under. Per the code guide, these
// are lowerCamelCase `const val`s in short upper-case acronym objects, always referenced qualified.

/**
 * **Full-scope** admin endpoint paths (before the API context root is prepended). Every one sits under the
 * `admin` *section*, which `RequestService.adminSections` gates on
 * [com.dynamicruntime.common.http.request.ROLE.allClients] -- the path prefix is the access control, so an
 * endpoint added here reaches every client by construction (issue #225).
 *
 * The same endpoints exist under [UADEP] for an administrator confined to their own client. A caller who holds
 * the capability satisfies both, and gets identical answers either way, since their scope is unrestricted.
 */
@Suppress("ConstPropertyName")
object ADEP {
    const val users = "/admin/users"

    /**
     * The brute-force search over the user cache (issue #411): richer than [users], which is a
     * newest-first SQL listing. This one searches and sorts *active* users in memory (email/name substring,
     * client, update-time range) and is the surface the console's search should call.
     */
    const val userSearch = "/admin/userSearch"
    const val userCreate = "/admin/user/create"
    const val userSetRoles = "/admin/user/setRoles"
    const val userSetEnabled = "/admin/user/setEnabled"
    const val userSetOrg = "/admin/user/setOrg"
    const val userSetName = "/admin/user/setName"
    /**
     * The verb says what happens; the path names the resource (issue #335). Its input rides in the query
     * string, since a DELETE carries no body -- see [com.dynamicruntime.common.endpoint.HttpMethod.DELETE].
     */
    const val userDelete = "/admin/user"

    /**
     * The clients this deployment carries (issue #343).
     *
     * Full-scope only, and it belongs here rather than under [UADEP] for a reason that is not merely tidiness:
     * a cross-client view is not a client-scoped administrator's business. Somebody confined to one client has
     * no question this listing answers that their own client's definition does not.
     */
    const val clients = "/admin/clients"
}

/**
 * **Scoped** user-administration paths: the same operations as [ADEP], reachable by any
 * [com.dynamicruntime.common.http.request.ROLE.admin] and confined to what their `ReadScope` allows (issue
 * #225). Its `clientAdmin` section is what a client-scoped administrator has instead of a narrowed view of the
 * full-scope surface.
 *
 * The section is named for **authority**, not topic (issue #466): `clientAdmin` says "an administrator confined
 * to one client", which is the statement a section makes. It sits opposite `admin` (the deployment-wide,
 * `allClients` surface) on the scope axis, and beside a future `/clientOperator` on the level axis. The old
 * name `userAdmin` described a topic, which stopped being right the moment anything but user administration
 * (the cfact discovery listing, #455) joined the section. `UADEP` keeps its name -- these paths still *do*
 * user administration; only the section they hang under was renamed.
 *
 * **This is the surface a frontend should call.** It serves both kinds of administrator correctly -- a caller
 * with `allClients` is simply unconfined -- so a console built on it needs no branch on who is asking.
 */
@Suppress("ConstPropertyName")
object UADEP {
    const val users = "/clientAdmin/users"
    /** The scoped counterpart to [ADEP.userSearch] -- the brute-force cache search (issue #411). */
    const val userSearch = "/clientAdmin/userSearch"
    const val userCreate = "/clientAdmin/user/create"
    const val userSetRoles = "/clientAdmin/user/setRoles"
    const val userSetEnabled = "/clientAdmin/user/setEnabled"
    const val userSetOrg = "/clientAdmin/user/setOrg"
    const val userSetName = "/clientAdmin/user/setName"
    /** `DELETE`, like [ADEP.userDelete], and scoped to the caller's own client. */
    const val userDelete = "/clientAdmin/user"
}

/** Admin request/response field (JSON key) names. */
@Suppress("ConstPropertyName")
object ADF {
    const val userId = "userId"
    const val primaryId = "primaryId"
    const val username = "username"
    const val roles = "roles"
    const val org = "org"
    const val isEntity = "isEntity"
    const val name = "name"
    const val enabled = "enabled"

    /** On the delete call: obfuscate the user's identity irrecoverably rather than merely disable them. */
    const val permanent = "permanent"

    /**
     * The client the user belongs to (issue #352).
     *
     * Read on the "create" call and reported on every user. **Create only**: a user's client cannot move
     * afterward without stranding their content, which carries the old client both in the `client` column and
     * inside every `GedraId` -- so there is no set-client call for this to name.
     */
    const val client = "client"
    const val hasPassword = "hasPassword"

    /** Whether the account was permanently deleted -- an obfuscated tombstone that can no longer be edited. */
    const val deleted = "deleted"

    /** When it was permanently deleted, for a deleted account. */
    const val deletedAt = "deletedAt"

    /**
     * When the row was last updated (the `updatedAt` protocol column, surfaced on the admin projection for
     * issue #411): the default sort key of the cache search, and a column the console can order on.
     */
    const val updatedAt = "updatedAt"

    /** Case-insensitive substring filter applied to `primaryId`, `username` and `name` by the list endpoint. */
    const val search = "search"
}

/**
 * The **user-search** request fields and sort keys (issue #411) -- the brute-force search/sort over the user
 * cache, distinct from [ADF.search]'s single one-term-many-fields filter on the plain listing.
 *
 * Shared with the frontend, like the rest of this file, so the console builds its query from the same strings
 * the endpoint reads and a rename breaks compilation rather than a request. The four sort-key names
 * ([email], [publicName], [client], [updatedAt]) double as the values [sortBy] accepts and as the columns the
 * console offers to order on; adding a searchable/sortable attribute later is one entry here and one in the
 * backend `userSearchFields` registry.
 */
@Suppress("ConstPropertyName")
object USF {
    /** Case-insensitive **substring** of the email address. */
    const val email = "email"

    /** Case-insensitive **substring** of the public name (the username, or the email while still a placeholder). */
    const val publicName = "publicName"

    /**
     * Case-insensitive **substring** of the account's real-world name (a person's full name or a business's)
     * **or its username** -- the field matches either, so pasting a known username into the console's Name box
     * finds the account, the behavior the old single search box had. The same string as [ADF.name]; it filters
     * on the field the console shows as "Name", which is what an administrator types when searching for someone.
     */
    const val name = "name"

    /**
     * **Exact** client id to confine the search to. Only an `allClients` caller can widen past their own
     * client, so for anyone else this narrows within a client they are already confined to (or, naming
     * another, returns nothing) -- the scope does the enforcing, this only picks among what it allows.
     */
    const val client = "client"

    /** The `updatedAt` sort key, and the field carrying the update time on each returned row. */
    const val updatedAt = "updatedAt"

    /** Only users updated **at or after** this instant (ISO-8601); the low end of the date range. */
    const val updatedAfter = "updatedAfter"

    /** Only users updated **at or before** this instant (ISO-8601); the high end of the date range. */
    const val updatedBefore = "updatedBefore"

    /** Which attribute to sort by -- one of [email], [name], [publicName], [client], [updatedAt]. Defaults to [updatedAt]. */
    const val sortBy = "sortBy"

    /** Sort descending rather than ascending. Defaults to true (newest / Z-A first). */
    const val descending = "descending"

    /** The default (and fairly large) cap on how many users the search returns when the caller names no limit. */
    const val defaultLimit = 500
}

/** Admin schema type names. */
@Suppress("ConstPropertyName")
object ADTY {
    const val adminUser = "AdminUser"
}
