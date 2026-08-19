package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.util.isEmailAddress
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the users page's suspend backend calls. */
private val usersScope = MainScope()

/** What identifies a users-page destination: which record the editor is open on. Drafts never appear here. */
private val userIdentity = setOf(HP.user)

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
    // True once the hash has been read for a record to open; until then the URL is not written back.
    var restored by useState(false)

    // The editor's draft. Nothing here reaches the backend until Save.
    var draftEmail by useState("")
    var draftLevel by useState(ROLE.user)
    var draftAllClients by useState(false)
    var draftOrg by useState("")
    var draftClient by useState("")
    /** The clients a full-scope administrator may create into; empty for everybody else, who get no choice. */
    var clientChoices by useState<List<ClientChoice>>(emptyList())
    var draftIsEntity by useState(false)
    var draftName by useState("")
    var draftEnabled by useState(true)

    // Whether the permanent-delete danger button has been armed -- a two-step confirm, since there is no
    // Popconfirm wrapper and an irreversible delete is the one action here a stray click must not perform.
    var confirmingDelete by useState(false)

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
                // Only a full-scope administrator is offered a choice of client, and only they can ask for the
                // list -- it is a cross-client question. A failure leaves the list empty, which falls back to
                // the read-only field rather than an error: the client is not the reason they came here.
                if (c.user.roles.contains(ROLE.allClients)) {
                    clientChoices = runCatching { AdminApi.listClients() }.getOrDefault(emptyList())
                }
            }
        }
    }

    /** Seeds the draft from [user]'s current state, or empties it for a user being created. */
    fun seedDraft(user: AdminUser?) {
        draftEmail = user?.primaryId ?: ""
        draftLevel = user?.level ?: ROLE.user
        draftAllClients = user?.roles?.contains(ROLE.allClients) == true
        // A new user starts in the editor's own organization; an existing one shows theirs, blank included.
        draftOrg = (if (user != null) user.org else config?.user?.org) ?: ""
        // The client works the same way, and is the one field with no edit form at all: it is fixed once the
        // user exists, so an existing row shows theirs read-only.
        draftClient = (if (user != null) user.client else config?.user?.client) ?: ""
        draftIsEntity = user?.isEntity == true
        draftName = user?.name ?: ""
        draftEnabled = user?.enabled ?: true
        confirmingDelete = false
        note = null
        error = null
    }

    /** Opens the editor on [user], seeding the draft from their current state. */
    fun startEdit(user: AdminUser) {
        editing = user
        creating = false
        seedDraft(user)
    }

    /** Opens the editor on a new user. */
    fun startCreate() {
        editing = null
        creating = true
        seedDraft(null)
    }

    fun closeEditor() {
        editing = null
        creating = false
        error = null
    }

    // --- the editor as a place you can come back to (issue #324) ---------------------------------------
    //
    // Opening a user used to be React state and nothing else, so it left no history entry: Back from the
    // editor skipped the whole page and landed wherever you were before arriving at Users. The hash now says
    // which record is open, exactly as the endpoint catalog says which endpoint is.

    /** Who the editor is open on, as the hash should say it: a user id, `new`, or nothing in the list view. */
    fun openRecord(): String? = when {
        creating -> HP.newRecord
        else -> editing?.userId?.toString()
    }

    /** The rows the listener can resolve against, read through a ref (it is registered once). */
    val usersRef = useRef<List<AdminUser>>(emptyList())
    usersRef.current = users

    /** Opens the editor on what the hash names, or closes it. Used by the hashchange listener below. */
    fun applyHash() {
        val open = hashParams()[HP.user]
        when {
            open == null -> closeEditor()
            open == HP.newRecord -> startCreate()
            else -> {
                // Resolved against the rows we hold: there is no fetch-one call, and the draft is seeded from
                // a row in any case. A record we cannot find leaves the editor shut rather than half-open --
                // and `reachable` below keeps that from being read as a navigation.
                val found = usersRef.current?.firstOrNull { it.userId.toString() == open }
                if (found != null) startEdit(found) else closeEditor()
            }
        }
    }

    useEffectOnce {
        onHashChange { applyHash() }
    }

    // Open whatever the hash named once the rows are in -- a reload inside the editor, or a link. Until then
    // the hash cannot be honoured, so `restored` also holds the sync effect below: without that gate it would
    // run first with an empty editor and push the record out of the URL before anything could read it.
    useEffect(loaded) {
        if (loaded && !restored) {
            applyHash()
            restored = true
        }
    }

    // Keep the hash in step with the editor -- the same arrangement the endpoint catalog uses, and for the
    // same reason: opening a record is a navigation, so it earns a history entry to come back from.
    useEffect(editing, creating, restored) {
        if (!restored) {
            return@useEffect
        }
        val params = buildList {
            add(HP.page to HMENU.pageUsers)
            openRecord()?.let { add(HP.user to it) }
        }
        // Reachable means the hash as it stands names something this page could show. A `u=` naming a row we
        // do not hold is a URL to correct in place -- otherwise Back onto it would push again and never move.
        val current = hashParams()[HP.user]
        val reachable = current == null || current == HP.newRecord ||
            users.any { it.userId.toString() == current }
        applyHashWrite(params, userIdentity, reachable)
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
            // Caught here so a mistyped address is an immediate, local message rather than a round trip -- the
            // backend runs this same check (base/kernel), so it is the authority; this only spares the trip.
            val email = draftEmail.trim()
            if (!email.isEmailAddress()) {
                error = DisplayError.expected("\"$email\" is not a valid email address.")
                return@run
            }
            val created = AdminApi.createUser(
                email, username = null, roles = draftRoles(emptyList()),
                org = draftOrg.trim().ifEmpty { null },
                isEntity = draftIsEntity, name = draftName.trim().ifEmpty { null },
                client = draftClient.trim().ifEmpty { null },
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
            // The name and what kind of account it is travel together: the flag labels the name rather than
            // selecting a different field, so one call carries both. The trimmed name is compared so
            // whitespace-only edits are not a change.
            val draftNameValue = draftName.trim().ifEmpty { null }
            if (draftNameValue != target.name || draftIsEntity != target.isEntity) {
                AdminApi.setName(target.userId, draftNameValue, draftIsEntity)
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

    /**
     * Permanently deletes the user being edited: the email and identity are obfuscated irrecoverably. Armed
     * by [confirmingDelete] so the danger button is a deliberate two-step, not a single stray click. The
     * recoverable "delete" is the Enabled checkbox, not this.
     */
    fun performDelete() = run {
        val target = editing ?: return@run
        val result = AdminApi.deleteUser(target.userId, permanent = true)
        note = "Permanently deleted ${target.primaryId}: ${result.primaryId}."
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

            if (editing?.deleted == true) {
                // A permanently-deleted tombstone: nothing to edit, and re-enabling it is precisely the bug
                // this read-only view exists to prevent. Show what survives and stop -- the backend refuses an
                // edit either way (loadEditableUser), so this is a courtesy over an enforced rule. The login
                // identity is obfuscated; the name and organization are kept so the record stays recognizable
                // to somebody debugging what the account owned.
                readOnlyField("Email address", draftEmail)
                readOnlyField("Id", editing?.userId?.toString() ?: "")
                readOnlyField(
                    if (editing?.isEntity == true) "Business name" else "Full name",
                    editing?.name?.takeIf { it.isNotBlank() } ?: "\u2014",
                )
                editing?.org?.takeIf { it.isNotBlank() }?.let { readOnlyField("Organization", it) }
                p {
                    className = ClassName("subtitle")
                    +("This account was permanently deleted: its email was obfuscated and freed for reuse, and " +
                        "it cannot be recovered, edited, or re-enabled. The name is kept for reference.")
                }
            } else {
            if (creating) {
                textField("Email address", draftEmail, disabled = busy, autoComplete = AC.username) {
                    draftEmail = it
                }
                p {
                    className = ClassName("type-hint")
                    +"A user created here skips email verification: the address is taken as already confirmed."
                }
            } else {
                // Identity is display-only: the backend offers no rename, and showing an editable field that
                // silently discards its value would be worse than showing none.
                readOnlyField("Email address", draftEmail)
                readOnlyField("Id", editing?.userId?.toString() ?: "")
            }

            // What kind of account this is, and its name. Sits with identity rather than down beside the
            // authority controls, because that is what it is. One name field for both kinds: the checkbox
            // labels it rather than revealing a second field, which is the same rule the backend applies
            // (see UserProfile.displayName) -- so unticking it reclassifies the name instead of losing it.
            div {
                className = ClassName("row")
                Checkbox {
                    checked = draftIsEntity
                    disabled = busy
                    onChange = { event -> draftIsEntity = event.target.checked as Boolean }
                    +"Business account"
                }
            }
            textField(
                if (draftIsEntity) "Business name" else "Full name", draftName, disabled = busy,
            ) { draftName = it }
            p {
                className = ClassName("type-hint")
                +nameHint
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

            // Chosen at creation and never again (issue #352). A user's content carries their client both in
            // its `client` column and inside every gedra id, so moving one would strand it -- there is no
            // set-client call for an editor to offer, which is why this is a selector on create and plain text
            // afterward. Offered only to a caller holding the capability, for the same reason the checkbox
            // above is: a scoped administrator naming another client could only ever produce a 400.
            if (creating && clientChoices.isNotEmpty()) {
                div {
                    className = ClassName("row")
                    span {
                        className = ClassName("field-label")
                        +"Client"
                    }
                    Select {
                        value = draftClient
                        options = clientOptions(clientChoices)
                        disabled = busy
                        style = js("({ minWidth: 180 })")
                        onChange = { v -> draftClient = v as? String ?: draftClient }
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +clientHint
                }
            } else {
                readOnlyField("Client", draftClient.ifEmpty { "—" })
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

            div {
                className = ClassName("row")
                Checkbox {
                    checked = draftEnabled
                    disabled = busy || self
                    onChange = { event -> draftEnabled = event.target.checked as Boolean }
                    +"Enabled"
                }
            }
            p {
                className = ClassName("type-hint")
                // Names what unchecking this actually is, so it is not confused with -- or duplicated by --
                // the permanent delete below. This is the recoverable half of "deleting" a user.
                +("Unchecking disables the account — a recoverable delete: the user cannot sign in, but you " +
                    "can re-enable them here. To remove the account for good, use Delete user below.")
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

            // ---- permanent delete (edit mode only, never your own account) ---
            //
            // Deliberately *only* the irreversible delete: the recoverable "delete" is the Enabled checkbox
            // above, so offering it a second time here as a plain disable would be the same action twice.
            if (editing != null && !self) {
                p {
                    className = ClassName("type-hint")
                    +("Delete permanently: the email is obfuscated and freed for reuse, and the account cannot " +
                        "be recovered. To only suspend the user, uncheck Enabled instead.")
                }
                div {
                    className = ClassName("row")
                    if (!confirmingDelete) {
                        Button {
                            danger = true
                            disabled = busy
                            onClick = { confirmingDelete = true }
                            +"Delete user…"
                        }
                    } else {
                        Button {
                            danger = true
                            loading = busy
                            onClick = { performDelete() }
                            +"Confirm permanent delete"
                        }
                        Button {
                            type = "link"
                            disabled = busy
                            onClick = { confirmingDelete = false }
                            +"Keep user"
                        }
                    }
                }
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
                    // Names what the console actually shows. The backend still matches a username too, so
                    // pasting a known one finds the account -- but this page displays none, so advertising it
                    // would point at something you cannot see here.
                    placeholder = "Email or name"
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
                    showClient = config?.user?.roles?.contains(ROLE.allClients) == true
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
 * The selector's options, built per render from what the backend served rather than from a fixed list -- the
 * clients a deployment carries are configuration, and this page has no business having an opinion about them.
 */
private fun clientOptions(choices: List<ClientChoice>): Array<dynamic> = choices.map { choice ->
    val obj: dynamic = js("({})")
    obj.label = clientChoiceLabel(choice)
    obj.value = choice.clientId
    obj
}.toTypedArray()

/** Says why the choice is offered here and nowhere else. */
private const val clientHint =
    "Which client the new user belongs to. It cannot be changed afterward: their content carries the " +
        "client, so moving them would leave it behind."

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

/** Explains what the name is for, and that it is display copy rather than an identifier. */
private const val nameHint =
    "The name shown for this account. It need not be unique, and is not a login -- the email address and " +
        "username remain the identifiers."

/** Says what the middle rung is for, since "operator" does not explain itself the way the other two do. */
private const val accessLevelHint =
    "An operator can reach the operator endpoints (system diagnostics); an administrator can do that and " +
        "manage users. Each level includes the ones below it."
