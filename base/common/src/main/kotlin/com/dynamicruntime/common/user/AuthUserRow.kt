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
 * `AuthUserRow`, kd2-simplified to a single [account] (no group/shard). The password is promoted out of the
 * stored [authUserData] map into [encodedPassword] and scrubbed from [data], so it never rides downstream.
 * The class shape is deliberately independent of the storage shape (fields are read/written explicitly).
 */
class AuthUserRow(val userId: Long, val account: String, val primaryId: String) {
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
     * Loads a [UserProfile] from this row -- the acting/display identity for a logged-in user. A convenience
     * for now; a future variant may load only part of the profile for high-volume paths.
     */
    fun toUserProfile(): UserProfile = UserProfile(
        authId = userId.toString(), userId = userId, account = account, roles = roles.toSet(),
        publicName = publicName(), hasPassword = encodedPassword != null,
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
        ADF.roles to roles,
        ADF.enabled to enabled,
        ADF.hasPassword to (encodedPassword != null),
    )

    /** Repackages the typed fields into a storage map (roles and password folded back into `authUserData`). */
    fun toMap(): Map<String, Any?> {
        val newAuthData = authUserData.toMutableMap()
        newAuthData[AD.roles] = roles
        if (encodedPassword != null) newAuthData[AD.encodedPassword] = encodedPassword else newAuthData.remove(AD.encodedPassword)
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
                property(ADF.enabled, "Whether the account is active.", required = true) { type = SCT.boolean }
                property(ADF.hasPassword, "Whether the user has opted into a password.", required = true) {
                    type = SCT.boolean
                }
            }
        }

        /** Builds a typed row from a stored `AuthUsers` map. */
        fun extract(data: Map<String, Any?>): AuthUserRow {
            val userId = data[AU.userId].toOptLong() ?: throw KdrException("AuthUsers row is missing its userId.")
            val account = data[PF.account].toOptStr() ?: ""
            val primaryId = data[AU.primaryId].toOptStr()
                ?: throw KdrException("AuthUsers row is missing its primaryId.")
            val row = AuthUserRow(userId, account, primaryId)
            row.enabled = data[PF.enabled] == true
            row.username = data[AU.username].toOptStr() ?: (usernameTmpPrefix + primaryId)
            val userData = (data[AU.authUserData]?.toJsonMap() ?: emptyMap()).toMutableMap()
            row.roles = (userData[AD.roles] as? List<*>)?.mapNotNull { it?.toString() } ?: listOf(ROLE.user)
            row.encodedPassword = userData[AD.encodedPassword].toOptStr()
            userData.remove(AD.encodedPassword) // never let the password leak downstream via `data`
            row.authUserData = userData
            row.data = data
            return row
        }

        /** The initially provisioned row for a freshly verified [primaryId] contact (placeholder username). */
        fun mkInitialUser(primaryId: String, account: String, roles: List<String>): Map<String, Any?> = mapOf(
            AU.primaryId to primaryId,
            AU.username to (usernameTmpPrefix + primaryId),
            PF.account to account,
            AU.authUserData to mutableMapOf<String, Any?>(AD.roles to roles),
        )
    }
}
