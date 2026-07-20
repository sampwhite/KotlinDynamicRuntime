package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
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
    val enabled: Boolean,
    val hasPassword: Boolean,
) {
    /** Whether this user holds the administrator role -- what the Users page's admin checkbox reflects. */
    val isAdmin: Boolean get() = roles.contains(ROLE.admin)

    /** This user's roles with [ROLE.admin] added or removed, preserving everything else the deployment set. */
    fun rolesWithAdmin(admin: Boolean): List<String> = when {
        admin && !isAdmin -> roles + ROLE.admin
        !admin && isAdmin -> roles.filter { it != ROLE.admin }
        else -> roles
    }
}

/**
 * The user-administration calls, behind the `admin` section -- so every one of them 401s unless the caller
 * holds the capability the shell advertised as `canManageUsers`. The frontend uses that flag to decide what to
 * *show*; this is the surface that is actually gated.
 *
 * As with the other API objects, paths and field names come from the shared kernel constants, so a rename on
 * the backend breaks compilation here rather than at runtime.
 */
object AdminApi {
    /** GET the user list, newest first; a blank [search] lists everyone (up to the endpoint's limit). */
    suspend fun listUsers(search: String): List<AdminUser> {
        val term = search.trim()
        val path = if (term.isEmpty()) ADEP.users else "${ADEP.users}?${ADF.search}=${encodeUriComponent(term)}"
        return Http.getApi(path)[EP.items].toJsonListOfMaps().map { it.toAdminUser() }
    }

    /** Creates a user directly (no email verification); [username] and [roles] are optional. */
    suspend fun createUser(primaryId: String, username: String?, roles: List<String>?): AdminUser {
        val body = buildMap<String, Any?> {
            put(ADF.primaryId, primaryId.trim())
            username?.trim()?.takeIf { it.isNotEmpty() }?.let { put(ADF.username, it) }
            roles?.takeIf { it.isNotEmpty() }?.let { put(ADF.roles, it) }
        }
        return Http.sendApi("POST", ADEP.userCreate, body).results().toAdminUser()
    }

    /** Replaces a user's roles -- the call that grants or revokes administrator rights. */
    suspend fun setRoles(userId: Long, roles: List<String>): AdminUser =
        Http.sendApi("POST", ADEP.userSetRoles, mapOf(ADF.userId to userId, ADF.roles to roles))
            .results().toAdminUser()

    /** Enables or disables a user's account. */
    suspend fun setEnabled(userId: Long, enabled: Boolean): AdminUser =
        Http.sendApi("POST", ADEP.userSetEnabled, mapOf(ADF.userId to userId, ADF.enabled to enabled))
            .results().toAdminUser()

    private fun Map<String, Any?>.results(): Map<String, Any?> = this[EP.results].toJsonMapOrEmpty()

    /** Percent-encodes a query value via the browser global (as [SchemaCatalogApi] does for its own links). */
    private fun encodeUriComponent(s: String): String = js("encodeURIComponent(s)") as String

    private fun Map<String, Any?>.toAdminUser(): AdminUser = AdminUser(
        userId = this[ADF.userId].toOptLong() ?: -1L,
        primaryId = this[ADF.primaryId] as? String ?: "",
        username = this[ADF.username] as? String ?: "",
        roles = this[ADF.roles].toJsonListOfStrings(),
        enabled = this[ADF.enabled] == true,
        hasPassword = this[ADF.hasPassword] == true,
    )
}
