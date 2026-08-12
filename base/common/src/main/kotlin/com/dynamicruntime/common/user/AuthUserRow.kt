package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/**
 * A user's authentication row, extracted from the `AuthUsers` table into typed fields. Ported from dn's
 * `AuthUserRow`, kd2-simplified to a single [client] (no group/shard). The password is promoted out of the
 * stored [authUserData] map into [encodedPassword] and scrubbed from [data], so it never rides downstream.
 * The class shape is deliberately independent of the storage shape (fields are read/written explicitly).
 */
class AuthUserRow(val userId: Long, val client: String, val primaryId: String) {
    /** The user's primary organization within [client], or null when they have none (issue #225). */
    var org: String? = null

    /** Whether this account belongs to a business rather than a person (see [AD.isEntity]). */
    var isEntity: Boolean = false

    /** The business's (non-unique) name when [isEntity]; null for a personal account or an unnamed entity. */
    var entityName: String? = null

    var enabled: Boolean = false
    lateinit var username: String
    var roles: List<String> = listOf(ROLE.user)

    /** The encoded password, or null when the user logs in by verification code only (optional passwords). */
    var encodedPassword: String? = null

    /** The remaining auth-data map (roles/password promoted out). */
    var authUserData: MutableMap<String, Any?> = mutableMapOf()

    /** The raw stored row (with the password scrubbed), for callers that mutate and write it back. */
    var data: Map<String, Any?> = emptyMap()

    /** A placeholder username (`@<primaryId>`) means the user has not chosen a real username yet. */
    val needsRealUsername: Boolean get() = username.startsWith(usernameTmpPrefix)

    /** The name to show others: the chosen username, or the primaryId while still a placeholder. */
    fun publicName(): String = if (needsRealUsername) primaryId else username

    /**
     * Whether this row matches an admin-console [lowerTerm] (already lower-cased by the caller): a
     * case-insensitive substring of the email, the username, or -- for an entity -- its business name. Lives
     * here so the searchable fields stay listed beside the fields themselves; `UserService.listUsers` applies
     * it after extraction, since `entityName` is not an SQL column.
     */
    fun matchesSearch(lowerTerm: String): Boolean =
        primaryId.lowercase().contains(lowerTerm) ||
            username.lowercase().contains(lowerTerm) ||
            (entityName?.lowercase()?.contains(lowerTerm) == true)

    /**
     * Loads a [UserProfile] from this row -- the acting/display identity for a logged-in user. A convenience
     * for now; a future variant may load only part of the profile for high-volume paths.
     */
    fun toUserProfile(): UserProfile = UserProfile(
        authId = userId.toString(), userId = userId, client = client, org = org, roles = roles.toSet(),
        publicName = publicName(), hasPassword = encodedPassword != null,
        isEntity = isEntity, entityName = entityName,
    )

    /**
     * The admin console's view of this user ([ADTY.adminUser], defined by [defineAdminType]): identity, roles,
     * and account state. Deliberately *not* [toUserProfile] -- that is the acting identity of the caller, while
     * this describes some other user being administered, and the two must be free to diverge. The password
     * itself is never exposed, only whether one is set.
     */
    fun toAdminInfo(): Map<String, Any?> = mapOf(
        ADF.userId to userId,
        ADF.primaryId to primaryId,
        ADF.username to username,
        ADF.org to org,
        ADF.isEntity to isEntity,
        ADF.entityName to entityName,
        ADF.roles to roles,
        ADF.enabled to enabled,
        ADF.hasPassword to (encodedPassword != null),
    )

