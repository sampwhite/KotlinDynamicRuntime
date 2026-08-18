package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong

/** One client a new user may be put in, as the create form's selector offers them (issue #352). */
class ClientChoice(val clientId: String, val name: String)

/**
 * How a client reads in the create form's selector.
 *
 * Both halves, because neither alone is enough: the **id** is what gets stored and what appears inside every
 * one of that user's gedra ids, so it is the thing an administrator will later recognize in a log or a URL,
 * while the **name** is what a person actually calls the client. A client that has not been given a name shows
 * its id alone rather than an empty pair of brackets.
 *
 * Pure, and covered under `jsNodeTest`, like the other two helpers here.
 */
fun clientChoiceLabel(choice: ClientChoice): String =
    if (choice.name.isEmpty()) choice.clientId else "${choice.name} (${choice.clientId})"

/**
 * One administered user, as the `admin` endpoints describe them ([ADF]). Deliberately not [UserProfile]: that
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
 * The user-administration calls, behind the **`userAdmin`** section (issue #225) -- so every one of them 403s
 * unless the caller holds the role the shell advertised as `canManageUsers`. The frontend uses that flag to
 * decide what to *show*; this is the surface that is actually gated.
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
        val path = if (term.isEmpty()) UADEP.users else "${UADEP.users}?${ADF.search}=${encodeUriComponent(term)}"
        return Http.getApi(path)[EP.items].toJsonListOfMaps().map { it.toAdminUser() }
    }

    /** Creates a user directly (no email verification); [username], [roles], [org], and name data are optional. */
    suspend fun createUser(
        primaryId: String, username: String?, roles: List<String>?, org: String?,
        isEntity: Boolean = false, name: String? = null, client: String? = null,
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

    private fun Map<String, Any?>.results(): Map<String, Any?> = this[EP.results].toJsonMapOrEmpty()

    /** Percent-encodes a query value via the browser global (as [SchemaCatalogApi] does for its own links). */
    private fun encodeUriComponent(s: String): String = js("encodeURIComponent(s)") as String

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
    )
}
