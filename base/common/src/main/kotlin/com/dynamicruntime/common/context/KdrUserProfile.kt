package com.dynamicruntime.common.context

/**
 * Stubbed placeholder for the authenticated-user information carried by a
 * [KdrCxt]. A real implementation will carry identity, roles, and the user's
 * client account. For now, it holds just enough for context logging and account
 * defaulting.
 */
class KdrUserProfile(
    /** Authenticated identity, or null when no user is authenticated. */
    val authId: String? = null,
    /** The client account this user belongs to. */
    val account: String = AC.local,
) {
    companion object {
        /** The implicit, unauthenticated system user used until real auth exists. */
        fun systemUser(): KdrUserProfile = KdrUserProfile(authId = null, account = AC.local)
    }
}
