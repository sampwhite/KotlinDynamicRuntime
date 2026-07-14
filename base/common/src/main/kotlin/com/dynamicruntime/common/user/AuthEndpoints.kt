package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.getReqLong
import com.dynamicruntime.common.util.getReqStr

/** The user/auth endpoints (issue #67). Registered by the `common` component. */
fun authSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "user") {
    type("FormToken") {
        type = SCT.kObject
        property("formAuthToken", "The encrypted, timeout-bounded form token to include in auth requests.", required = true)
    }
    type("AuthAck") { type = SCT.kObject } // an empty acknowledgement (e.g., a code was emailed)
    type("UserIdResult") {
        type = SCT.kObject
        property("userId", "The user's numeric id.", required = true) { type = SCT.integer }
    }
    // The shared UserInfo type (declared with UserProfile) is what login and self-info endpoints return.
    UserProfile.defineInfoType(this)

    // Issue a form token. No captcha (dn's formAuthCode is dropped): the token alone is what later requests carry.
    generalEndpoint("/auth/form/createToken", "Issues a form auth token for subsequent auth requests.",
        HttpMethod.GET, outputRef = "FormToken") { c, _ ->
        mapOf("formAuthToken" to handler(c).generateFormToken(c))
    }

    // Email a verification code to a new contact (registration).
    generalEndpoint("/auth/newContact/sendVerify", "Emails a verification code to a new email contact.",
        HttpMethod.POST, outputRef = "AuthAck", inputFields = {
            field("contactAddress", "The email address to verify.", required = true)
            field("contactType", "The contact type (currently only 'email').", required = true)
            field("formAuthToken", "The form auth token.", required = true)
        }) { c, req ->
        handler(c).sendVerifyToContact(c, req.getReqStr("contactAddress"), req.getReqStr("formAuthToken"))
        emptyMap<String, Any?>()
    }

    // Email a verification code to an existing user (for code login / forgot-password / activating a password).
    generalEndpoint("/auth/user/sendVerify", "Emails a verification code to an existing user.",
        HttpMethod.POST, outputRef = "AuthAck", inputFields = {
            field("username", "The user's username.", required = true)
            field("formAuthToken", "The form auth token.", required = true)
            field("addPassword", "When true, the emailed code is framed for setting/changing a password.") {
                type = SCT.boolean
            }
        }) { c, req ->
        handler(c).sendVerifyToUser(
            c, req.getReqStr("username"), req.getReqStr("formAuthToken"), req.getOptBool("addPassword") == true,
        )
        emptyMap<String, Any?>()
    }

    // Provision the initial user row from a verified contact.
    generalEndpoint("/auth/user/createInitial", "Creates the initial user row from a verified email contact.",
        HttpMethod.PUT, outputRef = "UserIdResult", inputFields = {
            field("contactAddress", "The verified email address.", required = true)
            field("contactType", "The contact type (currently only 'email').", required = true)
            field("formAuthToken", "The form auth token.", required = true)
            field("verifyCode", "The verification code emailed to the contact.", required = true)
        }) { c, req ->
        val userId = handler(c).createInitialUser(
            c, req.getReqStr("contactAddress"), req.getReqStr("formAuthToken"), req.getReqStr("verifyCode"),
        )
        mapOf("userId" to userId)
    }

    // Set username (and optional password) after verifying, then log in.
    generalEndpoint("/auth/user/setLoginData", "Sets the user's username (and optional password) and logs in.",
        HttpMethod.PUT, outputRef = "UserInfo", inputFields = {
            field("userId", "The user's numeric id.", required = true) { type = SCT.integer }
            field("username", "The chosen username.")
            field("password", "An optional password to set (login by code works without one).")
            field("formAuthToken", "The form auth token.", required = true)
            field("verifyCode", "The verification code.", required = true)
        }) { c, req ->
        handler(c).setLoginData(
            c, req.getReqLong("userId"), req.getOptStr("username"), req.getOptStr("password"),
            req.getReqStr("formAuthToken"), req.getReqStr("verifyCode"),
        )
    }

    // Log in by verification code (the primary login path).
    generalEndpoint("/auth/login/byCode", "Logs a user in with a username and verification code.",
        HttpMethod.POST, outputRef = "UserInfo", inputFields = {
            field("username", "The user's username.", required = true)
            field("formAuthToken", "The form auth token.", required = true)
            field("verifyCode", "The verification code.", required = true)
        }) { c, req ->
        handler(c).loginByCode(c, req.getReqStr("username"), req.getReqStr("formAuthToken"), req.getReqStr("verifyCode"))
    }

    // Log in by password -- permitted only from a familiar (verified) device. On any failure the caller gets a
    // single opaque message (the real reason is only logged); the client then falls back to code login.
    generalEndpoint("/auth/login/byPassword", "Logs a user in by password (familiar devices only).",
        HttpMethod.POST, outputRef = "UserInfo", inputFields = {
            field("username", "The user's username.", required = true)
            field("password", "The user's password.", required = true)
        }) { c, req ->
        handler(c).loginByPassword(c, req.getReqStr("username"), req.getReqStr("password"))
    }

    // Set or change the user's password after verifying a code. This is a code login, so it also logs the user
    // in and makes the current device familiar (so the new password is usable from this browser next time).
    generalEndpoint("/auth/user/setPassword", "Sets or changes the user's password (verified by a code).",
        HttpMethod.PUT, outputRef = "UserInfo", inputFields = {
            field("username", "The user's username.", required = true)
            field("password", "The new password.", required = true)
            field("formAuthToken", "The form auth token.", required = true)
            field("verifyCode", "The verification code.", required = true)
        }) { c, req ->
        handler(c).changePassword(
            c, req.getReqStr("username"), req.getReqStr("password"),
            req.getReqStr("formAuthToken"), req.getReqStr("verifyCode"),
        )
    }

    // Opt back out of password login. Under the `user` section, so it requires a logged-in user (the profile
    // page action); it relies on the session rather than a verification code.
    generalEndpoint("/user/self/clearPassword", "Removes the caller's password (opt out of password login).",
        HttpMethod.POST, outputRef = "UserInfo") { c, _ ->
        handler(c).removePassword(c)
    }

    // The caller's own info. Under the anonymous `auth` section, so it never 401s: a logged-in caller gets
    // their profile (freshly loaded for the display name), a logged-out caller gets the anonymous profile.
    // (Renamed from `/user/self/info`; per CRDR/cedar, self-info should be callable without a login.)
    generalEndpoint("/auth/self/info", "Returns the caller's user info (anonymous when not logged in).",
        HttpMethod.GET, outputRef = "UserInfo") { c, _ ->
        val profile = c.userProfile
        val loaded = if (profile.authId != null) UserService.get(c)?.queryByUserId(c, profile.userId)?.toUserProfile() else null
        (loaded ?: UserProfile.anonymous()).toUserInfo()
    }

    // Log out: flag the request so the auth hook clears the session cookie.
    generalEndpoint("/logout", "Logs the current user out (clears the session cookie).",
        HttpMethod.GET, outputRef = "AuthAck") { c, _ ->
        c.request?.clearAuth = true
        emptyMap<String, Any?>()
    }
}

private fun handler(cxt: KdrCxt): AuthFormHandler {
    val service = UserService.get(cxt) ?: throw KdrException("UserService is not available.")
    service.checkInit(cxt) // idempotent; ensures the handler is built
    return service.authFormHandler
}
