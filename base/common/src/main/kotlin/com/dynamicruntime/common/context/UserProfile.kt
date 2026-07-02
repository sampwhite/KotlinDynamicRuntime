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
