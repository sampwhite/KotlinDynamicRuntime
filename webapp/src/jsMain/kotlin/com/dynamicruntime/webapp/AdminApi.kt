package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.clientLabel
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.user.UserFilterKind
import com.dynamicruntime.common.user.userSearchFieldSpecs
import com.dynamicruntime.common.user.userSearchFieldSpecsByName
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong

/** One client a new user may be put in, as the create form's selector offers them (issue #352). */
class ClientChoice(val clientId: String, val name: String)

/**
 * How a client reads in the create form's selector.
 *
 * The rule itself moved to the kernel, onto [clientLabel], once the backend needed it too: a sourced choice
 * list of clients (issue #413) renders the same clients into the same kind of dropdown, and two copies of
 * "how a client reads" could disagree about one. This stays as the adapter from the frontend's own
 * [ClientChoice], and is still covered under `jsNodeTest`.
 */
fun clientChoiceLabel(choice: ClientChoice): String = clientLabel(choice.clientId, choice.name)

/**
 * A client list as antd `Select` `{ label, value }` options, built per render from what the backend served
 * rather than a fixed list -- the clients a deployment carries are configuration. Shared by the user-create
 * selector and the endpoint catalog's client selector (issue #394), the same clients endpoint feeding both.
 */
fun clientOptions(choices: List<ClientChoice>): Array<dynamic> = choices.map { choice ->
    val obj: dynamic = js("({})")
    obj.label = clientChoiceLabel(choice)
    obj.value = choice.clientId
    obj
}.toTypedArray()

/**
 * One administered user, as the `admin` endpoints describe them ([ADF]). Deliberately not "UserProfile": that
 * is who *you* are, while this is a row in a list of other people, and the two are free to diverge.
 */
class AdminUser(
    val userId: Long,
    val primaryId: String,
    val username: String,
    val roles: List<String>,
    /** The client they belong to (issue #352). Fixed at creation: moving one would strand their content. */
    val client: String,
    /** Their primary organization within the client, or null when they have none (issue #225). */
    val org: String?,
    /** Whether this account belongs to a business rather than a person. */
    val isEntity: Boolean,
    /** The account's real-world name -- a person's full name, or a business's -- or null when unnamed. */
    val name: String?,
    val enabled: Boolean,
    val hasPassword: Boolean,
    /** Whether the account was permanently deleted -- an obfuscated tombstone that can no longer be edited. */
    val deleted: Boolean,
    /**
     * When the row was last updated, as the wire ISO-8601 string, or null when the row carried none (issue
     * #411). The default sort key of the search, and a column the console can order on.
     */
    val updatedAt: String? = null,
    /** The tracked dates (issue #462); absent until the event that sets each one has happened. */
    val lastEditedAt: String? = null,
    val lastLoggedInAt: String? = null,
    val activatedAt: String? = null,
) {
    /**
     * This user's access level: the highest rung of [RoleLadder] they hold, which is what the Users page's
     * level selector reflects. Falls back to [ROLE.user] for a row holding no ladder role at all -- the
     * backend will not create one, but a display should not depend on that.
     */
    val level: String get() = RoleLadder.highestHeld(roles) ?: ROLE.user
}

// `rolesAtLevel` (the inverse of [AdminUser.level]) moved to the kernel, onto RoleLadder, once test
// provisioning needed the same rule: "what role list puts someone at this level" is a question about the
// ladder, and two copies of it could disagree about a rung. Callers here reach it as RoleLadder.rolesAtLevel.

/**
 * [roles] with [capability] added or removed -- the off-ladder counterpart to `RoleLadder.rolesAtLevel`, which
 * only ever moves someone between rungs.
 *
 * Separate from that function rather than folded into it, because the two answer different questions and a
 * combined one would have to take a level *and* a capability set at every call site. Composing them in that
 * order is what makes a level change preserve a capability and a capability change preserve a level.
 *
 * Granting is idempotent (a role list is a set in all but type, and the backend stores it as written, so a
 * duplicate would be visible in the admin console). Pure, and covered under `jsNodeTest`.
 */
fun rolesWithCapability(roles: List<String>, capability: String, granted: Boolean): List<String> =
    if (granted) (roles + capability).distinct() else roles.filter { it != capability }

/**
 * Whether [ROLE.allClients] would be **dormant** at [level]: held, stored, and doing nothing (issue #225).
 *
 * The full-scope surface requires the `admin` level *and* the capability, so granting the capability to
 * somebody below that level changes nothing about what they can reach. That is a legitimate state and the
 * editor allows it -- demoting an administrator keeps their capability rather than making someone remember to
 * re-grant it -- but it is not a state a checkbox communicates on its own, so the editor says so instead.
 *
 * Asked through [RoleLadder] rather than by comparing to `admin` directly, so it stays true if the level the
 * capability qualifies ever moves. Pure, and covered under `jsNodeTest`.
 */
