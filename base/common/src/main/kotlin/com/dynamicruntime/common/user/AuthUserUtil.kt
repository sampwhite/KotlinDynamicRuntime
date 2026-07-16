package com.dynamicruntime.common.user

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
