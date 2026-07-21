package com.dynamicruntime.common.user

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.content.fragmentRefs
import com.dynamicruntime.common.content.uiFragmentsProperty
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.getReqLong
import com.dynamicruntime.common.util.getReqStr

/**
 * The user/auth endpoints (issues #67, #69, #70). Registered by the `common` component. Paths, field names,
 * feature flags, and type names come from the kernel auth constants ([AEP]/[AFLD]/[AFEAT]/[ATYPE]/[AFRAG]) so
 * the frontend references the same strings; see also [profileSchema].
 */
fun authSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "user") {
    type(ATYPE.formToken) {
        type = SCT.kObject
        property(AFLD.formAuthToken, "The encrypted, timeout-bounded form token to include in auth requests.", required = true)
    }
    type(ATYPE.authAck) { type = SCT.kObject } // an empty acknowledgement (e.g., a code was emailed)
    type(ATYPE.userIdResult) {
        type = SCT.kObject
        property(AFLD.userId, "The user's numeric id.", required = true) { type = SCT.integer }
    }
    // The shared UserInfo type (declared with UserProfile) is what login and self-info endpoints return.
    UserProfile.defineInfoType(this)

    // The auth widget-group's UI config (issue #70): the manifest the frontend fetches to build the
    // register/login flow -- which fragment file holds its copy, which features are on, and the caller's state.
    type(ATYPE.authUiConfig) {
        type = SCT.kObject
        uiFragmentsProperty()
        property(UIC.features, "Which auth features are offered.", required = true) {
            type = SCT.kObject
            property(AFEAT.registration, "Whether new-user registration is offered.", required = true) { type = SCT.boolean }
            property(AFEAT.codeLogin, "Whether verification-code login is offered.", required = true) { type = SCT.boolean }
            property(AFEAT.passwordLogin, "Whether password login is offered.", required = true) { type = SCT.boolean }
            property(AFEAT.simulatedEmail, "Whether email is simulated (dev: the code can be read back).", required = true) { type = SCT.boolean }
        }
        property(UIC.state, "Dynamic state for constructing the auth flow.", required = true) {
            type = SCT.kObject
            property(AFLD.userInfo, "The caller's user info (anonymous when not logged in).", required = true) {
                ref(UserProfile.infoTypeName)
            }
        }
    }

    // Issue a form token. No captcha (dn's formAuthCode is dropped): the token alone is what later requests carry.
    generalEndpoint(AEP.createToken, "Issues a form auth token for subsequent auth requests.",
        HttpMethod.GET, outputRef = ATYPE.formToken) { c, _ ->
        mapOf(AFLD.formAuthToken to authHandler(c).generateFormToken(c))
    }

    // Email a verification code to a new contact (registration).
    generalEndpoint(AEP.newContactSendVerify, "Emails a verification code to a new email contact.",
        HttpMethod.POST, outputRef = ATYPE.authAck, inputFields = {
            field(AFLD.contactAddress, "The email address to verify.", required = true)
            field(AFLD.contactType, "The contact type (currently only 'email').", required = true)
            field(AFLD.formAuthToken, "The form auth token.", required = true)
        }) { c, req ->
        authHandler(c).sendVerifyToContact(c, req.getReqStr(AFLD.contactAddress), req.getReqStr(AFLD.formAuthToken))
        emptyMap<String, Any?>()
    }

    // Email a verification code to an existing user (for code login / forgot-password / activating a password).
    generalEndpoint(AEP.userSendVerify, "Emails a verification code to an existing user.",
        HttpMethod.POST, outputRef = ATYPE.authAck, inputFields = {
            field(AFLD.loginId, "The user's username or email address.", required = true)
            field(AFLD.formAuthToken, "The form auth token.", required = true)
            field(AFLD.addPassword, "When true, the emailed code is framed for setting/changing a password.") {
                type = SCT.boolean
            }
        }) { c, req ->
        authHandler(c).sendVerifyToUser(
            c, req.getReqStr(AFLD.loginId), req.getReqStr(AFLD.formAuthToken), req.getOptBool(AFLD.addPassword) == true,
        )
        emptyMap<String, Any?>()
    }

    // Provision the initial user row from a verified contact.
    generalEndpoint(AEP.createInitial, "Creates the initial user row from a verified email contact.",
        HttpMethod.PUT, outputRef = ATYPE.userIdResult, inputFields = {
            field(AFLD.contactAddress, "The verified email address.", required = true)
            field(AFLD.contactType, "The contact type (currently only 'email').", required = true)
            field(AFLD.formAuthToken, "The form auth token.", required = true)
            field(AFLD.verifyCode, "The verification code emailed to the contact.", required = true)
        }) { c, req ->
        val userId = authHandler(c).createInitialUser(
            c, req.getReqStr(AFLD.contactAddress), req.getReqStr(AFLD.formAuthToken), req.getReqStr(AFLD.verifyCode),
        )
        mapOf(AFLD.userId to userId)
    }

    // Set username (and optional password) after verifying, then log in.
    generalEndpoint(AEP.setLoginData, "Sets the user's username (and optional password) and logs in.",
        HttpMethod.PUT, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.userId, "The user's numeric id.", required = true) { type = SCT.integer }
            field(AFLD.username, "The chosen username.")
            field(AFLD.password, "An optional password to set (login by code works without one).")
            field(AFLD.formAuthToken, "The form auth token.", required = true)
            field(AFLD.verifyCode, "The verification code.", required = true)
        }) { c, req ->
        authHandler(c).setLoginData(
            c, req.getReqLong(AFLD.userId), req.getOptStr(AFLD.username), req.getOptStr(AFLD.password),
            req.getReqStr(AFLD.formAuthToken), req.getReqStr(AFLD.verifyCode),
        )
    }

    // Log in by verification code (the primary login path).
    generalEndpoint(AEP.loginByCode, "Logs a user in with a username/email and verification code.",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.loginId, "The user's username or email address.", required = true)
            field(AFLD.formAuthToken, "The form auth token.", required = true)
            field(AFLD.verifyCode, "The verification code.", required = true)
        }) { c, req ->
        authHandler(c).loginByCode(c, req.getReqStr(AFLD.loginId), req.getReqStr(AFLD.formAuthToken), req.getReqStr(AFLD.verifyCode))
    }

    // Log in by password -- permitted only from a familiar (verified) device. On any failure the caller gets a
    // single opaque message (the real reason is only logged); the client then falls back to code login.
    generalEndpoint(AEP.loginByPassword, "Logs a user in by password (familiar devices only).",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.loginId, "The user's username or email address.", required = true)
            field(AFLD.password, "The user's password.", required = true)
        }) { c, req ->
        authHandler(c).loginByPassword(c, req.getReqStr(AFLD.loginId), req.getReqStr(AFLD.password))
    }

    // Set or change the user's password after verifying a code. This is a code login, so it also logs the user
    // in and makes the current device familiar (so the new password is usable from this browser next time).
    generalEndpoint(AEP.setPassword, "Sets or changes the user's password (verified by a code).",
        HttpMethod.PUT, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.loginId, "The user's username or email address.", required = true)
            field(AFLD.password, "The new password.", required = true)
            field(AFLD.formAuthToken, "The form auth token.", required = true)
            field(AFLD.verifyCode, "The verification code.", required = true)
        }) { c, req ->
        authHandler(c).changePassword(
            c, req.getReqStr(AFLD.loginId), req.getReqStr(AFLD.password),
            req.getReqStr(AFLD.formAuthToken), req.getReqStr(AFLD.verifyCode),
        )
    }

    // A manifest for building the auth flow (issue #70). Under the anonymous `auth` section, so a logged-out
    // caller can bootstrap the register/login UI; the returned state carries the (anonymous or real) user info.
    generalEndpoint(AEP.authUiConfig, "Returns the config for constructing the auth (register/login) UI.",
        HttpMethod.GET, outputRef = ATYPE.authUiConfig) { c, _ ->
        mapOf(
            UIC.fragments to fragmentRefs(AFRAG.auth),
            UIC.features to mapOf(
                AFEAT.registration to true, AFEAT.codeLogin to true, AFEAT.passwordLogin to true,
                AFEAT.simulatedEmail to (MailService.get(c)?.useSimulatedEmail == true),
            ),
            UIC.state to mapOf(AFLD.userInfo to currentUserInfo(c)),
        )
    }

    // The caller's own info. Under the anonymous `auth` section, so it never 401s: a logged-in caller gets
    // their profile (freshly loaded for the display name), a logged-out caller gets the anonymous profile.
    // (Renamed from `/user/self/info`; per CRDR/cedar, self-info should be callable without a login.)
    generalEndpoint(AEP.selfInfo, "Returns the caller's user info (anonymous when not logged in).",
        HttpMethod.GET, outputRef = UserProfile.infoTypeName) { c, _ -> currentUserInfo(c) }

    // Log out: flag the request so the auth hook clears the session cookie.
    generalEndpoint(AEP.logout, "Logs the current user out (clears the session cookie).",
        HttpMethod.GET, outputRef = ATYPE.authAck) { c, _ ->
        c.request?.clearAuth = true
        emptyMap<String, Any?>()
    }
    // The recent-simulated-emails endpoint moved to the `test` module as `/test/simulatedEmails` (issue #158),
    // governed by the unified test-instance gate instead of a bespoke runtime check.
}

/** Resolves the [AuthFormHandler] (ensuring it is built). Shared with the profile endpoints (same package). */
internal fun authHandler(cxt: KdrCxt): AuthFormHandler {
    val service = UserService.get(cxt) ?: throw KdrException("UserService is not available.")
    service.checkInit(cxt) // idempotent; ensures the handler is built
    return service.authFormHandler
}

/**
 * The caller's user info: a logged-in caller's profile (freshly loaded from the row for the display name and
 * password status), or the anonymous profile when not logged in. Shared by `/auth/self/info`, the auth UI
 * config, and the profile UI config (all in this package).
 */
internal fun currentUserInfo(cxt: KdrCxt): Map<String, Any?> {
    val profile = cxt.userProfile
    val loaded = if (profile.authId != null) UserService.get(cxt)?.queryByUserId(cxt, profile.userId)?.toUserProfile() else null
    return (loaded ?: UserProfile.anonymous()).toUserInfo()
}
