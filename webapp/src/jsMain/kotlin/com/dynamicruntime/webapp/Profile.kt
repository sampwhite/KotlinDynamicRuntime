package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import com.dynamicruntime.common.user.passwordRuleError
import com.dynamicruntime.common.user.personaTerm
import com.dynamicruntime.common.util.evalTemplate
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.ul
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
    // Which panel's action is under way (a [PRFP] id, or [logoutWork]): every control waits while one runs, and only
    // that panel's own button spins.
    var working by useState<String?>(null)
    val busy = working != null
    var devFilled by useState(false)
    // Each panel's own result (rule 3 of #1477), drawn inside it, never in one line shared by the page.
    var usersError by useState<DisplayError?>(null)
    var usersNote by useState<String?>(null)
    var nameError by useState<DisplayError?>(null)
    var nameNote by useState<String?>(null)
    var signInError by useState<DisplayError?>(null)
    var signInNote by useState<String?>(null)
    var logoutError by useState<DisplayError?>(null)

    /**
     * The name field's draft. Seeded from the loaded config rather than held independently, so a refresh bump
     * (or a change made elsewhere) re-seeds it instead of leaving a stale value sitting in the field.
     */
    var draftName by useState("")
    // The stored name the draft was last seeded from: until the seed has caught up with what is stored, the draft is not
    // an edit, so a name arriving does not flash as unsaved for the render before its seed.
    var nameSeededFrom by useState<String?>(null)
    // The person's users (issues #752, #1330): the table of them, from which the person switches, chooses the one a
    // sign-in lands on, or removes a `public` one; and which removal is armed (a two-step confirm, it being permanent).
    var myUsers by useState<List<UserChoice>>(emptyList())
    // Whether the users have been read once, so the page draws the structure line and the client by their names rather
    // than by ids for a moment first.
    var usersLoaded by useState(false)
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
    val storedName = config?.user?.name.orEmpty()
    useEffect(storedName) {
        draftName = storedName
        nameSeededFrom = storedName
    }

    // Re-read the config (and its copy) on every refresh generation (issue #115) -- on mount, and whenever a
    // navigation or state mutation bumps it, so a password change here reloads the page's affordances.
    useEffect(generation) {
        profileScope.launch {
            myUsers = apiResult { AuthApi.fetchUsers() }.valueOr(emptyList())
            usersLoaded = true
        }
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

    /**
     * Runs a "suspend" [block] for the panel [panelId], every control waiting while it runs, its failure told to
     * [onError] -- the panel it was asked from.
     */
    fun run(panelId: String, onError: (DisplayError) -> Unit, block: suspend () -> Unit) {
        working = panelId
        profileScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                onError(userFacingError(e))
            } finally {
                working = null
            }
        }
    }

    /** Step one of a password change: email a code, framed for setting a password. */
    fun sendCode() {
        val cfg = config ?: return
        signInError = null
        signInNote = null
        run(PRFP.signIn, { signInError = it }) {
            val tk = AuthApi.createToken()
            AuthApi.sendVerifyUser(cfg.loginId, tk, addPassword = true)
            // The dev read-back is a convenience: when it fails, the code is typed from the mail as anywhere else, and
            // the step still opens -- a failure here is no failure to save a password.
            val devCode = if (cfg.features.codeReadBack) {
                try {
                    AuthApi.fetchDevCode(cfg.loginId)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            devCode?.let {
                code = it
                devFilled = true
            }
            token = tk
        }
    }

    /** Takes the password step back to its start: no code, no password typed. */
    fun resetPasswordStep() {
        token = null
        code = ""
        password = ""
        devFilled = false
    }

    /** Step two: save the new password against the emailed code. */
    fun savePassword() {
        val cfg = config ?: return
        val tk = token ?: return
        signInError = null
        run(PRFP.signIn, { signInError = it }) {
            AuthApi.setPassword(cfg.loginId, password, tk, code.trim())
            resetPasswordStep()
            signInNote = t("password", "saved", "Your password was saved. You can use it to sign in from this browser next time.")
            // Bump instead of reloading by hand: the generation effect above re-reads config + copy, and the
            // account menu follows too (issue #115).
            bump()
        }
    }

    /**
     * Saves the name the caller is shown under (issue #323). A blank value clears it, which is a legitimate
     * thing to want -- it falls the display back to the login name -- so it is not treated as an empty form.
     */
    fun saveName() {
        nameError = null
        nameNote = null
        run(PRFP.name, { nameError = it }) {
            ProfileApi.setName(draftName.trim())
            nameNote = if (draftName.isBlank()) {
                t("profile", "nameCleared", "Your name was cleared.")
            } else {
                t("profile", "nameSaved", "Your name was saved.")
            }
            // Bump so the app bar follows: it shows the same displayName this page just changed.
            bump()
        }
    }

    fun removePassword() {
        signInError = null
        signInNote = null
        run(PRFP.signIn, { signInError = it }) {
            ProfileApi.clearPassword()
            signInNote = t("password", "removedNote", "Your password was removed. You can still sign in with a verification code.")
            bump()
        }
    }

    /**
     * Removes [target], one of the person's `public` users (issue #752). Removing the user the session acts as
     * moves the session to another of theirs, so the app goes home and re-reads, as after a switch (issue #1015);
     * removing another only needs the page and the badge to re-read.
     */
    fun removePublic(target: UserChoice) {
        usersError = null
        usersNote = null
        run(PRFP.users, { usersError = it }) {
            AuthApi.removePublicUser(target.userId)
            confirmingRemove = null
            if (target.isCurrent) {
                afterSessionChange(bump)
            } else {
                usersNote = t("placeholder", "removedNote", "The public account was removed.")
                bump()
            }
        }
    }

    /**
     * Becomes [target] and **stays on this page** (issue #1330): a fresh session as that user, then a bump, so the
     * page, the table and the bar re-read as the new user. From inside a sandbox the switch is the leave (issue #1197),
     * as the bar's switcher makes it. Every panel's result is of the user left behind, so each is cleared.
     */
    fun switchTo(target: UserChoice) {
        usersError = null
        usersNote = null
        nameNote = null
        nameError = null
        signInNote = null
        signInError = null
        run(PRFP.users, { usersError = it }) {
            if (shellFacts.inSandbox) AuthApi.leaveSandbox(target.userId) else AuthApi.switchUser(target.userId)
            confirmingRemove = null
            afterSessionChange(bump, listOf(HP.page to HMENU.pageProfile))
        }
    }

    /** Chooses [target] as the user a sign-in by the address lands on (issue #1330); the bar's list follows the bump. */
    fun makeDefault(target: UserChoice) {
        usersError = null
        usersNote = null
        run(PRFP.users, { usersError = it }) {
            myUsers = AuthApi.setDefaultUser(target.userId)
            usersNote = t("users", "nextSignInSet", "Your next sign-in now takes you to that user.")
            bump()
        }
    }

    fun logout() {
        logoutError = null
        run(logoutWork, { logoutError = it }) {
            AuthApi.logout()
            navigate(emptyList())
            bump()
        }
    }

    /**
     * The person's users as a table (issue #1330): each one's persona and client, the active one highlighted, the one
     * a sign-in lands on marked, and on each row what may be done with it -- switch to it (staying on this page),
     * make it the one a sign-in lands on, or remove it when it is a `public` one (issue #752, two steps, since it is
     * permanent). The help above it says what these users are, filled from the table's own facts; [roles] are the
     * active user's as the page describes them ([profileShownRoles]).
     */
    fun ChildrenBuilder.usersTable(roles: Set<String>) {
        val rows = profileUserRows(myUsers, roles)
        val nameShown = showsNameColumn(myUsers)
        div {
            className = ClassName("subtitle")
            Markdown {
                // The copy may pull the flavor's addition (`users.helpExtra`) with `${@t(...)}`, resolved against this
                // page's own copy -- empty as shipped, and a reference deployment's overlay fills it.
                source = t("users", "help", usersHelpDefault)
                    .evalTemplate(mapOf("users" to usersHelpData(myUsers, roles)), resolver = copy.fragmentResolver())
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
        panelNote(usersError, usersNote)
    }

    /**
     * Setting, changing, or removing a password (issue #96): the status, then "Email me a code" and "Remove password",
     * which act at once -- the removal asking first (rule 6 of #1477) -- then, once a code is sent, the code and the new
     * password, saved by the panel's one Save.
     */
    fun ChildrenBuilder.signInBody(cfg: ProfileConfig) {
        val has = cfg.features.hasPassword
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
                    loading = working == PRFP.signIn
                    disabled = busy
                    onClick = { sendCode() }
                    // The button says what the code is for -- on its own it reads as unrelated to passwords.
                    +if (has) {
                        t("password", "sendCodeChange", "Email me a code so I can change my password")
                    } else {
                        t("password", "sendCodeSet", "Email me a code so I can set a password")
                    }
                }
            }
            if (has) {
                DangerConfirm {
                    recordKey = cfg.user.userId.toString()
                    actionText = t("password", "remove", "Remove password") + "…"
                    question = t(
                        "password", "removeQuestion",
                        "Remove your password? You can still sign in with a verification code, and set a password again later.",
                    )
                    confirmText = t("password", "removeConfirm", "Remove password")
                    keepText = t("password", "keep", "Keep password")
                    inProgress = working == PRFP.signIn
                    onConfirm = { removePassword() }
                }
            }
            panelNote(signInError, signInNote)
        } else {
            p {
                className = ClassName("subtitle")
                MarkdownInline {
                    source = t("password", "codeSent", $$"We emailed a verification code to `${user.email}`.")
                        .evalTemplate(mapOf("user" to mapOf("email" to cfg.loginId)))
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

            // The shared rule the backend enforces, surfaced as a correction below the field rather than an
            // instruction under it -- and only once there is something to correct. Further rules (an upper-case
            // character, a digit) will arrive through the same one message.
            val passwordRule = if (password.isEmpty()) null else passwordRuleError(password)
            passwordRule?.let {
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
            SaveBar {
                saveText = t("password", "save", "Save password")
                discardText = t("password", "cancel", "Cancel")
                saving = working == PRFP.signIn
                canSave = !busy && password.isNotEmpty() && code.isNotBlank() && passwordRule == null
                failure = signInError
                failureLead = t("password", "saveFailed", "Couldn't save your password.")
                onSave = { savePassword() }
                onDiscard = {
                    resetPasswordStep()
                    signInError = null
                }
            }
        }
    }

    div {
        // Wide for its tables (issues #1330, #1402): the person's users, and their API keys, which every profile shows
        // -- a sandbox's too (issue #1453) -- so the card is always wide.
        className = ClassName("card profile-wide")
        h1 { +t("profile", "title", "Your profile") }

        val cfg = config
        val user = cfg?.user
        // Drawn once the users are in too, so the structure line and the client are named rather than shown by id first.
        if (cfg != null && user != null && usersLoaded) {
            val roles = profileShownRoles(user)
            // How the account is put together (rule 7 of #1477), before any panel: the address, the person's users, and
            // the access of the one acted as.
            structureLine(profileStructure(cfg.loginId, myUsers, user), t("profile", "structureLabel", "How your account is put together"))

            val usersShown = showsUsersTable(myUsers)
            val signInShown = cfg.features.canSetPassword
            val panels = buildList {
                add(PRFP.account to t(PRFA.account, "title", "Your account"))
                if (usersShown) add(PRFP.users to t("users", "title", "Your users"))
                add(PRFP.name to t("profile", "nameTitle", "Your name"))
                if (signInShown) add(PRFP.signIn to t("password", "title", "Sign-in"))
                add(PRFP.keys to t("profile", "keysTitle", "API keys"))
            }
            panelLinks(panels)

            // ---- Your account: read-only (issue #1479) ---------------------
            panel(PRFP.account, t(PRFA.account, "title", "Your account")) {
                // A removal that leaves one ordinary user takes the users panel away with it, so its note is said here.
                if (!usersShown) panelNote(null, usersNote)
                // What the shell says of the caller, only once it is about this user: before it has been told, or for a
                // moment after a switch, it would describe somebody else.
                val facts = shellFacts.takeIf { it.describes(user.userId) } ?: ShellFacts()
                val words: (String, String) -> String = { key, shipped -> t(PRFA.account, key, shipped) }
                for (fact in profileAccountFacts(user, cfg.loginId, myUsers, facts, cfg.account, words = words)) {
                    if (fact.key == PRFA.timeZone && !shellFacts.describes(user.userId)) continue
                    readOnlyField(t(PRFA.account, fact.key, profileAccountDefault(fact.key)), fact.value)
                }
                if (shellFacts.describes(user.userId)) {
                    h3 { +t(PRFA.account, "canDoTitle", "What you can do here") }
                    ul {
                        className = ClassName("profile-abilities")
                        for (ability in profileAbilities(shellFacts.reach, roles, user.org)) {
                            li {
                                key = ability.unsafeCast<Key>()
                                +t(PRFA.account, ability, profileAccountDefault(ability))
                            }
                        }
                    }
                }
            }

            // ---- Your users: live (issues #1330, #752) ---------------------
            //
            // Who the person can act as, which one they are, and which one a sign-in lands on -- the thing the person
            // most often finds confusing -- with the switch, the default and a `public` user's removal on each row.
            // Shown when there is more than one user, or a `public` one to remove; a person with one ordinary user has
            // nothing to choose, and the account panel says where they are.
            if (usersShown) {
                panel(PRFP.users, t("users", "title", "Your users"), copy.explanation("users"), PanelKind.live) {
                    usersTable(roles)
                }
            }

            // ---- Your name: a draft (issue #323) ---------------------------
            //
            // Its own panel, not among the password controls: what you are called is not a credential, which is also
            // why saving it needs no emailed code.
            val nameDirty = nameSeededFrom == storedName && draftName.trim() != storedName
            panel(PRFP.name, t("profile", "nameTitle", "Your name"), kind = PanelKind.draft, dirty = nameDirty) {
                p {
                    className = ClassName("subtitle")
                    +t(
                        "profile", "nameHelp",
                        "The name you are shown under. It need not be unique, and is not how you sign in. " +
                            "Leave it empty to be shown by your login email instead.",
                    )
                }
                // A business account's name is its business's, as the Users editor labels it.
                val nameLabel = if (user.isEntity) {
                    t("profile", "businessNameLabel", "Business name")
                } else {
                    t("profile", "nameLabel", "Name")
                }
                textField(nameLabel, draftName, disabled = busy) {
                    draftName = it
                    nameNote = null
                }
                SaveBar {
                    saveText = t("profile", "nameSave", "Save name")
                    saving = working == PRFP.name
                    canSave = nameDirty && !busy
                    failure = nameError
                    failureLead = t("profile", "nameFailed", "Couldn't save your name.")
                    onSave = { saveName() }
                    onDiscard = {
                        draftName = storedName
                        nameError = null
                    }
                }
                panelNote(null, nameNote)
            }

            // ---- Sign-in: live, then a draft once a code is sent (issue #96) --
            //
            // Asking for a code and removing the password act at once; the code and the new password then wait for the
            // panel's one Save, so the panel is a draft only while that step is open.
            if (signInShown) {
                val drafting = token != null
                panel(
                    PRFP.signIn, t("password", "title", "Sign-in"),
                    kind = if (drafting) PanelKind.draft else PanelKind.live,
                    dirty = drafting && password.isNotEmpty(),
                ) {
                    signInBody(cfg)
                }
            }

            // ---- API keys: live (issue #1402) ------------------------------
            //
            // For programs that act as the person -- in a sandbox too (issue #1453), where a key acts with the standing
            // the sandbox's rules give this user, as a session does, and stays in the sandbox.
            panel(PRFP.keys, t("profile", "keysTitle", "API keys"), kind = PanelKind.live) {
                ApiKeysSection {}
            }
        }

        // The page's way out, like a back link at a page's top, belongs to no panel (rule 1 of #1477).
        div {
            className = ClassName("row")
            Button {
                type = "link"
                loading = working == logoutWork
                disabled = busy
                onClick = { logout() }
                +t("profile", "logout", "Log out")
            }
        }
        logoutError?.let { errorText(t("profile", "logoutFailed", "Couldn't log out."), it) }
    }
}

/** What [Profile]'s working marker names while a log out runs: no panel's. */
private const val logoutWork = "logout"

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

/**
 * The users table's rows (issue #1330), in the list's order. The session's [roles] speak for the active row only
 * (issue #1450): its persona says the access it has when that differs from the persona's, as the app bar's badge
 * does. Pure, covered under `jsNodeTest`.
 */
fun profileUserRows(users: List<UserChoice>, roles: Collection<String>? = null): List<ProfileUserRow> = users.map { u ->
    ProfileUserRow(
        user = u,
        label = PERSONA.withActing(personaTerm(u.persona, u.personaSuffix), u.persona, roles.takeIf { u.isCurrent }),
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
 * active one when the list marks none. The session's [roles] speak for the active user (issue #1450): `Member
 * (Administrator access) in Acme`. Pure, covered under `jsNodeTest`.
 */
fun usersHelpData(users: List<UserChoice>, roles: Collection<String>? = null): Map<String, Any?> {
    fun phrase(u: UserChoice?) = u?.let {
        val term = PERSONA.withActing(personaTerm(it.persona, it.personaSuffix), it.persona, roles.takeIf { _ -> it.isCurrent })
        "$term in ${it.clientName?.ifBlank { null } ?: it.client}"
    } ?: ""
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