fun isAllClientsDormant(level: String, granted: Boolean): Boolean =
    granted && !RoleLadder.satisfies(setOf(level), ROLE.admin)

/**
 * The user-administration calls, behind the **`clientAdmin`** section (issue #225, renamed in #466) -- so every
 * one of them 403s unless the caller holds the role the shell advertised as `canManageUsers`. The frontend uses
 * that flag to decide what to *show*; this is the surface that is actually gated.
 *
 * The scoped surface rather than the full-scope `admin` one, because it serves both kinds of administrator
 * correctly: a caller with `allClients` is simply unconfined there, so this console needs no branch on who is
 * asking, and a client-scoped administrator gets the same screens narrowed to their own client.
 *
 * As with the other API objects, paths and field names come from the shared kernel constants, so a rename on
 * the backend breaks compilation here rather than at runtime.
 */
object AdminApi {
    /** GET the user list, newest first; a blank [search] lists everyone (up to the endpoint's limit). */
    suspend fun listUsers(search: String): List<AdminUser> {
        val term = search.trim()
        // Non-empty only: an empty term lists everyone, and appending `?search=` (empty) is a different call.
        val path = if (term.isEmpty()) UADEP.users else UADEP.users + queryString(mapOf(ADF.search to term))
        return Http.getApi(path)[EP.items].toJsonListOfMaps().map { it.toAdminUser() }
    }

    /**
     * The brute-force cache search (issue #411): filters and sorts *active* users, returning the matched total
     * beside the capped page. Only non-blank terms are sent -- a blank one is no filter, and the endpoint reads
     * an absent field and an empty one differently. The sort is always sent, so the server orders the whole
     * matched set before capping (client-side sorting would only reorder the page, misleading past the cap).
     */
    suspend fun searchUsers(query: UserSearchQuery, limit: Int? = null): UserSearchResult {
        // The limit is a fetch concern, not part of the query's identity, so it rides on the URL here rather
        // than in `userSearchArgs` -- which the shareable-hash encoding mirrors, and where a suggestion cap has
        // no business (issue #581). A type-ahead asks for the few it will show; the console omits it and takes
        // the endpoint's own default.
        val args = userSearchArgs(query) + (limit?.let { mapOf(EP.limit to it) } ?: emptyMap())
        val env = Http.getApi(UADEP.userSearch + queryString(args))
        return UserSearchResult(
            users = env[EP.items].toJsonListOfMaps().map { it.toAdminUser() },
            numAvailable = (env[EP.numAvailable] as? Number)?.toInt() ?: 0,
            hasMore = env[EP.hasMore] == true,
        )
    }

    /** Creates a user directly (no email verification); [username], [roles], [org], and name data are optional. */
    suspend fun createUser(
        primaryId: String, username: String?, roles: List<String>?, org: String?,
        isEntity: Boolean = false, name: String? = null, client: String? = null, enabled: Boolean = true,
    ): AdminUser {
        val body = buildMap<String, Any?> {
            put(ADF.primaryId, primaryId.trim())
            username?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ADF.username, it) }
            roles?.takeIf { it.isNotEmpty() }?.let { put(ADF.roles, it) }
            org?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ADF.org, it) }
            // Sent only when chosen, so an administrator who never saw the selector gets the backend's own
            // default -- their own client -- rather than this having to know what that is.
            client?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ADF.client, it) }
            if (isEntity) put(ADF.isEntity, true)
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ADF.name, it) }
            // Sent only to create a disabled account: the backend defaults to enabled, so the common case
            // stays a shorter body and existing callers are unchanged.
            if (!enabled) put(ADF.enabled, false)
        }
        return Http.sendApi("POST", UADEP.userCreate, body).results().toAdminUser()
    }

    /**
     * The clients this node carries, for the create form's selector (issue #352).
     *
     * The **full-scope** path, unlike everything else here: listing clients is a cross-client question, so
     * only an `allClients` caller can ask it -- which is exactly the caller who is offered the choice. A
     * scoped administrator never calls this, because their client is not a decision.
     */
    suspend fun listClients(): List<ClientChoice> =
        Http.getApi(ADEP.clients)[EP.items].toJsonListOfMaps().map {
            ClientChoice(it[CLD.clientId] as? String ?: "", it[CLD.name] as? String ?: "")
        }

    /** Replaces a user's roles -- the call that grants or revokes administrator rights. */
    suspend fun setRoles(userId: Long, roles: List<String>): AdminUser =
        Http.sendApi("POST", UADEP.userSetRoles, mapOf(ADF.userId to userId, ADF.roles to roles))
            .results().toAdminUser()

    /** Sets or clears a user's primary organization; a null [org] clears it. */
    suspend fun setOrg(userId: Long, org: String?): AdminUser =
        Http.sendApi("POST", UADEP.userSetOrg, buildMap {
            put(ADF.userId, userId)
            if (org != null) put(ADF.org, org)
        }).results().toAdminUser()

    /** Sets a user's name, and whether the account is a business; the name survives a change of [isEntity]. */
    suspend fun setName(userId: Long, name: String?, isEntity: Boolean): AdminUser =
        Http.sendApi("POST", UADEP.userSetName, buildMap {
            put(ADF.userId, userId)
            put(ADF.isEntity, isEntity)
            if (name != null) put(ADF.name, name)
        }).results().toAdminUser()

    /** Enables or disables a user's account. */
    suspend fun setEnabled(userId: Long, enabled: Boolean): AdminUser =
        Http.sendApi("POST", UADEP.userSetEnabled, mapOf(ADF.userId to userId, ADF.enabled to enabled))
            .results().toAdminUser()

    /**
     * Deletes a user: recoverable (merely disabled) by default, or -- when [permanent] -- disabled with the
     * email and identity obfuscated so it cannot be undone. Returns the resulting (obfuscated, for a permanent
     * delete) row.
     */
    suspend fun deleteUser(userId: Long, permanent: Boolean): AdminUser =
        // A DELETE, so the input travels as query args rather than a body (issue #335); deleteApi encodes them.
        Http.deleteApi(UADEP.userDelete, buildMap {
            put(ADF.userId, userId)
            if (permanent) put(ADF.permanent, true)
        }).results().toAdminUser()

    private fun Map<String, Any?>.results(): Map<String, Any?> = this[EP.results].toJsonMapOrEmpty()


    private fun Map<String, Any?>.toAdminUser(): AdminUser = AdminUser(
        userId = this[ADF.userId].toOptLong() ?: -1L,
        primaryId = this[ADF.primaryId] as? String ?: "",
        username = this[ADF.username] as? String ?: "",
        roles = this[ADF.roles].toJsonListOfStrings(),
        client = this[ADF.client] as? String ?: "",
        org = this[ADF.org] as? String,
        isEntity = this[ADF.isEntity] == true,
        name = this[ADF.name] as? String,
        enabled = this[ADF.enabled] == true,
        hasPassword = this[ADF.hasPassword] == true,
        deleted = this[ADF.deleted] == true,
        updatedAt = this[ADF.updatedAt] as? String,
        lastEditedAt = this[USF.lastEdited.at] as? String,
        lastLoggedInAt = this[USF.lastLoggedIn.at] as? String,
        activatedAt = this[USF.activated.at] as? String,
    )
}