    /** Repackages the typed fields into a storage map (roles and password folded back into `authUserData`). */
    fun toMap(): Map<String, Any?> {
        val newAuthData = authUserData.toMutableMap()
        newAuthData[AD.roles] = roles
        if (encodedPassword != null) newAuthData[AD.encodedPassword] = encodedPassword else newAuthData.remove(AD.encodedPassword)
        // Removed rather than written as null when absent: most users have no organization, and an explicit
        // null would be stored in every row's JSON for the sake of the few that do.
        if (org != null) newAuthData[AD.org] = org else newAuthData.remove(AD.org)
        // Entity data rides along the same way: written only when it is actually set, so a personal account's
        // JSON does not carry `isEntity: false` in every row.
        if (isEntity) newAuthData[AD.isEntity] = true else newAuthData.remove(AD.isEntity)
        if (entityName != null) newAuthData[AD.entityName] = entityName else newAuthData.remove(AD.entityName)
        val retData = data.toMutableMap()
        retData[AU.username] = username
        retData[AU.authUserData] = newAuthData
        // Every typed field this class exposes has to travel back out, or a caller that sets one sees it
        // silently dropped on write. `enabled` is the one that bites: see UserService.updateUser, which has to
        // defend it a second time from the standard column stamping.
        retData[PF.enabled] = enabled
        return retData
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** Prefix marking a not-yet-chosen (placeholder) username. */
        const val usernameTmpPrefix = "@"

        /**
         * Defines the [ADTY.adminUser] schema type -- the shape of [toAdminInfo] -- on [builder]. Kept beside
         * the serialization it describes (the co-location rule), so a field added to one is visibly missing
         * from the other.
         */
        fun defineAdminType(builder: SchTypesBuilder) {
            builder.type(ADTY.adminUser) {
                type = SCT.kObject
                property(ADF.userId, "The user's numeric id.", required = true) { type = SCT.integer }
                property(ADF.primaryId, "Primary identifier (the primary email address).", required = true)
                property(ADF.username, "The user's unique preferred name.", required = true)
                property(ADF.roles, "The roles granted to the user.", required = true) {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(ADF.org, "The user's primary organization within their client, when they have one.")
                property(ADF.isEntity, "Whether this account belongs to a business rather than a person.") { type = SCT.boolean }
                property(ADF.entityName, "The business's name, when this is an entity account.")
                property(ADF.enabled, "Whether the account is active.", required = true) { type = SCT.boolean }
                property(ADF.hasPassword, "Whether the user has opted into a password.", required = true) {
                    type = SCT.boolean
                }
            }
        }

        /** Builds a typed row from a stored `AuthUsers` map. */
        fun extract(data: Map<String, Any?>): AuthUserRow {
            val userId = data[AU.userId].toOptLong() ?: throw KdrException("AuthUsers row is missing its userId.")
            val client = data[PF.client].toOptStr() ?: ""
            val primaryId = data[AU.primaryId].toOptStr()
                ?: throw KdrException("AuthUsers row is missing its primaryId.")
            val row = AuthUserRow(userId, client, primaryId)
            row.enabled = data[PF.enabled] == true
            row.username = data[AU.username].toOptStr() ?: (usernameTmpPrefix + primaryId)
            val userData = (data[AU.authUserData]?.toJsonMap() ?: emptyMap()).toMutableMap()
            row.roles = (userData[AD.roles] as? List<*>)?.mapNotNull { it?.toString() } ?: listOf(ROLE.user)
            row.org = userData[AD.org].toOptStr()?.ifEmpty { null }
            row.isEntity = userData[AD.isEntity] == true
            row.entityName = userData[AD.entityName].toOptStr()?.ifEmpty { null }
            row.encodedPassword = userData[AD.encodedPassword].toOptStr()
            userData.remove(AD.encodedPassword) // never let the password leak downstream via `data`
            row.authUserData = userData
            row.data = data
            return row
        }

        /** The initially provisioned row for a freshly verified [primaryId] contact (placeholder username). */
        fun mkInitialUser(
            primaryId: String,
            client: String,
            roles: List<String>,
            org: String? = null,
        ): Map<String, Any?> = mapOf(
            AU.primaryId to primaryId,
            AU.username to (usernameTmpPrefix + primaryId),
            PF.client to client,
            AU.authUserData to mutableMapOf<String, Any?>(AD.roles to roles).also {
                if (org != null) it[AD.org] = org
            },
        )
    }
}
