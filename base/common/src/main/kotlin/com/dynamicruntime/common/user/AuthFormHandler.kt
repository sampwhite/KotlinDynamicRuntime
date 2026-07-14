package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.AC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.mkRndString

/**
 * The auth flow orchestrator (issue #67), ported from dn's `AuthFormHandler` and pared to the verify-code
 * core. It issues and validates the encrypted, timeout-bounded **form token** (dn's captcha `formAuthCode` is
 * dropped -- no captcha), emails **verification codes** (a deterministic hash of the token + contact, never
 * stored), provisions initial users, and logs users in **by verification code**. Passwords are optional: a
 * user may set one via [setLoginData], but login-by-password (and the familiar-device step-up gate) are a
 * follow-up.
 *
 * On a successful login it binds [KdrCxt.userProfile] and flags the request so the post-dispatch auth hook
 * writes the session cookie; it never touches cookies directly.
 *
 * Deferred from dn (not needed for the verify-code core): the per-hour/per-IP/per-token rate limiting, and
 * login-by-password with the familiar-device step-up gate.
 */
class AuthFormHandler(
    private val userService: UserService,
    private val node: NodeService,
    private val mail: MailService,
) {
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
            throw KdrException.mkInput("The form auth token has expired; request a new one.")
        }
    }

    // --- sending verification codes -----------------------------------------

    /** Emails a verification code to a new email [contactAddress] (registration). */
    fun sendVerifyToContact(cxt: KdrCxt, contactAddress: String, formAuthToken: String) {
        requireValidToken(cxt, formAuthToken)
        if (!contactAddress.contains("@")) throw KdrException.mkInput("An email address must contain an '@'.")
        sendVerifyEmail(cxt, contactAddress, computeVerifyCode(formAuthToken, contactAddress))
    }

    /** Emails a verification code to an existing user (by [username]) at their primary contact. */
    fun sendVerifyToUser(cxt: KdrCxt, username: String, formAuthToken: String) {
        requireValidToken(cxt, formAuthToken)
        val user = userService.queryByUsername(cxt, username)
            ?: throw KdrException("Username '$username' is not in the system.", code = EXC.notFound)
        sendVerifyEmail(cxt, user.primaryId, computeVerifyCode(formAuthToken, user.primaryId))
    }

    private fun sendVerifyEmail(cxt: KdrCxt, address: String, verifyCode: String) {
        val text = $$"Your verification code is ${verifyCode}. It expires in fifteen minutes."
            .evalTemplate(mapOf("verifyCode" to verifyCode))
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
        if (computeVerifyCode(formAuthToken, contactAddress) != verifyCode) {
            throw KdrException.mkInput("The verification code is incorrect.")
        }
        val existing = userService.queryByPrimaryId(cxt, contactAddress)
        if (existing != null && existing.enabled && (!existing.needsRealUsername || existing.encodedPassword != null)) {
            throw KdrException(
                "Email '$contactAddress' is not available for creating a new user.", code = EXC.badInput,
            )
        }
        val data = AuthUserRow.mkInitialUser(contactAddress, AC.public, ROLE.user).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val authUserData = data[AU.authUserData] as MutableMap<String, Any?>
        authUserData[AD.validatedContacts] = listOf(contactAddress)
        authUserData[AD.contacts] = listOf(mapOf("address" to contactAddress, "type" to "email"))

        return if (existing != null) {
            existing.username = AuthUserRow.usernameTmpPrefix + contactAddress
            existing.roles = listOf(ROLE.user)
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
        if (computeVerifyCode(formAuthToken, row.primaryId) != verifyCode) {
            throw KdrException.mkInput("The verification code is incorrect.")
        }
        if (username != null) {
            val other = userService.queryByUsername(cxt, username)
            if (other != null && other.userId != userId) {
                throw KdrException("Username '$username' has already been taken.", code = EXC.badInput)
            }
        }
        updateUsernameAndPassword(row, username, password)
        userService.updateUser(cxt, row)
        return completeLogin(cxt, row)
    }

    /** Logs a user in by verification code (username + code), the primary login path. */
    fun loginByCode(cxt: KdrCxt, username: String, formAuthToken: String, verifyCode: String): Map<String, Any?> {
        requireValidToken(cxt, formAuthToken)
        val row = userService.queryByUsername(cxt, username)
            ?: throw KdrException("Username '$username' could not be found.", code = EXC.notFound)
        if (computeVerifyCode(formAuthToken, row.primaryId) != verifyCode) {
            throw KdrException.mkInput("The verification code is incorrect.")
        }
        return completeLogin(cxt, row)
    }

    /** Binds the acting profile and flags the request for the cookie hook; returns the user-info payload. */
    private fun completeLogin(cxt: KdrCxt, row: AuthUserRow): Map<String, Any?> {
        if (!row.enabled) throw KdrException("The user account is not active.", code = EXC.badInput)
        val profile = row.toUserProfile()
        cxt.bindToUserProfile(profile)
        cxt.request?.setAuthCookie = true
        return profile.toUserInfo()
    }

    @Suppress("ConstPropertyName")
    private companion object {
        /** Separator between the creation timestamp and salt inside a form token's plaintext. */
        const val tokenSep = "|"
    }
}
