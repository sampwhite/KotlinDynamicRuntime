package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
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
    /**
     * This user's access level: the highest rung of [RoleLadder] they hold, which is what the Users page's
     * level selector reflects. Falls back to [ROLE.user] for a row holding no ladder role at all -- the
     * backend will not create one, but a display should not depend on that.
     */
    val level: String get() = RoleLadder.highestHeld(roles) ?: ROLE.user
}

/**
 * The role list that puts a user at [level], given the roles they hold now.
 *
 * Three things it has to get right, which is why it is a function rather than a `+ role` at the call site:
 *
 *  - **The rungs are exclusive.** They are an ordering, not independent flags, so moving to a level *replaces*
 *    whatever rung was held rather than adding to it. Leaving `admin` behind while granting `operator` would
 *    be a demotion that demotes nothing.
 *  - **[ROLE.user] is always kept.** A user without it cannot log in at all (the backend's
 *    `requireUsableRoles` refuses the write), so it is the floor of every level rather than one of the choices.
 *  - **Roles off the ladder survive untouched.** A deployment's own roles are capabilities, not levels; a
 *    change of level must not silently strip someone's `billing`.
 *
 * A [level] that is not a ladder role leaves the user at the floor, so a bad value can only ever under-grant.
 * Pure, and tested under `jsNodeTest` -- the frontend guidance keeps this kind of mapping out of the component.
 */
fun rolesAtLevel(current: List<String>, level: String): List<String> {
    val capabilities = current.filter { RoleLadder.rankOf(it) == null }
    val rung = level.takeIf { RoleLadder.rankOf(it) != null && it != ROLE.user }
    return listOfNotNull(ROLE.user, rung) + capabilities
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
