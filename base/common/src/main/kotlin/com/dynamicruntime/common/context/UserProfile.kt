package com.dynamicruntime.common.context

/**
 * Stubbed placeholder for the authenticated-user information carried by a
 * [KdrCxt]. A real implementation will carry identity, roles, and the user's
 * client account. For now, it holds just enough for context logging and account
 * defaulting.
 *
 * (Named without the `Kdr` prefix per the naming guide: `UserProfile` is specific
 * enough not to be ambiguous.)
 */
class UserProfile(
    /** Authenticated identity, or null when no user is authenticated. */
    val authId: String? = null,
    /**
     * Numeric identity of the user, used by the database layer to stamp `createdBy`/`updatedBy` and to own
     * user-scoped rows. Defaults to [AC.systemUserId] (the implicit system user) until real auth exists.
     */
    val userId: Long = AC.systemUserId.toLong(),
    /** The client account this user belongs to. */
    val account: String = AC.local,
    /**
     * Role privileges granted to the user, established by the authentication layer before an endpoint
     * runs. Interior privileges (to specific organizations or content) are determined inside endpoints.
     */
    val roles: Set<String> = emptySet(),
) {
    companion object {
        /** The implicit, unauthenticated system user used until real auth exists. */
        fun systemUser(): UserProfile = UserProfile(authId = null, account = AC.local)
    }
}
