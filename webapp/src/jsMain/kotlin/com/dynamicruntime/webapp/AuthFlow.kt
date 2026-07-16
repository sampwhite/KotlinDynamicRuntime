package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.evalTemplate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the auth flow's suspend backend calls. */
private val authScope = MainScope()

external interface AuthFlowProps : Props {
    /** "login" or "register". */
    var mode: String
}

/**
 * The register/login widget-group (issue #81): a bespoke, config-driven flow (not the generic SchemaForm). It
 * fetches the auth UI-config for its copy (the `auth` Markdown fragment file) and feature flags, then walks the
 * user through email → verification code (both modes), plus an optional password path on login. A returning
 * user is identified by a login id (email or username), so the UI works purely in emails. On success, it
 * navigates home; the [AppBar] re-reads the auth config on that navigation and updates the account menu.
 */
val AuthFlow = FC<AuthFlowProps> { props ->
    val register = props.mode == "register"

    var config by useState<AuthConfig?>(null)
    var copy by useState<Map<String, Map<String, String>>>(emptyMap())
    var email by useState("")
    var password by useState("")
    var code by useState("")
    // The form token, set once a verification code has been sent, also marks the "enter the code" step.
    var token by useState<String?>(null)
    var error by useState<String?>(null)
    var busy by useState(false)
    // True when the code was autofilled from a simulated email (local dev only).
    var devFilled by useState(false)

    useEffectOnce {
        authScope.launch {
            try {
                val c = AuthApi.fetchConfig()
                config = c
                copy = AuthApi.fetchFragments(c.fragmentFileId, c.fragmentBuildId)
            } catch (e: Throwable) {
                error = "Could not load the sign-in page. (${e.message})"
            }
        }
    }

    // The App reuses this one component instance across login/register, so reset the transient flow state when
    // the mode changes -- otherwise a half-finished register (code step) would leak into login. This is React's
    // "adjust state when a prop changes" pattern: set during render, which re-renders immediately.
    var stateMode by useState(props.mode)
    if (props.mode != stateMode) {
        stateMode = props.mode
        email = ""
        password = ""
        code = ""
        token = null
        error = null
        devFilled = false
    }

    // Copy lookup with a fallback, so the page renders even before the fragments arrive.
    fun t(ns: String, key: String, dflt: String): String = copy[ns]?.get(key) ?: dflt

    /** Runs a "suspend" [block] with busy/error bookkeeping. */
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        authScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                error = e.message ?: "Something went wrong."
            } finally {
                busy = false
            }
        }
    }

    fun goHomeSignedIn() = navigateHash(emptyList())

    val ns = if (register) "register" else "login"

    fun sendCode() {
        val id = email.trim()
        if (!id.contains("@")) {
            error = "Enter your email address."
            return
        }
        run {
            val tk = AuthApi.createToken()
            if (register) AuthApi.sendVerifyNewContact(id, tk) else AuthApi.sendVerifyUser(id, tk)
            token = tk
            // Local dev only: when email is simulated (per the config), read the code back and pre-fill it.
            // Gated on the flag so a real-email deployment never calls the (404) dev endpoint.
            if (config?.features?.simulatedEmail == true) {
                AuthApi.fetchDevCode(id)?.let {
                    code = it
                    devFilled = true
                }
            }
        }
    }

    fun submitCode() {
        val tk = token ?: return
        run {
            if (register) {
                val userId = AuthApi.createInitial(email.trim(), tk, code.trim())
                AuthApi.finishRegistration(userId, tk, code.trim())
            } else {
                AuthApi.loginByCode(email.trim(), tk, code.trim())
            }
            goHomeSignedIn()
        }
    }

    fun submitPassword() = run {
        AuthApi.loginByPassword(email.trim(), password)
        goHomeSignedIn()
    }

    div {
        className = ClassName("card")
        h1 { +t(ns, "title", if (register) "Create your account" else "Log in") }
        error?.let {
            p {
                className = ClassName("todo-error")
                +it
            }
        }

        val codeSent = token != null

        // The email (login id) is always shown; it locks once a code has been sent.
        textField(t(ns, "emailLabel", "Email address"), email, disabled = busy || codeSent) { email = it }

        if (!codeSent) {
            // Login-only password path, when the deployment enables it.
            if (!register && config?.features?.passwordLogin == true) {
                textField(t("login", "passwordLabel", "Password"), password, isPassword = true, disabled = busy) {
                    password = it
                }
                Button {
                    type = "primary"
                    loading = busy
                    disabled = email.isBlank() || password.isEmpty()
                    onClick = { submitPassword() }
                    +t("login", "submit", "Log in")
                }
            }
            Button {
                loading = busy
                disabled = email.isBlank()
                onClick = { sendCode() }
                +t(ns, "sendCode", if (register) "Send verification code" else "Email me a code")
            }
        } else {
            p {
                className = ClassName("subtitle")
                +codeSentNote(register, email) { n, k, d -> t(n, k, d) }
            }
            textField(t(ns, "codeLabel", "Verification code"), code, disabled = busy) { code = it }
            Button {
                type = "primary"
                loading = busy
                disabled = code.isBlank()
                onClick = { submitCode() }
                +t(ns, "finish", if (register) "Create account" else "Log in")
            }
            Button {
                type = "link"
                disabled = busy
                onClick = {
                    token = null
                    code = ""
                    devFilled = false
                }
                +t("verify", "resend", "Send a new code")
            }
            if (devFilled) {
                p {
                    className = ClassName("type-hint")
                    +"Code auto-filled from the simulated email (local dev only)."
                }
            }
            p {
                className = ClassName("type-hint")
                +t("verify", "expiresNote", "The code expires in fifteen minutes.")
            }
        }

        // Switch between the two modes.
        div {
            className = ClassName("row")
            when {
                register -> Button {
                    type = "link"
                    onClick = { navigateHash(listOf("page" to "login")) }
                    +t("menu", "login", "Log in")
                }
                config?.features?.registration == true -> Button {
                    type = "link"
                    onClick = { navigateHash(listOf("page" to "register")) }
                    +t("menu", "register", "Register")
                }
            }
        }
    }
}

/** The "we sent a code" note: the register fragment (with `${user.email}` resolved), or a login default. */
private fun codeSentNote(register: Boolean, email: String, t: (String, String, String) -> String): String =
    if (register) {
        t("register", "codeSent", "A code was sent to your email. Enter it below.")
            .evalTemplate(mapOf("user" to mapOf("email" to email)))
    } else {
        "We emailed a verification code to $email."
    }

/** A labeled antd text/password input row. */
private fun ChildrenBuilder.textField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    disabled: Boolean = false,
    onChange: (String) -> Unit,
) {
    div {
        className = ClassName("row")
        span {
            className = ClassName("field-label")
            +label
        }
        Input {
            this.value = value
            this.disabled = disabled
            if (isPassword) type = "password"
            this.onChange = { event -> onChange(event.target.value as String) }
        }
    }
}
