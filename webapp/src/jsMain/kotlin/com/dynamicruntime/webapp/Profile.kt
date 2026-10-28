package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.UserChoice
import com.dynamicruntime.common.user.passwordRuleError
import com.dynamicruntime.common.user.personaTerm
import com.dynamicruntime.common.util.evalTemplate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
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
    // The person's users (issues #752, #1330): the table of them, from which the person switches, chooses the one a
    // sign-in lands on, or removes a `public` one; and which removal is armed (a two-step confirm, it being permanent).
    var myUsers by useState<List<UserChoice>>(emptyList())
    var confirmingRemove by useState<Long?>(null)
    val generation = useRefreshGeneration()
    val bump = useRefreshBump()
    val shellFacts = useShellFacts()

    /** Loads (or reloads) the config; the page re-reads it after a password change, so the copy follows. */
    fun loadConfig(onLoaded: (ProfileConfig) -> Unit = {}) {
        profileScope.launch {
            when (val loaded = apiResult { ProfileApi.fetchConfig() }) {
                is ApiResult.Ok -> {
                    config = loaded.value
                    onLoaded(loaded.value)
                }
                // The config is login-required, so the overwhelmingly likely failure is "not logged in" -- and with
                // no answer at all there is nothing here to show either.
                else -> navigate(listOf("page" to "login"))
            }
        }
    }

    // Seeded from the stored name, and again only when that changes (a save) -- never from a re-read, which a refresh
    // makes whether or not anything changed and which would wipe a name being typed (issue #1313).
    val storedName = config?.user?.name
    useEffect(storedName) { draftName = storedName ?: "" }

    // Re-read the config (and its copy) on every refresh generation (issue #115) -- on mount, and whenever a
    // navigation or state mutation bumps it, so a password change here reloads the page's affordances.
    useEffect(generation) {
        profileScope.launch { myUsers = apiResult { AuthApi.fetchUsers() }.valueOr(emptyList()) }
        loadConfig { c ->
            profileScope.launch {
                // Recover a stale build id (a rolling deploy) via the shared retry, rather than silently
                // falling back to Copy.empty and showing every label's hardcoded default forever (#469).
                copy = apiResult {
                    fetchCopyWithRetry(c.fragment) { apiResult { ProfileApi.fetchConfig().fragment }.valueOrNull() }
                }.valueOr(Copy.empty)
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
            if (cfg.features.codeReadBack) {
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
     * moves the session to another of theirs, so the app goes home and re-reads, as after a switch (issue #1015);
     * removing another only needs the page and the badge to re-read.
     */
    fun removePublic(target: UserChoice) = run {
        AuthApi.removePublicUser(target.userId)
        confirmingRemove = null
        if (target.isCurrent) {
            afterSessionChange(bump)
        } else {
            note = t("placeholder", "removedNote", "The public account was removed.")
            bump()
        }
    }

    /**
     * Becomes [target] and **stays on this page** (issue #1330): a fresh session as that user, then a bump, so the
     * page, the table and the bar re-read as the new user. From inside a sandbox the switch is the leave (issue #1197),
     * as the bar's switcher makes it.
     */
    fun switchTo(target: UserChoice) = run {
        if (shellFacts.inSandbox) AuthApi.leaveSandbox(target.userId) else AuthApi.switchUser(target.userId)
        confirmingRemove = null
        note = null
        afterSessionChange(bump, listOf(HP.page to HMENU.pageProfile))
    }

    /** Chooses [target] as the user a sign-in by the address lands on (issue #1330); the bar's list follows the bump. */
    fun makeDefault(target: UserChoice) = run {
        myUsers = AuthApi.setDefaultUser(target.userId)
        note = t("users", "nextSignInSet", "Your next sign-in now takes you to that user.")
        bump()
    }

    fun logout() = run {
        AuthApi.logout()
        navigate(emptyList())
        bump()
    }

    /**
     * The person's users as a table (issue #1330): each one's persona and client, the active one highlighted, the one
     * a sign-in lands on marked, and on each row what may be done with it -- switch to it (staying on this page),
     * make it the one a sign-in lands on, or remove it when it is a `public` one (issue #752, two steps, since it is
     * permanent). The help above it says what these users are, filled from the table's own facts.
     */
    fun ChildrenBuilder.usersSection() {
        val rows = profileUserRows(myUsers)
        val nameShown = showsNameColumn(myUsers)
        // The status stays in view; the concept behind it -- why one sign-in reaches several users -- is a click away.
        WhatIsThis {
            heading = t("users", "title", "Your users")
            text = copy.explanation("users")
        }
        div {
            className = ClassName("subtitle")
            Markdown {
                // The copy may pull the flavor's addition (`users.helpExtra`) with `${@t(...)}`, resolved against this
                // page's own copy -- empty as shipped, and a reference deployment's overlay fills it.
                source = t("users", "help", usersHelpDefault)
                    .evalTemplate(mapOf("users" to usersHelpData(myUsers)), resolver = copy.fragmentResolver())
            }
        }
        // Scrolls inside the card rather than spilling over its edge when the window is too narrow for its columns.
        div {
            className = ClassName("profile-users-scroll")
            table {
                className = ClassName("op-table profile-users")
                thead {
                    tr {
                        th { +t("users", "personaColumn", "Persona") }
                        th { +t("users", "statusColumn", "Status") }
                        th { +t("users", "clientColumn", "Client") }
                        if (nameShown) th { +t("users", "nameColumn", "Name") }
                        th { +"" }
                    }
                }
                tbody {
                    for (row in rows) {
                        val target = row.user
                        tr {
                            if (row.isActive) className = ClassName("profile-user-active")
                            td { +row.label }
                            // The marks have a column of their own, side by side, so a row keeps to one line (issue #1445).
                            td {
                                if (row.isActive) span {
                                    className = ClassName("user-mark active")
                                    +t("users", "activeMark", "Active now")
                                }
                                if (row.signsInAs) span {
                                    className = ClassName("user-mark")
                                    +t("users", "nextSignInMark", "Next sign-in")
                                }
                            }
                            td {
                                span {
                                    title = target.client
                                    +row.clientLabel
                                }
                            }
                            if (nameShown) td { +(target.name ?: "") }
                            td {
                                div {
                                    className = ClassName("profile-user-actions")
                                    if (confirmingRemove == target.userId) {
                                        Button {
                                            danger = true
                                            size = "small"
                                            disabled = busy
                                            onClick = { removePublic(target) }
                                            +t("placeholder", "confirm", "Permanently remove")
                                        }
                                        Button {
                                            size = "small"
                                            disabled = busy
                                            onClick = { confirmingRemove = null }
                                            +t("placeholder", "cancel", "Cancel")
                                        }
                                    } else {
                                        if (row.canSwitch) {
                                            Button {
                                                size = "small"
                                                disabled = busy
                                                onClick = { switchTo(target) }
                                                +t("users", "switch", "Switch to this user")
                                            }
                                        }
                                        if (row.canMakeDefault) {
                                            Button {
                                                size = "small"
                                                disabled = busy
                                                onClick = { makeDefault(target) }
                                                +t("users", "useForNextSignIn", "Use for next sign-in")
                                            }
                                        }
                                        if (row.canRemove) {
                                            Button {
                                                size = "small"
                                                disabled = busy
                                                onClick = { confirmingRemove = target.userId }
                                                +t("placeholder", "remove", "Remove")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // A `public` user's removal is permanent, so what it costs is said beside the table -- the whole registration
        // when it is the person's only user (issue #752).
        if (rows.any { it.canRemove }) {
            p {
                className = ClassName("type-hint")
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
        }
    }

    div {
        // Wider while the users table shows (issue #1330): its columns and a row of actions do not fit a form's card.
        // Wide for a table: the person's users, or their API keys (issue #1402), which every profile outside a sandbox shows.
        className = ClassName(if (showsUsersTable(myUsers) || !shellFacts.inSandbox) "card profile-wide" else "card")
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

        // The client this user belongs to (issue #1330), by its name -- what a person calls it -- with the id when it has
        // none. Taken from the person's own entry in their users, which carries the name; the profile carries the id.
        val clientId = user?.client?.ifBlank { null }
        if (clientId != null) {
            p {
                className = ClassName("subtitle")
                MarkdownInline {
                    source = t("profile", "clientLine", $$"Client: **${user.clientName}**")
                        .evalTemplate(mapOf("user" to mapOf("clientName" to profileClientName(myUsers, clientId))))
                }
            }
        }

        // The person's users (issues #1330, #752): who they can act as, which one they are, and which one a sign-in
        // lands on -- the thing the person most often finds confusing -- with the switch, the default and a `public`
        // user's removal on each row. Shown when there is more than one user, or a `public` one to remove; a person
        // with one ordinary user has nothing to choose, and the client line above says where they are.
        if (showsUsersTable(myUsers)) usersSection()

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

        // The person's API keys (issue #1402), for programs that act as them. Not in a sandbox, whose users take their
        // standing from the person's users outside it -- which a program with nobody signed in could not ask about.
        if (!shellFacts.inSandbox) ApiKeysSection {}

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

/**
 * The help over the users table when the page's copy has not arrived (issue #1330): which user is active, and which
 * one a sign-in lands on. The shipped copy is `users.help` in the `profile` fragment file, which
 * also pulls the flavor's addition; this fallback pulls nothing, there being no copy to pull from.
 */
private const val usersHelpDefault =
    $$"You are acting as **${users.active}**. Next sign-in takes you to **${users.signsInAs}**."

/** One row of the profile's users table (issue #1330): the user, how it reads, its marks, and what it offers. */
class ProfileUserRow(
    val user: UserChoice,
    /** The persona and suffix, as the badge words a user: `Member`, `Member B`, `Admin`. */
    val label: String,
    /** The client, by its name when it has one. */
    val clientLabel: String,
    /** The user the session is acting as. */
    val isActive: Boolean,
    /** The user a sign-in by the address lands on. */
    val signsInAs: Boolean,
    val canSwitch: Boolean,
    /** Whether "Use for next sign-in" is offered: on every row a sign-in does not already reach. */
    val canMakeDefault: Boolean,
    /** A `public` user, which the person may remove (issue #752). */
    val canRemove: Boolean,
)

/** The users table's rows (issue #1330), in the list's order. Pure, covered under `jsNodeTest`. */
fun profileUserRows(users: List<UserChoice>): List<ProfileUserRow> = users.map { u ->
    ProfileUserRow(
        user = u,
        label = personaTerm(u.persona, u.personaSuffix),
        clientLabel = u.clientName?.ifBlank { null } ?: u.client,
        isActive = u.isCurrent,
        signsInAs = u.signsInAs,
        canSwitch = !u.isCurrent,
        canMakeDefault = !u.signsInAs && users.size > 1,
        canRemove = u.client == CL.public,
    )
}

/**
 * Whether the profile shows the users table (issue #1330): when the person has more than one user to tell apart, or
 * a `public` one they may remove. A person with one ordinary user has nothing to choose. Pure, covered under
 * `jsNodeTest`.
 */
fun showsUsersTable(users: List<UserChoice>): Boolean = users.size > 1 || removablePublicUsers(users).isNotEmpty()

/**
 * What the users table's help is filled from (issue #1330): `active`, the user the session is acting as, and
 * `signsInAs`, the one a sign-in lands on -- each as `Member B in Acme`. The one a sign-in lands on falls back to the
 * active one when the list marks none. Pure, covered under `jsNodeTest`.
 */
fun usersHelpData(users: List<UserChoice>): Map<String, Any?> {
    fun phrase(u: UserChoice?) = u?.let { "${personaTerm(it.persona, it.personaSuffix)} in ${it.clientName?.ifBlank { null } ?: it.client}" } ?: ""
    val active = users.firstOrNull { it.isCurrent }
    return mapOf("active" to phrase(active), "signsInAs" to phrase(users.firstOrNull { it.signsInAs } ?: active))
}

/**
 * The name of the client [clientId] the profile's user belongs to (issue #1330): from the person's entry for it in
 * [users], which carries the client's name, else the id. Pure, covered under `jsNodeTest`.
 */
fun profileClientName(users: List<UserChoice>, clientId: String): String =
    users.firstOrNull { it.isCurrent && it.client == clientId }?.clientName?.ifBlank { null } ?: clientId

/**
 * Whether the users table shows a Name column (issue #1330): only when the person's users are not all named alike --
 * each user has its own profile, so its own name -- since a column repeating one name on every row tells nothing.
 * Pure, covered under `jsNodeTest`.
 */
fun showsNameColumn(users: List<UserChoice>): Boolean = users.map { it.name?.trim().orEmpty() }.distinct().size > 1
