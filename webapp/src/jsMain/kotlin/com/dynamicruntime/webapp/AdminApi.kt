package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong

/**
 * One administered user, as the `admin` endpoints describe them ([ADF]). Deliberately not [UserProfile]: that
 * is who *you* are, while this is a row in a list of other people, and the two are free to diverge.
 */
class AdminUser(
    val userId: Long,
    val primaryId: String,
    val username: String,
    val roles: List<String>,
    /** Their primary organization within the client, or null when they have none (issue #225). */
    val org: String?,
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

    /** Creates a user directly (no email verification); [username] and [roles] are optional. */
    suspend fun createUser(primaryId: String, username: String?, roles: List<String>?, org: String?): AdminUser {
        val body = buildMap<String, Any?> {
            put(ADF.primaryId, primaryId.trim())
            username?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ADF.username, it) }
            roles?.takeIf { it.isNotEmpty() }?.let { put(ADF.roles, it) }
            org?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ADF.org, it) }
        }
        return Http.sendApi("POST", UADEP.userCreate, body).results().toAdminUser()
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
        org = this[ADF.org] as? String,
        enabled = this[ADF.enabled] == true,
        hasPassword = this[ADF.hasPassword] == true,
    )
}