/** A from/to bound over an instant (ISO-8601 strings), for a [UserFilterKind.dateRange] field. */
class DateRange(val after: String? = null, val before: String? = null) {
    val isEmpty: Boolean get() = after == null && before == null
}

/**
 * What the console asked the user search for (issue #411) -- held **generically, keyed by field name**, so the
 * console builds it by iterating [userSearchFieldSpecs] rather than naming each field, and a field added to the
 * shared spec flows through here with no change (the SDUI extra credit).
 *
 * [textTerms] holds the substring/exact filters (email, name, client) by field name; [ranges] holds the
 * date-range filters (update time) by field name. A blank or absent entry is simply no filter.
 */
class UserSearchQuery(
    val textTerms: Map<String, String> = emptyMap(),
    val ranges: Map<String, DateRange> = emptyMap(),
    val sortBy: String = USF.lastEdited.at,
    val descending: Boolean = true,
    /**
     * A single free-text term matched across email, name, and username at once (issue #581) -- the OR term the
     * scope-bar type-ahead sends, distinct from the per-field [textTerms] which AND. Blank is no constraint.
     * Appended after the existing parameters so the positional call sites (the users console) are unaffected.
     */
    val anyText: String? = null,
)

/**
 * The user-search endpoint's query args for [query] (issue #411), built by iterating the shared spec: each
 * non-blank text term travels under its field name (which is its wire param), each range under the two param
 * names the spec declares, plus the sort. Shared by [AdminApi.searchUsers] and the page's shareable-URL
 * encoding, so the wire and the hash carry the same keys -- and adding a spec field needs no edit here.
 */
fun userSearchArgs(query: UserSearchQuery): Map<String, Any?> = buildMap {
    query.anyText?.trim()?.takeIf { it.isNotEmpty() }?.let { put(EI.q, it) }
    query.textTerms.forEach { (field, term) -> term.trim().takeIf { it.isNotEmpty() }?.let { put(field, it) } }
    query.ranges.forEach { (field, range) ->
        val keys = userSearchFieldSpecsByName[field]?.rangeKeys ?: return@forEach
        range.after?.let { put(keys.first, it) }
        range.before?.let { put(keys.second, it) }
    }
    put(USF.sortBy, query.sortBy)
    put(USF.descending, query.descending)
}

/**
 * One page of a user search: the [users] returned, [numAvailable] (how many matched before the cap), and
 * [hasMore] (whether the cap hid some). The console shows the two counts, so an over-broad search reads as
 * "showing 500 of 4000" rather than looking like the whole population.
 */
class UserSearchResult(val users: List<AdminUser>, val numAvailable: Int, val hasMore: Boolean)
