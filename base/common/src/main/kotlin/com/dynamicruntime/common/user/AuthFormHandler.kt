package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.KdrMsg
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.PlanLimits
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.util.checkPassword
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
     * so the short code cannot be cheaply brute-forced; a correct code clears the counter.
     *
     * The counter is [attemptKey]'s: **what the caller typed** -- the login id, the user id -- whether or not it names an
     * account, and the same key a refusal for an unknown one counts against (issue #1299). Keyed by the account's
     * address for a known account and by the typed id for an unknown one, exhausting the counter by address and then
     * asking by username would say whether the two are one account.
     */
    private fun verifyCodeOrThrow(
        cxt: KdrCxt,
        contactAddress: String,
        formAuthToken: String,
        verifyCode: String,
        attemptKey: String = contactAddress,
    ) {
        val key = "vc:$attemptKey"
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
        // No user yet, so no client the mail is about: the caller's own, which for a registration is none.
        sendVerifyEmail(cxt, address, node.computeVerifyCode(formAuthToken, address), addPassword = false, client = cxt.client)
    }

    /**
     * Emails a verification code to an existing user (by [loginId] -- username or email) at their primary
     * contact. When [addPassword] is set, the email is framed for setting/changing a password -- kept
     * deliberately ambiguous about whether the user already has one.
     */
    fun sendVerifyToUser(cxt: KdrCxt, loginId: String, formAuthToken: String, addPassword: Boolean = false) {
        requireValidToken(cxt, formAuthToken)
        // Refused by the login id as typed, before it is looked up (issue #1150): a refusal for known users alone
        // would say which ids have accounts. An admin-domain user exempted there signs in by their address.
        PublicTestDeployment.requireSignInOpen(cxt, loginId)
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
        sendVerifyEmail(cxt, user.primaryId, node.computeVerifyCode(formAuthToken, user.primaryId), addPassword, client = user.client)
    }

    /**
     * Throttles verification emails per source IP and per targeted contact, to blunt flooding -- the bound a public
     * test deployment mailing the public for real relies on (issue #1150), which also refuses its sign-in flows to a
     * caller without env auth first ([PublicTestDeployment.requireSignInOpen]).
     */
    private fun requireSendAllowed(cxt: KdrCxt, contactAddress: String) {
        PublicTestDeployment.requireSignInOpen(cxt, contactAddress)
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

    /**
     * Throttles the mail an administrator sends to [address] (issue #1504), an invitation or a reminder, as
     * [requireSendAllowed] does a verification mail's: per address, so one person cannot be flooded. Not per sender, since
     * an administrator inviting a whole team mails many addresses in a sitting.
     */
    private fun requireAdminMailAllowed(cxt: KdrCxt, address: String) {
        if (!rateLimiter.allow("admail:$address", RL.sendPerContactMax, RL.sendPerContactWindowMs, cxt.now().toEpochMilliseconds())) {
            throw KdrException("Too many mails to $address just now; try again later.", code = EXC.tooManyRequests)
        }
    }

    /** The code mail, as [client] words it (issue #773); the password framing is its own copy. */
    private fun sendVerifyEmail(cxt: KdrCxt, address: String, verifyCode: String, addPassword: Boolean, client: String?) {
        val which = if (addPassword) MCOPY.verifyCodePassword else MCOPY.verifyCode
        // In the background (issue #1299): no answer waits on the provider, nor fails only for a real account.
        mail.sendFragmentMail(cxt, address, client, which, mapOf(MCOPY.codeParam to verifyCode), inBackground = true)
    }

    // --- user creation ------------------------------------------------------

    /**
     * Provisions the initial user row for a freshly verified email [contactAddress], returning its `userId`.
     * A fresh email inserts a new row; a lingering placeholder (started-but-unfinished) row is re-provisioned.
     * An active, real user already at that email cannot be recreated.
     *
     * An **`allClients` caller** may say where the new user goes (issue #751): [client], [persona] and
     * [personaSuffix] beside the address, the code arriving at that address as for anyone. That is how an
     * administrator provisions a truly new identity somewhere other than `public` -- a new identity is only
     * ever created by proving its address, so even they go through the code. Anyone else naming one of the
     * three is refused: a self-registration lands in `public` as a member.
     */
    fun createInitialUser(
        cxt: KdrCxt, contactAddress: String, formAuthToken: String, verifyCode: String,
        client: String? = null, persona: String? = null, personaSuffix: String = "",
    ): Long {
        val address = contactAddress.normalizeEmail()
        requireValidToken(cxt, formAuthToken)
        // A registration is a sign-in flow, refused to a caller a public test deployment does not admit (issue #1150).
        PublicTestDeployment.requireSignInOpen(cxt, address)
        val placing = client != null || persona != null || personaSuffix.isNotEmpty()
        // The capability as it is now, not as the session cookie recorded it: `auth` is not a gated section, so
        // nothing has refreshed the roles, and a cookie outlives a revocation by up to its whole life.
        if (placing) refreshActingRoles(cxt)
        if (placing && !cxt.userProfile.roles.contains(ROLE.allClients)) {
            throw KdrException.mkInput("Only an administrator holding '${ROLE.allClients}' may choose the client, persona or personaSuffix of a new account.")
        }
        verifyCodeOrThrow(cxt, address, formAuthToken, verifyCode)
        val existing = userService.queryByPrimaryId(cxt, address)
        if (existing != null && existing.enabled && (!existing.needsRealUsername || existing.hasPassword)) {
            // A privileged caller placing a user is told plainly (there is no oracle to protect against them): a
            // further user for a person who already has one is the Users page's create, which invites them.
            if (placing) {
                throw KdrException.mkInput(
                    "'$address' already has an account. To give that person a further user, create it from the Users " +
                        "page, which sends them an invitation; registration is for an address that has none.",
                )
            }
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
            val placedIn = namedClientOrRefuse(cxt, client, initialRoles)
            return userService.provisionUser(
                cxt, address, placedIn, AdminRules.rolesInClient(placedIn, initialRoles), createdAt = now,
                persona = persona ?: PERSONA.defaultFor(initialRoles), personaSuffix = personaSuffix,
                verifiedAt = now, registered = true,
            )
        }
        // A self-registration lands in the default client for its roles -- `hub` for an allClients user (the
        // auto-admin domain's first admin, issue #799), else the placeholder `public` -- with the default persona;
        // the `+client%persona` tag that could once say otherwise is retired (issue #750).
        // In `public` every user administers their own users (issue #805), so the role is added once the client
        // is known -- after, so it never moves a registrant out of `public`. The persona stays the one asked for:
        // `member`, since in `public` the admin role marks no different kind of user.
        return if (existing != null) {
            // A lingering row (started-but-unfinished, or recoverably deleted) is re-provisioned in place; its
            // identity is marked verified now, as a fresh one would be.
            // A recoverably deleted row coming back counts against its client's plan as a new user does (issue #1109).
            if (!existing.enabled) PlanLimits.requireRoomForUser(cxt, existing.client)
            userService.getOrCreateIdentity(cxt, address, verifiedAt = now)
            existing.username = AuthUserRow.placeholderUsername(address, existing.client, existing.persona, existing.personaSuffix)
            existing.roles = AdminRules.rolesInClient(existing.client, initialRoles)
            existing.authUserData = mutableMapOf()
            existing.enabled = true
            existing.registeredAt = now
            userService.updateUser(cxt, existing)
            existing.userId
        } else {
            val landsIn = AddressRules.defaultClient(cxt, initialRoles)
            userService.provisionUser(
                cxt, address, landsIn, AdminRules.rolesInClient(landsIn, initialRoles),
                createdAt = now, verifiedAt = now, registered = true, persona = PERSONA.defaultFor(initialRoles),
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
        // An id with no user is refused as a wrong code for one that has a user (issue #1293): a form token is all
        // an anonymous caller needs to ask, so a distinct "not found" would let anybody count the user ids there are.
        val row = userService.queryByUserId(cxt, userId)
            ?: refuseUnknownLogin(cxt, userId.toString(), formAuthToken, verifyCode)
        verifyCodeOrThrow(cxt, row.primaryId, formAuthToken, verifyCode, attemptKey = userId.toString())
        if (username != null) {
            // Whether it is a username at all, before whether it is taken (issue #1298): a placeholder holds an address
            // and a client, and asked first, "taken" would tell anybody signed in whether that address has a user there.
            checkValidUsername(username)
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
        // Refused by the login id as typed, before it is looked up (issue #1150): a refusal for known users alone
        // would say which ids have accounts. An admin-domain user exempted there signs in by their address.
        PublicTestDeployment.requireSignInOpen(cxt, loginId)
        val row = userService.queryByLoginId(cxt, loginId)
            ?: refuseUnknownLogin(cxt, loginId, formAuthToken, verifyCode)
        verifyCodeOrThrow(cxt, row.primaryId, formAuthToken, verifyCode, attemptKey = loginId)
        return completeLogin(cxt, row, byCode = true)
    }

    /**
     * Logs a user in by [password] ([loginId] is a username or email) -- permitted **only from a familiar
     * device** (issue #69). Every failure (unknown user, unverified device, no password, or a wrong password)
     * returns the *same* opaque message; the real reason is only logged, so the caller cannot tell whether the
     * password was wrong or the device was unfamiliar. Attempts are throttled per **login id as typed** and per
     * source IP before any password work, known account or not (issue #1299): counted per identity for a known one,
     * as they once were, exhausting the counter by one login id and then trying another of the person's would say
     * the two are one person. The per-IP limit still bounds guesses spread across a person's login ids.
     */
    fun loginByPassword(cxt: KdrCxt, loginId: String, password: String): Map<String, Any?> {
        // Refused by the login id as typed, before it is looked up (issue #1150): a refusal for known users alone
        // would say which ids have accounts. An admin-domain user exempted there signs in by their address.
        PublicTestDeployment.requireSignInOpen(cxt, loginId)
        val nowMs = cxt.now().toEpochMilliseconds()
        val ip = cxt.forwardedFor ?: unknownIp
        val attemptKey = "pw:$loginId"
        if (!rateLimiter.allow(attemptKey, RL.pwPerUserMax, RL.pwWindowMs, nowMs) ||
            !rateLimiter.allow("pwip:$ip", RL.pwPerIpMax, RL.pwWindowMs, nowMs)
        ) {
            throw KdrException.mkMsg(
                KdrMsg(AFRAG.auth, AERR.ns, AERR.tooManyLoginAttempts),
                code = EXC.tooManyRequests,
            )
        }
        // Looked up only once the throttle passed, so a refused attempt does no lookup to time.
        val row = userService.queryByLoginId(cxt, loginId)
        val identity = row?.let { userService.identityOfUser(cxt, it) }

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
     * user, the one the rules name -- the default client for the initial roles (`AddressRules.defaultClient`:
     * `hub` for an allClients user, else `public`, until the request's host can say otherwise), the persona the
     * initial roles imply (`member`, or `admin` on the
     * auto-admin domain), no personaSuffix -- which the person is
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
        val client = AddressRules.defaultClient(cxt, roles)
        // The persona from the roles as the rules chose them; `public`'s admin role (issue #805) is added after,
        // and marks no different kind of user, so it does not make the person an `admin` persona.
        return userService.claimUser(
            cxt, identity, client, PERSONA.defaultFor(roles), personaSuffix = "",
            roles = AdminRules.rolesInClient(client, roles),
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
        // Refused by the login id as typed, before it is looked up (issue #1150): a refusal for known users alone
        // would say which ids have accounts. An admin-domain user exempted there signs in by their address.
        PublicTestDeployment.requireSignInOpen(cxt, loginId)
        val row = userService.queryByLoginId(cxt, loginId)
            ?: refuseUnknownLogin(cxt, loginId, formAuthToken, verifyCode)
        verifyCodeOrThrow(cxt, row.primaryId, formAuthToken, verifyCode, attemptKey = loginId)
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
        val live = row.toActingProfile(cxt)
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
        return row.toActingProfile(cxt).copy(hasPassword = false).toUserInfo()
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
        client: String? = null, name: String? = null, persona: String? = null, personaSuffix: String = "",
    ): Map<String, Any?> {
        val found = findOrProvisionUser(
            cxt, email, level, capabilities, failIfUserAlreadyExists, client, name, persona, personaSuffix,
        )
        // The fixture is proof by fiat (issue #749): becoming a user nobody has claimed registers it.
        return if (found.existed) {
            completeLogin(cxt, found.row, byCode = false, register = true)
        } else {
            completeLogin(cxt, found.row, byCode = false)
        }
    }

    /**
     * The user [becomeUserByEmail] would become -- found by the same rules, or created with the same fields -- without
     * logging in as them (issue #997): how a simulation provisions the users it then reports, for a person to sign in
     * as later. [ProvisionedUser.existed] says which it was.
     */
    fun findOrProvisionUser(
        cxt: KdrCxt, email: String, level: String, capabilities: List<String>, failIfUserAlreadyExists: Boolean,
        client: String? = null, name: String? = null, persona: String? = null, personaSuffix: String = "",
    ): ProvisionedUser {
        val address = email.normalizeEmail()
        // A username as the login id resolves directly; an address resolves to its identity's users, and the
        // one to become is the match on (client, persona, personaSuffix) when the caller named any of them, else
        // the identity's default user (issue #747) -- so a test can put several users under one address. An
        // unnamed persona is the one the level implies on a create (`PERSONA.defaultFor`), and any on a find.
        val named = client != null || persona != null || personaSuffix.isNotEmpty()
        val existing = if (address.contains('@')) {
            val identity = userService.queryIdentityByAddress(cxt, address)
            val users = identity?.let { userService.usersOfIdentity(cxt, it.identityId) }.orEmpty()
            if (named) {
                users.firstOrNull { (client == null || it.client == client) && (persona == null || it.persona == persona) && it.personaSuffix == personaSuffix }
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
            return ProvisionedUser(existing, existed = true)
        }
        val roles = RoleLadder.rolesAtLevel(emptyList(), level) + capabilities.filter { it.isNotBlank() }
        val userId = userService.provisionUser(
            cxt, address, namedClientOrRefuse(cxt, client, roles), roles,
            createdAt = cxt.now(), persona = persona, personaSuffix = personaSuffix, verifiedAt = cxt.now(), registered = true,
        ) { authUserData ->
            // The person's real-world name (issue #736), set the same way the admin-create path does -- display
            // copy, independent of the username, so a fixture can exercise a name-driven feature (a prefill) that
            // `publicName` (which falls back to the email) cannot show. Ignored when the user already exists, like
            // the other create-time fields above.
            AuthUserRow.normalizeName(name)?.let { authUserData[AD.name] = it }
        }
        val row = userService.queryByUserId(cxt, userId)
            ?: throw KdrException("Could not load the just-created user '$address'.", code = EXC.internalError)
        return ProvisionedUser(row, existed = false)
    }

    /**
     * The client a caller named for a new user -- the fixture's, or an allClients registration's (issue #751)
     * -- or, when [named] is null, the default client for the [roles] being provisioned
     * ([AddressRules.defaultClient]: `hub` for an allClients user, else `public`; issue #799).
     *
     * An explicit client this node does not carry is **refused**, where an unnamed one takes the default.
     * The difference is who is on the other end. A test or an administrator asking for a client that is not
     * present has made a mistake, and silently getting `public` is how it goes unnoticed until an assertion
     * three files away fails for a reason that has nothing to do with what it was checking.
     */
    private fun namedClientOrRefuse(cxt: KdrCxt, named: String?, roles: Collection<String>): String {
        val client = named ?: return AddressRules.defaultClient(cxt, roles)
        val clients = ClientService.get(cxt)
        if (!clients.isPresent(client)) {
            val known = clients.known(client)
            val why = if (known?.retired == true) {
                "it has been retired"
            } else if (known != null) {
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
        if (user.isRegistered) throw KdrException.mkInput("${user.primaryId} has already claimed this user; there is nothing to invite them to.")
        if (!user.enabled) throw KdrException.mkInput("This user of ${user.primaryId} is disabled; enable the account before inviting.")
        requireAdminMailAllowed(cxt, user.primaryId)
        val token = InvitationToken(user.identityId, user.userId, cxt.now().toEpochMilliseconds() + AUTHC.invitationMillis).encode(node)
        val url = INVITE.invitationUrl(INVITE.publicUrlFor(cxt), token)
        // The copy (issue #773) carries the link and, beside it, the recipe for the login page's "claim" path
        // -- the client and the persona as the page wants them typed -- rendered as the user's client words it.
        mail.sendFragmentMail(
            cxt, user.primaryId, user.client, MCOPY.invitation,
            mapOf(
                MCOPY.addressParam to user.primaryId, MCOPY.clientParam to user.client, MCOPY.urlParam to url,
                MCOPY.personaParam to PERSONA.typed(user.persona, user.personaSuffix),
                MCOPY.personaLabelParam to PERSONA.typed(PERSONA.label(user.persona), user.personaSuffix),
            ),
        )
        LogAuth.info(cxt) {
            "User ${cxt.userProfile.userId} invited user ${user.userId} ('${user.primaryId}') to '${user.client}' as '${user.persona}'."
        }
        return token
    }

    /**
     * Mails [user]'s person a reminder of where and how to sign in (issue #1504): for a user already claimed, which an
     * invitation is refused for, since its link signs its holder in with no code. The mail carries the ordinary sign-in
     * page, so it gives nobody access the address did not already have. Not for a user nobody has claimed (their
     * invitation says more), nor one that could not sign in: disabled, in a client this node does not carry, or opened
     * in a sandbox by somebody, which is reached through the sandbox's opening alone.
     *
     * Only where the deployment names its own URL: a person is told to sign in with their password at this link, so it
     * cannot be built from a request's headers, which are the caller's to set. A test instance may fall back on them.
     */
    fun remindUser(cxt: KdrCxt, user: AuthUserRow) {
        if (!user.isRegistered) throw KdrException.mkInput("${user.primaryId} has not claimed this user; send an invitation instead.")
        if (!user.enabled) throw KdrException.mkInput("This user of ${user.primaryId} is disabled; enable the account before reminding them.")
        if (user.isOpenedSandboxUser) throw KdrException.mkInput("This user of ${user.primaryId} was opened in a sandbox, and is not signed in to.")
        if (!ClientService.get(cxt).isPresent(user.client)) {
            throw KdrException.mkInput("The client '${user.client}' is not available here, so ${user.primaryId} could not sign in to it.")
        }
        if (INVITE.configuredPublicUrl(cxt) == null && !cxt.instanceConfig.isTestInstance) {
            throw KdrException.mkInput("A reminder needs this deployment's public URL: set ${INVITE.publicUrl.name}.")
        }
        requireAdminMailAllowed(cxt, user.primaryId)
        mail.sendFragmentMail(
            cxt, user.primaryId, user.client, MCOPY.signInReminder,
            mapOf(
                MCOPY.addressParam to user.primaryId, MCOPY.clientParam to user.client,
                MCOPY.urlParam to INVITE.signInUrl(INVITE.publicUrlFor(cxt)),
                MCOPY.personaLabelParam to PERSONA.typed(PERSONA.label(user.persona), user.personaSuffix),
            ),
        )
        LogAuth.info(cxt) {
            "User ${cxt.userProfile.userId} sent user ${user.userId} ('${user.primaryId}') a sign-in reminder for '${user.client}'."
        }
    }

    /**
     * What an invitation [token] is for, before it is accepted: the address, client, persona, personaSuffix, and
     * name of the invited user. So the page can say what accepting means, and so that merely *opening* the
     * link -- which a mail scanner does -- accepts nothing.
     */
    fun previewInvitation(cxt: KdrCxt, token: String): Map<String, Any?> {
        val user = invitedUser(cxt, token)
        return buildMap {
            put(AFLD.email, user.primaryId)
            put(AFLD.client, user.client)
            put(AFLD.persona, user.persona)
            put(AFLD.personaSuffix, user.personaSuffix)
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
     * The user a [token] invites the reader to claim, or a refusal: one message for a malformed, tampered, or
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

    // --- claiming an account created for you (issue #751) ----------------------

    /** The user [key] names, or null: the enabled or disabled, non-deleted user under it, never for an invalid key. */
    private fun claimedUser(cxt: KdrCxt, key: ClaimKey): AuthUserRow? {
        if (!key.isValid) return null
        val identity = userService.queryIdentityByAddress(cxt, key.address) ?: return null
        return userService.usersOfIdentity(cxt, identity.identityId).firstOrNull { key.matches(it) }
    }

    /**
     * Mails what the claim page asks for (issue #751): a verification code for the user the [key] names, or
     * -- when nothing matches -- a mail saying so, as specifically as the inbox's owner is entitled to (that the
     * address does have a user in that client, when it does). Either way this returns normally and the page reports a code as sent: the truth goes to the
     * inbox, never to an anonymous caller, so the page is no oracle for who has what.
     *
     * **Nothing typed reaches a mail unless it is an id** (`ClaimKey.isValid`): this is anonymous and mails any
     * address, so echoing a free value would let a stranger put their own sentence into a message sent from
     * the deployment's domain. A key that is not made of ids names nobody, and its mail says only that.
     */
    fun sendClaimCode(cxt: KdrCxt, key: ClaimKey, formAuthToken: String) {
        requireValidToken(cxt, formAuthToken)
        val address = key.address
        if (!address.isEmailAddress()) throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.emailInvalid))
        requireSendAllowed(cxt, address)
        val user = claimedUser(cxt, key)
        // Which copy (issue #773): the code, or one of three "no match" answers that say as much as the inbox's
        // owner is entitled to. A key that is not made of ids names nobody, so its answer names nothing typed.
        val code = if (user != null) node.computeVerifyCode(formAuthToken, key.hashText) else null
        val which = when {
            user != null -> MCOPY.claimCode
            !key.isValid -> MCOPY.claimNoMatch
            else -> {
                val identity = userService.queryIdentityByAddress(cxt, address)
                val inClient = identity != null && userService.usersOfIdentity(cxt, identity.identityId).any { it.client == key.client && !it.isDeleted }
                if (inClient) MCOPY.claimNoMatchInClient else MCOPY.claimNoMatchNamed
            }
        }
        LogAuth.info(cxt) {
            "Claim code requested for '$address' (${if (key.isValid) key.describe() else "not an id"}): " +
                (if (user != null) "user ${user.userId}." else "no match.")
        }
        val params = mapOf(MCOPY.addressParam to address, MCOPY.codeParam to code) +
            if (key.isValid) mapOf(
                MCOPY.clientParam to key.client,
                MCOPY.personaParam to PERSONA.typed(key.persona, key.personaSuffix),
                MCOPY.personaLabelParam to PERSONA.typed(PERSONA.label(key.persona), key.personaSuffix),
            ) else emptyMap()
        mail.sendFragmentMail(cxt, address, if (key.isValid) key.client else null, which, params, inBackground = true)
    }

    /**
     * Registers -- and logs in as -- the user the [key] names, on the code mailed for that key. The code proves both the address and the key, so this is a code login
     * that lands on a *named* user: it claims an unclaimed one (registering it and verifying the identity), and
     * simply signs in as one already claimed, which is also the one way to log in as a particular non-default
     * user by address alone. A key that names no user is refused exactly as a wrong code is.
     */
    fun claimAccount(cxt: KdrCxt, key: ClaimKey, formAuthToken: String, verifyCode: String): Map<String, Any?> {
        requireValidToken(cxt, formAuthToken)
        verifyCodeOrThrow(cxt, key.hashText, formAuthToken, verifyCode)
        val user = claimedUser(cxt, key) ?: throw KdrException.mkMsg(KdrMsg(AFRAG.auth, AERR.ns, AERR.codeIncorrect))
        return completeLogin(cxt, user, byCode = true)
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
    fun switchUser(cxt: KdrCxt, userId: Long): Map<String, Any?> =
        completeLogin(cxt, ownUser(cxt, userId), byCode = false, sessionChange = true)

    /**
     * Becomes the person's user in [client] (issue #1110): the switch by client rather than by user, for a surface that
     * names a client -- the one a person just made, one of their customer's -- with no user id in hand. Lands on
     * [UserService.userInClient], the person's default there, and completes as [switchUser] does. Refused when the
     * person has no registered, enabled user in [client].
     */
    fun switchClient(cxt: KdrCxt, client: String): Map<String, Any?> {
        val identityId = cxt.userProfile.identityId
            ?: throw KdrException("This session predates identities; log in again to switch clients.", code = EXC.authNeeded)
        val identity = userService.queryIdentityById(cxt, identityId)
            ?: throw KdrException("This session's identity could not be found; log in again.", code = EXC.authNeeded)
        val user = userService.userInClient(cxt, identity, client)
            ?: throw KdrException.mkInput("You have no user in the client '$client'.")
        return completeLogin(cxt, user, byCode = false, sessionChange = true)
    }

    /**
     * Switches a session that is **in a sandbox** to [userId], another of the person's own users (issue #1197),
     * completing as [switchUser] does -- which the webapp sends every switch made from inside a sandbox through.
     *
     * Not gated by role, unlike [switchUser]: a sandbox user acts with what the user it was opened from holds, and
     * with nothing once nobody stands behind it any more (`SandboxAccess`) -- a session left in that state could
     * reach nothing at all, the way back included. What it still needs is the rest of what a switch asks: the
     * session's own user still acts for it ([actsForSession]: enabled, and its identity's), so a disabled or
     * detached user goes no further here than at the gate, and the user it lands on is one of the identity's own,
     * registered and enabled. Where it lands, what it acts with is decided afresh at the next request, as after
     * any switch.
     */
    fun leaveSandbox(cxt: KdrCxt, userId: Long): Map<String, Any?> {
        // The live profile, as a gated request would have it: this section refreshes nothing on its own, and the
        // cookie's roles are a snapshot. Nothing below decides on them today; a later check must not by accident.
        refreshActingRoles(cxt)
        val profile = cxt.userProfile
        if (!profile.isRowBacked || !profile.isLoggedIn) {
            throw KdrException("Sign in to leave a sandbox.", code = EXC.authNeeded)
        }
        if (profile.identityId == null) {
            throw KdrException("This session predates identities; log in again to switch users.", code = EXC.authNeeded)
        }
        val current = userService.queryByUserId(cxt, profile.userId)
        if (current == null || !isSandboxClient(current.client) || !current.actsForSession(profile)) {
            throw KdrException.mkInput("This session is not in a sandbox it can leave.")
        }
        return completeLogin(cxt, ownUser(cxt, userId), byCode = false, sessionChange = true)
    }

    /**
     * Opens [named]'s Shadow Sandbox -- the caller's own client when null -- as the caller's own user in it (issue
     * #929, rule 1): found by the caller's identity, persona and suffix, or on first use created there, registered,
     * with the caller's levels. Then a fresh session as that user, the way [switchUser] completes.
     *
     * Opened from the **parent** side, by an administrator of the parent or an `allClients` administrator: authority
     * flows from parent to sandbox. **What is recorded is who opened it** ([AuthUserRow.openedBy]) and that user's
     * organization, on the found user as on a new one, and the sandbox user then acts as that user outside, asked at every request
     * ([SandboxAccess]) -- so no role is kept in step afterwards and none needs to be. The levels a new one is
     * created with are a record, and a capability is not written at all. Refused for a client without the
     * `sandbox` flag, and from inside a sandbox, which has none of its own.
     */
    fun openSandbox(cxt: KdrCxt, named: String?): Map<String, Any?> {
        val profile = cxt.userProfile
        val parent = named ?: profile.client
        if (isSandboxClient(parent)) {
            throw KdrException.mkInput("'$parent' is a sandbox; a sandbox is opened from its parent.")
        }
        if (!SandboxAccess.mayOpen(cxt, parent)) {
            throw KdrException("Only an administrator of '$parent' may open its sandbox.", code = EXC.notAuthorized)
        }
        if (!SandboxAccess.hasSandbox(cxt, parent)) {
            throw KdrException.mkInput("Client '$parent' has no sandbox.")
        }
        val identityId = profile.identityId
            ?: throw KdrException.mkInput("This session carries no identity to open a sandbox under; sign in again.")
        val identity = userService.queryIdentityById(cxt, identityId)
            ?: throw KdrException("The session's identity could not be found.", code = EXC.notFound)
        val own = userService.queryByUserId(cxt, profile.userId)
            ?: throw KdrException("The current user could not be found.", code = EXC.notFound)
        val clone = userService.claimUser(
            cxt, identity, sandboxOf(parent), own.persona, own.personaSuffix,
            profile.roles.filter { RoleLadder.rankOf(it) != null }.sorted(), openedFrom = own,
        )
        return completeLogin(cxt, clone, byCode = false, sessionChange = true)
    }

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
     * Permanently removes [userId], one of the person's own `public` users (issue #752), and returns the user
     * info of whoever the session is acting as afterward -- anonymous when nothing is left.
     *
     * `public` is the placeholder client: where a person's first user lands when they register before anybody
     * has placed them anywhere, on a deployment that allows anonymous self-registration. Such a person may
     * remove their `public` users themselves, **up to and including the last of everything**: a `public` user
     * exists only because its owner registered, so its owner can always register again (Sam, 2026-09-22).
     *
     * The removal is the ordinary permanent one (`UserService.deleteUser`): the row becomes a tombstone and gives
     * up its key, and when it was the identity's last live user the identity is retired with it -- the address
     * freed, the credentials, links and devices gone. When the removed user is the one the session is acting
     * as, the session moves to the person's default user, as a switch does, or ends when there is none.
     */
    fun removePublicUser(cxt: KdrCxt, userId: Long): Map<String, Any?> {
        val identityId = cxt.userProfile.identityId
            ?: throw KdrException("This session predates identities; log in again to remove a user.", code = EXC.authNeeded)
        val target = userService.queryByUserId(cxt, userId)
        // One message for every way the target is not the caller's own live `public` user, so this confirms
        // nothing about users that are not theirs.
        if (target == null || target.identityId != identityId || target.isDeleted || target.client != CL.public) {
            throw KdrException.mkInput("User $userId is not one of your '${CL.public}' users.")
        }
        val identity = userService.identityOfUser(cxt, target)
        userService.deleteUser(cxt, target, permanent = true)
        LogAuth.info(cxt) { "User ${target.userId} ('${target.primaryId}') removed their own '${CL.public}' user." }
        // A default pointing at the removed user would name a tombstone. Re-read, since a retirement just wrote it.
        userService.queryIdentityById(cxt, identity.identityId)
            ?.takeIf { it.defaultUserId == target.userId }
            ?.let { it.defaultUserId = null; userService.updateIdentity(cxt, it) }
        if (cxt.userProfile.userId != target.userId) {
            return currentUserInfo(cxt)
        }
        // The session was acting as the user just removed: move it, as a switch would, to the person's default
        // -- or, when no registered user of theirs remains, end it.
        val next = userService.queryIdentityById(cxt, identity.identityId)?.let { userService.registeredDefaultOf(cxt, it) }
        if (next != null) {
            return completeLogin(cxt, next, byCode = false, sessionChange = true)
        }
        cxt.request?.clearAuth = true
        return UserProfile.anonymous().toUserInfo()
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
     *
     * A [sessionChange] -- a switch to another of the person's users, into or out of a sandbox -- is not a sign-in:
     * the new cookie keeps the session's original sign-in (see KdrRequest.sessionChange), so the deployment's limit
     * on a session is not restarted by switching (issue #1372).
     */
    private fun completeLogin(
        cxt: KdrCxt, row: AuthUserRow, byCode: Boolean, register: Boolean = byCode, sessionChange: Boolean = false,
    ): Map<String, Any?> {
        // Every login completes here, so a public test deployment refuses a caller without env auth here (issue
        // #1150) -- by code, password, Google, claim or invitation alike -- as the user's own address decides.
        PublicTestDeployment.requireSignInOpen(cxt, row.primaryId)
        if (!row.enabled) throw KdrException("The user account is not active.", code = EXC.badInput)
        // A user whose client is not carried here -- retired (issue #1111), or not enabled in this environment --
        // does not get in: everything they would reach is scoped by that client.
        val clients = ClientService.get(cxt)
        if (!clients.isPresent(row.client)) {
            val why = if (clients.known(row.client)?.retired == true) "has been retired" else "is not available here"
            throw KdrException("The account's client '${row.client}' $why.", code = EXC.badInput)
        }
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
        // reaches this method. `isEdit = false`, because signing in is not an edit of the account. A session change is
        // not a sign-in either (issue #1480), so a switch or a sandbox's opening leaves the stamps as they were; a
        // sign-in moves the last one to the previous, which is what Profile shows the person.
        if (!sessionChange) {
            row.previousLoggedInAt = row.lastLoggedInAt
            row.lastLoggedInAt = cxt.now()
        }
        UserService.get(cxt).updateUser(cxt, row, isEdit = false)

        // The password status comes from the identity in hand rather than the row: a row read before the
        // password was set (`changePassword`, `setLoginData`) would otherwise report the person as without one.
        // The acting roles (issue #929), so the cookie's snapshot never carries a sandbox admin role the gate withholds.
        val profile = row.toActingProfile(cxt).copy(hasPassword = identity.hasPassword)
        cxt.bindToUserProfile(profile)
        cxt.request?.let {
            it.setAuthCookie = true
            it.sessionChange = sessionChange
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

/** A user [AuthFormHandler.findOrProvisionUser] found or created, and whether it [existed] already. */
class ProvisionedUser(val row: AuthUserRow, val existed: Boolean)
