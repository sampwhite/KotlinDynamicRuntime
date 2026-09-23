package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.user.UserChoice
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

    /**
     * The name field's draft. Seeded from the loaded config rather than held independently, so a refresh bump
     * (or a change made elsewhere) re-seeds it instead of leaving a stale value sitting in the field.
     */
    var draftName by useState("")
    // The person's users (issue #752), for offering to remove a `public` placeholder once they hold a user in a
    // real client; and which removal is armed (a two-step confirm, since it is permanent).
    var myUsers by useState<List<UserChoice>>(emptyList())
    var confirmingRemove by useState<Long?>(null)
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
    useEffect(config) { draftName = config?.user?.name ?: "" }

    useEffect(generation) {
        profileScope.launch { myUsers = runCatching { AuthApi.fetchUsers() }.getOrDefault(emptyList()) }
        loadConfig { c ->
            profileScope.launch {
                // Recover a stale build id (a rolling deploy) via the shared retry, rather than silently
                // falling back to Copy.empty and showing every label's hardcoded default forever (#469).
                copy = runCatching {
                    fetchCopyWithRetry(c.fragment) { runCatching { ProfileApi.fetchConfig().fragment }.getOrNull() }
                }.getOrDefault(Copy.empty)
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

    /**
     * Saves the name the caller is shown under (issue #323). A blank value clears it, which is a legitimate
     * thing to want -- it falls the display back to the login name -- so it is not treated as an empty form.
     */
    fun saveName() = run {
        ProfileApi.setName(draftName.trim())
        note = if (draftName.isBlank()) {
            t("profile", "nameCleared", "Your name was cleared.")
        } else {
            t("profile", "nameSaved", "Your name was saved.")
        }
        // Bump so the app bar follows: it shows the same displayName this page just changed.
        bump()
    }

    fun removePassword() = run {
        ProfileApi.clearPassword()
        note = t("password", "removedNote", "Your password was removed. You can still sign in with a code.")
        bump()
    }

    /**
     * Removes [target], one of the person's `public` users (issue #752). Removing the user the session acts as
     * moves the session to another of theirs, so the whole app reloads, as after a switch; removing another
     * only needs the page and the badge to re-read.
     */
    fun removePublic(target: UserChoice) = run {
        AuthApi.removePublicUser(target.userId)
        confirmingRemove = null
        if (target.isCurrent) {
            navigateHash(emptyList())
            reloadWebApp()
        } else {
            note = t("placeholder", "removedNote", "The public account was removed.")
            bump()
        }
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
                // The entity's business name when this is an entity account, the personal name otherwise --
                // one rule, on UserProfile.displayName, shared with the app bar (entity accounts).
                source = t("profile", "signedInAs", $$"Signed in as **${user.publicName}**")
                    .evalTemplate(mapOf("user" to mapOf("publicName" to (user?.displayName ?: "your account"))))
            }
        }

        // The account's email address -- the identity the display name sits on top of, and the one thing not
        // otherwise visible on this page once a name is set. It is the login id (`loginId` = the primary
        // contact), rendered in a code span like the "we emailed a code to ..." line uses, so it reads as an
        // address rather than prose. Absent only for an account with no primary contact, which the page has
        // no other business showing.
        config?.loginId?.takeIf { it.isNotBlank() }?.let { email ->
            p {
                className = ClassName("subtitle")
                MarkdownInline {
                    source = t("profile", "emailLine", $$"Email: `${user.email}`")
                        .evalTemplate(mapOf("user" to mapOf("email" to email)))
                }
            }
        }

        // Sits with the identity line, not among the password controls: what you are called is not a
        // credential, which is also why saving it needs no emailed code.
        h2 { +t("profile", "nameTitle", "Your name") }
        p {
            className = ClassName("subtitle")
            +t(
                "profile", "nameHelp",
                "The name you are shown under. It need not be unique, and is not how you sign in. " +
                    "Leave it empty to be shown by your login email instead.",
            )
        }
        textField(t("profile", "nameLabel", "Name"), draftName, disabled = busy) { draftName = it }
        div {
            className = ClassName("row")
            Button {
                type = "primary"
                loading = busy
                disabled = draftName.trim() == (config?.user?.name ?: "")
                onClick = { saveName() }
                +t("profile", "nameSave", "Save name")
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
            }
        }

        // The `public` placeholder (issue #752): a person may remove their `public` users, up to and including the
        // last of everything -- they registered themselves, so they can register again. The copy says which.
        val removable = removablePublicUsers(myUsers)
        if (removable.isNotEmpty()) {
            h2 { +t("placeholder", "title", "Your public account") }
            p {
                className = ClassName("subtitle")
                +if (removalEndsEverything(myUsers)) {
                    t(
                        "placeholder", "helpOnly",
                        "This is your only account. Removing it permanently deletes your registration: you are " +
                            "signed out, and your email address is free to register again.",
                    )
                } else {
                    t(
                        "placeholder", "help",
                        "You registered before being placed anywhere, so you have an account in the public " +
                            "placeholder client. You can remove it. This is permanent, and your other accounts are " +
                            "not affected.",
                    )
                }
            }
            for (target in removable) {
                val label = target.qualifierWithin(myUsers).ifEmpty { target.label() }
                div {
                    className = ClassName("row")
                    if (confirmingRemove == target.userId) {
                        Button {
                            danger = true
                            disabled = busy
                            onClick = { removePublic(target) }
                            +(t("placeholder", "confirm", "Permanently remove") + " [$label]")
                        }
                        Button {
                            disabled = busy
                            onClick = { confirmingRemove = null }
                            +t("placeholder", "cancel", "Cancel")
                        }
                    } else {
                        Button {
                            disabled = busy
                            onClick = { confirmingRemove = target.userId }
                            +(t("placeholder", "remove", "Remove") + " [$label]")
                        }
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

/**
 * The person's users the profile page offers to remove (issue #752): every `public` one. A person may remove
 * their placeholder users down to nothing, since they registered themselves and can register again. [users] is
 * the switcher's list (registered, enabled). Pure, covered under `jsNodeTest`.
 */
fun removablePublicUsers(users: List<UserChoice>): List<UserChoice> = users.filter { it.client == CL.public }

/**
 * Whether removing a `public` user would leave the person with no user at all (issue #752) -- when it is the
 * only one in [users] -- so the page can say that the whole registration goes, and the session with it. Pure,
 * covered under `jsNodeTest`.
 */
fun removalEndsEverything(users: List<UserChoice>): Boolean = users.size == 1
