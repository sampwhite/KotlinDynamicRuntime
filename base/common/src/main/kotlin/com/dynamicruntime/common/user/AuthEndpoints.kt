package com.dynamicruntime.common.user

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.content.fragmentRefs
import com.dynamicruntime.common.content.uiFragmentsProperty
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.InputFieldsBuilder
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.gedra.clientAttribute
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.getReqLong
import com.dynamicruntime.common.util.getReqStr
import com.dynamicruntime.common.util.normalizeEmail
import com.dynamicruntime.common.util.normalizeLoginId

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
    // The switcher's list (issue #749): the users the signed-in person may act as, as `UserChoice`s.
    UserChoice.defineInfoType(this)
    type(ATYPE.userChoices) {
        type = SCT.kObject
        property(AFLD.users, "The registered, enabled users of the caller's identity; the current one is marked.", required = true) {
            type = SCT.array
            items { ref(UserChoice.infoTypeName) }
        }
    }

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
            property(AFEAT.googleLogin, "Whether Google sign-in is offered.", required = true) { type = SCT.boolean }
            property(AFEAT.simulatedEmail, "Whether email is simulated (dev: the code can be read back).", required = true) { type = SCT.boolean }
        }
        property(UIC.state, "Dynamic state for constructing the auth flow.", required = true) {
            type = SCT.kObject
            property(AFLD.userInfo, "The caller's user info (anonymous when not logged in).", required = true) {
                ref(UserProfile.infoTypeName)
            }
            // Public by design -- it identifies the application to Google and the browser has to present it.
            // Empty when Google sign-in is off, so the frontend never has to special-case a missing key.
            property(AFLD.googleClientId, "The Google OAuth client id (empty when Google sign-in is off).",
                required = true) {
                // Empty is a real answer here -- "this deployment configured no client id" -- so it must not
                // read as absent and fail the required check it is paired with.
                emptyIsAbsent = false
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
        authHandler(c).sendVerifyToContact(c, req.getReqStr(AFLD.contactAddress).normalizeEmail(), req.getReqStr(AFLD.formAuthToken))
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
            c, req.getReqStr(AFLD.loginId).normalizeLoginId(), req.getReqStr(AFLD.formAuthToken), req.getOptBool(AFLD.addPassword) == true,
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
            // Where the new user goes (issue #751): for an allClients caller only; refused for anyone else.
            field(AFLD.client, "The new user's client; only an '${ROLE.allClients}' caller may name one (else '${CL.hub}' for a user granted '${ROLE.allClients}', '${CL.public}' otherwise).") { clientAttribute() }
            field(AFLD.persona, "The new user's persona; only an '${ROLE.allClients}' caller may name one (else '${PERSONA.member}').") {
                for (def in PERSONA.defs) option(def.name, def.label)
            }
            field(AFLD.personaSuffix, "A further user of the same address, client and persona; only an '${ROLE.allClients}' caller may name one.") {
                maxLength = PERSONASUFFIX.maxLength
            }
        }) { c, req ->
        val userId = authHandler(c).createInitialUser(
            c, req.getReqStr(AFLD.contactAddress).normalizeEmail(), req.getReqStr(AFLD.formAuthToken), req.getReqStr(AFLD.verifyCode),
            client = req.getOptStr(AFLD.client)?.trim()?.ifEmpty { null },
            persona = req.getOptStr(AFLD.persona)?.trim()?.ifEmpty { null },
            personaSuffix = req.getOptStr(AFLD.personaSuffix)?.trim() ?: "",
        )
        mapOf(AFLD.userId to userId)
    }

    // Claiming an account created for you (issue #751): the login page's path for an invited person who has
    // nothing but what the invitation said. The client and persona are typed, never offered, and the send
    // always answers as a success -- the mail says whether anything matched.
    generalEndpoint(AEP.claimSendVerify, "Mails a verification code for the user an address, client and persona name.",
        HttpMethod.POST, outputRef = ATYPE.authAck, inputFields = {
            claimKeyInput()
            field(AFLD.formAuthToken, "The form auth token.", required = true)
        }) { c, req ->
        authHandler(c).sendClaimCode(c, claimKeyOf(req), req.getReqStr(AFLD.formAuthToken))
        emptyMap<String, Any?>()
    }
    generalEndpoint(AEP.claimAccount, "Registers and logs in as the user an address, client and persona name, with the mailed code.",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName, inputFields = {
            claimKeyInput()
            field(AFLD.formAuthToken, "The form auth token.", required = true)
            field(AFLD.verifyCode, "The verification code mailed for this claim.", required = true)
        }) { c, req ->
        authHandler(c).claimAccount(c, claimKeyOf(req), req.getReqStr(AFLD.formAuthToken), req.getReqStr(AFLD.verifyCode))
    }

    // Invitations (issue #751). Preview says what the link is for and changes nothing; accept is the claim.
    type(ATYPE.invitationInfo) {
        type = SCT.kObject
        property(AFLD.email, "The invited address.", required = true)
        property(AFLD.client, "The client the invited user is in.", required = true)
        property(AFLD.persona, "The invited user's persona.", required = true)
        property(AFLD.personaSuffix, "The invited user's personaSuffix; empty for the ordinary user.", required = true) { emptyIsAbsent = false }
        property(AFLD.name, "The invited user's name, when the inviter gave one.")
    }
    generalEndpoint(AEP.invitationPreview, "Says what an invitation link is for, without accepting it.",
        HttpMethod.POST, outputRef = ATYPE.invitationInfo, inputFields = {
            field(AFLD.invitationToken, "The token from the mailed link.", required = true)
        }) { c, req ->
        authHandler(c).previewInvitation(c, req.getReqStr(AFLD.invitationToken))
    }
    generalEndpoint(AEP.invitationAccept, "Accepts an invitation: registers the invited user and logs in as it.",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.invitationToken, "The token from the mailed link.", required = true)
        }) { c, req ->
        authHandler(c).acceptInvitation(c, req.getReqStr(AFLD.invitationToken))
    }

    // Set username (and optional password) after verifying, then log in.
    generalEndpoint(AEP.setLoginData, "Sets the user's username (and optional password) and logs in.",
        HttpMethod.PUT, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.userId, "The user's numeric id.", required = true) { type = SCT.integer }
            field(AFLD.username, "The chosen username.")
            // A password's edge whitespace is content, not a paste artifact: opt out of the input trim default
            // (issue #765) so the credential is stored exactly as given, the same on the login path below.
            field(AFLD.password, "An optional password to set (login by code works without one).") { preserveWhitespace() }
            field(AFLD.isEntity, "Whether this is a business account rather than a personal one.") { type = SCT.boolean }
            field(AFLD.name, "The account's name: the registrant's full name, or the business's name.")
            field(AFLD.formAuthToken, "The form auth token.", required = true)
            field(AFLD.verifyCode, "The verification code.", required = true)
        }) { c, req ->
        authHandler(c).setLoginData(
            c, req.getReqLong(AFLD.userId), req.getOptStr(AFLD.username), req.getOptStr(AFLD.password),
            req.getOptBool(AFLD.isEntity), req.getOptStr(AFLD.name),
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
        authHandler(c).loginByCode(c, req.getReqStr(AFLD.loginId).normalizeLoginId(), req.getReqStr(AFLD.formAuthToken), req.getReqStr(AFLD.verifyCode))
    }

    // Log in by password -- permitted only from a familiar (verified) device. On any failure the caller gets a
    // single opaque message (the real reason is only logged); the client then falls back to code login.
    generalEndpoint(AEP.loginByPassword, "Logs a user in by password (familiar devices only).",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.loginId, "The user's username or email address.", required = true)
            // Match the value exactly as set (issue #765): trimming here could fail a password whose stored
            // form legitimately carries edge whitespace.
            field(AFLD.password, "The user's password.", required = true) { preserveWhitespace() }
        }) { c, req ->
        authHandler(c).loginByPassword(c, req.getReqStr(AFLD.loginId).normalizeLoginId(), req.getReqStr(AFLD.password))
    }

    // Log in with Google (issue #157). The browser's Google sign-in hands back an ID token, which is all this
    // endpoint takes -- no form token, since the token is itself the (Google-signed) proof of identity.
    generalEndpoint(AEP.loginByGoogle, "Logs a user in from a Google ID token (linking the account on first use).",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.googleCredential, "The Google ID token from the browser's sign-in.", required = true)
        }) { c, req ->
        authHandler(c).loginByGoogle(c, req.getReqStr(AFLD.googleCredential))
    }

    // Set or change the user's password after verifying a code. This is a code login, so it also logs the user
    // in and makes the current device familiar (so the new password is usable from this browser next time).
    generalEndpoint(AEP.setPassword, "Sets or changes the user's password (verified by a code).",
        HttpMethod.PUT, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.loginId, "The user's username or email address.", required = true)
            // Store the new credential exactly as given (issue #765); see the note on the set-login-data call.
            field(AFLD.password, "The new password.", required = true) { preserveWhitespace() }
            field(AFLD.formAuthToken, "The form auth token.", required = true)
            field(AFLD.verifyCode, "The verification code.", required = true)
        }) { c, req ->
        authHandler(c).changePassword(
            c, req.getReqStr(AFLD.loginId).normalizeLoginId(), req.getReqStr(AFLD.password),
            req.getReqStr(AFLD.formAuthToken), req.getReqStr(AFLD.verifyCode),
        )
    }

    // A manifest for building the auth flow (issue #70). Under the anonymous `auth` section, so a logged-out
    // caller can bootstrap the register/login UI; the returned state carries the (anonymous or real) user info.
    generalEndpoint(AEP.authUiConfig, "Returns the config for constructing the auth (register/login) UI.",
        HttpMethod.GET, outputRef = ATYPE.authUiConfig, tags = setOf(ETAG.frontend)) { c, _ ->
        mapOf(
            UIC.fragments to fragmentRefs(c, AFRAG.auth),
            UIC.features to mapOf(
                AFEAT.registration to true, AFEAT.codeLogin to true, AFEAT.passwordLogin to true,
                AFEAT.googleLogin to authHandler(c).googleLoginEnabled,
                AFEAT.simulatedEmail to MailService.get(c).useSimulatedEmail,
            ),
            UIC.state to mapOf(
                AFLD.userInfo to currentUserInfo(c),
                AFLD.googleClientId to (GoogleAuthConfig.clientId(c.instanceConfig) ?: ""),
            ),
        )
    }

    // The caller's own info. Under the anonymous `auth` section, so it never 401s: a logged-in caller gets
    // their profile (freshly loaded for the display name), a logged-out caller gets the anonymous profile.
    // (Renamed from `/user/self/info`; per CRDR/cedar, self-info should be callable without a login.)
    generalEndpoint(AEP.selfInfo, "Returns the caller's user info (anonymous when not logged in).",
        // Auth self-info: part of the published API (issue #489) and consumed by the frontend auth flow.
        HttpMethod.GET, outputRef = UserProfile.infoTypeName, publicApi = true, tags = setOf(ETAG.frontend)) { c, _ ->
        currentUserInfo(c)
    }

    // The switcher (issue #749), under the login-gated `user` section: the gate re-reads the acting row, so a
    // disabled or detached user cannot switch away from itself, and every call acts on the session's identity.
    generalEndpoint(AEP.selfUsers, "Lists the users the signed-in person may act as.",
        HttpMethod.GET, outputRef = ATYPE.userChoices, tags = setOf(ETAG.frontend)) { c, _ ->
        authHandler(c).selfUsers(c)
    }
    generalEndpoint(AEP.switchUser, "Becomes another of the signed-in person's users (a fresh session).",
        HttpMethod.POST, outputRef = UserProfile.infoTypeName, inputFields = {
            field(AFLD.userId, "The user to become: a registered, enabled user of the caller's own identity.", required = true) { type = SCT.integer }
        }) { c, req ->
        authHandler(c).switchUser(c, req.getReqLong(AFLD.userId))
    }
    generalEndpoint(AEP.setDefaultUser, "Chooses which of the signed-in person's users their address logs in as.",
        HttpMethod.POST, outputRef = ATYPE.userChoices, inputFields = {
            field(AFLD.userId, "The user to make the default: a registered, enabled user of the caller's own identity.", required = true) { type = SCT.integer }
        }) { c, req ->
        authHandler(c).setDefaultUser(c, req.getReqLong(AFLD.userId))
    }

    // Log out: flag the request so the auth hook clears the session cookie.
    generalEndpoint(AEP.logout, "Logs the current user out (clears the session cookie).",
        HttpMethod.GET, outputRef = ATYPE.authAck) { c, _ ->
        c.request?.clearAuth = true
        emptyMap<String, Any?>()
    }
    // The recent-simulated-emails endpoint moved to the `test` module as `/test/simulatedEmails` (issue #158),
    // governed by the unified test-instance gate instead of a bespoke runtime check.
}

