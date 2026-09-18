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
    const val loginByGoogle = "/auth/login/google"
    const val setPassword = "/auth/user/setPassword"
    const val authUiConfig = "/auth/ui/config"
    const val selfInfo = "/auth/self/info"
    const val logout = "/logout"
    const val profileUiConfig = "/profile/ui/config"
    const val profileClearPassword = "/profile/self/clearPassword"
    const val profileSetName = "/profile/self/setName"

    // The switcher (issue #749), in the login-gated `user` section rather than the anonymous `auth` one: every
    // call acts on the session's identity, so the gate -- which also re-reads the acting row -- is the right
    // one, and there is nothing here for a logged-out caller.
    /** The users the signed-in person may act as. */
    const val selfUsers = "/user/self/users"
    /** Become another of the person's users; issues a fresh session cookie. */
    const val switchUser = "/user/self/switch"
    /** Choose which of the person's users an address logs in as. */
    const val setDefaultUser = "/user/self/setDefault"
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

    /** Registration flag: this account belongs to a business, not a person (sets `authUserData.isEntity`). */
    const val isEntity = "isEntity"

    /**
     * The account's real-world (non-unique) name, captured at registration: the registrant's full name, or the
     * business's name when [isEntity] is set.
     */
    const val name = "name"

    /** The Google ID token (a JWT) the browser's Google sign-in hands back, POSTed to [AEP.loginByGoogle]. */
    const val googleCredential = "googleCredential"

    /**
     * The Google OAuth **client id**. Public by design (it identifies the application to Google, and the
     * browser must present it), so the auth UI config carries it to the frontend; the deployment sets it with
     * `KDR_GOOGLE_CLIENT_ID`. Empty when Google sign-in is not configured.
     */
    const val googleClientId = "googleClientId"

    /** The `state.userInfo` key of a UI-config payload (the caller's user info). */
    const val userInfo = "userInfo"

    /** The `items` of the users list (issue #749): the caller's `UserChoice`s. */
    const val users = "users"
}

/** UI-config feature-flag keys for the auth and profile widget-groups. */
@Suppress("ConstPropertyName")
object AFEAT {
    const val registration = "registration"
    const val codeLogin = "codeLogin"
    const val passwordLogin = "passwordLogin"

    /** Whether Google sign-in is offered (on only when the deployment configured a client id). */
    const val googleLogin = "googleLogin"
    const val hasPassword = "hasPassword"
    const val canSetPassword = "canSetPassword"

    /** Whether email is simulated, so the frontend may read a verification code back from `/test/simulatedEmails`. */
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
    /** The users list (issue #749): the caller's `UserChoice`s under `users`. */
    const val userChoices = "UserChoices"
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
    /** The address is not a well-formed email (issue #743; was `emailNoAt`, which checked only for an `@`). */
    const val emailInvalid = "emailInvalid"
    const val loginFailed = "loginFailed"
    const val tooManyVerifyAttempts = "tooManyVerifyAttempts"
    const val tooManyVerifyRequests = "tooManyVerifyRequests"
    const val tooManyLoginAttempts = "tooManyLoginAttempts"

    /** Google sign-in was attempted but the deployment has no client id configured. */
    const val googleNotConfigured = "googleNotConfigured"

    /** The Google ID token did not verify (bad signature, wrong audience/issuer, or expired). */
    const val googleTokenInvalid = "googleTokenInvalid"

    /** Google supplied the account's email but has not itself verified it, so it cannot identify a user. */
    const val googleEmailUnverified = "googleEmailUnverified"

    // `noAccount` was removed with issue #275: it named an account by whether it exists, and an auth entry
    // point that answers "no account was found" is a membership oracle. The login/send-verify paths now fail
    // *identically* whether or not the account exists (see AuthFormHandler), so there is nothing to say here
    // -- and the message is deliberately gone rather than merely unused, so it cannot be wired back in.

    /** Registration attempted for an already-taken email. Param `email`; sensitive (obfuscated in prod). */
    const val emailNotAvailable = "emailNotAvailable"

    /** Param key the `${...}` placeholder in [emailNotAvailable] references. */
    const val emailParam = "email"
}

/**
 * The personas a user may be created with (issue #747): what relationship the user has to the application,
 * frozen at creation and part of the user's unique key with its identity, client and `personId`. Two to
 * start; phase D makes this a registry with default roles and labels, and a client may later add its own. A
 * persona is deliberately **not** a role -- `admin` here says how to read the user, and the roles say what
 * they may do.
 *
 * The default persona is **`member`** (Sam, 2026-09-18), not `user`: what it says is that the person belongs
 * to the client, which pairs naturally with `admin` and the later `reviewer` and `advisor`, and it stays clear
 * of the *role* `user`, a rung on the privilege ladder on a different axis. (The alternatives -- standard,
 * regular, ordinary -- read as tiers or carry an edge.)
 */
@Suppress("ConstPropertyName")
object PERSONA {
    const val member = "member"
    const val admin = "admin"

    /**
     * How a persona is shown: capitalized, `Member` / `Admin`. The wire value stays lowercase; phase D's
     * registry gives each persona a proper label, and this is the rule until then -- in the kernel so the bar
     * and the backend show the same word.
     */
    fun label(persona: String): String = persona.replaceFirstChar { it.uppercaseChar() }
}
