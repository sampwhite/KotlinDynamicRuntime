package com.dynamicruntime.common.user

// Auth/profile constants that the *frontend* (Kotlin/JS) shares with the backend: endpoint paths, request and
// response field (JSON key) names, UI-config feature flags, schema type names, and fragment file ids. They
// live in the KMP kernel (not base:common) precisely so the transpiled frontend can reference the same
// strings the backend serves, instead of re-hardcoding them. Per the code guide, these are lowerCamelCase
// `const val`s scoped in short upper-case acronym objects, always referenced qualified.

/** Auth and profile endpoint paths (as the frontend calls them, before the API context root is prepended). */
@Suppress("ConstPropertyName")
object AEP {
    const val createToken = "/auth/form/createToken"
    const val newContactSendVerify = "/auth/newContact/sendVerify"
    const val userSendVerify = "/auth/user/sendVerify"
    const val createInitial = "/auth/user/createInitial"
    const val setLoginData = "/auth/user/setLoginData"
    const val loginByCode = "/auth/login/byCode"
    const val loginByPassword = "/auth/login/byPassword"
    const val setPassword = "/auth/user/setPassword"
    const val authUiConfig = "/auth/ui/config"
    const val selfInfo = "/auth/self/info"
    const val logout = "/logout"
    const val profileUiConfig = "/profile/ui/config"
    const val profileClearPassword = "/profile/self/clearPassword"

    /** Dev-only: recent simulated emails (only served when email is simulated), so a dev UI can read a code. */
    const val simulatedEmails = "/auth/simulatedEmails"
}

/** Fields of the simulated-emails (dev) endpoint. */
@Suppress("ConstPropertyName")
object ASE {
    const val emails = "emails"
    const val to = "to"
    const val subject = "subject"
    const val text = "text"
}

/** Auth request/response field (JSON key) names, shared so the frontend builds and reads payloads by constant. */
@Suppress("ConstPropertyName")
object AFLD {
    const val username = "username"

    /** A login identifier for looking up an existing account: a username **or** an email address. */
    const val loginId = "loginId"
    const val password = "password"
    const val formAuthToken = "formAuthToken"
    const val verifyCode = "verifyCode"
    const val contactAddress = "contactAddress"
    const val contactType = "contactType"
    const val addPassword = "addPassword"
    const val userId = "userId"

    /** The `state.userInfo` key of a UI-config payload (the caller's user info). */
    const val userInfo = "userInfo"
}

/** UI-config feature-flag keys for the auth and profile widget-groups. */
@Suppress("ConstPropertyName")
object AFEAT {
    const val registration = "registration"
    const val codeLogin = "codeLogin"
    const val passwordLogin = "passwordLogin"
    const val hasPassword = "hasPassword"
    const val canSetPassword = "canSetPassword"

    /** Whether email is simulated, so the frontend may read a verification code back from `/auth/simulatedEmails`. */
    const val simulatedEmail = "simulatedEmail"
}

/** Auth/profile schema type names (the backend's output/input type refs; also useful to the frontend). */
@Suppress("ConstPropertyName")
object ATYPE {
    const val formToken = "FormToken"
    const val authAck = "AuthAck"
    const val userIdResult = "UserIdResult"
    const val authUiConfig = "AuthUiConfig"
    const val profileUiConfig = "ProfileUiConfig"
    const val simulatedEmail = "SimulatedEmail"
    const val simulatedEmails = "SimulatedEmails"
}

/** Markdown fragment file ids for the auth-area widget-groups (each also the group's fragment namespace). */
@Suppress("ConstPropertyName")
object AFRAG {
    const val auth = "auth"
    const val profile = "profile"
}

/**
 * Keys of the `error` namespace in the `auth` fragment file: the copy for auth error messages (issue #108).
 * The backend renders these from `KdrException`'s `KdrMsg` at the top-level handler -- the sentence lives here
 * (in `auth.md`), not duplicated in Kotlin. The `${...}`-bearing ones take the noted params.
 */
@Suppress("ConstPropertyName")
object AERR {
    const val ns = "error"

    // Parameter-free.
    const val codeIncorrect = "codeIncorrect"
    const val tokenExpired = "tokenExpired"
    const val emailNoAt = "emailNoAt"
    const val loginFailed = "loginFailed"
    const val tooManyVerifyAttempts = "tooManyVerifyAttempts"
    const val tooManyVerifyRequests = "tooManyVerifyRequests"
    const val tooManyLoginAttempts = "tooManyLoginAttempts"

    // Account-existence (email leak); take a param. Marked sensitive for obfuscation in a later phase.
    const val noAccount = "noAccount" // param: loginId
    const val emailNotAvailable = "emailNotAvailable" // param: email

    /** Param keys the `${...}` placeholders reference. */
    const val loginIdParam = "loginId"
    const val emailParam = "email"
}
