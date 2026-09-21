package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.KdrMsg
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.checkPassword
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.isEmailAddress
import com.dynamicruntime.common.util.mkRndString
import com.dynamicruntime.common.util.normalizeEmail

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
 * Every flow resolves a login id to the **identity** first (issue #748) -- the password, the device trust, and
 * the verification are the person's -- and then to the user the person acts as: the one a username names, or
 * the identity's default user for an address (`UserService.defaultUserOf`).
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
    /** Google sign-in (issue #157); null when the deployment configured no client id, disabling the feature. */
    private val googleVerifier: GoogleIdTokenVerifier? = null,
) {
    /** Whether Google sign-in is available on this deployment (drives the auth UI config's feature flag). */
    val googleLoginEnabled: Boolean get() = googleVerifier != null

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
        if (node.computeVerifyCode(formAuthToken, contactAddress) != verifyCode) {
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.codeIncorrect))
        }
        rateLimiter.reset(key)
    }

    /**
     * Refuses a code login for a [loginId] that has no account, **identically to a wrong code for one that
     * does** (issue #275). Returning a distinct "no account" error here made the endpoint an existence oracle;
     * this runs the same throttle-and-compare against [loginId] as a contact, so the missing-account case and
     * the wrong-code case have the same status, message, rate-limiting, and timing. It cannot succeed -- a
     * keyed code for a contact the caller does not control will not match -- but if it somehow did, there is
     * still no account, so it refuses regardless.
     */
    private fun refuseUnknownLogin(cxt: KdrCxt, loginId: String, formAuthToken: String, verifyCode: String): Nothing {
        verifyCodeOrThrow(cxt, loginId, formAuthToken, verifyCode)
        throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.codeIncorrect))
    }

    // --- sending verification codes -----------------------------------------

    /** Emails a verification code to a new email [contactAddress] (registration). */
    fun sendVerifyToContact(cxt: KdrCxt, contactAddress: String, formAuthToken: String) {
        requireValidToken(cxt, formAuthToken)
        // Normalized before anything reads it (issue #743): the code is computed from the address, and the row
        // is keyed by it, so the send and the registration that follows must agree on one spelling. The shape
        // check is the same validator the admin form runs: the emailed code proves the inbox is *reachable*,
        // not that the address is well-formed, and mailing a code to `ada@localhost` helps nobody.
        val address = contactAddress.normalizeEmail()
        if (!address.isEmailAddress()) throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.emailInvalid))
        requireSendAllowed(cxt, address)
        sendVerifyEmail(cxt, address, node.computeVerifyCode(formAuthToken, address), addPassword = false)
    }

    /**
     * Emails a verification code to an existing user (by [loginId] -- username or email) at their primary
     * contact. When [addPassword] is set, the email is framed for setting/changing a password -- kept
     * deliberately ambiguous about whether the user already has one.
     */
    fun sendVerifyToUser(cxt: KdrCxt, loginId: String, formAuthToken: String, addPassword: Boolean = false) {
        requireValidToken(cxt, formAuthToken)
        val user = userService.queryByLoginId(cxt, loginId)
        if (user == null) {
            // Answer exactly as the success path does, rather than a 404 that would confirm the address has no
            // account (issue #275). The old `noAccount` error made this a free membership oracle: ask for a
            // code, learn whether the address is registered. Run the same throttle so rate-limit behavior does
            // not distinguish the two either, then send nothing -- there is no mailbox to send to. The cost is
            // that a real user who mistypes their address gets no email; that is the accepted price of not
            // being an oracle, and is why the UI copy is "if that address has an account, a code is on its way".
            requireSendAllowed(cxt, loginId)
            LogAuth.info(cxt) { "sendVerify for a login id with no account; answering as success (issue #275)." }
            return
        }
        requireSendAllowed(cxt, user.primaryId)
        sendVerifyEmail(cxt, user.primaryId, node.computeVerifyCode(formAuthToken, user.primaryId), addPassword)
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
     *
     * An **`allClients` caller** may say where the new user goes (issue #751): [client], [persona] and
     * [personId] beside the address, the code arriving at that address as for anyone. That is how an
     * administrator provisions a truly new identity somewhere other than `public` -- a new identity is only
     * ever created by proving its address, so even they go through the code. Anyone else naming one of the
     * three is refused: a self-registration lands in `public` as a member.
     */
    fun createInitialUser(
        cxt: KdrCxt, contactAddress: String, formAuthToken: String, verifyCode: String,
        client: String? = null, persona: String? = null, personId: String = "",
    ): Long {
        val address = contactAddress.normalizeEmail()
        requireValidToken(cxt, formAuthToken)
        val placing = client != null || persona != null || personId.isNotEmpty()
        if (placing && !cxt.userProfile.roles.contains(ROLE.allClients)) {
            throw KdrException.mkInput("Only an administrator holding '${ROLE.allClients}' may choose the client, persona or personId of a new account.")
        }
        verifyCodeOrThrow(cxt, address, formAuthToken, verifyCode)
        val existing = userService.queryByPrimaryId(cxt, address)
        if (existing != null && existing.enabled && (!existing.needsRealUsername || existing.hasPassword)) {
            // FUTURE (with the other security hardening -- single-use verify tokens, logout invalidating the
            // auth cookie): rather than an error, *pretend success* here and email the existing account that
            // someone tried to register with their address. For now, it is a sensitive error -- obfuscated to a
            // generic message in prod so it does not confirm the email is taken, and the attempt is logged.
            LogAuth.info(cxt) { "Registration attempted for an already-registered email '$address'." }
            throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.emailNotAvailable), mapOf(AERR.emailParam to address),
                sensitive = true,
            )
        }
        // The roles a new user starts with: the persona's (the member's unless an allClients caller named one),
        // but an address matching the deployment's configured admin domain is provisioned as an admin -- how
        // the first admin comes to exist (AdminRules).
        val initialRoles = AdminRules.initialRoles(cxt, address, persona ?: PERSONA.member)
        val now = cxt.now()
        // The proof, and the contact it proves, are recorded on the identity (issues #747, #748); the user the
        // code was used for is the person's from the start -- registered (issue #749).
        if (placing) {
            // Placed by an allClients caller: the one provisioning path creates it under the named key, or
            // recovers a recoverably deleted user there, or refuses an enabled one as the duplicate it is.
            return userService.provisionUser(
                cxt, address, namedClientOrRefuse(cxt, client), initialRoles, createdAt = now,
                persona = persona, personId = personId, verifiedAt = now, registered = true,
            )
        }
        // A self-registration lands in the placeholder client (`public`) with the default persona; the
        // `+client%persona` tag that could once say otherwise is retired (issue #750).
        return if (existing != null) {
            // A lingering row (started-but-unfinished, or recoverably deleted) is re-provisioned in place; its
            // identity is marked verified now, as a fresh one would be.
            userService.getOrCreateIdentity(cxt, address, verifiedAt = now)
            existing.username = AuthUserRow.usernameTmpPrefix + address
            existing.roles = initialRoles
            existing.authUserData = mutableMapOf()
            existing.enabled = true
            existing.registeredAt = now
            userService.updateUser(cxt, existing)
            existing.userId
        } else {
            userService.provisionUser(
                cxt, address, AddressRules.defaultClient(cxt), initialRoles,
                createdAt = now, verifiedAt = now, registered = true,
            )
        }
    }

    // --- login --------------------------------------------------------------

    /**
     * Sets the user's [username] (and, if provided, opt-in [password]) after verifying the code, then logs in.
     * Passwords are optional -- omit it and the user logs in by code only.
     */
    fun setLoginData(
        cxt: KdrCxt, userId: Long, username: String?, password: String?,
        isEntity: Boolean?, name: String?, formAuthToken: String, verifyCode: String,
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
        val identity = userService.identityOfUser(cxt, row)
        updateUsernameAndPassword(row, identity, username, password)
        // Each is only touched when the caller says so, so a registration that omits them leaves the defaults.
        // They are independent: `name` is a person's full name just as readily as a business's, so it is not
        // conditioned on the flag. Neither is required nor checked for uniqueness -- the name is display copy,
        // and the account is still keyed by its primaryId and username.
        if (isEntity != null) row.isEntity = isEntity
        if (name != null) row.name = name // the row normalizes: trimmed, blank means none
        userService.updateUser(cxt, row)
        // The password is the identity's (issue #748), so it is the identity that is written when one was set.
        if (password != null) userService.updateIdentity(cxt, identity)
        return completeLogin(cxt, row, byCode = true)
    }

    /** Logs a user in by verification code ([loginId] -- username or email -- plus code), the primary path. */
    fun loginByCode(cxt: KdrCxt, loginId: String, formAuthToken: String, verifyCode: String): Map<String, Any?> {
        requireValidToken(cxt, formAuthToken)
        val row = userService.queryByLoginId(cxt, loginId)
            ?: refuseUnknownLogin(cxt, loginId, formAuthToken, verifyCode)
        verifyCodeOrThrow(cxt, row.primaryId, formAuthToken, verifyCode)
        return completeLogin(cxt, row, byCode = true)
    }

    /**
     * Logs a user in by [password] ([loginId] is a username or email) -- permitted **only from a familiar
     * device** (issue #69). Every failure (unknown user, unverified device, no password, or a wrong password)
     * returns the *same* opaque message; the real reason is only logged, so the caller cannot tell whether the
     * password was wrong or the device was unfamiliar. Attempts are throttled per **identity** and per source
     * IP before any password work: the password and the device trust are the person's (issue #748), so the
     * guesses against them are counted for the person too, whichever of their users the login id names -- and
     * against the login id itself when it names nobody, so an unknown account is throttled all the same.
     */
    fun loginByPassword(cxt: KdrCxt, loginId: String, password: String): Map<String, Any?> {
        val nowMs = cxt.now().toEpochMilliseconds()
        val ip = cxt.forwardedFor ?: unknownIp
        // Resolved before the throttle only to key it; a cache hit, and nothing below runs until it passes.
        val row = userService.queryByLoginId(cxt, loginId)
        val identity = row?.let { userService.identityOfUser(cxt, it) }
        val attemptKey = "pw:" + (identity?.identityId ?: loginId)
        if (!rateLimiter.allow(attemptKey, RL.pwPerUserMax, RL.pwWindowMs, nowMs) ||
            !rateLimiter.allow("pwip:$ip", RL.pwPerIpMax, RL.pwWindowMs, nowMs)
        ) {
            throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.tooManyLoginAttempts),
                code = EXC.tooManyRequests,
            )
        }

        val deviceGuid = cxt.request?.webRequest?.getRequestCookies()?.get(AUTHC.deviceCookie)
        val stored = identity?.encodedPassword
        val failReason: String? = when {
            row == null || identity == null -> "unknown account"
            deviceGuid == null -> "no device cookie"
            !userService.isDeviceTrusted(cxt, identity.identityId, deviceGuid) -> "unverified device"
            stored == null -> "no password set"
            !password.checkPassword(stored) -> "incorrect password"
            else -> null
        }
        if (failReason != null) {
            LogAuth.info(cxt) { "Password login failed for '$loginId': $failReason." }
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.loginFailed), code = EXC.authNeeded)
        }
        rateLimiter.reset(attemptKey)
        return completeLogin(cxt, row!!, byCode = false)
    }

    // --- Google sign-in (issue #157) ----------------------------------------

    /**
     * Logs a user in from a Google ID token, linking the Google identity to a local identity on first use.
     *
     * The Google identity is its `sub`, held in `LinkedUsers` against our `identityId` (issue #748). Once that
     * link exists, it is the *only* thing consulted, so a later change to the account's Google email -- or
     * that email being reassigned to someone else, which a Workspace domain can do -- cannot re-point the link
     * or hand the account to a stranger. The person then acts as the identity's default user, like any login.
     *
     * On a **first** link there is nothing but the email to match on, and that is the one moment this path can
     * reach an existing local identity. Hence [GoogleIdToken.emailVerified] is a hard precondition here rather
     * than a detail: an unverified Google address is one the signer never proved they control, so honoring it
     * would let anyone who sets their Google email to a victim's address inherit that account. Unverified is
     * refused outright -- it does not fall through to creating a new user, which would silently squat the
     * address instead. A verified one counts as proof of the address: the identity is marked verified by it,
     * as a code read from the inbox would.
     *
     * The device is deliberately **not** marked familiar ([completeLogin] with `byCode = false`). Device trust
     * is what later permits a password login, and the invariant in this class's own contract is that every
     * trust decision traces back to proving control of the contact via a code we sent. A third party's
     * assertion, however well verified, is not that.
     */
    fun loginByGoogle(cxt: KdrCxt, credential: String): Map<String, Any?> {
        val verifier = googleVerifier
            ?: throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.googleNotConfigured), code = EXC.notFound)
        // Google throttles its own side, but this endpoint still accepts an attacker-supplied blob and does
        // RSA work on it, so it gets the same per-IP ceiling the password path has.
        val ip = cxt.forwardedFor ?: unknownIp
        if (!rateLimiter.allow("goog:$ip", RL.pwPerIpMax, RL.pwWindowMs, cxt.now().toEpochMilliseconds())) {
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.tooManyLoginAttempts), code = EXC.tooManyRequests)
        }
        val token = verifier.verify(cxt, credential)

        // An identity we have seen before: the link is the answer, and the email is not consulted at all.
        userService.queryLinkedIdentity(cxt, LSRC.google, token.subject)?.let { linked ->
            return completeLogin(cxt, googleUserOf(cxt, linked), byCode = false)
        }

        // First sight of this Google account -- the only path that may touch an existing local user.
        if (!token.emailVerified) {
            LogAuth.info(cxt) { "Google sign-in refused: Google has not verified the email on subject '${token.subject}'." }
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.googleEmailUnverified))
        }
        val email = token.email?.normalizeEmail()
        if (email == null) {
            LogAuth.info(cxt) { "Google sign-in refused: the token for subject '${token.subject}' carries no email." }
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.googleEmailUnverified))
        }

        // The existing identity at the address, marked verified by Google's word for it, or a new one.
        val identity = userService.getOrCreateIdentity(cxt, email, verifiedAt = cxt.now())
        val row = googleUserOf(cxt, identity)
        userService.insertLinkedIdentity(
            cxt, LSRC.google, token.subject, identity.identityId,
            // Captured for support and for showing the user what is linked -- never read back as an authority
            // on identity, which is the `sub` in the key.
            mapOf(GOOG.email to email, GOOG.name to token.displayName),
        )
        LogAuth.info(cxt) { "Linked Google subject '${token.subject}' to identity '${identity.identityId}' ('${identity.primaryId}')." }
        return completeLogin(cxt, row, byCode = false)
    }

    /**
     * The user a Google sign-in acts as (issue #749). A **registered** user of [identity] exists: the standard
     * default among the registered ones, and the sign-in registers nothing. None: Google can reach exactly one
     * user, the one the rules name -- the default client (`AddressRules.defaultClient`, `public` until the
     * request's host can say otherwise), the persona the initial roles imply (`member`, or `admin` on the
     * auto-admin domain), no personId -- which the person is
     * claiming by signing in (`UserService.claimUser`): an existing one under that key is registered, a
     * disabled one re-enabled as it was and registered, and otherwise a registered user is created with the
     * initial roles, so the auto-admin domain reaches a Google-provisioned operator as it does a registration.
     * So a non-admin cannot validate further users through Google; those take a verification code.
     *
     * Still to come (Sam, 2026-09-18): the client the rules choose will say whether a person with **no
     * provisioned user** there may sign in at all -- historically clients have not allowed it, and `public`
     * will -- so the create at the end becomes a per-client decision.
     */
    private fun googleUserOf(cxt: KdrCxt, identity: AuthIdentityRow): AuthUserRow {
        userService.registeredDefaultOf(cxt, identity)?.let { return it }
        val address = identity.primaryId
        val roles = AdminRules.initialRoles(cxt, address)
        return userService.claimUser(
            cxt, identity, AddressRules.defaultClient(cxt), PERSONA.defaultFor(roles), personId = "",
            roles = roles,
        )
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
            ?: refuseUnknownLogin(cxt, loginId, formAuthToken, verifyCode)
        verifyCodeOrThrow(cxt, row.primaryId, formAuthToken, verifyCode)
        val identity = userService.identityOfUser(cxt, row)
        setPassword(identity, row, password)
        userService.updateIdentity(cxt, identity)
        return completeLogin(cxt, row, byCode = true)
    }

    /**
     * Sets the caller's own [name] -- what they are shown as -- and returns their refreshed info. A blank name
     * clears it, which falls the display back to [AuthUserRow.publicName].
     *
     * Takes the **session** as its authority, with no verification code, deliberately: a display name is not a
     * credential, and requiring an emailed code to correct your own name would be the wrong bar (the password
     * calls next door need one because they change how you sign in). It also does not touch `username`, which
     * *is* a login identifier and is unique -- editing that is a separate question (issue #323).
     *
     * No uniqueness check, because there is none to make: a name is non-unique by design, so two people may
     * share one exactly as they do in life.
     */
    fun setOwnName(cxt: KdrCxt, name: String?): Map<String, Any?> {
        val row = userService.queryByUserId(cxt, cxt.userProfile.userId)
            ?: throw KdrException("The current user could not be found.", code = EXC.notFound)
        row.name = name // the row normalizes: trimmed, and a blank name clears it
        userService.updateUser(cxt, row)
        // Rebind, so the rest of this request -- and the info returned -- speak of the new name rather than the
        // one the session was carrying.
        val live = row.toUserProfile()
        cxt.bindToUserProfile(live)
        return live.toUserInfo()
    }

    /**
     * Removes the currently-logged-in person's password (opt back out of password login; code login still
     * works). Reached from the profile page, so it relies on the authenticated session rather than a code.
     * The password is the identity's (issue #748), so it goes for every user the person holds.
     */
    fun removePassword(cxt: KdrCxt): Map<String, Any?> {
        val row = userService.queryByUserId(cxt, cxt.userProfile.userId)
            ?: throw KdrException("The current user could not be found.", code = EXC.notFound)
        val identity = userService.identityOfUser(cxt, row)
        clearPassword(identity)
        userService.updateIdentity(cxt, identity)
        // The row's password status was derived when it was read, before the write; say what is true now.
        return row.toUserProfile().copy(hasPassword = false).toUserInfo()
    }

    /**
     * Test-only (issue #125): logs [cxt] in as the user whose primary contact is [email], **creating** that
     * user first if none exists. On a create, [level] places the new user on the privilege ladder (see
     * [RoleLadder.rolesAtLevel], which is also what the admin console composes with) and [capabilities] adds
     * roles that are *not* rungs -- `allClients` and the like, which no level can express. On an existing
     * user both are ignored: you become whoever is already there, roles and all. When
     * [failIfUserAlreadyExists] is set, an existing user is an error instead of a login.
     *
     * No verification code or password is involved -- it exists purely to seed an authenticated session for
     * tests and local simulations, which is why it is reachable only through a `forTestingOnly` endpoint. Like
     * a real login it flags the session cookie to be written (via [completeLogin]) and returns the user info.
     */
    fun becomeUserByEmail(
        cxt: KdrCxt, email: String, level: String, capabilities: List<String>, failIfUserAlreadyExists: Boolean,
        client: String? = null, name: String? = null, persona: String? = null, personId: String = "",
    ): Map<String, Any?> {
        val address = email.normalizeEmail()
        // A username as the login id resolves directly; an address resolves to its identity's users, and the
        // one to become is the match on (client, persona, personId) when the caller named any of them, else
        // the identity's default user (issue #747) -- so a test can put several users under one address. An
        // unnamed persona is the one the level implies on a create (`PERSONA.defaultFor`), and any on a find.
        val named = client != null || persona != null || personId.isNotEmpty()
        val existing = if (address.contains('@')) {
            val identity = userService.queryIdentityByAddress(cxt, address)
            val users = identity?.let { userService.usersOfIdentity(cxt, it.identityId) }.orEmpty()
            if (named) {
                users.firstOrNull { (client == null || it.client == client) && (persona == null || it.persona == persona) && it.personId == personId }
            } else {
                identity?.let { userService.defaultUserOf(cxt, it) }
            }
        } else {
            userService.queryByLoginId(cxt, address)
        }
        if (existing != null) {
            if (failIfUserAlreadyExists) {
                throw KdrException("A user with email '$address' already exists.", code = EXC.badInput)
            }
            // The fixture is proof by fiat (issue #749): becoming a user nobody has claimed registers it.
            return completeLogin(cxt, existing, byCode = false, register = true)
        }
        val roles = RoleLadder.rolesAtLevel(emptyList(), level) + capabilities.filter { it.isNotBlank() }
        val userId = userService.provisionUser(
            cxt, address, namedClientOrRefuse(cxt, client), roles,
            createdAt = cxt.now(), persona = persona, personId = personId, verifiedAt = cxt.now(), registered = true,
        ) { authUserData ->
            // The person's real-world name (issue #736), set the same way the admin-create path does -- display
            // copy, independent of the username, so a fixture can exercise a name-driven feature (a prefill) that
            // `publicName` (which falls back to the email) cannot show. Ignored when the user already exists, like
            // the other create-time fields above.
            AuthUserRow.normalizeName(name)?.let { authUserData[AD.name] = it }
        }
        val row = userService.queryByUserId(cxt, userId)
            ?: throw KdrException("Could not load the just-created user '$address'.", code = EXC.internalError)
        return completeLogin(cxt, row, byCode = false)
    }

    /**
     * The client a caller named for a new user -- the fixture's, or an allClients registration's (issue #751)
     * -- or the default client a registration lands in when [named] is null.
     *
     * An explicit client this node does not carry is **refused**, where a plain registration lands in `public`.
     * The difference is who is on the other end. A test or an administrator asking for a client that is not
     * present has made a mistake, and silently getting `public` is how it goes unnoticed until an assertion
     * three files away fails for a reason that has nothing to do with what it was checking.
     */
    private fun namedClientOrRefuse(cxt: KdrCxt, named: String?): String {
        val client = named ?: return AddressRules.defaultClient(cxt)
        val clients = ClientService.get(cxt)
        if (!clients.isPresent(client)) {
            val why = if (clients.known(client) != null) {
                "it is declared but not enabled in '${cxt.instanceConfig.env}'"
            } else {
                "no config declares it"
            }
            throw KdrException(
                "Cannot create a user in the client '$client': $why. The clients present here are " +
                    "${clients.presentClients.joinToString(", ") { it.clientId }}.",
                code = EXC.badInput,
            )
        }
        return client
    }

    // --- invitations (issue #751) ---------------------------------------------

    /**
     * Mails [user]'s person an invitation to claim it: a link carrying an [InvitationToken] for the user, good
     * for [AUTHC.invitationMillis]. What an administrator's create sends for a user at somebody else's address
     * -- a new identity, or a further user for an existing one -- and what `admin/user/invite` re-sends. The
     * link is the proof: opening it and accepting registers the user (and verifies the identity, if nothing
     * had), with no code to type, since the token reached only the inbox. Returns the token, for a test.
     */
    fun inviteUser(cxt: KdrCxt, user: AuthUserRow): String {
        if (user.isRegistered) throw KdrException.mkInput("User ${user.userId} has already been claimed; there is nothing to invite them to.")
        if (!user.enabled) throw KdrException.mkInput("User ${user.userId} is disabled; enable the account before inviting.")
        val token = InvitationToken(user.identityId, user.userId, cxt.now().toEpochMilliseconds() + AUTHC.invitationMillis).encode(node)
        val url = INVITE.invitationUrl(INVITE.publicUrlFor(cxt), token)
        val what = "'${user.client}' as ${PERSONA.label(user.persona)}" + (if (user.personId.isEmpty()) "" else " ${user.personId}")
        val template = $$"An account has been created for you in ${what}. Open this link to accept it and sign in: " +
            $$"${url}\n\nThe link expires in seven days. If you were not expecting this, ignore it."
        val text = template.evalTemplate(mapOf("what" to what, "url" to url))
        mail.sendEmail(cxt, to = user.primaryId, subject = "You have been invited", text = text)
        LogAuth.info(cxt) { "Invited user ${user.userId} ('${user.primaryId}') to '${user.client}' as '${user.persona}'." }
        return token
    }

    /**
     * What an invitation [token] is for, before it is accepted: the address, client, persona, personId and
     * name of the invited user. So the page can say what accepting means, and so that merely *opening* the
     * link -- which a mail scanner does -- accepts nothing.
     */
    fun previewInvitation(cxt: KdrCxt, token: String): Map<String, Any?> {
        val user = invitedUser(cxt, token)
        return buildMap {
            put(AFLD.email, user.primaryId)
            put(AFLD.client, user.client)
            put(AFLD.persona, user.persona)
            put(AFLD.personId, user.personId)
            user.name?.let { put(AFLD.name, it) }
        }
    }

    /**
     * Accepts an invitation: the user it names is registered -- and its identity verified, if nothing had yet
     * -- and the session becomes that user, with the device made familiar, exactly as a code login does. The
     * token reached only the inbox, so it is the same proof a code is. A second acceptance is refused: a
     * registered user is logged into by the ordinary means, and a mailed link must not be a standing login.
     */
    fun acceptInvitation(cxt: KdrCxt, token: String): Map<String, Any?> {
        val user = invitedUser(cxt, token)
        return completeLogin(cxt, user, byCode = true)
    }

    /**
     * The user a [token] invites the reader to claim, or a refusal: one message for a malformed, tampered or
     * expired token and for a user that no longer fits it (gone, deleted, or now under another identity), and
     * another for one already claimed.
     */
    private fun invitedUser(cxt: KdrCxt, token: String): AuthUserRow {
        val decoded = InvitationToken.decode(node, token)
        val user = decoded?.let { userService.queryByUserId(cxt, it.userId) }
        if (decoded == null || user == null || user.isDeleted || user.identityId != decoded.identityId ||
            cxt.now().toEpochMilliseconds() > decoded.expireEpochMs
        ) {
            throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.invitationInvalid))
        }
        if (user.isRegistered) throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.invitationUsed))
        return user
    }

    // --- the switcher (issue #749) --------------------------------------------

    /** The users the caller may switch to, as the endpoint lists them. */
    fun selfUsers(cxt: KdrCxt): Map<String, Any?> = mapOf(AFLD.users to userService.selfUserChoices(cxt).map { it.toInfo() })

    /**
     * Becomes another of the person's users: a fresh session as [userId], which must be a registered, enabled
     * user of the **session's own identity** -- strictly same-identity; impersonation is a later, separately
     * gated capability. Completes as a login does (the cookie reissued, the timeout reset, `lastUsedUserId`
     * stamped), without touching device trust: a switch proves nothing new about the person.
     */
    fun switchUser(cxt: KdrCxt, userId: Long): Map<String, Any?> = completeLogin(cxt, ownUser(cxt, userId), byCode = false)

    /** Chooses which of the person's users an address logs in as ([UserService.defaultUserOf]'s first rule); returns the list. */
    fun setDefaultUser(cxt: KdrCxt, userId: Long): Map<String, Any?> {
        val target = ownUser(cxt, userId)
        val identity = userService.identityOfUser(cxt, target)
        if (identity.defaultUserId != target.userId) {
            identity.defaultUserId = target.userId
            userService.updateIdentity(cxt, identity)
        }
        return selfUsers(cxt)
    }

    /**
     * The user [userId] as one the caller may act as, or a refusal. One message for every way it is not --
     * unknown, another person's, disabled, or not yet claimed -- so the endpoint confirms nothing about users
     * that are not the caller's. A session issued before the split carries no identity and is asked to log in
     * again, since nothing then says whose users are whose.
     */
    private fun ownUser(cxt: KdrCxt, userId: Long): AuthUserRow {
        val identityId = cxt.userProfile.identityId
            ?: throw KdrException("This session predates identities; log in again to switch users.", code = EXC.authNeeded)
        val target = userService.queryByUserId(cxt, userId)
        if (target == null || target.identityId != identityId || !target.enabled || !target.isRegistered) {
            throw KdrException.mkInput("User $userId is not one of your users.")
        }
        return target
    }

    /**
     * Binds the acting profile and flags the request for the cookie hook; returns the user-info payload. A
     * [byCode] login additionally flags the device to be marked familiar (see KdrRequest.trustDevice), and
     * -- having just read a code from the inbox -- proves the identity's address if nothing had yet, and the
     * user it landed on ([register], which the fixture also asserts): a code used for this user is what makes
     * it the person's (issue #749).
     */
    private fun completeLogin(cxt: KdrCxt, row: AuthUserRow, byCode: Boolean, register: Boolean = byCode): Map<String, Any?> {
        if (!row.enabled) throw KdrException("The user account is not active.", code = EXC.badInput)
        if (register && !row.isRegistered) row.registeredAt = cxt.now()
        // The identity records the proof and which user the person last acted as (the fallback default,
        // issue #747); written only when either moved, so the ordinary repeat login costs no identity write.
        val identity = userService.identityOfUser(cxt, row)
        val proven = byCode && identity.markVerified(cxt.now())
        val moved = identity.lastUsedUserId != row.userId
        if (proven || moved) {
            identity.lastUsedUserId = row.userId
            userService.updateIdentity(cxt, identity)
        }
        // The auto-admin rule is not re-applied here (issue #352). It used to be, so that configuring the
        // admin domain afterward reached an operator who had already registered -- but a grant that
        // re-asserts itself on every login is a permanent property of an address rather than a statement
        // about how an account was created, and it only ever grants, so a role an administrator deliberately
        // removed came back at the next login. An address now decides what a user is provisioned as, and from
        // then on their roles are whatever an administrator has made them.
        // The one place a login *completes* -- byCode, byPassword and Google all funnel through here -- which
        // is why the stamp goes here and not at each of them (issue #462). It is also why "a refreshed cookie
        // does not count" needs no enforcing: `extractSessionAuth` restores a profile from a cookie and never
        // reaches this method. `isEdit = false`, because signing in is not an edit of the account.
        row.lastLoggedInAt = cxt.now()
        UserService.get(cxt).updateUser(cxt, row, isEdit = false)

        // The password status comes from the identity in hand rather than the row: a row read before the
        // password was set (`changePassword`, `setLoginData`) would otherwise report the person as without one.
        val profile = row.toUserProfile().copy(hasPassword = identity.hasPassword)
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