/**
 * The fields that name the user a claim is for (issue #751), shared by the claim send and the claim itself: the
 * address, and the client, persona, and persona suffix the invitation named. Each endpoint adds its own token
 * fields after these. The parts are read back as one [ClaimKey] by [claimKeyOf], which is where the defaults
 * for an absent client or persona are applied.
 */
private fun InputFieldsBuilder.claimKeyInput() {
    field(AFLD.contactAddress, "The email address the account was created for.", required = true)
    field(AFLD.client, "The client the invitation named; '${CL.public}' when absent.") { maxLength = ClaimKey.maxPartLength }
    field(AFLD.persona, "The persona the invitation named; '${PERSONA.member}' when absent.") { maxLength = ClaimKey.maxPartLength }
    field(AFLD.personaSuffix, "The persona suffix the invitation named, when it did.") { maxLength = PERSONASUFFIX.maxLength }
}

/** The [ClaimKey] a request's [claimKeyInput] fields name. */
private fun claimKeyOf(req: Map<String, Any?>): ClaimKey = ClaimKey(
    req.getReqStr(AFLD.contactAddress).normalizeEmail(), req.getOptStr(AFLD.client), req.getOptStr(AFLD.persona),
    req.getOptStr(AFLD.personaSuffix)?.trim() ?: "",
)

/** Resolves the [AuthFormHandler] (ensuring it is built). Shared with the profile endpoints (same package). */
internal fun authHandler(cxt: KdrCxt): AuthFormHandler {
    val service = UserService.get(cxt)
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
    // Guarded on `isRowBacked` rather than on a non-null `authId`, which is the same distinction
    // `refreshActingRoles` already draws and for the reason its note gives: the question is whether there is a
    // row to read, and the anonymous profile has an `authId` while having no row. Testing the id was harmless
    // only while an unauthenticated request still carried the *system* profile; now that it carries the
    // anonymous one, it would send a query after `CL.systemUserId` on every logged-out call.
    val loaded = if (profile.isRowBacked) UserService.get(cxt).queryByUserId(cxt, profile.userId)?.toUserProfile() else null
    return (loaded ?: UserProfile.anonymous()).toUserInfo()
}
