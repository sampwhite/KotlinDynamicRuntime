package com.dynamicruntime.common.context

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.util.getOptLong
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toJsonListOfStrings

/** Attribute keys for a [UserProfile]'s info dump ([UserProfile.toUserInfo]). Each name matches its value. */
@Suppress("ConstPropertyName")
object UPF {
    const val authId = "authId"
    const val userId = "userId"
    const val client = "client"
    const val org = "org"
    const val roles = "roles"
    const val publicName = "publicName"
    const val isEntity = "isEntity"
    const val name = "name"
    const val hasPassword = "hasPassword"
}

/**
 * The authenticated-user information carried by a KdrCxt: identity, roles, the client and org, and a
 * display name. A real login populates it from the user's row (see the auth layer); an unauthenticated
 * request carries the anonymous profile ([anonymous]). Still lightweight -- richer profile data will load on
 * demand.
 *
 * A **data class**, for [copy] (issue #282). A caller that wants this profile with one thing changed must use
 * it rather than calling the constructor with the other eight fields written out: that reconstruction silently
 * drops whatever it does not mention, and it did -- twice, losing `org` when #225 added it and the entity
 * fields when #284 did, each time producing a profile that compiled, ran, and was quietly missing a field.
 * `copy` carries every present *and future* field, so the mistake is no longer available. Note that a
 * handwritten `withRoles`-style helper would **not** have fixed this: it would still list every field, just
 * one level further in.
 *
 * (Named without the `Kdr` prefix per the naming guide: `UserProfile` is specific enough not to be ambiguous.)
 */
