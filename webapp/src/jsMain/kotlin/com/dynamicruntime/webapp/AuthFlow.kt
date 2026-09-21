package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.passwordRuleError
import com.dynamicruntime.common.util.evalTemplate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the auth flow's suspend backend calls. */
private val authScope = MainScope()

external interface AuthFlowProps : Props {
    /** "login", "register", or "claim" (an account created for you, issue #751). */
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
    // Claiming an account somebody created for you (issue #751): the address plus the client and persona the
    // invitation named, typed rather than chosen, then a mailed code -- the login flow aimed at one named user.
    val claim = props.mode == HMENU.pageClaim
    // The plain login mode: the one with the password path, the set-a-password code, and Google sign-in --
    // none of which belongs on the register or claim pages.
    val login = !register && !claim

    var config by useState<AuthConfig?>(null)
    var copy by useState(Copy.empty)
    var email by useState("")
    var password by useState("")
    var code by useState("")
    // Business-account registration (entity accounts). Both reset on a mode change, below.
    var isEntity by useState(false)
    var name by useState("")
    // Where the new account goes (issue #751), for an allClients administrator only: sent only when set, so an
    // ordinary registration is unchanged. The client list is fetched for that caller alone (it is a cross-client
    // question the endpoint answers only to them).
    var placeClient by useState("")
    var placePersona by useState("")
    var placePersonId by useState("")
    var clientChoices by useState<List<ClientChoice>>(emptyList())
    // The claim's key beside the address: the client, and the persona as typed (`admin`, `member B`).
    var claimClient by useState("")
    var claimPersona by useState("")
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
                // Recover a stale build id (a rolling deploy) rather than erroring on a healthy runtime (#469).
                copy = fetchCopyWithRetry(c.fragment) { runCatching { AuthApi.fetchConfig().fragment }.getOrNull() }
                if (register && c.user.roles.contains(ROLE.allClients)) {
                    clientChoices = runCatching { AdminApi.listClients() }.getOrDefault(emptyList())
                }
            } catch (e: Throwable) {
                error = userFacingError(e)
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
        isEntity = false
        name = ""
        placeClient = ""
        placePersona = ""
        placePersonId = ""
        claimClient = ""
        claimPersona = ""
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

    val ns = when {
        register -> "register"
        claim -> "claim"
        else -> "login"
    }

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
            when {
                register -> AuthApi.sendVerifyNewContact(id, tk)
                claim -> AuthApi.sendClaimCode(id, claimClient, claimPersona, tk)
                else -> AuthApi.sendVerifyUser(id, tk, withPassword)
            }
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
                    val userId = AuthApi.createInitial(
                        id, tk, code.trim(),
                        client = placeClient.ifEmpty { null }, persona = placePersona.ifEmpty { null },
                        personId = placePersonId.trim().ifEmpty { null },
                    )
                    AuthApi.finishRegistration(
                        userId, tk, code.trim(), password.ifEmpty { null },
                        isEntity = isEntity, name = name.trim().ifEmpty { null },
                    )
                }
                // The claim: registers the named user if nobody has, and signs in as it either way.
                claim -> AuthApi.claimAccount(id, claimClient, claimPersona, tk, code.trim())
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

    /**
     * Completes a Google sign-in with the ID token Google's button produced. The same call serves both modes:
     * the backend creates the account on a first sign-in, so "register with Google" and "log in with Google"
     * are one path and the button reads the same in either mode.
     */
    fun submitGoogle(credential: String) = run {
        AuthApi.loginByGoogle(credential)
        goHomeSignedIn()
    }

    div {
        className = ClassName("card")
        h1 { +t(ns, "title", if (register) "Create your account" else "Log in") }
        error?.let { errorText(it) }

        val codeSent = token != null

        if (claim) {
            p {
                className = ClassName("subtitle")
                +t("claim", "intro", "Somebody created an account for you and told you the client and the persona. Enter them with your email address, and a code will be sent to that address.")
            }
        }
        // The email (login id) is always shown; it locks once a code has been sent.
        textField(
            t(ns, "emailLabel", "Email address"), email, disabled = busy || codeSent,
            autoComplete = AC.username,
        ) { email = it }
        // The claim's key (issue #751): typed, never offered -- an anonymous caller is told nothing about which
        // clients exist or what personas they have. Locked with the address once the code is on its way, since
        // the code was computed over all of them.
        if (claim) {
            textField(t("claim", "clientLabel", "Client"), claimClient, disabled = busy || codeSent) { claimClient = it }
            textField(t("claim", "personaLabel", "Persona"), claimPersona, disabled = busy || codeSent) { claimPersona = it }
            p {
                className = ClassName("type-hint")
                +t("claim", "personaHelp", "As the invitation gave it: `admin`, or `member B` when it named a person id. Leave blank for `member`.")
            }
        }

        // What kind of account this is, and its name. Register mode only, and both stay visible through the
        // code step so the choice is not lost when the email field locks. The name is asked of everyone -- a
        // person has a full name just as a business has a business name -- and the checkbox relabels the one
        // field rather than revealing a second, which is the rule the app displays by (UserProfile.displayName).
        if (register) {
            div {
                className = ClassName("row")
                Checkbox {
                    checked = isEntity
                    disabled = busy
                    onChange = { event -> isEntity = event.target.checked as Boolean }
                    +t("register", "isEntityLabel", "This is a business account")
                }
            }
            textField(
                if (isEntity) t("register", "businessNameLabel", "Business name")
                else t("register", "nameLabel", "Full name"),
                name, disabled = busy,
            ) { name = it }
            p {
                className = ClassName("type-hint")
                +t("register", "nameHelp", "The name shown for your account. It need not be unique.")
            }
            // Placing the new account (issue #751): an allClients administrator registering somebody -- or
            // themself again -- somewhere other than `public`. Unset, the registration is the ordinary one.
            if (config?.user?.roles?.contains(ROLE.allClients) == true) {
                if (clientChoices.isNotEmpty()) {
                    div {
                        className = ClassName("row")
                        span { className = ClassName("field-label"); +t("register", "clientLabel", "Client") }
                        Select {
                            value = placeClient.ifEmpty { null }
                            options = clientOptions(clientChoices)
                            disabled = busy || codeSent
                            allowClear = true
                            placeholder = "public"
                            style = js("({ minWidth: 180 })")
                            onChange = { v -> placeClient = v as? String ?: "" }
                        }
                    }
                }
                div {
                    className = ClassName("row")
                    span { className = ClassName("field-label"); +t("register", "personaLabel", "Persona") }
                    Select {
                        value = placePersona.ifEmpty { null }
                        options = personaOptions()
                        disabled = busy || codeSent
                        allowClear = true
                        placeholder = PERSONA.label(PERSONA.member)
                        style = js("({ minWidth: 180 })")
                        onChange = { v -> placePersona = v as? String ?: "" }
                    }
                }
                textField(t("register", "personIdLabel", "Person id"), placePersonId, disabled = busy || codeSent) { placePersonId = it }
                p {
                    className = ClassName("type-hint")
                    +t("register", "provisionHelp", "As an administrator across clients, you may place the new account. Leave these alone for an ordinary registration.")
                }
            }
        }

        if (!codeSent) {
            // Login-only password path, when the deployment enables it.
            if (login && config?.features?.passwordLogin == true) {
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
                if (login && config?.features?.passwordLogin == true) {
                    Button {
                        type = "link"
                        loading = busy
                        disabled = email.isBlank()
                        onClick = { sendCode(withPassword = true) }
                        +t("login", "sendCodeSetPassword", "Email me a code and set a password")
                    }
                }
            }

            // Google sign-in, when the deployment configured it. Placed after the email paths and set off by a
            // divider: it is an alternative to the whole email flow above, not another button within it. Not
            // on the claim page: Google lands on the rule-chosen user, never on a named one.
            val googleCfg = config
            if (!claim && googleCfg != null && googleCfg.features.googleLogin && googleCfg.googleClientId.isNotEmpty()) {
                p {
                    className = ClassName("type-hint")
                    +t(ns, "orDivider", "or")
                }
                GoogleSignInButton {
                    clientId = googleCfg.googleClientId
                    onCredential = { submitGoogle(it) }
                }
            }
        } else {
            p {
                className = ClassName("subtitle")
                // Rendered as Markdown so the copy can set the address apart from the surrounding prose; the
                // substitution runs first, so an address is escaped as text rather than read as Markdown.
                //
                // Login hedges on purpose (issues #275, #565): the backend answers an unknown address exactly as
                // a known one, so this copy cannot promise a code was sent -- it says to check the address
                // instead, vaguely, since naming the no-account case here would be the very oracle #275 closed.
                // Register's address has no account by definition, so its copy can say what it did.
                MarkdownInline {
                    source = t(
                        ns, "codeSent",
                        when {
                            register -> $$"A code was sent to `${user.email}`."
                            claim -> $$"If `${user.email}` has that account, a code is on its way. Check the address, the client and the persona."
                            else -> $$"If `${user.email}` has an account, a code is on its way. Check that the address is correct."
                        },
                    ).evalTemplate(mapOf("user" to mapOf("email" to email.trim())))
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
                    +t(ns, "finish", when {
                        register -> "Create account"
                        claim -> "Claim and sign in"
                        else -> "Log in"
                    })
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
                    // Named as the way back, not only a resend (issue #565): it unlocks the address too, and an
                    // address that got no code is exactly when someone needs to know that.
                    +t("verify", "resend", "Change the address or send a new code")
                }
            }
            passwordError?.let {
                p {
                    className = ClassName("error-text")
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

        // Switch between the modes: register and claim offer the way back to login; login offers both.
        div {
            className = ClassName("row")
            when {
                register || claim -> Button {
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
            if (!register && !claim) {
                Button {
                    type = "link"
                    onClick = { navigateHash(listOf("page" to HMENU.pageClaim)) }
                    +t("claim", "loginLink", "Claim an account created for you")
                }
            }
        }
    }
}


