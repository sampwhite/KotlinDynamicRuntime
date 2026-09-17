package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.util.hashPassword

// The verification code moved to `NodeService.computeVerifyCode`, because it must be keyed under the node's
// secret and this was a free function over public inputs alone. See that method for the takeover it fixes.

/** Validates a username: starts with a letter, contains only letters/digits/underscore, at least 4 chars. */
fun checkValidUsername(username: String) {
    val valid = username.length >= 4 && username[0].isLetter() &&
        username.all { it.isLetterOrDigit() || it == '_' }
    if (!valid) {
        throw KdrException.mkInput(
            "Username '$username' is invalid: it must start with a letter, contain only letters, digits, or " +
                "underscores, and be at least four characters long.",
        )
    }
}

/**
 * Sets [username] (when given) on [user] and, **only when [password] is provided**, the encoded password on
 * its [identity] -- passwords are optional in kd2, so a person may log in by verification code alone and opt
 * into a password later. The user must be enabled and carry the user role, and the identity must have a
 * recorded contact. The caller writes back whichever of the two it changed.
 */
fun updateUsernameAndPassword(user: AuthUserRow, identity: AuthIdentityRow, username: String?, password: String?) {
    if (username != null) checkValidUsername(username)
    requireUsableForLogin(user, identity)
    if (username != null) user.username = username
    if (password != null) setPassword(identity, user, password)
}

/**
 * Hashes and stores [password] on [identity] (opting the person into password login), on behalf of [user],
 * the one they are logging in as -- which must be usable for login. Enforces the shared [passwordRuleError]
 * rules -- the same ones the frontend explains before submitting, so what it tells the user and what this
 * rejects cannot disagree. The password is the identity's (issue #748): set through any of the person's
 * users, it logs the person in as whichever of them the login id names.
 */
fun setPassword(identity: AuthIdentityRow, user: AuthUserRow, password: String) {
    requireUsableForLogin(user, identity)
    passwordRuleError(password)?.let { throw KdrException.mkInput(it) }
    identity.encodedPassword = password.hashPassword()
}

/** Clears [identity]'s password, opting the person back out of password login (code login still works). */
fun clearPassword(identity: AuthIdentityRow) {
    identity.encodedPassword = null
}

/** Guards that [user] is a real, enabled user of an [identity] with a contact -- the precondition for assigning login data. */
private fun requireUsableForLogin(user: AuthUserRow, identity: AuthIdentityRow) {
    if (!user.enabled || !user.roles.contains(ROLE.user) || !identity.hasContact || user.identityId != identity.identityId) {
        throw KdrException("User is not in a state where a login can be assigned to it.")
    }
}

/**
 * Re-reads the acting user's roles from their `AuthUsers` row, replacing the ones their session cookie carried.
 *
 * The cookie is the fast path -- it holds roles precisely so an ordinary request needs no database read -- but
 * those roles are a *snapshot* taken at login, and a session lasts 30 days ([AUTHC.sessionMillis]). Without
 * this, revoking an administrator would leave them administering for up to a month.
 *
 * So the live read is paid only where the answer actually matters, and there are two such places:
 *  - the dispatcher, before enforcing a section's `requiredRole` -- so a revoked admin is refused promptly;
 *  - the shell's UI-config, before deciding which menu items to offer -- so the menu stops offering a page the
 *    caller can no longer open. (Without it the menu is merely *stale*, not unsafe: following the item still
 *    hits the gate above and 401s. But offering a door that will not open is its own bug.)
 *
 * **How prompt is "promptly":** the read goes through the `AuthUsers` table cache, so on the node that made
 * the change it is immediate (a local write forces the next read to reload), and on other nodes it lands when
 * the writer's request ends and their next request re-checks -- a fraction of a second, bounded by the
 * cache's state-row read throttle. Only a change made *outside* the application (a hand-edited row, a
 * migration) waits for the cache's recheck floor, ~30s by default. That is the deliberate trade against a
 * per-request SQL read; the yardstick remains the 30-day cookie this refresh exists to overrule.
 *
 * Ordinary user traffic still never touches the database for auth. A disabled account loses every role here,
 * which is what makes `admin/user/setEnabled` bite within the same bounds rather than at cookie expiry; a row
 * that has vanished is treated the same way, as is one that **no longer belongs to the cookie's identity**
 * (issue #748) -- the session was issued to a person, and a user detached from that person is not theirs to
 * act as, however the detachment came about.
 */
fun refreshActingRoles(cxt: KdrCxt) {
    val profile = cxt.userProfile
    // Skipped when there is no row to read -- which is the actual reason, and worth saying rather than
    // relying on `isLoggedIn` to imply it (issue #386). The system and anonymous profiles happen to be caught
    // by both; an env-authed caller is the first that is genuinely logged in and still has no row, and asking
    // the wrong question about it would send a query after `CL.systemUserId`.
    if (!profile.isRowBacked || !profile.isLoggedIn) {
        return
    }
    val row = UserService.get(cxt).queryByUserId(cxt, profile.userId) ?: return
    // A cookie issued before the split carries no identity and skips the membership check; the next login
    // replaces it with one that does.
    val detached = profile.identityId != null && row.identityId != profile.identityId
    if (detached) {
        LogAuth.info(cxt) { "User ${profile.userId} no longer belongs to the session's identity; treating it as disabled." }
    }
    val liveRoles = if (row.enabled && !detached) row.roles.toSet() else emptySet()
    if (liveRoles != profile.roles) {
        LogAuth.debug(cxt) { "Roles for user ${profile.userId} changed since login: $liveRoles." }
    }
    // Rebind unconditionally, not only when the roles moved: the row is already loaded, and the profile the
    // cookie produced carries identity alone -- no display name, no password status, since those would be
    // stale in a 30-day cookie. Anything that has paid for this read gets the whole live profile.
    //
    // `copy` rather than the constructor (issue #282): the only thing being changed is the role set, and
    // writing the other fields out by hand is what silently dropped `org`, and later the entity fields, from
    // every caller that arrived through here.
    cxt.bindToUserProfile(row.toUserProfile().copy(roles = liveRoles))
}
