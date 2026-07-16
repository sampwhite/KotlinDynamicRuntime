package com.dynamicruntime.common.user

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.content.fragmentRefs
import com.dynamicruntime.common.content.uiFragmentsProperty
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.schema.SCT

/**
 * The profile widget-group's endpoints (issue #70). A separate namespace/section from `auth`: the profile
 * page is a *different* group -- login-required, post-authentication account management -- so it gets its own
 * UI-config endpoint and its own `profile` Markdown fragment file, per the per-group endpoint model.
 *
 * The `profile` section is login-gated (see `RequestService.userSections`), so these endpoints run with a real
 * authenticated user. Registered by the `common` component.
 */
fun profileSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "profile") {
    // UserInfo (declared with UserProfile) is reused for the config state and the clear-password response.
    UserProfile.defineInfoType(this)

    // The profile widget-group's UI config: which fragment file holds its copy, which password features apply
    // to this user, and the user's current info.
    type(ATYPE.profileUiConfig) {
        type = SCT.kObject
        uiFragmentsProperty()
        property(UIC.features, "Which profile features apply to the current user.", required = true) {
            type = SCT.kObject
            property(AFEAT.hasPassword, "Whether the user currently has a password set.", required = true) { type = SCT.boolean }
            property(AFEAT.canSetPassword, "Whether the user may set or change a password.", required = true) { type = SCT.boolean }
            property(AFEAT.simulatedEmail, "Whether email is simulated (dev: the code can be read back).", required = true) { type = SCT.boolean }
        }
        property(UIC.state, "Dynamic state for constructing the profile page.", required = true) {
            type = SCT.kObject
            property(AFLD.userInfo, "The current user's info.", required = true) { ref(UserProfile.infoTypeName) }
            property(AFLD.loginId, "The id this user signs in with, for the password calls.", required = true)
        }
    }

    // The manifest for building the profile page. Login-required (the `profile` section), so it always runs for
    // a real user; it loads that user's row for the password status and info.
    generalEndpoint(AEP.profileUiConfig, "Returns the config for constructing the profile page.",
        HttpMethod.GET, outputRef = ATYPE.profileUiConfig) { c, _ ->
        val row = UserService.get(c)?.queryByUserId(c, c.userProfile.userId)
            ?: throw KdrException("The current user could not be found.", code = EXC.notFound)
        mapOf(
            UIC.fragments to fragmentRefs(AFRAG.profile),
            UIC.features to mapOf(
                AFEAT.hasPassword to (row.encodedPassword != null), AFEAT.canSetPassword to true,
                // Carried here too (the auth config has it as well): the page enters a code, so it wants the
                // same dev autofill, and a group's config should carry what that group needs.
                AFEAT.simulatedEmail to (MailService.get(c)?.useSimulatedEmail == true),
            ),
            // The page needs a login id for the (code-verified) password calls. It is served explicitly rather
            // than reused from the user info's publicName: that is a display name, and only resolves as a login
            // id by coincidence of today's fallback (see AuthUserRow.publicName).
            UIC.state to mapOf(AFLD.userInfo to row.toUserProfile().toUserInfo(), AFLD.loginId to row.primaryId),
        )
    }

    // Opt back out of password login. Login-required (relies on the session, not a verification code); removing
    // a password is a de-escalation, and code login still works afterward.
    generalEndpoint(AEP.profileClearPassword, "Removes the caller's password (opt out of password login).",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName) { c, _ ->
        authHandler(c).removePassword(c)
    }
}
