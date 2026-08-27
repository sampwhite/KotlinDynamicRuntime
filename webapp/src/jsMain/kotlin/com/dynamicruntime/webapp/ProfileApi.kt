package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFEAT
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/** Which password affordances apply to the current user (from the profile UI-config `features`). */
class ProfileFeatures(
    /** Whether the user currently has a password (so the page offers change/remove rather than set). */
    val hasPassword: Boolean,
    /** Whether the user may set or change a password at all. */
    val canSetPassword: Boolean,
    /** Dev only: email is simulated, so the code can be read back from `/test/simulatedEmails`. */
    val simulatedEmail: Boolean,
)

/**
 * The profile page's construction manifest: where its copy lives, which password affordances apply, who the
 * user is, and the [loginId] its password calls need.
 */
class ProfileConfig(
    val fragment: FragmentRef,
    val features: ProfileFeatures,
    val user: UserProfile,
    /**
     * The id this user signs in with, served by the config rather than taken from [user]'s publicName -- that
     * is a display name, and resolves as a login id only by coincidence of today's fallback.
     */
    val loginId: String,
)

/**
 * The pure [UiConfig] -> [ProfileConfig] mapping, separated from the fetch so it is unit-testable (issue #161):
 * the password affordance flags, the caller's profile, and the login id its password calls need.
 */
fun profileConfigFrom(config: UiConfig): ProfileConfig = ProfileConfig(
    fragment = config.fragment,
    features = ProfileFeatures(
        hasPassword = config.features[AFEAT.hasPassword] == true,
        canSetPassword = config.features[AFEAT.canSetPassword] == true,
        simulatedEmail = config.features[AFEAT.simulatedEmail] == true,
    ),
    user = UserProfile.fromUserInfo(config.state[AFLD.userInfo].toJsonMapOrEmpty()),
    loginId = config.state.getOptStr(AFLD.loginId) ?: "",
)

/**
 * The profile widget-group's backend calls (issue #70 Piece 3), keyed off the shared kernel constants so the
 * frontend never re-hardcodes a path or a JSON key the backend serves.
 *
 * Setting or changing a password is **code-verified** even though the caller is already logged in: it is a
 * step-up, and it runs through the same `auth` endpoints the login flow uses ([AuthApi.sendVerifyUser] with
 * `addPassword`, then [AuthApi.setPassword]). Only *removing* a password is a plain session call -- it is a
 * de-escalation, and code login still works afterward.
 */
object ProfileApi {
    /** GET the profile UI-config. Login-required: a logged-out caller raises, and the page sends them to login. */
    suspend fun fetchConfig(): ProfileConfig = profileConfigFrom(fetchUiConfig(AEP.profileUiConfig))

    /**
     * Sets the name the caller is shown under; a blank [name] clears it, falling the display back to their
     * login name. Session-authorized -- no verification code, because a display name is not a credential.
     */
    suspend fun setName(name: String): UserProfile =
        UserProfile.fromUserInfo(
            Http.sendApi("POST", AEP.profileSetName, mapOf(AFLD.name to name))[EP.results].toJsonMapOrEmpty(),
        )

    /** Removes the caller's password (opting back out of password login); returns the updated user info. */
    suspend fun clearPassword(): UserProfile =
        UserProfile.fromUserInfo(
            Http.sendApi("POST", AEP.profileClearPassword, emptyMap())[EP.results].toJsonMapOrEmpty(),
        )
}
