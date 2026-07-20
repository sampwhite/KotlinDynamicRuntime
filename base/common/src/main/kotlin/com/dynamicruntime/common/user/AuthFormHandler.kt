package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.AC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.KdrMsg
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.checkPassword
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.mkRndString

/** Topic logger for the auth subsystem (placed beside the code that owns the `"auth"` topic). */
object LogAuth : KdrLogger("auth")

/**
 * The auth flow orchestrator (issues #67, #69), ported from dn's `AuthFormHandler`. It issues and validates
 * the encrypted, timeout-bounded **form token** (dn's captcha `formAuthCode` is dropped -- no captcha), emails
 * **verification codes** (a deterministic hash of the token and contact, never stored), provisions users, and
 * logs users in.
 *
 * Two login paths: **by verification code** (the primary path, which also marks the current device *familiar*),
 * and **by password** (optional, opt-in), which is permitted **only from a familiar device** -- a hard
 * precondition, not a post-success step-up. A password login rides existing device trust but never grants it,
 * so every trust decision traces back to proving control of the contact via a code.
 *
 * Brute force and email flooding are throttled by an in-memory [AuthRateLimiter] *before* codes/passwords are
 * validated; the throttle is independent of the future single-use verify-code table.
 *
 * On a successful login it binds [KdrCxt.userProfile] and flags the request so the post-dispatch auth hook
 * writes the session cookie (and, for a code login, marks the device familiar); it never touches cookies
 * directly.
 */
