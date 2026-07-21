package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.passwordRuleError
import com.dynamicruntime.common.util.evalTemplate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the profile page's suspend backend calls. */
private val profileScope = MainScope()

/**
 * The profile widget-group (issue #70 Piece 3): the login-required account page, and the home of setting,
 * changing, and removing a password (issue #96).
 *
 * Like [AuthFlow] this is the config-driven mode, not the generic [SchemaForm]: handwritten React whose copy
 * is the `profile` Markdown fragment file and whose affordances come from `GET /profile/ui/config`.
 *
 * Setting a password is **code-verified** even here, where the caller is already logged in -- it is a step-up,
 * and it reuses the same `auth` calls the login flow uses. Because the emailed copy is framed up front (the
 * `addPassword` flag), the code is requested by an explicit "email me a code" step rather than on page load.
 * *Removing* a password needs no code: it is a de-escalation, and code login still works afterward.
 */
val Profile = FC<Props> {
    var config by useState<ProfileConfig?>(null)
    var copy by useState(Copy.empty)
    var password by useState("")
    var code by useState("")
    // The form token, set once a verification code has been sent, also marks the "enter the code" step.
    var token by useState<String?>(null)
    var error by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    var busy by useState(false)
    var devFilled by useState(false)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()

    /** Loads (or reloads) the config; the page re-reads it after a password change, so the copy follows. */
    fun loadConfig(onLoaded: (ProfileConfig) -> Unit = {}) {
        profileScope.launch {
            try {
                val c = ProfileApi.fetchConfig()
                config = c
                onLoaded(c)
            } catch (_: Throwable) {
                // The config is login-required, so the overwhelmingly likely failure is "not logged in".
                navigateHash(listOf("page" to "login"))
            }
        }
    }

    // Re-read the config (and its copy) on every refresh generation (issue #115) -- on mount, and whenever a
    // navigation or state mutation bumps it, so a password change here reloads the page's affordances.
    useEffect(generation) {
        loadConfig { c ->
            profileScope.launch {
                copy = runCatching { fetchCopy(c.fragment) }.getOrDefault(Copy.empty)
            }
        }
    }

    fun t(ns: String, key: String, dflt: String): String = copy.t(ns, key, dflt)

    /** Runs a "suspend" [block] with busy/error bookkeeping. */
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        profileScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    /** Step one of a password change: email a code, framed for setting a password. */
    fun sendCode() {
        val cfg = config ?: return
        note = null
        run {
            val tk = AuthApi.createToken()
            AuthApi.sendVerifyUser(cfg.loginId, tk, addPassword = true)
            token = tk
            if (cfg.features.simulatedEmail) {
                AuthApi.fetchDevCode(cfg.loginId)?.let {
                    code = it
                    devFilled = true
                }
            }
        }
    }

    /** Step two: save the new password against the emailed code. */
    fun savePassword() {
        val cfg = config ?: return
        val tk = token ?: return
        run {
            AuthApi.setPassword(cfg.loginId, password, tk, code.trim())
            password = ""
            code = ""
            token = null
            devFilled = false
            note = t("password", "saved", "Your password was saved.")
            // Bump instead of reloading by hand: the generation effect above re-reads config + copy, and the
            // account menu follows too (issue #115).
            bump()
        }
    }

    fun removePassword() = run {
        ProfileApi.clearPassword()
        note = t("password", "removedNote", "Your password was removed. You can still sign in with a code.")
        bump()
    }

    fun logout() = run {
        AuthApi.logout()
        navigateHash(emptyList())
        bump()
    }

    div {
        className = ClassName("card")
        h1 { +t("profile", "title", "Your profile") }

        val user = config?.user
        p {
            className = ClassName("subtitle")
            // Markdown, so the copy can set the name apart from the prose; substitution runs first, so a name
            // carrying Markdown or HTML is escaped as text rather than interpreted.
            MarkdownInline {
                source = t("profile", "signedInAs", $$"Signed in as **${user.publicName}**")
                    .evalTemplate(mapOf("user" to mapOf("publicName" to (user?.publicName ?: "your account"))))
            }
        }

        error?.let { errorText(it) }
        note?.let {
            p {
                className = ClassName("form-ok")
                +it
            }
        }

        val features = config?.features
        if (features?.canSetPassword == true) {
            val has = features.hasPassword
            h2 { +if (has) t("password", "changeTitle", "Change your password") else t("password", "setTitle", "Set a password") }
            p {
                className = ClassName("subtitle")
                +if (has) {
                    t("password", "hasPassword", "You have a password set. You can change it or remove it.")
                } else {
                    t("password", "noPassword", "You have not set a password. You currently sign in with a verification code.")
                }
            }

            if (token == null) {
                // The emailed copy is framed for a password up front, so the code is requested explicitly.
                div {
                    className = ClassName("row")
                    Button {
                        loading = busy
                        onClick = { sendCode() }
                        // The button says what the code is for -- on its own it reads as unrelated to passwords.
                        +if (has) {
                            t("password", "sendCodeChange", "Email me a code so I can change my password")
                        } else {
                            t("password", "sendCodeSet", "Email me a code so I can set a password")
                        }
                    }
                    if (has) {
                        Button {
                            danger = true
                            disabled = busy
                            onClick = { removePassword() }
                            +t("password", "remove", "Remove password")
                        }
                    }
                }
            } else {
                p {
                    className = ClassName("subtitle")
                    MarkdownInline {
                        source = t("password", "codeSent", $$"We emailed a verification code to `${user.email}`.")
                            .evalTemplate(mapOf("user" to mapOf("email" to (config?.loginId ?: ""))))
                    }
                }
                // Code first, then the new password -- the order the copy above just described ("we emailed a
                // code"), and the same order the registration flow uses, so the one step that appears in both
                // places does not swap its fields depending on where you reached it from.
                textField(
                    t("password", "codeLabel", "Verification code"), code, disabled = busy,
                    autoComplete = AC.oneTimeCode,
                ) { code = it }
                textField(
                    t("password", "newPasswordLabel", "New password"), password, isPassword = true,
                    disabled = busy, autoComplete = AC.newPassword,
                ) { password = it }

                // The shared rule the backend enforces, surfaced as a correction below the action rather than
                // an instruction under the field -- and only once there is something to correct. Further rules
                // (an upper-case character, a digit) will arrive through the same one message.
                val passwordError = if (password.isEmpty()) null else passwordRuleError(password)

                div {
                    className = ClassName("row")
                    Button {
                        type = "primary"
                        loading = busy
                        disabled = password.isEmpty() || code.isBlank() || passwordError != null
                        onClick = { savePassword() }
                        +t("password", "save", "Save password")
                    }
                    Button {
                        type = "link"
                        disabled = busy
                        onClick = {
                            token = null
                            code = ""
                            password = ""
                            devFilled = false
                        }
                        +t("password", "cancel", "Cancel")
                    }
                }
                passwordError?.let {
                    p {
                        className = ClassName("todo-error")
                        +it
                    }
                }
                if (devFilled) {
                    p {
                        className = ClassName("type-hint")
                        +"Code auto-filled from the simulated email (local dev only)."
                    }
                }
            }
        }

        div {
            className = ClassName("row")
            Button {
                type = "link"
                disabled = busy
                onClick = { logout() }
                +t("profile", "logout", "Log out")
            }
        }
    }
}
