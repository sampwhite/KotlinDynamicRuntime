package com.dynamicruntime.common.context

import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toOptLong

/** Attribute keys for a [UserProfile]'s info dump ([UserProfile.toUserInfo]). Each name matches its value. */
@Suppress("ConstPropertyName")
object UPF {
    const val authId = "authId"
    const val userId = "userId"
    const val account = "account"
    const val roles = "roles"
    const val publicName = "publicName"
    const val hasPassword = "hasPassword"
}

/**
 * The authenticated-user information carried by a KdrCxt: identity, roles, the client account, and a
 * display name. A real login populates it from the user's row (see the auth layer); an unauthenticated
 * request carries the anonymous profile ([anonymous]). Still lightweight -- richer profile data will load on
 * demand.
 *
 * (Named without the `Kdr` prefix per the naming guide: `UserProfile` is specific enough not to be ambiguous.)
 */
class UserProfile(
    /** Authenticated identity, or null when no user is authenticated. [anonymous] uses [anonymousAuthId]. */
    val authId: String? = null,
    /**
     * Numeric identity of the user, used by the database layer to stamp `createdBy`/`updatedBy` and to own
     * user-scoped rows. Defaults to [AC.systemUserId] (the implicit system user) until real auth exists.
     */
    val userId: Long = AC.systemUserId.toLong(),
    /**
     * The client account this user belongs to. Defaults to [AC.local] (the general acting default); the auth
     * layer uses [AC.public] for the anonymous profile and for users it manufactures without an explicit
     * account.
     */
    val account: String = AC.local,
    /**
     * Role privileges granted to the user, established by the authentication layer before an endpoint
     * runs. Interior privileges (to specific organizations or content) are determined inside endpoints.
     */
    val roles: Set<String> = emptySet(),
    /** The user's public display name, when known. */
    val publicName: String? = null,
    /**
     * Whether the user has opted into a password (login by code always works regardless). Known only on a
     * profile freshly loaded from the auth row; null (and omitted from [toUserInfo]) on the fast path where
     * the profile was restored from the session cookie without a database read.
     */
    val hasPassword: Boolean? = null,
) {
    /**
     * A JSON-friendly map dump of this profile's attributes -- the payload returned by user-info endpoints so
     * a frontend can learn who the caller is. `authId` falls back to [anonymousAuthId], and a null
     * `publicName` is simply omitted; the shape is described by [defineInfoType].
     */
    fun toUserInfo(): Map<String, Any?> = buildMap {
        put(UPF.authId, authId ?: anonymousAuthId)
        put(UPF.userId, userId)
        put(UPF.account, account)
        put(UPF.roles, roles.toList())
        if (publicName != null) put(UPF.publicName, publicName)
        if (hasPassword != null) put(UPF.hasPassword, hasPassword)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** The `authId` used for the anonymous (not-logged-in) profile. */
        const val anonymousAuthId = "anonymous"

        /** Schema type name for the [toUserInfo] dump. */
        const val infoTypeName = "UserInfo"

        /** The implicit, unauthenticated system user used for internal/acting defaults. */
        fun systemUser(): UserProfile = UserProfile(authId = null)

        /** The profile for a caller who is not logged in: an anonymous identity in the public account. */
        fun anonymous(): UserProfile = UserProfile(authId = anonymousAuthId, account = AC.public)

        /**
         * Reconstructs a [UserProfile] from a [toUserInfo] map -- the mirror of [toUserInfo], so the frontend
         * (or any KMP consumer) can turn a `UserInfo` response back into the typed object. Missing/blank fields
         * fall back to the same defaults the constructor uses.
         */
        fun fromUserInfo(info: Map<String, Any?>): UserProfile = UserProfile(
            authId = info.getOptStr(UPF.authId),
            userId = info[UPF.userId].toOptLong() ?: AC.systemUserId.toLong(),
            account = info.getOptStr(UPF.account) ?: AC.local,
            roles = (info[UPF.roles] as? List<*>)?.mapNotNull { it?.toString() }?.toSet() ?: emptySet(),
            publicName = info.getOptStr(UPF.publicName),
            hasPassword = info[UPF.hasPassword] as? Boolean,
        )

        /**
         * Defines the `UserInfo` schema type (the shape of [toUserInfo]) on [builder]. Kept with the class, so
         * the type and the serialization cannot drift apart (mirrors `KdrTable.defineInfoType`); an endpoint
         * module that returns user info pulls it in and references it by [infoTypeName].
         */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(infoTypeName) {
                type = SCT.kObject
                property(UPF.authId, "Authenticated identity, or 'anonymous' when not logged in.", required = true)
                property(UPF.userId, "The user's numeric id.", required = true) { type = SCT.integer }
                property(UPF.account, "The client account the user belongs to.", required = true)
                property(UPF.roles, "The roles granted to the user.") {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(UPF.publicName, "The user's public display name, when known.")
                property(UPF.hasPassword, "Whether the user has opted into a password.") { type = SCT.boolean }
            }
        }
    }
}