class AuthFormHandler(
    private val userService: UserService,
    private val node: NodeService,
    private val mail: MailService,
) {
    /** Throttles for auth brute force / email flooding (issue #69). Per-node and non-durable by design. */
    val rateLimiter = AuthRateLimiter()

    // --- form token ---------------------------------------------------------

    /** Issues a fresh encrypted form token stamping the creation time (for the timeout) plus random salt. */
    fun generateFormToken(cxt: KdrCxt): String =
        node.encryptString("${cxt.now().toEpochMilliseconds()}$tokenSep${mkRndString(6)}")

    /** Throws if [formAuthToken] is undecryptable, malformed, or older than [AUTHC.formTokenMillis]. */
    private fun requireValidToken(cxt: KdrCxt, formAuthToken: String) {
        val plain = try {
            node.decryptString(formAuthToken)
        } catch (_: Exception) {
            throw KdrException.mkInput("The form auth token is invalid.")
        }
        val createdMs = plain.substringBefore(tokenSep).toLongOrNull()
            ?: throw KdrException.mkInput("The form auth token is malformed.")
        if (cxt.now().toEpochMilliseconds() - createdMs > AUTHC.formTokenMillis) {
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.tokenExpired))
        }
    }

    /**
     * Rate-limits, then validates, a verification [verifyCode] for [contactAddress]. The throttle runs first,
     * so the short code cannot be cheaply brute-forced; a correct code clears the counter for that contact.
     */
    private fun verifyCodeOrThrow(cxt: KdrCxt, contactAddress: String, formAuthToken: String, verifyCode: String) {
        val key = "vc:$contactAddress"
        if (!rateLimiter.allow(key, RL.verifyMax, RL.verifyWindowMs, cxt.now().toEpochMilliseconds())) {
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.tooManyVerifyAttempts), code = EXC.tooManyRequests)
        }
        if (computeVerifyCode(formAuthToken, contactAddress) != verifyCode) {
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.codeIncorrect))
        }
        rateLimiter.reset(key)
    }

    // --- sending verification codes -----------------------------------------

    /** Emails a verification code to a new email [contactAddress] (registration). */
    fun sendVerifyToContact(cxt: KdrCxt, contactAddress: String, formAuthToken: String) {
        requireValidToken(cxt, formAuthToken)
        if (!contactAddress.contains("@")) throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.emailNoAt))
        requireSendAllowed(cxt, contactAddress)
        sendVerifyEmail(cxt, contactAddress, computeVerifyCode(formAuthToken, contactAddress), addPassword = false)
    }

    /**
     * Emails a verification code to an existing user (by [loginId] -- username or email) at their primary
     * contact. When [addPassword] is set, the email is framed for setting/changing a password -- kept
     * deliberately ambiguous about whether the user already has one.
     */
    fun sendVerifyToUser(cxt: KdrCxt, loginId: String, formAuthToken: String, addPassword: Boolean = false) {
        requireValidToken(cxt, formAuthToken)
        val user = userService.queryByLoginId(cxt, loginId)
            ?: throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.noAccount), mapOf(AERR.loginIdParam to loginId),
                code = EXC.notFound, sensitive = true, // reveals whether an account exists -> obfuscated in prod
            )
        requireSendAllowed(cxt, user.primaryId)
        sendVerifyEmail(cxt, user.primaryId, computeVerifyCode(formAuthToken, user.primaryId), addPassword)
    }

    /** Throttles verification emails per source IP and per targeted contact, to blunt flooding. */
    private fun requireSendAllowed(cxt: KdrCxt, contactAddress: String) {
        val nowMs = cxt.now().toEpochMilliseconds()
        val ip = cxt.forwardedFor ?: unknownIp
        if (!rateLimiter.allow("svip:$ip", RL.sendPerIpMax, RL.sendPerIpWindowMs, nowMs) ||
            !rateLimiter.allow("svc:$contactAddress", RL.sendPerContactMax, RL.sendPerContactWindowMs, nowMs)
        ) {
            throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.tooManyVerifyRequests),
                code = EXC.tooManyRequests,
            )
        }
    }

    private fun sendVerifyEmail(cxt: KdrCxt, address: String, verifyCode: String, addPassword: Boolean) {
        val template = if (addPassword) {
            $$"Your verification code is ${verifyCode}. Enter it to set or change your password. " +
                "It expires in fifteen minutes."
        } else {
            $$"Your verification code is ${verifyCode}. It expires in fifteen minutes."
        }
        val text = template.evalTemplate(mapOf("verifyCode" to verifyCode))
        mail.sendEmail(cxt, to = address, subject = "Your verification code", text = text)
    }

    // --- user creation ------------------------------------------------------

    /**
     * Provisions the initial user row for a freshly verified email [contactAddress], returning its `userId`.
     * A fresh email inserts a new row; a lingering placeholder (started-but-unfinished) row is re-provisioned.
     * An active, real user already at that email cannot be recreated.
     */
    fun createInitialUser(cxt: KdrCxt, contactAddress: String, formAuthToken: String, verifyCode: String): Long {
        requireValidToken(cxt, formAuthToken)
        verifyCodeOrThrow(cxt, contactAddress, formAuthToken, verifyCode)
        val existing = userService.queryByPrimaryId(cxt, contactAddress)
        if (existing != null && existing.enabled && (!existing.needsRealUsername || existing.encodedPassword != null)) {
            // FUTURE (with the other security hardening -- single-use verify tokens, logout invalidating the
            // auth cookie): rather than an error, *pretend success* here and email the existing account that
            // someone tried to register with their address. For now, it is a sensitive error -- obfuscated to a
            // generic message in prod so it does not confirm the email is taken, and the attempt is logged.
            LogAuth.info(cxt) { "Registration attempted for an already-registered email '$contactAddress'." }
            throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.emailNotAvailable), mapOf(AERR.emailParam to contactAddress),
                sensitive = true,
            )
        }
        // The roles a new user starts with: normally just ROLE.user, but an address matching the deployment's
        // configured admin domain is provisioned as an admin -- how the first admin comes to exist (AdminRules).
        val initialRoles = AdminRules.initialRoles(cxt, contactAddress)
        val data = AuthUserRow.mkInitialUser(contactAddress, AC.public, initialRoles).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val authUserData = data[AU.authUserData] as MutableMap<String, Any?>
        authUserData[AD.validatedContacts] = listOf(contactAddress)
        authUserData[AD.contacts] = listOf(mapOf("address" to contactAddress, "type" to "email"))

        return if (existing != null) {
            existing.username = AuthUserRow.usernameTmpPrefix + contactAddress
            existing.roles = initialRoles
            existing.encodedPassword = null
            existing.authUserData = authUserData
            existing.enabled = true
            userService.updateUser(cxt, existing)
            existing.userId
        } else {
            userService.insertUser(cxt, data)
        }
    }

    // --- login --------------------------------------------------------------

    /**
     * Sets the user's [username] (and, if provided, opt-in [password]) after verifying the code, then logs in.
     * Passwords are optional -- omit it and the user logs in by code only.
     */
    fun setLoginData(
        cxt: KdrCxt, userId: Long, username: String?, password: String?, formAuthToken: String, verifyCode: String,
    ): Map<String, Any?> {
        requireValidToken(cxt, formAuthToken)
        val row = userService.queryByUserId(cxt, userId)
            ?: throw KdrException("User $userId could not be found.", code = EXC.notFound)
        verifyCodeOrThrow(cxt, row.primaryId, formAuthToken, verifyCode)
        if (username != null) {
            val other = userService.queryByUsername(cxt, username)
            if (other != null && other.userId != userId) {
                throw KdrException("Username '$username' has already been taken.", code = EXC.badInput)
            }
        }
        updateUsernameAndPassword(row, username, password)
        userService.updateUser(cxt, row)
        return completeLogin(cxt, row, byCode = true)
    }

    /** Logs a user in by verification code ([loginId] -- username or email -- plus code), the primary path. */
    fun loginByCode(cxt: KdrCxt, loginId: String, formAuthToken: String, verifyCode: String): Map<String, Any?> {
        requireValidToken(cxt, formAuthToken)
        val row = userService.queryByLoginId(cxt, loginId)
            ?: throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.noAccount), mapOf(AERR.loginIdParam to loginId),
                code = EXC.notFound, sensitive = true, // reveals whether an account exists -> obfuscated in prod
            )
        verifyCodeOrThrow(cxt, row.primaryId, formAuthToken, verifyCode)
        return completeLogin(cxt, row, byCode = true)
    }

    /**
     * Logs a user in by [password] ([loginId] is a username or email) -- permitted **only from a familiar
     * device** (issue #69). Every failure (unknown user, unverified device, no password, or a wrong password)
     * returns the *same* opaque message; the real reason is only logged, so the caller cannot tell whether the
     * password was wrong or the device was unfamiliar. Attempts are throttled per login id and per source IP
     * before any password work.
     */
    fun loginByPassword(cxt: KdrCxt, loginId: String, password: String): Map<String, Any?> {
        val nowMs = cxt.now().toEpochMilliseconds()
        val ip = cxt.forwardedFor ?: unknownIp
        if (!rateLimiter.allow("pw:$loginId", RL.pwPerUserMax, RL.pwWindowMs, nowMs) ||
            !rateLimiter.allow("pwip:$ip", RL.pwPerIpMax, RL.pwWindowMs, nowMs)
        ) {
            throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.tooManyLoginAttempts),
                code = EXC.tooManyRequests,
            )
        }

        val row = userService.queryByLoginId(cxt, loginId)
        val deviceGuid = cxt.request?.webRequest?.getRequestCookies()?.get(AUTHC.deviceCookie)
        val stored = row?.encodedPassword
        val failReason: String? = when {
            row == null -> "unknown account"
            deviceGuid == null -> "no device cookie"
            !userService.isDeviceTrusted(cxt, row.userId, deviceGuid) -> "unverified device"
            stored == null -> "no password set"
            !password.checkPassword(stored) -> "incorrect password"
            else -> null
        }
        if (failReason != null) {
            LogAuth.info(cxt) { "Password login failed for '$loginId': $failReason." }
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.loginFailed), code = EXC.authNeeded)
        }
        rateLimiter.reset("pw:$loginId")
        return completeLogin(cxt, row!!, byCode = false)
    }

    // --- password management ------------------------------------------------

    /**
     * Sets or changes the user's password after verifying a code (the code, sent to the user's contact, is the
     * authorization). Because it is a code login, it also completes a login: the session cookie is written and
     * the current device becomes familiar -- so the just-set password is usable from this browser next time.
     */
    fun changePassword(
        cxt: KdrCxt, loginId: String, password: String, formAuthToken: String, verifyCode: String,
    ): Map<String, Any?> {
        requireValidToken(cxt, formAuthToken)
        val row = userService.queryByLoginId(cxt, loginId)
            ?: throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.noAccount), mapOf(AERR.loginIdParam to loginId),
                code = EXC.notFound, sensitive = true, // reveals whether an account exists -> obfuscated in prod
            )
        verifyCodeOrThrow(cxt, row.primaryId, formAuthToken, verifyCode)
        setPassword(row, password)
        userService.updateUser(cxt, row)
        return completeLogin(cxt, row, byCode = true)
    }

    /**
     * Removes the currently-logged-in user's password (opt back out of password login; code login still
     * works). Reached from the profile page, so it relies on the authenticated session rather than a code.
     */
    fun removePassword(cxt: KdrCxt): Map<String, Any?> {
        val row = userService.queryByUserId(cxt, cxt.userProfile.userId)
            ?: throw KdrException("The current user could not be found.", code = EXC.notFound)
        clearPassword(row)
        userService.updateUser(cxt, row)
        return row.toUserProfile().toUserInfo()
    }

    /**
     * Test-only (issue #125): logs [cxt] in as the user whose primary contact is [email], **creating** that
     * user first if none exists. On a create, [grantAdmin] additionally grants the new user the `admin` role;
     * on an existing user it is ignored (you become whoever is already there). When [failIfUserAlreadyExists]
     * is set, an existing user is an error instead of a login.
     *
     * No verification code or password is involved -- it exists purely to seed an authenticated session for
     * tests and local simulations, which is why it is reachable only through a `forTestingOnly` endpoint. Like
     * a real login it flags the session cookie to be written (via [completeLogin]) and returns the user info.
     */
    fun becomeUserByEmail(
        cxt: KdrCxt, email: String, grantAdmin: Boolean, failIfUserAlreadyExists: Boolean,
    ): Map<String, Any?> {
        val existing = userService.queryByLoginId(cxt, email)
        if (existing != null) {
            if (failIfUserAlreadyExists) {
                throw KdrException("A user with email '$email' already exists.", code = EXC.badInput)
            }
            return completeLogin(cxt, existing, byCode = false)
        }
        val roles = if (grantAdmin) listOf(ROLE.user, ROLE.admin) else listOf(ROLE.user)
        val data = AuthUserRow.mkInitialUser(email, AC.public, roles).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val authUserData = data[AU.authUserData] as MutableMap<String, Any?>
        authUserData[AD.validatedContacts] = listOf(email)
        authUserData[AD.contacts] = listOf(mapOf("address" to email, "type" to "email"))
        val userId = userService.insertUser(cxt, data)
        val row = userService.queryByUserId(cxt, userId)
            ?: throw KdrException("Could not load the just-created user '$email'.", code = EXC.internalError)
        return completeLogin(cxt, row, byCode = false)
    }

    /**
     * Binds the acting profile and flags the request for the cookie hook; returns the user-info payload. A
     * [byCode] login additionally flags the device to be marked familiar (see KdrRequest.trustDevice).
     */
    private fun completeLogin(cxt: KdrCxt, row: AuthUserRow, byCode: Boolean): Map<String, Any?> {
        if (!row.enabled) throw KdrException("The user account is not active.", code = EXC.badInput)
        // Re-apply the auto-admin rule here, not just at provisioning: the deployment's admin domain is usually
        // configured *after* its operator already registered, and this is the point where that reaches them. It
        // writes only on the login that actually changes the roles.
        if (AdminRules.syncAdminRole(cxt, row)) {
            userService.updateUser(cxt, row)
        }
        val profile = row.toUserProfile()
        cxt.bindToUserProfile(profile)
        cxt.request?.let {
            it.setAuthCookie = true
            if (byCode) it.trustDevice = true
        }
        return profile.toUserInfo()
    }

    @Suppress("ConstPropertyName")
    private companion object {
        /** Separator between the creation timestamp and salt inside a form token's plaintext. */
        const val tokenSep = "|"

        /** Placeholder source IP for rate-limit keys when the request carries no forwarded-for address. */
        const val unknownIp = "unknown"
    }
}
