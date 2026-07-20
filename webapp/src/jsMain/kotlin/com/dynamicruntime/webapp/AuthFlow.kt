package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.passwordRuleError
import com.dynamicruntime.common.util.evalTemplate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffect
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
    var copy by useState(Copy.empty)
    var email by useState("")
    var password by useState("")
    var code by useState("")
    // The form token, set once a verification code has been sent, also marks the "enter the code" step.
    var token by useState<String?>(null)
    var error by useState<DisplayError?>(null)
    var busy by useState(false)
    // True when the code was autofilled from a simulated email (local dev only).
    var devFilled by useState(false)
    // This is true when this round is setting a password: the emailed copy is framed for it (the addPassword flag), so
    // the choice is made before the code is sent and remembered until it is submitted.
    var settingPassword by useState(false)

    // Re-fetch this group's config on every refresh generation (issue #115) -- on mount, and whenever a
    // navigation or state mutation bumps it -- so the flow follows any config change.
    val generation = useRefreshGeneration()
    useEffect(generation) {
        authScope.launch {
            try {
                val c = AuthApi.fetchConfig()
                config = c
                copy = fetchCopy(c.fragment)
            } catch (e: Throwable) {
                error = DisplayError.expected("Could not load the sign-in page. (${e.message})")
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
        settingPassword = false
    }

    @Suppress("DuplicatedCode")
    fun t(ns: String, key: String, dflt: String): String = copy.t(ns, key, dflt)

    /** Runs a "suspend" [block] with busy/error bookkeeping. */
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        authScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    fun goHomeSignedIn() = navigateHash(emptyList())

    val ns = if (register) "register" else "login"

    /**
     * Emails a verification code. [withPassword] asks for the code that also *sets* a password (login only):
     * the backend frames the emailed copy from it, which is why it is chosen here rather than at the code step.
     */
    fun sendCode(withPassword: Boolean = false) {
        val id = email.trim()
        if (!id.contains("@")) {
            error = DisplayError.expected("Enter your email address.")
            return
        }
        // Any password typed against the password-login field belongs to that path, not to this round.
        password = ""
        settingPassword = withPassword
        run {
            val tk = AuthApi.createToken()
            if (register) AuthApi.sendVerifyNewContact(id, tk) else AuthApi.sendVerifyUser(id, tk, withPassword)
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
        val id = email.trim()
        run {
            when {
                // Registration takes the password (if any) straight into the account it is creating.
                register -> {
                    val userId = AuthApi.createInitial(id, tk, code.trim())
                    AuthApi.finishRegistration(userId, tk, code.trim(), password.ifEmpty { null })
                }
                // Setting a password *is* a code login, so this both saves it and signs the user in.
                settingPassword -> AuthApi.setPassword(id, password, tk, code.trim())
                else -> AuthApi.loginByCode(id, tk, code.trim())
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
        error?.let { d ->
            p {
                // An internal (non-fragment) error is shown as plain text, marked as raw (issue #111); designed
                // copy is Markdown-rendered, since fragment messages may use it and are sanitized server-side.
                className = ClassName(if (d.internal) "internal-error" else "todo-error")
                if (d.internal) +d.text else MarkdownInline { source = d.text }
            }
        }

        val codeSent = token != null

        // The email (login id) is always shown; it locks once a code has been sent.
        textField(
            t(ns, "emailLabel", "Email address"), email, disabled = busy || codeSent,
            autoComplete = AC.username,
        ) { email = it }

        if (!codeSent) {
            // Login-only password path, when the deployment enables it.
            if (!register && config?.features?.passwordLogin == true) {
                textField(
                    t("login", "passwordLabel", "Password"), password, isPassword = true, disabled = busy,
                    autoComplete = AC.currentPassword,
                ) { password = it }
                Button {
                    type = "primary"
                    loading = busy
                    disabled = email.isBlank() || password.isEmpty()
                    onClick = { submitPassword() }
                    +t("login", "submit", "Log in")
                }
            }
            div {
                className = ClassName("row")
                Button {
                    loading = busy
                    disabled = email.isBlank()
                    onClick = { sendCode() }
                    +t(ns, "sendCode", if (register) "Send verification code" else "Email me a code")
                }
                // Log in by code *and* set a password for a user who has none yet or has forgotten theirs.
                // Offered only where password login is on -- otherwise a password would be unusable.
                if (!register && config?.features?.passwordLogin == true) {
                    Button {
                        type = "link"
                        loading = busy
                        disabled = email.isBlank()
                        onClick = { sendCode(withPassword = true) }
                        +t("login", "sendCodeSetPassword", "Email me a code and set a password")
                    }
                }
            }
        } else {
            p {
                className = ClassName("subtitle")
                // Rendered as Markdown so the copy can set the address apart from the surrounding prose; the
                // substitution runs first, so an address is escaped as text rather than read as Markdown.
                MarkdownInline {
                    source = t(ns, "codeSent", $$"A code was sent to `${user.email}`.")
                        .evalTemplate(mapOf("user" to mapOf("email" to email.trim())))
                }
            }
            textField(
                t(ns, "codeLabel", "Verification code"), code, disabled = busy, autoComplete = AC.oneTimeCode,
            ) { code = it }

            // A password at this step is optional when registering (code login works without one) and required
            // when the round was started to set one.
            val wantsPassword = register || settingPassword
            if (wantsPassword) {
                val label = if (register) {
                    t("register", "passwordLabel", "Password (optional)")
                } else {
                    t("login", "newPasswordLabel", "New password")
                }
                textField(
                    label, password, isPassword = true, disabled = busy, autoComplete = AC.newPassword,
                ) { password = it }
                p {
                    className = ClassName("type-hint")
                    +if (register) {
                        t("register", "passwordHelp", "Optional -- you can add one later from your profile.")
                    } else {
                        t("login", "newPasswordHelp", "You can use it to sign in from this browser next time.")
                    }
                }
            }

            // The shared rule the backend enforces. Held back until they have typed something: it is a
            // correction, not an instruction, and an empty field has nothing to correct yet.
            val passwordError = if (wantsPassword && password.isNotEmpty()) passwordRuleError(password) else null
            // Registering with a password is optional -- an empty one is fine, a bad one is not.
            val passwordBlocks = passwordError != null || (settingPassword && password.isEmpty())

            div {
                className = ClassName("row")
                Button {
                    type = "primary"
                    loading = busy
                    disabled = code.isBlank() || passwordBlocks
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
                        settingPassword = false
                    }
                    +t("verify", "resend", "Send a new code")
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


