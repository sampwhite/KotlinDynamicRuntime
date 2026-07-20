package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.util.hashPassword
import com.dynamicruntime.common.util.stdHashToBytes
import com.dynamicruntime.common.util.toReadableChars

/**
 * The verification code for a (form token, contact) pair. It is deliberately **not stored**: it is a
 * deterministic hash of the contact address and the encrypted, timeout-bounded form token, so it can be
 * recomputed to verify. Ported from dn's `computeVerifyCode`.
 */
fun computeVerifyCode(formAuthToken: String, contactAddress: String): String =
    (contactAddress + formAuthToken).stdHashToBytes().toReadableChars(4)

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
 * Sets [username] (when given) and, **only when [password] is provided**, the encoded password on [row] --
 * passwords are optional in kd2, so a user may log in by verification code alone and opt into a password
 * later. The row must be enabled, carry the user role, and have a recorded contact.
 */
fun updateUsernameAndPassword(row: AuthUserRow, username: String?, password: String?) {
    if (username != null) checkValidUsername(username)
    requireUsableForLogin(row)
    if (username != null) row.username = username
    if (password != null) setPassword(row, password)
}

/**
 * Hashes and stores [password] on [row] (opting the user into password login). Enforces the shared
 * [passwordRuleError] rules -- the same ones the frontend explains before submitting, so what it tells the
 * user and what this rejects cannot disagree.
 */
fun setPassword(row: AuthUserRow, password: String) {
    requireUsableForLogin(row)
    passwordRuleError(password)?.let { throw KdrException.mkInput(it) }
    row.encodedPassword = password.hashPassword()
}

/** Clears [row]'s password, opting the user back out of password login (code login still works). */
fun clearPassword(row: AuthUserRow) {
    row.encodedPassword = null
}

/** Guards that [row] is a real, enabled user with a contact -- the precondition for assigning login data. */
private fun requireUsableForLogin(row: AuthUserRow) {
    if (!row.enabled || !row.roles.contains(ROLE.user) || !row.authUserData.containsKey(AD.contacts)) {
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
 *  - the dispatcher, before enforcing a section's `requiredRole` -- so a revoked admin is refused immediately;
 *  - the shell's UI-config, before deciding which menu items to offer -- so the menu stops offering a page the
 *    caller can no longer open. (Without it the menu is merely *stale*, not unsafe: following the item still
 *    hits the gate above and 401s. But offering a door that will not open is its own bug.)
 *
 * Ordinary user traffic still never touches the database for auth. A disabled account loses every role here,
 * which is what makes `admin/user/setEnabled` bite at once rather than at cookie expiry; a row that has
 * vanished is treated the same way.
 */
fun refreshActingRoles(cxt: KdrCxt) {
    val profile = cxt.userProfile
    if (!profile.isLoggedIn) {
        return // anonymous/system callers have no row to read
    }
    val row = UserService.get(cxt)?.queryByUserId(cxt, profile.userId) ?: return
    val liveRoles = if (row.enabled) row.roles.toSet() else emptySet()
    if (liveRoles != profile.roles) {
        LogAuth.debug(cxt) { "Roles for user ${profile.userId} changed since login: $liveRoles." }
    }
    // Rebind unconditionally, not only when the roles moved: the row is already loaded, and the profile the
    // cookie produced carries identity alone -- no display name, no password status, since those would be
    // stale in a 30-day cookie. Anything that has paid for this read gets the whole live profile.
    val live = row.toUserProfile()
    cxt.bindToUserProfile(
        UserProfile(
            authId = live.authId, userId = live.userId, account = live.account, roles = liveRoles,
            publicName = live.publicName, hasPassword = live.hasPassword,
        ),
    )
}
