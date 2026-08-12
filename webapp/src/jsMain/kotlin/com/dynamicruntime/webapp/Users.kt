package com.dynamicruntime.webapp

import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
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
 * users in their own client, say -- the backend answers differently and this page needs no change.
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
    var draftLevel by useState(ROLE.user)
    var draftAllClients by useState(false)
    var draftOrg by useState("")
    var draftIsEntity by useState(false)
    var draftEntityName by useState("")
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
        draftLevel = user.level
        draftAllClients = user.roles.contains(ROLE.allClients)
        draftOrg = user.org ?: ""
        draftIsEntity = user.isEntity
        draftEntityName = user.entityName ?: ""
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
        draftLevel = ROLE.user
        draftAllClients = false
        draftOrg = config?.user?.org ?: ""
        draftIsEntity = false
        draftEntityName = ""
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
     * The role list this draft describes, given what [current] holds now: the level sets the rung (preserving
     * anything off the ladder) and the capability toggle is applied on top. Composing in that order is what
     * lets a level change keep a capability and a capability change keep a level.
     */
    fun draftRoles(current: List<String>): List<String> =
        rolesWithCapability(RoleLadder.rolesAtLevel(current, draftLevel), ROLE.allClients, draftAllClients)

    /**
     * Applies the draft. Only what actually changed is sent -- the backend has one call per concern (roles,
     * enabled), so an untouched field means no request rather than a redundant write.
     *
     * The note distinguishes the two outcomes rather than saying "Saved" either way. A confirmation that
     * appears when nothing was written is worse than none: it is indistinguishable from a real save, so a
     * change that silently failed to register still reads as success -- which is exactly how a control that
     * was not updating its draft went unnoticed.
     */
    fun save() = run {
        val target = editing
        if (target == null) {
            val created = AdminApi.createUser(
                draftEmail, draftUsername, draftRoles(emptyList()), draftOrg.trim().ifEmpty { null },
                isEntity = draftIsEntity, entityName = draftEntityName.trim().ifEmpty { null },
            )
            note = "Created ${created.primaryId}."
        } else {
            var changed = false
            // Compared as sets rather than by level, because the draft now carries two independent things: a
            // rung and a capability. Testing the level alone would silently drop a capability-only edit.
            val desired = draftRoles(target.roles)
            if (desired.toSet() != target.roles.toSet()) {
                AdminApi.setRoles(target.userId, desired)
                changed = true
            }
            if (draftOrg.trim().ifEmpty { null } != target.org) {
                AdminApi.setOrg(target.userId, draftOrg.trim().ifEmpty { null })
                changed = true
            }
            // Entity status and its name are one concern (clearing the flag also clears the name), so a change
            // to either sends the pair. The trimmed name is compared so whitespace-only edits are not a change.
            val draftEntity = draftEntityName.trim().ifEmpty { null }
            if (draftIsEntity != target.isEntity || (draftIsEntity && draftEntity != target.entityName)) {
                AdminApi.setEntity(target.userId, draftIsEntity, draftEntity)
                changed = true
            }
            if (draftEnabled != target.enabled) {
                AdminApi.setEnabled(target.userId, draftEnabled)
                changed = true
            }
            note = if (changed) "Saved ${target.primaryId}." else "No changes to ${target.primaryId}."
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
                span {
                    className = ClassName("field-label")
                    +"Access level"
                }
                // A single choice, not a checkbox each: the levels are rungs of an ordering, so holding two is
                // not a thing one can be. Picking a rung replaces the one below it (see [RoleLadder.rolesAtLevel]).
                Select {
                    value = draftLevel
                    options = accessLevelOptions
                    disabled = busy || self
                    style = js("({ minWidth: 180 })")
                    onChange = { v -> draftLevel = v as? String ?: ROLE.user }
                }
            }
            p {
                className = ClassName("type-hint")
                +accessLevelHint
            }

            // Offered only to a caller who holds the capability, because the backend refuses to let anyone
            // grant reach they do not have themselves -- a control that could only ever produce a 400 is the
            // advertise-versus-serve drift this codebase keeps removing, just relocated into a form.
            if (config?.user?.roles?.contains(ROLE.allClients) == true) {
                div {
                    className = ClassName("row")
                    Checkbox {
                        checked = draftAllClients
                        disabled = busy || self
                        onChange = { event -> draftAllClients = event.target.checked as Boolean }
                        +"All clients"
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +allClientsHint
                }
                // Granting the capability below the admin level is allowed and saves cleanly, and it also does
                // nothing -- the full-scope surface wants both. Without this the checkbox reads as conferring
                // reach it is not conferring, which is the advertise-versus-serve drift noted above, in the
                // one direction a hidden control cannot fix: here the state is legal and worth keeping.
                if (isAllClientsDormant(draftLevel, draftAllClients)) {
                    p {
                        className = ClassName("type-hint")
                        +allClientsDormantHint
                    }
                }
            }

            // Editable only by someone not confined to an organization: the backend lets a confined
            // administrator assign only their own, so anything else here could only ever produce a 400.
            if (config?.user?.org == null) {
                textField("Organization", draftOrg, disabled = busy) { draftOrg = it }
                p {
                    className = ClassName("type-hint")
                    +orgHint
                }
            } else {
                readOnlyField("Organization", draftOrg.ifEmpty { "—" })
            }

            // Business account: mark it, and give the business the name shown in place of a personal one.
            // Clearing the checkbox drops the name, matching the backend.
            div {
                className = ClassName("row")
                Checkbox {
                    checked = draftIsEntity
                    disabled = busy
                    onChange = { event -> draftIsEntity = event.target.checked as Boolean }
                    +"Business account"
                }
            }
            if (draftIsEntity) {
                textField("Business name", draftEntityName, disabled = busy) { draftEntityName = it }
                p {
                    className = ClassName("type-hint")
                    +entityNameHint
                }
            }

            div {
                className = ClassName("row")
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
                    placeholder = "Email, username, or business name"
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

/**
 * The access levels an administrator can assign, lowest first -- the ladder's rungs, labeled for a person.
 * Built from [RoleLadder.ordered] so a rung added to the ladder cannot be silently missing here; an unlabeled
 * one falls back to its role name rather than vanishing from the list.
 */
private val accessLevelLabels = mapOf(
    ROLE.user to "User",
    ROLE.operator to "Operator",
    ROLE.admin to "Administrator",
)

private val accessLevelOptions: Array<dynamic> = RoleLadder.ordered.map { role ->
    val obj: dynamic = js("({})")
    obj.label = accessLevelLabels[role] ?: role
    obj.value = role
    obj
}.toTypedArray()

/**
 * Says what the capability does, and that it is a different axis from the level -- the distinction the whole
 * scope design rests on, and the one a checkbox beside a dropdown will not convey on its own.
 */
private const val allClientsHint =
    "Access level says what someone may do; this says whose data they may do it to. Without it an " +
        "administrator manages only their own client. It is also what the full-scope admin endpoints require."

/**
 * Shown when the capability is checked below the Administrator level, where it is stored but inert. Says that
 * it is kept rather than dropped, because that is the part an operator cannot see and would otherwise have to
 * discover by re-granting it after a promotion.
 */
private const val allClientsDormantHint =
    "Dormant below Administrator: the full-scope endpoints require the access level as well as this. " +
        "It is kept, and takes effect if the level is raised."

/** Explains the optional middle width of the scope, and what leaving it blank means. */
private const val orgHint =
    "An optional organization within the client. Someone assigned one sees only their own organization, " +
        "plus anything belonging to no organization at all. Leave it blank for client-wide."

/** Explains what the business name is for, and that it is display copy rather than an identifier. */
private const val entityNameHint =
    "Shown in place of a personal name for a business account. It need not be unique, and is not a login."

/** Says what the middle rung is for, since "operator" does not explain itself the way the other two do. */
private const val accessLevelHint =
    "An operator can reach the operator endpoints (system diagnostics); an administrator can do that and " +
        "manage users. Each level includes the ones below it."