data class UserProfile(
    /** Authenticated identity, or null when no user is authenticated. [anonymous] uses [anonymousAuthId]. */
    val authId: String? = null,
    /**
     * Numeric identity of the user, used by the database layer to stamp `createdBy`/`updatedBy` and to own
     * user-scoped rows. Defaults to [CL.systemUserId] (the implicit system user) until real auth exists.
     */
    val userId: Long = CL.systemUserId.toLong(),
    /**
     * The client this user belongs to. Defaults to [CL.hub] (the general acting default); the auth
     * layer uses [CL.public] for the anonymous profile and for users it manufactures without an explicit
     * client.
     */
    val client: String = CL.hub,
    /**
     * Whether a **database row** stands behind this profile (issue #386).
     *
     * False for the three profiles the code manufactures -- [systemUser], [anonymous] and [envAuthed] -- and
     * true for one read from `AuthUsers`. A property those first two always had and never expressed: they are
     * skipped by `refreshActingRoles` today because [isLoggedIn] is false, which is *true* but is not the
     * reason. An env-authed caller is the first case where the two diverge, since that one genuinely is
     * logged in.
     *
     * **Defaults to true, and that direction is deliberate**, against the usual instinct here. Wrongly marked
     * row-backed means a refresh is attempted: on an ordinary node the row is simply absent and roles are
     * cleared, which narrows; on a node with no user service it fails loudly. Wrongly marked *not*
     * row-backed means the refresh is skipped and a **revoked role is retained** -- which widens, and
     * silently. The safer error is the one that costs access rather than grants it.
     */
    val isRowBacked: Boolean = true,
    /**
     * The user's **primary organization** within their [client], or null when they have none -- either the
     * client has no organizations at all, or this user is not confined to one (issue #225).
     *
     * Optional by design: organizations are a per-client choice, so null is the ordinary case and means
     * "belongs to the client, not to any organization". Held in `authUserData` and carried here rather than
     * in a database column, which is what lets a write stamp it onto the row without first looking the user up.
     */
    val org: String? = null,
    /**
     * Role privileges granted to the user, established by the authentication layer before an endpoint
     * runs. Interior privileges (to specific organizations or content) are determined inside endpoints.
     */
    val roles: Set<String> = emptySet(),
    /** The user's public display name, when known. */
    val publicName: String? = null,
    /**
     * Whether this account belongs to a **business** rather than a person. It says how to *read* [name] --
     * a business name rather than a personal one -- and gates business-specific behavior; it deliberately no
     * longer selects which field to display, because [name] now serves both kinds of account.
     */
    val isEntity: Boolean = false,
    /**
     * This account's real-world name: the person's full name, or the business's name when [isEntity]. **Not
     * unique** -- two people or two businesses may share one -- so it is display copy and never an identifier;
     * the account stays keyed by its primary id and username. Null when the account has not given one.
     */
    val name: String? = null,
    /**
     * Whether the user has opted into a password (login by code always works regardless). Known only on a
     * profile freshly loaded from the auth row; null (and omitted from [toUserInfo]) on the fast path where
     * the profile was restored from the session cookie without a database read.
     */
    val hasPassword: Boolean? = null,
) {
    /**
     * Whether this profile represents an authenticated user -- as opposed to the anonymous profile
     * ([anonymousAuthId]) or the unauthenticated system profile (null [authId]). The frontend reads this off
     * the profile it reconstructs from a user-info payload ([fromUserInfo]) to drive login-vs-logout UI.
     */
    val isLoggedIn: Boolean get() = authId != null && authId != anonymousAuthId

    /**
     * The name to present for this identity: its real-world [name] when it has one, and the [publicName]
     * (a login identifier) only as a fallback. A single rule, in the kernel, so the backend and the
     * (transpiled) frontend cannot disagree about which name to show.
     *
     * [isEntity] does not appear here on purpose. It used to select *which field* to display, back when only a
     * business had a real name; now both kinds of account carry [name], so the flag says what the name means
     * rather than where to find it -- and a person's full name gets shown where the username used to be.
     *
     * An account with no name yet, or a blank one, falls back to [publicName] rather than showing nothing.
     * Null only when there is no name at all (e.g., the anonymous profile).
     */
    val displayName: String? get() = name?.trim()?.ifEmpty { null } ?: publicName

    /**
     * Deliberately **not** the data class's generated dump. This object is on every context, so interpolating
     * one into a log line is an obvious thing to do while debugging -- and the generated version would write
     * the person's real name and their email (as [publicName]) into the log file, which becoming a data class
     * would otherwise have quietly enabled. What is left is what a log is actually for here: who is acting and
     * what they may do. Use [toUserInfo] to render the whole profile deliberately.
     */
    override fun toString(): String = "UserProfile(userId=$userId, client=$client, roles=$roles)"

    /**
     * A JSON-friendly map dump of this profile's attributes -- the payload returned by user-info endpoints so
     * a frontend can learn who the caller is. `authId` falls back to [anonymousAuthId], and a null
     * `publicName` is simply omitted; the shape is described by [defineInfoType].
     */
    fun toUserInfo(): Map<String, Any?> = buildMap {
        put(UPF.authId, authId ?: anonymousAuthId)
        put(UPF.userId, userId)
        put(UPF.client, client)
        if (org != null) put(UPF.org, org)
        put(UPF.roles, roles.toList())
        if (publicName != null) put(UPF.publicName, publicName)
        if (isEntity) put(UPF.isEntity, true)
        if (name != null) put(UPF.name, name)
        if (hasPassword != null) put(UPF.hasPassword, hasPassword)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** The `authId` used for the anonymous (not-logged-in) profile. */
        const val anonymousAuthId = "anonymous"

        /** Schema type name for the [toUserInfo] dump. */
        const val infoTypeName = "UserInfo"

        /** The implicit, unauthenticated system user used for internal/acting defaults. */
        fun systemUser(): UserProfile = UserProfile(authId = null, isRowBacked = false)

        /** The profile for a caller who is not logged in: an anonymous identity in the public client. */
        fun anonymous(): UserProfile =
            UserProfile(authId = anonymousAuthId, client = CL.public, isRowBacked = false)

        /**
         * The profile for a caller an **edge** vouched for (issue #386): authenticated by [email] at the
         * perimeter, acting for [CL.house], and backed by no database row anywhere.
         *
         * **[ROLE.operator], because `admin` has nothing to mean on an edge.** The admin sections are user
         * administration, and an edge has no user store -- there is nothing there to administer. `operator` is
         * the level for *running the deployment rather than using it*, which is exactly an edge's own surface.
         * `RoleLadder` ranks admin above it, so nothing is locked in should that change.
         *
         * The address is the identity: it is what reaches the log line, and it is why [userId] stays the
         * default rather than being invented. Nothing may query by it -- see [isRowBacked].
         */
        fun envAuthed(email: String): UserProfile = UserProfile(
            authId = email,
            client = CL.house,
            roles = setOf(ROLE.operator),
            isRowBacked = false,
        )

        /**
         * Reconstructs a [UserProfile] from a [toUserInfo] map -- the mirror of [toUserInfo], so the frontend
         * (or any KMP consumer) can turn a `UserInfo` response back into the typed object. Missing/blank fields
         * fall back to the same defaults the constructor uses.
         */
        fun fromUserInfo(info: Map<String, Any?>): UserProfile = UserProfile(
            authId = info.getOptStr(UPF.authId),
            userId = info.getOptLong(UPF.userId) ?: CL.systemUserId.toLong(),
            client = info.getOptStr(UPF.client) ?: CL.hub,
            org = info.getOptStr(UPF.org),
            roles = info[UPF.roles].toJsonListOfStrings().toSet(),
            publicName = info.getOptStr(UPF.publicName),
            isEntity = info[UPF.isEntity] == true,
            name = info.getOptStr(UPF.name),
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
                property(UPF.client, "The client the user belongs to.", required = true)
                property(UPF.org, "The user's primary organization within the client, when they have one.")
                property(UPF.roles, "The roles granted to the user.") {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(UPF.publicName, "The user's public display name, when known.")
                property(UPF.isEntity, "Whether this account belongs to a business rather than a person.") { type = SCT.boolean }
                property(UPF.name, "The account's real-world name: a person's full name, or a business's name.")
                property(UPF.hasPassword, "Whether the user has opted into a password.") { type = SCT.boolean }
            }
        }
    }
}
