package com.dynamicruntime.common.user

// The rules a password must satisfy, in the KMP kernel so the backend (which *enforces* them, rejecting the
// request) and the frontend (which *explains* them, before the user submits) work from one definition. The
// alternative -- a length on each side -- is two numbers and two wordings that drift, and the frontend's copy
// would confidently describe a rule the server does not actually apply.

/** The shortest password we accept when a user opts into one. */
@Suppress("ConstPropertyName")
const val minPasswordLength = 8

/**
 * Checks [password] against the rules, returning **null when it is acceptable** and otherwise the reason it is
 * not, phrased for the user. One message at a time: this is the next thing to fix, not an audit.
 *
 * The single place to add a rule (an upper-case character, a digit, ...). Because the backend throws this same
 * text and the frontend shows it inline, a new rule lands in both at once, worded identically.
 *
 * The text is not fragment copy: it is a runtime message, the same string the server returns when it rejects
 * the request, and it is the *agreement* between the two that matters here.
 */
fun passwordRuleError(password: String): String? = when {
    password.length < minPasswordLength -> "A password must be at least $minPasswordLength characters long."
    else -> null
}
