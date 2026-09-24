package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.user.AERR
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.PERSONASUFFIX
import com.dynamicruntime.common.user.USF
import com.dynamicruntime.common.user.UserFilterKind
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.user.userLabelChoices
import com.dynamicruntime.common.user.userSearchFieldSpecs
import com.dynamicruntime.common.user.userSearchFieldSpecsByName
import com.dynamicruntime.common.user.userSortKeys
import com.dynamicruntime.common.util.isEmailAddress
import com.dynamicruntime.common.util.normalizeEmail
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
 * of a wall of failed requests. Neither is enforcement -- every call goes through `AdminApi` to the
 * `clientAdmin` section (issue #466), which refuses a caller without the role (401 anonymous, 403 logged in
 * without it) regardless of what the frontend believes.
 *
 * The capability is deliberately not "is an admin", and the narrowing is not hypothetical: a client-scoped
 * administrator already reaches this page, and the backend confines what they see to their own client (or the
 * org within it) rather than showing an error -- so this page needs no branch on who is asking.
 */
val Users = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var users by useState<List<AdminUser>>(emptyList())
    // The search filters (issue #411), held generically by field name so the panel renders from the shared spec
    // (the SDUI extra credit): `textFilters` holds the substring/exact terms (email, name, client), `rangeFilters`
    // the date-range bounds (update time). A field added to `userSearchFieldSpecs` flows through here untouched.
    var textFilters by useState<Map<String, String>>(emptyMap())
    var rangeFilters by useState<Map<String, DateRange>>(emptyMap())
    // The sort, driven by the table's column headers. Default: newest first, as the issue specifies.
    var sortBy by useState(defaultUserSortKey)
    var descending by useState(defaultUserSortDescending)
    // Whether the filter well is open (issue #683). View state, not search state: it is not in the URL, so a
    // shared link arrives with the well closed and the filters it carries said as chips. Tuple form so the
    // toggle is a functional update (`{ !it }`), the convention App.kt documents for its own counters.
    val (filtersOpen, setFiltersOpen) = useState(false)
    // What the last search reported: how many matched in all, and whether the cap hid some.
    var numAvailable by useState(0)
    var hasMore by useState(false)
    var loaded by useState(false)
    var busy by useState(false)
    var error by useState<DisplayError?>(null)
    var note by useState<String?>(null)

    // The user being edited, or null in the list view. `creating` opens the same editor with an empty draft.
    var editing by useState<AdminUser?>(null)
    var creating by useState(false)
    // The person behind the user being edited (issue #770): their identity's facts and the users of it this
    // administrator may see. Loaded when the editor opens on a user, and dropped when it closes.
    var identity by useState<AdminIdentity?>(null)
    // A create for the caller's own address (issue #797): the address is theirs and locked, and the user is
    // registered on creation with no invitation -- the backend's own-address rule, which this only names.
    var creatingForSelf by useState(false)
    // The caller's own address, from the profile config (its login id is the primary address); null until
    // known, and the "for me" button waits for it.
    var ownAddress by useState<String?>(null)
    // Read through a ref by [startCreate] and [applyEditorHash]: the hashchange listener is registered once,
    // so it would otherwise see the null this held on the first render (the same reason [usersRef] exists).
    val ownAddressRef = useRef<String>(null)
    ownAddressRef.current = ownAddress
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
    // The user's free-form labels (issue #786), and what each client suggests -- keyed by client, since a
    // full-scope administrator edits users in many clients and each suggests its own. Keyed rather than one
    // "current" list so a slow reply can only ever fill in *its own* client: opening an acme user and then a globex
    // one cannot leave acme's suggestions on the globex editor. It also means a client is fetched once per page.
    var draftLabels by useState<List<String>>(emptyList())
    val (labelSuggestions, setLabelSuggestions) = useState<Map<String, List<String>>>(emptyMap())
    // Read through a ref by [startEdit], which the once-registered hashchange listener reaches -- it would otherwise
    // see the empty map of the first render and refetch on every open (the reason [usersRef] exists, too).
    val labelSuggestionsRef = useRef<Map<String, List<String>>>(emptyMap())
    labelSuggestionsRef.current = labelSuggestions
    // The persona and personaSuffix (issue #750): chosen at creation, shown read-only afterward. The suffix box
    // is on every create form (issue #797): a first "Member B" needs it as much as a second one does. A create
    // that collides with an existing user of the same address, client and persona sets [personaSuffixCollided],
    // which turns the box's hint into the reason it is now needed.
    var draftPersona by useState(PERSONA.member)
    var draftPersonaSuffix by useState("")
    var personaSuffixCollided by useState(false)
    // Whether the administrator chose the persona themselves. Until they do, it follows the access level
    // (roles provide a default persona); once they do, the level may still move without dragging it back --
    // and only a chosen persona is sent, so the backend applies the same rule to an unnamed one.
    var personaChosen by useState(false)

    // Whether the permanent-delete danger button has been armed -- a two-step confirm, since there is no
    // Popconfirm wrapper and an irreversible delete is the one action here a stray click must not perform.
    var confirmingDelete by useState(false)

    val generation = useRefreshGeneration()
    val bump = useRefreshBump()
    // Guards against out-of-order search responses: only the newest request may publish its results.
    val searchSeq = useRef(0)
    // Pending debounce timer id, so a fast typist makes one request rather than one per keystroke.
    val searchTimer = useRef<Int>(0)
    // The latest query intended, updated on every change. A debounced text search must fire with *this* rather
    // than the query captured when it was scheduled: a sort click landing during the debounce updates it, and
    // firing the stale capture would re-fetch the old sort and clobber the sort the user just chose.
    val queryRef = useRef(UserSearchQuery())

    /** Runs a backend [block] with busy/error bookkeeping. Used by the actions, never by the search field. */
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        usersScope.launch {
            try {
                block()
                // Every action here can change what the caller may switch to (issue #797) -- a user created,
                // disabled or deleted at their own address -- and the badge reads that from the shell config,
                // which re-reads on a bump. Bumping after any success rather than working out which did is the
                // refresh bus's intent: a bump that changed nothing costs one re-fetch.
                bump()
            } catch (e: Throwable) {
                error = userFacingError(e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * A [UserSearchQuery] from the current filter and sort state, with any part overridable. Handlers pass the
     * value they are changing explicitly (`query(texts = next)`) rather than relying on the just-set state,
     * which has not landed yet inside the same event -- the classic React stale-closure trap.
     */
    fun query(
        texts: Map<String, String> = textFilters, ranges: Map<String, DateRange> = rangeFilters,
        sort: String = sortBy, desc: Boolean = descending,
    ): UserSearchQuery = UserSearchQuery(texts, ranges, sort, desc)

    /**
     * Runs a search. Deliberately *not* through [run]: that sets `busy`, which disables the controls -- and
     * disabling the very input being typed into blurs it, so every keystroke cost the field its focus.
     */
    fun runSearch(q: UserSearchQuery) {
        queryRef.current = q
        searchSeq.current = (searchSeq.current ?: 0) + 1
        val seq = searchSeq.current
        usersScope.launch {
            try {
                val result = AdminApi.searchUsers(q)
                // A slower earlier request must not overwrite a newer one's results.
                if (seq == searchSeq.current) {
                    users = result.users
                    numAvailable = result.numAvailable
                    hasMore = result.hasMore
                    loaded = true
                    error = null
                }
            } catch (e: Throwable) {
                if (seq == searchSeq.current) error = userFacingError(e)
            }
        }
    }

    /**
     * Debounces [runSearch] so typing does not fire a request per character. Records [q] as the latest intent
     * and, when the timer fires, runs whatever the latest intent is by then -- so a sort click during the
     * debounce is honoured rather than overwritten by this text change's stale snapshot.
     */
    fun scheduleSearch(q: UserSearchQuery) {
        queryRef.current = q
        searchTimer.current?.let { clearTimer(it) }
        searchTimer.current = setTimer({ queryRef.current?.let { runSearch(it) } }, searchDebounceMs)
    }

    /** Sets the visible filter/sort controls from [q] -- used to reflect a URL (a shared link, Back/Forward). */
    fun seedFilters(q: UserSearchQuery) {
        textFilters = q.textTerms
        rangeFilters = q.ranges
        sortBy = q.sortBy
        descending = q.descending
    }

    /** Updates one text filter (email/name/client) and runs; [immediate] fires at once (a select) or debounces
     *  (a text box). Only a truly *empty* value removes the entry -- a whitespace one is kept so the box does
     *  not erase a space as it is typed; the query serialization trims it away, so it filters nothing. */
    fun setText(field: String, value: String, immediate: Boolean) {
        val next = if (value.isEmpty()) textFilters - field else textFilters + (field to value)
        textFilters = next
        val q = query(texts = next)
        if (immediate) runSearch(q) else scheduleSearch(q)
    }

    /** Updates one date-range filter and runs at once (a picker is a deliberate choice). An empty range is dropped. */
    fun setRange(field: String, range: DateRange) {
        val next = if (range.isEmpty) rangeFilters - field else rangeFilters + (field to range)
        rangeFilters = next
        runSearch(query(ranges = next))
    }

    /** Resets every filter and the sort to their defaults and re-runs; the hash sync then clears the URL. */
    fun clearFilters() {
        val q = UserSearchQuery()
        seedFilters(q)
        runSearch(q)
    }

    useEffect(generation) {
        usersScope.launch {
            val c = runCatching { HomeApi.fetchConfig() }.getOrNull()
            config = c
            if (c?.canManageUsers == true) {
                // The caller's own address, for "Create a user for me" (issue #797). Before the search, so it is
                // known by the time the rows arrive and a reload inside the self form restores that form. A
                // failure leaves the button off rather than showing an error: it is a convenience over the
                // ordinary create.
                val own = runCatching { ProfileApi.fetchConfig().loginId }.getOrNull()?.ifBlank { null }
                ownAddress = own
                ownAddressRef.current = own
                // Seed the controls from the URL and run *that* search, so a shared/bookmarked link reproduces
                // the sender's filters and sort rather than the default view (issue #411).
                val q = searchQueryFromHash(hashParams())
                seedFilters(q)
                runSearch(q)
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
        draftLabels = user?.labels ?: emptyList()
        draftPersona = user?.persona ?: PERSONA.member
        draftPersonaSuffix = user?.personaSuffix ?: ""
        personaSuffixCollided = false
        personaChosen = false
        confirmingDelete = false
        note = null
        error = null
    }

    /** Opens the editor on [user], seeding the draft from their current state. */
    fun startEdit(user: AdminUser) {
        editing = user
        creating = false
        seedDraft(user)
        // The suggestions of the *user's* client, not the editor's, fetched the first time that client is edited.
        // A failure leaves the editor offering only the labels the user already has -- they can still type any
        // other, since labels are free-form -- and is not recorded, so the next open tries again.
        val client = user.client
        if (labelSuggestionsRef.current?.containsKey(client) != true) {
            usersScope.launch {
                // A functional update: this runs after the await, when the map captured above may be stale -- two
                // clients fetched at once would otherwise each write back a map missing the other.
                runCatching { AdminApi.labelSuggestions(client) }.onSuccess { fetched ->
                    setLabelSuggestions { current -> current + (client to fetched) }
                }
            }
        }
    }

    /** Opens the editor on a new user -- for the caller's own address when [forSelf] (issue #797). */
    fun startCreate(forSelf: Boolean = false) {
        editing = null
        creating = true
        creatingForSelf = forSelf
        seedDraft(null)
        if (forSelf) draftEmail = ownAddressRef.current ?: ""
    }

    fun closeEditor() {
        editing = null
        creating = false
        creatingForSelf = false
        error = null
    }

    // --- the editor as a place you can come back to (issue #324) ---------------------------------------
    //
    // Opening a user used to be React state and nothing else, so it left no history entry: Back from the
    // editor skipped the whole page and landed wherever you were before arriving at Users. The hash now says
    // which record is open, exactly as the endpoint catalog says which endpoint is.

    /** Who the editor is open on, as the hash should say it: a user id, `new`, or nothing in the list view. */
    fun openRecord(): String? = when {
        creating -> if (creatingForSelf) HP.selfRecord else HP.newRecord
        else -> editing?.userId?.toString()
    }

    /** The rows the listener can resolve against, read through a ref (it is registered once). */
    val usersRef = useRef<List<AdminUser>>(emptyList())
    // The open person's users as well (issue #770): choosing one of them edits a user the search may not hold,
    // and Back and Forward must still find it.
    usersRef.current = users + (identity?.users ?: emptyList())

    // Which user's person is wanted now, read through a ref: a slow load for a user the editor has since left
    // must not overwrite the one for the user it is on.
    val identityFor = useRef<Long>(null)
    useEffect(editing?.userId) {
        val open = editing
        identity = null
        identityFor.current = open?.userId
        if (open != null && !open.deleted) {
            usersScope.launch {
                val loaded = runCatching { AdminApi.userIdentity(open.userId) }
                    .onFailure { console.error("$errorLogPrefix could not load the person behind user ${open.userId}: ${it.message}") }
                    .getOrNull()
                if (identityFor.current == open.userId) identity = loaded
            }
        }
    }

    /** Opens the editor on what the hash names, or closes it. Used by the hashchange listener below. */
    fun applyEditorHash() {
        val open = hashParams()[HP.user]
        when {
            open == null -> closeEditor()
            open == HP.newRecord -> startCreate()
            // Needs the caller's address; until it is known the self form cannot be shown, so the ordinary one
            // opens instead rather than a self form with an empty, locked address.
            open == HP.selfRecord -> startCreate(forSelf = ownAddressRef.current != null)
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
        // Only the editor is restored on a hash change (Back/Forward, a menu link). The search is deliberately
        // NOT re-run here: `onHashChange` registers a listener that is never removed (see HashRoute), so one
        // survives per past visit to this page -- harmless while it only calls setState on an unmounted
        // component (React ignores that), but a search re-run would fire a real request from every stale
        // listener on every later hash change. The search reaches the URL only two ways, both safe: a fresh
        // mount restores it in the generation effect below (a reload or a pasted/bookmarked link), and within
        // the page it is written with `replace`, so there are no search history entries for Back to return to
        // anyway -- the only in-page history is the editor, which `applyEditorHash` handles.
        onHashChange { applyEditorHash() }
    }

    // Open whatever the hash named once the rows are in -- a reload inside the editor, or a link. Until then
    // the hash cannot be honoured, so `restored` also holds the sync effect below: without that gate it would
    // run first with an empty editor and push the record out of the URL before anything could read it. Only the
    // editor here: the initial search already ran from the hash in the generation effect.
    useEffect(loaded) {
        if (loaded && !restored) {
            applyEditorHash()
            restored = true
        }
    }

    // Keep the hash in step with the editor *and the search* (issue #411) -- the same arrangement the endpoint
    // catalog uses, and for the same reason: opening a record is a navigation (a pushed history entry), while
    // the search merely refines where you are (replaced in place), so typing a filter does not spam Back. Only
    // the open record is in [userIdentity], so a filter change never pushes.
    useEffect(
        editing, creating, restored,
        textFilters, rangeFilters, sortBy, descending,
    ) {
        if (!restored) {
            return@useEffect
        }
        val params = buildList {
            add(HP.page to HMENU.pageUsers)
            openRecord()?.let { add(HP.user to it) }
            addAll(searchHashParams(query()))
        }
        // Reachable means the hash as it stands names something this page could show. A `u=` naming a row we
        // do not hold is a URL to correct in place -- otherwise Back onto it would push again and never move.
        val current = hashParams()[HP.user]
        val reachable = current == null || current == HP.newRecord || current == HP.selfRecord ||
            users.any { it.userId.toString() == current } ||
            identity?.users?.any { it.userId.toString() == current } == true
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
            val email = draftEmail.normalizeEmail()
            if (!email.isEmailAddress()) {
                error = DisplayError.expected("\"$email\" is not a valid email address.")
                return@run
            }
            val created = try {
                AdminApi.createUser(
                    email, username = null, roles = draftRoles(emptyList()),
                    org = draftOrg.trim().ifEmpty { null },
                    isEntity = draftIsEntity, name = draftName.trim().ifEmpty { null },
                    client = draftClient.trim().ifEmpty { null }, enabled = draftEnabled,
                    persona = if (personaChosen) draftPersona else null, personaSuffix = draftPersonaSuffix,
                )
            } catch (e: Throwable) {
                // A collision on (address, client, persona) is what the personaSuffix is for: offer it, keep the
                // form, and let the refusal show as it is.
                if (isUserKeyCollision(e)) personaSuffixCollided = true
                throw e
            }
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
            // Compared after the kernel's normalization -- the rule the backend stores by -- so re-spacing or
            // repeating a label is no change, rather than a write that stores what was already there.
            val desiredLabels = normalizeUserLabels(draftLabels)
            if (desiredLabels != target.labels) {
                AdminApi.setLabels(target.userId, desiredLabels)
                changed = true
            }
            note = if (changed) "Saved ${target.primaryId}." else "No changes to ${target.primaryId}."
        }
        closeEditor()
        runSearch(query())
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
        runSearch(query())
    }

    val denied = config?.canManageUsers == false
    val inEditor = creating || editing != null

    div {
        className = ClassName("card full")

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
            h1 { +editorTitle(creating, creatingForSelf) }

            error?.let { errorText(it) }

            // The person behind the user (issue #770), above the editable data: their other users, and a summary.
            val open = editing
            val person = identity
            if (open != null && person != null && !open.deleted) {
                identityPanel(person, open, disabled = busy) { startEdit(it) }
            }

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
            if (creating && creatingForSelf) {
                // Locked: the point of this form is that the address is the caller's own.
                readOnlyField("Email address", draftEmail)
                p {
                    className = ClassName("type-hint")
                    +createAddressHint(forSelf = true)
                }
            } else if (creating) {
                textField("Email address", draftEmail, disabled = busy, autoComplete = AC.username) {
                    draftEmail = it
                }
                p {
                    className = ClassName("type-hint")
                    +createAddressHint(forSelf = false)
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

            // The Operator rung is deployment-wide since #464 (it requires allClients), so offer it only to a
            // caller who can grant that reach -- one holding allClients -- or when the edited user already is an
            // operator, so the value shows and can be kept (anti-escalation checks adding, not the result set).
            // Withholding a choice that would only 400 is the rule the All-clients checkbox below already keeps.
            val operatorSelectable = config?.user?.roles?.contains(ROLE.allClients) == true ||
                editing?.roles?.contains(ROLE.operator) == true

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
                    options = accessLevelOptions(operatorSelectable)
                    disabled = busy || self
                    style = js("({ minWidth: 180 })")
                    onChange = { v ->
                        val level = v as? String ?: ROLE.user
                        draftLevel = level
                        // Roles provide a default persona (issue #750), until one is chosen outright.
                        if (creating && !personaChosen) draftPersona = personaForLevel(level)
                    }
                }
            }
            p {
                className = ClassName("type-hint")
                +accessLevelHint(operatorSelectable)
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

            // The persona (issue #750): frozen at creation, like the client, so a selector on create and plain
            // text afterward. Choosing one moves the access level to the persona's default, which the
            // administrator may still change: the persona says what kind of user this is, the level what they
            // may do, and the default is only where the two usually agree.
            if (creating) {
                div {
                    className = ClassName("row")
                    span {
                        className = ClassName("field-label")
                        +"Persona"
                    }
                    Select {
                        value = draftPersona
                        options = personaOptions()
                        disabled = busy
                        style = js("({ minWidth: 180 })")
                        onChange = { v ->
                            val chosen = v as? String ?: PERSONA.member
                            draftPersona = chosen
                            personaChosen = true
                            draftLevel = levelForPersona(chosen)
                        }
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +personaHint
                }
                textField("Persona suffix", draftPersonaSuffix, disabled = busy) { draftPersonaSuffix = it }
                p {
                    className = ClassName("type-hint")
                    +personaSuffixHint(collided = personaSuffixCollided)
                }
            } else {
                readOnlyField("Persona", personaCell(draftPersona, draftPersonaSuffix))
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

            // Labels (issue #786): on an existing user only -- a new one has no client suggestions fetched yet, and
            // is labelled after it exists. A tags select: the client's suggestions are offered, and anything else
            // may be typed, because a label is free-form by design.
            if (!creating) {
                div {
                    className = ClassName("row")
                    span {
                        className = ClassName("field-label")
                        +"Labels"
                    }
                    Select {
                        mode = "tags"
                        value = draftLabels.toTypedArray()
                        options = labelSelectOptions(
                            userLabelChoices(labelSuggestions[editing?.client].orEmpty(), draftLabels),
                        )
                        placeholder = "(none)"
                        disabled = busy
                        style = js("({ minWidth: 240 })")
                        onChange = { v -> draftLabels = (v.unsafeCast<Array<String>>()).toList() }
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +labelsHint
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
            p {
                className = ClassName("type-hint")
                // Names what unchecking this actually is. The two modes differ: creating disabled makes an
                // account that exists but cannot yet sign in; unchecking on an existing user is the recoverable
                // half of "deleting" one, as distinct from -- not a duplicate of -- the permanent delete below.
                +(if (creating) {
                    "Leave checked for an active account. Uncheck to create it already disabled: the user " +
                        "exists but cannot sign in until you enable them here."
                } else {
                    "Unchecking disables the account — a recoverable delete: the user cannot sign in, but you " +
                        "can re-enable them here. To remove the account for good, use Delete user below."
                })
            }
            if (self) {
                p {
                    className = ClassName("type-hint")
                    +"This is your own account: another administrator has to change your role or disable you."
                }
            }
            // An unclaimed user (issue #751): the person has not accepted their invitation. Offer to send it
            // again -- a lapsed link, a lost mail, a user created disabled and enabled since. The backend
            // refuses a claimed or disabled one, so the button is shown only where it can succeed.
            if (editing?.registered == false && editing?.enabled == true) {
                div {
                    className = ClassName("row")
                    Button {
                        disabled = busy
                        onClick = { run { AdminApi.inviteUser(editing!!.userId); note = "Invitation sent to ${editing?.primaryId}." } }
                        +"Send invitation again"
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +"Nobody has accepted this account's invitation yet. Sending again mails a fresh link, good for seven days."
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
                +"Filter and sort active users, then select one to edit — or create a new user."
            }

            error?.let { errorText(it) }
            note?.let {
                p {
                    className = ClassName("form-ok")
                    +it
                }
            }

            // Only a full-scope caller sees the client filter and column -- the same rule the create selector
            // follows, since the client is a distinction only to somebody who can see more than one.
            val showClient = config?.user?.roles?.contains(ROLE.allClients) == true

            // A whitespace-only term is kept in the map (so it can be typed) but filters nothing, so it does not
            // count as an active filter here -- matching what the query serialization actually sends.
            val anyFilter = textFilters.values.any { it.isNotBlank() } || rangeFilters.values.any { !it.isEmpty }
            // The sort counts as something to reset too, so Clear returns the whole view to its default.
            val canReset = anyFilter || sortBy != USF.lastEdited.at || !descending
            // What the list is narrowed by, in words (issue #683), for the chips the closed well shows. Derived
            // from the very state `anyFilter` and `canReset` read, so a chip can never disagree with the count
            // or with whether Clear is offered -- and since the filters apply live, "in force" and "current" are
            // one state, so there is no applied-vs-draft distinction for the chips to get wrong. The sort is a
            // chip too (Clear resets it), but not a *filter*: the toggle counts only the filters.
            val filtersInForce = userFilterChips(textFilters, rangeFilters)
            val chips = filtersInForce + listOfNotNull(userSortChip(sortBy, descending))

            div {
                className = ClassName("row")
                // In `public` an administrator creates only users of their own (issue #805), so the ordinary create,
                // which mails an invitation to any other address, is not offered; "for me" is.
                if (config?.user?.let { mayCreateForOthers(it) } != false) {
                    Button {
                        onClick = { startCreate() }
                        +"Create user"
                    }
                }
                // A further user for the caller's own address (issue #797): registered on creation and switchable
                // at once from the badge, with no invitation -- the case that otherwise meant typing your own address.
                if (ownAddress != null) {
                    Button {
                        onClick = { startCreate(forSelf = true) }
                        +"Create a user for me"
                    }
                }
                // The filters live behind a toggle (issue #683), closed by default, so the table -- what the
                // page is for -- is on screen without first scrolling past six controls and their hints.
                filterToggle(filtersOpen, filtersInForce.size, usersFiltersWellId) { setFiltersOpen { !it } }
                // Offered only when there is something to undo, so it is not a permanent no-op button -- and
                // here rather than inside the well, so the way back is on screen while the well is shut.
                if (canReset) {
                    Button {
                        type = "link"
                        onClick = { clearFilters() }
                        +"Clear filters"
                    }
                }
            }
            filterChips(chips, filtersOpen)

            // The controls are rendered from the shared spec (issue #411, the SDUI extra credit): a field added
            // to `userSearchFieldSpecs` becomes a filter here with no further change. A substring field is a
            // text box (debounced, so a keystroke does not blur it); an exact field is a picker; a date-range
            // field is a pair of date-time pickers -- each firing at once, since a pick is a deliberate choice.
            // Laid out as the forms list lays out its own: label above control, in the shared well. A field with
            // no filter kind (sort-only) draws no control. A from-to pair wider than its track wraps (see
            // `.filter-range`), so the well needs no special width for it.
            filterWell(filtersOpen, usersFiltersWellId) {
                for (spec in userSearchFieldSpecs) {
                    if (spec.allClientsOnly && !showClient) continue
                    val kind = spec.filterKind ?: continue
                    filterGroup(spec.label) {
                        when (kind) {
                            UserFilterKind.substring -> Input {
                                value = textFilters[spec.name] ?: ""
                                placeholder = "${spec.label} contains…"
                                // Fills its track up to a cap, so a narrow browser still shrinks it (issue #462).
                                style = js("({ width: '100%', maxWidth: 420 })")
                                onChange = { event -> setText(spec.name, event.target.value as String, immediate = false) }
                            }
                            UserFilterKind.exact -> Select {
                                // Exact fields render as a picker; today only the client, whose options are the
                                // clients this caller may choose among.
                                value = (textFilters[spec.name] ?: "").ifEmpty { null }
                                options = clientOptions(clientChoices)
                                placeholder = "Any ${spec.label.lowercase()}"
                                allowClear = true
                                style = js("({ minWidth: 180 })")
                                onChange = { v -> setText(spec.name, v as? String ?: "", immediate = true) }
                            }
                            UserFilterKind.dateRange -> {
                                val range = rangeFilters[spec.name] ?: DateRange()
                                div {
                                    className = ClassName("filter-range")
                                    DatePicker {
                                        value = range.after?.let { dayjs(it) }?.takeIf { it.isValid() }
                                        showTime = true
                                        // Bounded: a date-time picker has a known amount to show, so it gains
                                        // nothing from being wider.
                                        style = js("({ maxWidth: 220 })")
                                        onChange = { date, _ -> setRange(spec.name, DateRange(date?.toISOString(), range.before)) }
                                    }
                                    span {
                                        className = ClassName("type-hint")
                                        +"to"
                                    }
                                    DatePicker {
                                        value = range.before?.let { dayjs(it) }?.takeIf { it.isValid() }
                                        showTime = true
                                        style = js("({ maxWidth: 220 })")
                                        onChange = { date, _ -> setRange(spec.name, DateRange(range.after, date?.toISOString())) }
                                    }
                                }
                                span {
                                    className = ClassName("type-hint")
                                    +"Leave either end empty for open-ended."
                                }
                            }
                        }
                    }
                }
            }

            if (loaded && users.isEmpty()) {
                p {
                    className = ClassName("subtitle")
                    +if (anyFilter) "No users match your filters." else "No users yet."
                }
            } else {
                if (loaded) {
                    p {
                        className = ClassName("subtitle")
                        +userCountLabel(users.size, numAvailable, hasMore)
                    }
                }
                UserTable {
                    this.showClient = showClient
                    this.users = users
                    this.sortBy = sortBy
                    this.descending = descending
                    onSort = { field, desc ->
                        sortBy = field
                        descending = desc
                        runSearch(query(sort = field, desc = desc))
                    }
                    onSelect = { startEdit(it) }
                }
            }
        }
    }
}

/** A label plus a static value, for the identity fields the backend does not let an administrator change. */
internal fun react.ChildrenBuilder.readOnlyField(label: String, value: String) {
    div {
        className = ClassName("row")
        span {
            className = ClassName("field-label")
            +label
        }
        span { +value }
    }
}

/**
 * The [UserSearchQuery] a shared/bookmarked URL encodes (issue #411), read generically from the shared spec:
 * the hash carries the **same keys as the wire** (a field's own name for a text filter, the spec's range keys
 * for a date range, plus sort), so this and [searchHashParams] mirror `AdminApi.userSearchArgs`. An absent key
 * is its default; an unrecognized sort key (a hand-edited or stale link) drops to the default so a pasted link
 * can never ask the endpoint for a field it would reject. Pure, and covered under `jsNodeTest`.
 */
fun searchQueryFromHash(hp: Map<String, String>): UserSearchQuery {
    val texts = buildMap {
        for (spec in userSearchFieldSpecs) {
            if (spec.filterKind == UserFilterKind.substring || spec.filterKind == UserFilterKind.exact) {
                hp[spec.name]?.takeIf { it.isNotBlank() }?.let { put(spec.name, it) }
            }
        }
    }
    val ranges = buildMap {
        for (spec in userSearchFieldSpecs) {
            val keys = spec.rangeKeys ?: continue
            // A bound present but blank (`lastEditedAfter=` in a hand-edited link) is no bound: kept, it would
            // count as a filter, be sent, and show as a chip with nothing after it (issue #683 review).
            val range = DateRange(hp[keys.first]?.takeIf { it.isNotBlank() }, hp[keys.second]?.takeIf { it.isNotBlank() })
            if (!range.isEmpty) put(spec.name, range)
        }
    }
    return UserSearchQuery(
        textTerms = texts,
        ranges = ranges,
        sortBy = hp[USF.sortBy]?.takeIf { userSortKeys.contains(it) } ?: defaultUserSortKey,
        // Descending is the default; only an explicit "false" means ascending.
        descending = hp[USF.descending] != "false",
        // The any-text term (issue #581) round-trips through the hash like the other filters -- read back so a
        // shared or bookmarked search keeps it, rather than silently dropping it on decode.
        anyText = hp[EI.q]?.takeIf { it.isNotBlank() },
    )
}

/**
 * The hash params for a search [query] (issue #411): the same key/value pairs `AdminApi.userSearchArgs` puts
 * on the wire (minus the limit), emitting only what differs from the default so a plain search stays a bare
 * `page=users`. Pure, and covered under `jsNodeTest` -- it round-trips with [searchQueryFromHash].
 */
fun searchHashParams(query: UserSearchQuery): List<Pair<String, String>> =
    userSearchArgs(query).mapNotNull { (k, v) ->
        when {
            // The sort defaults are omitted so an untouched search carries no params.
            k == USF.sortBy && v == USF.lastEdited.at -> null
            k == USF.descending && v == true -> null
            k == USF.descending -> k to "false"
            else -> k to v.toString()
        }
    }

/** The filter well's element id, so the toggle can name what it controls (`aria-controls`). */
private const val usersFiltersWellId = "users-filters"

/**
 * The filters in force said in words, one chip each, for the summary the closed well shows (issue #683):
 * `Email contains "ada"`, `Client is "acme"`, `Last login 2026-08-01 10:00 UTC – 2026-09-01 10:00 UTC`,
 * `Edited ≥ …`. Built from the shared spec in its own order, so a field added there gets a chip with no further
 * change, in the words the forms list's chips use ([textChip], [rangeChip]) so the two pages say the same thing
 * -- and a blank value is no chip, matching `anyFilter` and what the query sends.
 *
 * A date bound reads as the table renders the column it filters -- [formatTimestamp], UTC to the minute -- so
 * the chip and the rows it admitted are on one clock; a chip in local time beside a column in UTC would show
 * one instant two ways. Every term in force is a chip, including one the caller's own well does not offer (the
 * client, for a caller without `allClients`, carried in by a shared link): it still counts as a filter to Clear
 * and to the count line, so it must be visible somewhere, and the chip is then the only somewhere. Pure, and
 * covered under `jsNodeTest`.
 */
fun userFilterChips(texts: Map<String, String>, ranges: Map<String, DateRange>): List<String> =
    userSearchFieldSpecs.mapNotNull { spec ->
        when (spec.filterKind) {
            UserFilterKind.substring -> textChip(spec.label, texts[spec.name], contains = true)
            UserFilterKind.exact -> textChip(spec.label, texts[spec.name], contains = false)
            UserFilterKind.dateRange -> ranges[spec.name]?.let { r ->
                rangeChip(spec.label, r.after?.let(::formatTimestamp), r.before?.let(::formatTimestamp))
            }
            null -> null
        }
    }

/**
 * The sort as a chip when it is not the default (issue #683) -- `Sorted by Name, A–Z`, `Sorted by Last login,
 * oldest first` -- since Clear resets the sort too and a person should see what Clear would undo. Not a filter,
 * so not in the toggle's count: a sort narrows nothing. A date sorts by time and a text field alphabetically,
 * and the words say which; "a date" is a spec with range keys, which a sort-only date (`dateSpec` with no
 * filter kind) still has. Pure, and covered under `jsNodeTest`.
 */
fun userSortChip(sortBy: String, descending: Boolean): String? {
    if (sortBy == USF.lastEdited.at && descending) return null
    val spec = userSearchFieldSpecsByName[sortBy]
    val direction = if (spec?.rangeKeys != null) {
        if (descending) "newest first" else "oldest first"
    } else {
        if (descending) "Z–A" else "A–Z"
    }
    return "Sorted by ${spec?.label ?: sortBy}, $direction"
}

/**
 * The count line above the results (issue #411): how many are shown against how many matched, so an over-broad
 * search reads as "showing 500 of 4000" rather than looking like the whole population. When the cap hid none,
 * one plain count is clearer than repeating it. Pure, and covered under `jsNodeTest`.
 */
fun userCountLabel(shown: Int, available: Int, hasMore: Boolean): String = when {
    hasMore -> "Showing $shown of $available matching users — narrow your search to see the rest."
    available == 1 -> "1 matching user."
    else -> "$available matching users."
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

/**
 * The level values to offer, lowest first. The **operator** rung is deployment-wide since #464 -- it requires
 * `allClients` as well as the level -- so it is withheld unless [operatorSelectable]: a caller who cannot grant
 * that reach would only ever get a 400 from picking it, the advertise-versus-serve drift this page exists to
 * remove (the same rule the All-clients checkbox already follows). It is still offered when the edited user
 * *already* holds it, so a scoped administrator editing an operator sees the true value and may keep it (the
 * anti-escalation check is on adding, not on the resulting set). Pure, and covered under `jsNodeTest`.
 */
fun offeredAccessLevels(operatorSelectable: Boolean): List<String> =
    RoleLadder.ordered.filter { it != ROLE.operator || operatorSelectable }

/** The persona registry as antd `{ label, value }` option objects (issue #750), in the registry's order. */
fun personaOptions(): Array<dynamic> =
    PERSONA.defs.map { def ->
        val obj: dynamic = js("({})")
        obj.label = def.label
        obj.value = def.name
        obj
    }.toTypedArray()

/**
 * The access level a persona's default roles put a user at -- what the level selector moves to when a persona
 * is chosen (issue #750). The ladder's floor for a persona the registry does not hold. Pure, covered under
 * `jsNodeTest`.
 */
fun levelForPersona(persona: String): String =
    PERSONA.def(persona)?.let { RoleLadder.highestHeld(it.defaultRoles) } ?: ROLE.user

/**
 * The persona a user created at [level] takes when none is chosen -- the same rule the backend applies to an
 * unnamed persona (`PERSONA.defaultFor`), asked of the level's role list. Pure, covered under `jsNodeTest`.
 */
fun personaForLevel(level: String): String = PERSONA.defaultFor(RoleLadder.rolesAtLevel(emptyList(), level))

/**
 * Whether a create was refused because a user with the same address, client and persona already exists --
 * the backend's duplicate-key refusal, which is the one situation the personaSuffix box answers. Keyed on the
 * envelope's logical error code (`AERR.userKeyTaken`), not the sentence, so the wording is free to change; a
 * different refusal (a taken username, a bad address) leaves the box unoffered. Pure, covered under
 * `jsNodeTest`.
 */
fun isUserKeyCollision(error: Throwable): Boolean = (error as? ApiError)?.errorCode == AERR.userKeyTaken

private const val personaHint =
    "What kind of user this is: a member of the client, or one of its administrators. Chosen once, at " +
        "creation. It follows the access level until you pick one; picking one sets the level to its usual " +
        "value, which you may still change."

/**
 * Whether [user] may create users at other people's addresses (issue #805): everyone who reaches the page except
 * an administrator in `public` without `allClients`, whose reach is their own users only -- the backend refuses
 * them the ordinary create, which would mail an invitation to somebody else. Pure, jsNodeTest-covered.
 */
fun mayCreateForOthers(user: UserProfile): Boolean = user.client != CL.public || ROLE.allClients in user.roles

/** The editor's heading: which record it is open on (issue #797 adds the caller's own). Pure, jsNodeTest-covered. */
fun editorTitle(creating: Boolean, forSelf: Boolean): String = when {
    creating && forSelf -> "Create a user for me"
    creating -> "Create a user"
    else -> "Edit user"
}

/**
 * What happens at the address a create names (issues #751, #797) -- the backend's rule, said before the click:
 * the caller's own address gives a user registered at once, and anyone else's gets an invitation. Pure,
 * jsNodeTest-covered.
 */
fun createAddressHint(forSelf: Boolean): String = if (forSelf) {
    "Your own address. The new user is registered as soon as it is created, no invitation is sent, and it " +
        "appears in your account menu to switch to."
} else {
    "An invitation is mailed to this address, and the user is claimed when its owner accepts it. If it is " +
        "your own address, the user is registered at once instead, and no invitation is sent."
}

/**
 * The persona suffix box's hint (issues #750, #797): what the suffix is for, or -- once a create has collided
 * with an existing user of the same address, client and persona -- why this one now needs one. Pure,
 * jsNodeTest-covered.
 */
fun personaSuffixHint(collided: Boolean): String = if (collided) {
    "A user of this address, client and persona already exists. Give this one a short suffix (up to " +
        "${PERSONASUFFIX.maxLength} letters or digits: 1, 2, A, B) to create it as a further user of the same kind."
} else {
    "Optional. A short suffix (up to ${PERSONASUFFIX.maxLength} letters or digits: 1, 2, A, B) for a further " +
        "user of the same address, client and persona -- shown as \"Member B\". Leave blank for the first one."
}

/** The [offeredAccessLevels] as antd `{ label, value }` option objects. */
private fun accessLevelOptions(operatorSelectable: Boolean): Array<dynamic> =
    offeredAccessLevels(operatorSelectable).map { role ->
        val obj: dynamic = js("({})")
        obj.label = accessLevelLabels[role] ?: role
        obj.value = role
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

/** Says what a label is for -- and, since it sits beside the access level, what it is not. */
private const val labelsHint =
    "Free-form labels a workflow can test for -- \"reviewer\" can make someone a reviewer of a workflow's " +
        "task. They grant no access. The client's suggestions are offered; type to add any other."

/** A label list as antd `Select` `{ label, value }` options -- a label is its own caption. */
private fun labelSelectOptions(labels: List<String>): Array<dynamic> = labels.map { label ->
    val option: dynamic = js("({})")
    option.label = label
    option.value = label
    option
}.toTypedArray()

/** Explains what the name is for, and that it is display copy rather than an identifier. */
private const val nameHint =
    "The name shown for this account. It need not be unique, and is not a login -- the email address and " +
        "username remain the identifiers."

/**
 * Says what the levels are for. Two readings, because since #464 the operator rung is only offered to a caller
 * who can grant it ([operatorShown]): explaining a level that is not on screen would puzzle a scoped
 * administrator, and the operator rung no longer "includes the ones below it" in the way the old copy implied
 * -- reaching the operator endpoints takes the deployment-wide `allClients`, not merely ranking above `user`.
 */
private fun accessLevelHint(operatorShown: Boolean): String =
    if (operatorShown) {
        "An administrator manages users. The Operator level reaches the deployment diagnostics -- a " +
            "deployment-wide surface, so it takes All clients as well as the level."
    } else {
        "An administrator manages users in this client. The deployment-operator level is offered only to a " +
            "full-scope administrator, since its surface spans every client."
    }
