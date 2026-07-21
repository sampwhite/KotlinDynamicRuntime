package com.dynamicruntime.webapp

import com.dynamicruntime.common.http.request.ROLE
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the users page's suspend backend calls. */
private val usersScope = MainScope()

/**
 * User administration, in two views: **find** a user, then **edit** one.
 *
 * The list is a search result, not a form: rows are plain text and clicking one opens the editor for that user.
 * Editing collects changes in a draft and sends them when you press Save, so a half-finished edit is never
 * written -- the earlier version applied each checkbox the instant it was clicked, which made "change two
 * things" two irreversible writes and left no way to back out. Cancel simply drops the draft.
 *
 * Whether this page is offered at all is the *backend's* call: the shell's UI-config advertises a
 * `canManageUsers` capability for the current caller and only then includes the Users item in the menu. This
 * page reads the same flag, so arriving by a bookmarked `#page=users` gives an honest "not available" instead
 * of a wall of failed requests. Neither is enforcement -- every call sits behind the `admin` section and 401s
 * regardless of what the frontend believes.
 *
 * The capability is deliberately not "is an admin". When it grows narrower -- someone administering only the
 * users in their own account, say -- the backend answers differently and this page needs no change.
 */
val Users = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var users by useState<List<AdminUser>>(emptyList())
    var search by useState("")
    var loaded by useState(false)
    var busy by useState(false)
    var error by useState<DisplayError?>(null)
    var note by useState<String?>(null)

    // The user being edited, or null in the list view. `creating` opens the same editor with an empty draft.
    var editing by useState<AdminUser?>(null)
    var creating by useState(false)

    // The editor's draft. Nothing here reaches the backend until Save.
    var draftEmail by useState("")
    var draftUsername by useState("")
    var draftAdmin by useState(false)
    var draftEnabled by useState(true)

    val generation = useRefreshGeneration()
    // Guards against out-of-order search responses: only the newest request may publish its results.
    val searchSeq = useRef(0)
    // Pending debounce timer id, so a fast typist makes one request rather than one per keystroke.
    val searchTimer = useRef<Int>(0)

    /** Runs a backend [block] with busy/error bookkeeping. Used by the actions, never by the search field. */
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        usersScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Runs a search. Deliberately *not* through [run]: that sets `busy`, which disables the controls -- and
     * disabling the very input being typed into blurs it, so every keystroke cost the field its focus.
     */
    fun runSearch(term: String) {
        searchSeq.current = (searchSeq.current ?: 0) + 1
        val seq = searchSeq.current
        usersScope.launch {
            try {
                val found = AdminApi.listUsers(term)
                // A slower earlier request must not overwrite a newer one's results.
                if (seq == searchSeq.current) {
                    users = found
                    loaded = true
                    error = null
                }
            } catch (e: Throwable) {
                if (seq == searchSeq.current) error = userFacingError(e)
            }
        }
    }

    /** Debounces [runSearch] so typing does not fire a request per character. */
    fun scheduleSearch(term: String) {
        searchTimer.current?.let { clearTimer(it) }
        searchTimer.current = setTimer({ runSearch(term) }, searchDebounceMs)
    }

    useEffect(generation) {
        usersScope.launch {
            val c = runCatching { HomeApi.fetchConfig() }.getOrNull()
            config = c
            if (c?.canManageUsers == true) {
                runSearch(search)
            }
        }
    }

    /** Opens the editor on [user], seeding the draft from their current state. */
    fun startEdit(user: AdminUser) {
        editing = user
        creating = false
        draftEmail = user.primaryId
        draftUsername = user.username
        draftAdmin = user.isAdmin
        draftEnabled = user.enabled
        note = null
        error = null
    }

    /** Opens the editor on a new user. */
    fun startCreate() {
        editing = null
        creating = true
        draftEmail = ""
        draftUsername = ""
        draftAdmin = false
        draftEnabled = true
        note = null
        error = null
    }

    fun closeEditor() {
        editing = null
        creating = false
        error = null
    }

    /**
     * Applies the draft. Only what actually changed is sent -- the backend has one call per concern (roles,
     * enabled), so an untouched field means no request rather than a redundant write.
     */
    fun save() = run {
        val target = editing
        if (target == null) {
            val created = AdminApi.createUser(draftEmail, draftUsername, if (draftAdmin) adminRoles else null)
            note = "Created ${created.primaryId}."
        } else {
            if (draftAdmin != target.isAdmin) {
                AdminApi.setRoles(target.userId, target.rolesWithAdmin(draftAdmin))
            }
            if (draftEnabled != target.enabled) {
                AdminApi.setEnabled(target.userId, draftEnabled)
            }
            note = "Saved ${target.primaryId}."
        }
        closeEditor()
        runSearch(search)
    }

    val denied = config?.canManageUsers == false
    val inEditor = creating || editing != null

    div {
        className = ClassName("card wide")

        if (denied) {
            h1 { +"Users" }
            p {
                className = ClassName("subtitle")
                +"You do not have permission to manage users."
            }
        } else if (inEditor) {
            // ---- editor -----------------------------------------------------
            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    disabled = busy
                    onClick = { closeEditor() }
                    +"← Back to users"
                }
            }
            h1 { +if (creating) "Create a user" else "Edit user" }

            error?.let { errorText(it) }

            if (creating) {
                textField("Email address", draftEmail, disabled = busy, autoComplete = AC.username) {
                    draftEmail = it
                }
                textField("Username (optional)", draftUsername, disabled = busy) { draftUsername = it }
                p {
                    className = ClassName("type-hint")
                    +"A user created here skips email verification: the address is taken as already confirmed."
                }
            } else {
                // Identity is display-only: the backend offers no rename, and showing an editable field that
                // silently discards its value would be worse than showing none.
                readOnlyField("Email address", draftEmail)
                readOnlyField("Username", draftUsername)
                readOnlyField("Id", editing?.userId?.toString() ?: "")
            }

            // Editing yourself: the backend refuses to let anyone change their own administrator status or
            // disable their own account, so those controls are locked here rather than offered and then
            // rejected. The backend remains the enforcement point -- this only keeps the UI honest.
            val self = editing != null && editing?.userId == config?.user?.userId

            div {
                className = ClassName("row")
                Checkbox {
                    checked = draftAdmin
                    disabled = busy || self
                    onChange = { event -> draftAdmin = event.target.checked as Boolean }
                    +"Administrator"
                }
                Checkbox {
                    checked = draftEnabled
                    disabled = busy || self
                    onChange = { event -> draftEnabled = event.target.checked as Boolean }
                    +"Enabled"
                }
            }
            if (self) {
                p {
                    className = ClassName("type-hint")
                    +"This is your own account: another administrator has to change your role or disable you."
                }
            }

            div {
                className = ClassName("row")
                Button {
                    type = "primary"
                    loading = busy
                    disabled = creating && draftEmail.isBlank()
                    onClick = { save() }
                    +"OK"
                }
                Button {
                    type = "link"
                    disabled = busy
                    onClick = { closeEditor() }
                    +"Cancel"
                }
            }
        } else {
            // ---- find -------------------------------------------------------
            h1 { +"Users" }
            p {
                className = ClassName("subtitle")
                +"Search for a user and select them to edit, or create a new one."
            }

            error?.let { errorText(it) }
            note?.let {
                p {
                    className = ClassName("form-ok")
                    +it
                }
            }

            div {
                className = ClassName("row")
                span {
                    className = ClassName("field-label")
                    +"Search"
                }
                Input {
                    value = search
                    placeholder = "Email or username"
                    // Never disabled: this field must keep focus while its own results are loading.
                    onChange = { event ->
                        val term = event.target.value as String
                        search = term
                        scheduleSearch(term)
                    }
                }
                Button {
                    onClick = { startCreate() }
                    +"Create user"
                }
            }

            if (loaded && users.isEmpty()) {
                p {
                    className = ClassName("subtitle")
                    +if (search.isBlank()) "No users yet." else "No users match \"$search\"."
                }
            } else {
                UserTable {
                    this.users = users
                    onSelect = { startEdit(it) }
                }
            }
        }
    }
}

/** A label plus a static value, for the identity fields the backend does not let an administrator change. */
private fun react.ChildrenBuilder.readOnlyField(label: String, value: String) {
    div {
        className = ClassName("row")
        span {
            className = ClassName("field-label")
            +label
        }
        span { +value }
    }
}

/** How long to wait after the last keystroke before searching. */
private const val searchDebounceMs = 250

/** The browser's `setTimeout`/`clearTimeout`, declared so the debounce above does not reach for a DOM wrapper. */
private fun setTimer(block: () -> Unit, delayMs: Int): Int = js("setTimeout(block, delayMs)") as Int

private fun clearTimer(id: Int) {
    js("clearTimeout(id)")
}

/** The roles a user created as an administrator gets: the base role plus admin (the backend requires both). */
private val adminRoles = listOf(ROLE.user, ROLE.admin)
