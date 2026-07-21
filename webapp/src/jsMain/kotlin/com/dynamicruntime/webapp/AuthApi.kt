package com.dynamicruntime.webapp

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFEAT
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.ASE
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong

/** Which auth affordances this deployment offers (from the auth UI-config `features`). */
class AuthFeatures(
    val registration: Boolean,
    val codeLogin: Boolean,
    val passwordLogin: Boolean,
    /** Whether Google sign-in is offered (the deployment configured a client id). */
    val googleLogin: Boolean,
    /** Dev only: email is simulated, so the code can be read back from `/auth/simulatedEmails`. */
    val simulatedEmail: Boolean,
)

/** The auth widget-group's construction manifest: where its copy lives, which features are on, and who (if
 *  anyone) is currently signed in. */
class AuthConfig(
    val fragment: FragmentRef,
    val features: AuthFeatures,
    val user: UserProfile,
    /**
     * The Google OAuth client id, or empty when Google sign-in is off. Public by design -- Google's script has
     * to present it -- so the backend serves it in the config rather than the frontend hardcoding it.
     */
    val googleClientId: String,
)

/**
 * The auth widget-group's backend calls, keyed off the shared kernel constants ([AEP]/[AFLD]/[AFEAT]/[UIC]) so
 * the frontend never re-hardcodes a path or a JSON key the backend serves. A successful login writes the
 * session cookie server-side (the responses are the caller's [UserProfile]); a failure raises the runtime's
 * error message (e.g., the opaque password-login failure), which the flow shows the user.
 *
 * A returning user is identified by a **login id** -- a username *or* an email -- so the UI can work purely in
 * emails (the backend resolves either way).
 */
@Suppress("ConstPropertyName")
object AuthApi {
    private const val emailContactType = "email"

    suspend fun fetchConfig(): AuthConfig {
        val config = fetchUiConfig(AEP.authUiConfig)
        return AuthConfig(
            fragment = config.fragment,
            features = AuthFeatures(
                registration = config.features[AFEAT.registration] == true,
                codeLogin = config.features[AFEAT.codeLogin] == true,
                passwordLogin = config.features[AFEAT.passwordLogin] == true,
                googleLogin = config.features[AFEAT.googleLogin] == true,
                simulatedEmail = config.features[AFEAT.simulatedEmail] == true,
            ),
            user = UserProfile.fromUserInfo(config.state[AFLD.userInfo].toJsonMapOrEmpty()),
            googleClientId = config.state[AFLD.googleClientId] as? String ?: "",
        )
    }

    /** Issues a fresh form token; the same token is used for the send-verify and the following code call. */
    suspend fun createToken(): String =
        Http.getApi(AEP.createToken)[EP.results].toJsonMapOrEmpty()[AFLD.formAuthToken] as? String
            ?: error("The server did not return a form token.")

    /** Emails a verification code to a new [email] contact (registration). */
    suspend fun sendVerifyNewContact(email: String, token: String) {
        Http.sendApi(
            "POST", AEP.newContactSendVerify,
            mapOf(AFLD.contactAddress to email, AFLD.contactType to emailContactType, AFLD.formAuthToken to token),
        )
    }

    /**
     * Emails a verification code to an existing user (by [loginId] -- email or username). [addPassword] frames
     * the emailed copy for setting a password, so the caller has to decide before the code is sent.
     */
    suspend fun sendVerifyUser(loginId: String, token: String, addPassword: Boolean = false) {
        Http.sendApi(
            "POST", AEP.userSendVerify,
            mapOf(AFLD.loginId to loginId, AFLD.formAuthToken to token, AFLD.addPassword to addPassword),
        )
    }

    /** Provisions the initial user row from a verified email + code, returning the new userId. */
    suspend fun createInitial(email: String, token: String, code: String): Long {
        val results = Http.sendApi(
            "PUT", AEP.createInitial,
            mapOf(
                AFLD.contactAddress to email, AFLD.contactType to emailContactType,
                AFLD.formAuthToken to token, AFLD.verifyCode to code,
            ),
        )[EP.results].toJsonMapOrEmpty()
        return results[AFLD.userId].toOptLong() ?: error("The server did not return a user id.")
    }

    /**
     * Finishes registration, which logs the user in. No username (the frontend doesn't use them); [password] is
     * optional -- login by code works without one -- and when given, the account has it from the outset.
     */
    suspend fun finishRegistration(userId: Long, token: String, code: String, password: String? = null): UserProfile =
        userFrom(
            Http.sendApi(
                "PUT", AEP.setLoginData,
                buildMap {
                    put(AFLD.userId, userId)
                    put(AFLD.formAuthToken, token)
                    put(AFLD.verifyCode, code)
                    if (!password.isNullOrEmpty()) put(AFLD.password, password)
                },
            ),
        )

    /** Logs a returning user in by verification code. */
    suspend fun loginByCode(loginId: String, token: String, code: String): UserProfile =
        userFrom(
            Http.sendApi(
                "POST", AEP.loginByCode,
                mapOf(AFLD.loginId to loginId, AFLD.formAuthToken to token, AFLD.verifyCode to code),
            ),
        )

    /**
     * Logs in with the Google ID token Google's sign-in handed the browser. The backend verifies it and links
     * the Google identity to a local account (creating one on a first sign-in), so this one call covers both
     * registering and returning -- there is no separate "register with Google" path.
     */
    suspend fun loginByGoogle(credential: String): UserProfile =
        userFrom(Http.sendApi("POST", AEP.loginByGoogle, mapOf(AFLD.googleCredential to credential)))

    /** Logs a returning user in by password (familiar devices only; failure raises the opaque message). */
    suspend fun loginByPassword(loginId: String, password: String): UserProfile =
        userFrom(Http.sendApi("POST", AEP.loginByPassword, mapOf(AFLD.loginId to loginId, AFLD.password to password)))

    /**
     * Sets or changes the user's password, verified by an emailed code. This *is* a code login: the backend
     * also logs the user in and makes the device familiar, so the new password is usable from this browser
     * next time. Used both by the login flow (log in and set a password in one step) and by the profile page.
     */
    suspend fun setPassword(loginId: String, password: String, token: String, code: String): UserProfile =
        userFrom(
            Http.sendApi(
                "PUT", AEP.setPassword,
                mapOf(
                    AFLD.loginId to loginId, AFLD.password to password,
                    AFLD.formAuthToken to token, AFLD.verifyCode to code,
                ),
            ),
        )

    /** Clears the session cookie. */
    suspend fun logout() {
        Http.getApi(AEP.logout)
    }

    /**
     * Dev convenience: when email is simulated, reads the verification code back from the captured email for
     * [email] (much as `AuthFlowTest` does), so a login/registration can be completed locally without real
     * mail. Returns null when the endpoint isn't served (a real deployment) or no code is found.
     */
    suspend fun fetchDevCode(email: String): String? = try {
        val emails = Http.getApi(AEP.simulatedEmails)[EP.results].toJsonMapOrEmpty()[ASE.emails].toJsonListOfMaps()
        val text = emails.firstOrNull { it[ASE.to] == email }?.get(ASE.text) as? String
        text?.let { codePattern.find(it)?.groupValues?.get(1) }
    } catch (_: Throwable) {
        null
    }

    /** The verification code in an email body (see the auth email template: "... verification code is <code>."). */
    private val codePattern = Regex("verification code is (\\S+?)[.\\s]")

    private fun userFrom(response: Map<String, Any?>): UserProfile =
        UserProfile.fromUserInfo(response[EP.results].toJsonMapOrEmpty())
}
