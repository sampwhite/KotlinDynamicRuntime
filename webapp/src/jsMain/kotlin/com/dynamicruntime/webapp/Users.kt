package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.sandboxParentOf
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
import com.dynamicruntime.common.user.UserFilterChoices
import com.dynamicruntime.common.user.UserFilterKind
import com.dynamicruntime.common.user.UserFilterOption
import com.dynamicruntime.common.user.UserSearchFieldSpec
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.user.userLabelChoices
import com.dynamicruntime.common.user.userSearchFieldSpecs
import com.dynamicruntime.common.user.userSearchFieldSpecsByName
import com.dynamicruntime.common.user.userSortKeys
import com.dynamicruntime.common.util.isEmailAddress
import com.dynamicruntime.common.util.normalizeEmail
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import react.FC
import react.Key
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
 * `canManageUsers` capability for the current caller -- or `browsesUsers` for an editor (issue #1432), who gets the
 * page read-only -- and only then includes the Users item in the menu. This page reads the same flags, so arriving by a
 * bookmarked `/wa/users` gives an honest "not available" instead of a wall of failed requests. Neither is enforcement:
 * every write goes through `AdminApi` to the `clientAdmin` section (issue #466), and every read to the
 * `clientDirectory` section, each of which refuses a caller without its role (401 anonymous, 403 logged in without
 * it) regardless of what the frontend believes.
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
    // The free-text term (issue #581; a box of its own since #1432): one OR across email, name, username, persona,
    // persona suffix, and label, where the filters above AND. Its box is always on screen, so it is not a chip.
    var anyText by useState("")
    // The sort, driven by the table's column headers. Default: newest first, as the issue specifies.
    var sortBy by useState(defaultUserSortKey)
    var descending by useState(defaultUserSortDescending)
    // Whether the filter well is open (issue #683). View state, not search state: it is not in the URL, so a
    // shared link arrives with the well closed and the filters it carries said as chips. Tuple form so the
    // toggle is a functional update (`{ !it }`), the convention App.kt documents for its own counters.
    val (filtersOpen, setFiltersOpen) = useState(false)
    // The filter well's width, followed while it is open: how many columns it lays out decides whether the date ranges
    // start a row of their own ([breakBeforeTall]).
    var wellWidth by useState(0.0)
    useEffect(filtersOpen) {
        if (!filtersOpen) return@useEffect
        val well = document.getElementById(usersFiltersWellId) ?: return@useEffect
        val measure: () -> Unit = { wellWidth = well.clientWidth.toDouble() }
        measure()
        val observer = resizeObserver(measure)
        observer.observe(well)
        // The effect's scope is cancelled when the well closes or the page goes: stop observing then.
        try {
            awaitCancellation()
        } finally {
            observer.disconnect()
        }
    }
    // What the last search reported: how many matched in all.
    var numAvailable by useState(0)
    // How many users the listing asks for (issue #1315): the first batch, grown on request, carried in the address.
    var shown by useState(listingBatch)
    var loaded by useState(false)
    var busy by useState(false)
    var error by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    // Whether [note] reports something the administrator must act on, rather than something done (issue #1504).
    var noteFailed by useState(false)

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
    // Read through a ref by [startCreate] and [applyEditorRoute]: the route listener is registered once,
    // so it would otherwise see the null this held on the first render (the same reason [usersRef] exists).
    val ownAddressRef = useRef<String>(null)
    ownAddressRef.current = ownAddress
    // True once the address has been read for a record to open; until then the URL is not written back.
    var restored by useState(false)

    // The editor's draft. Nothing here reaches the backend until Save.
    var draftEmail by useState("")
    var draftLevel by useState(ROLE.user)
    var draftAllClients by useState(false)
    var draftEditor by useState(false)
    var draftOrg by useState("")
    var draftClient by useState("")
    /** The clients a full-scope administrator may create into; empty for everybody else, who get no choice. */
    var clientChoices by useState<List<ClientChoice>>(emptyList())
    // What the links from a user to their forms and client need (issue #1081), asked once for the list and the
    // editor alike: whether this caller is offered them, and the clients a Forms link may choose -- the ones this
    // node carries, which only a caller who sees across clients is told (so a scoped one's links choose none).
    // An editor (issue #1432) is offered the way to a user's forms too, which it reads; the client link is left off.
    val linksOffered = config?.user?.let { userLinksOffered(it) } == true || usersReadOnly(config)
    val carriedClients = clientChoices.map { it.clientId }.toSet()

    /** Whether the page offers to become [user] (issue #1511). */
    fun offersBecome(user: AdminUser): Boolean = becomeOffered(user, appConfig().hasTestAccess, config?.user?.userId)

    var draftIsEntity by useState(false)
    var draftName by useState("")
    var draftEnabled by useState(true)
    var draftInvite by useState(true)
    // The user's free-form labels (issue #786), and what each client suggests -- keyed by client, since a
    // full-scope administrator edits users in many clients and each suggests its own. Keyed rather than one
    // "current" list so a slow reply can only ever fill in *its own* client: opening an acme user and then a globex
    // one cannot leave acme's suggestions on the globex editor. A client is fetched once, and again after a save
    // writes labels (see [forgetLabelSuggestions]).
    var draftLabels by useState<List<String>>(emptyList())
    val (labelSuggestions, setLabelSuggestions) = useState<Map<String, List<String>>>(emptyMap())
    // Read through a ref by [ensureLabelSuggestions], whose fetch outlives the render that started it.
    val labelSuggestionsRef = useRef<Map<String, List<String>>>(emptyMap())
    labelSuggestionsRef.current = labelSuggestions
    // How many times each client's suggestions have been dropped after a save. A fetch already under way when one is
    // dropped carries the labels from before that save, so it is discarded and asked again rather than written back.
    val labelGenerations = useRef(mutableMapOf<String, Int>())
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

    // Each panel's own result (issue #1478, rule 3 of #1477): the User panel's save, the mail panel's send, and
    // the danger zone's delete each say how it went where it was asked for. A save's failure partway through says what
    // it had saved first ([userSaveFailureLead]), since it is one call per change rather than one transaction.
    var saveError by useState<DisplayError?>(null)
    // What a create form opened with, so a field changed and left is asked about on leaving (issue #1478).
    var createSeed by useState<CreateDraft?>(null)
    var saveErrorLead by useState<String?>(null)
    var inviteNote by useState<String?>(null)
    var inviteError by useState<DisplayError?>(null)
    var inviteErrorLead by useState<String?>(null)
    var deleteError by useState<DisplayError?>(null)

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
    // The wire arguments of the search last run, so a debounced search that would only repeat it is skipped.
    val lastRunArgs = useRef<Map<String, Any?>>(null)

    /**
     * Runs a backend [block] with busy bookkeeping. Used by the actions, never by the search field. A failure goes to
     * [onError], the panel that asked (rule 3 of #1477).
     */
    fun run(onError: (DisplayError) -> Unit, block: suspend () -> Unit) {
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
                onError(userFacingError(e))
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
        sort: String = sortBy, desc: Boolean = descending, any: String = anyText,
    ): UserSearchQuery = UserSearchQuery(texts, ranges, sort, desc, anyText = any.trim().ifEmpty { null })

    /**
     * Runs a search. Deliberately *not* through [run]: that sets `busy`, which disables the controls -- and
     * disabling the very input being typed into blurs it, so every keystroke cost the field its focus.
     *
     * [limit] is how many users to show (issue #1315): the first batch unless said otherwise, so a changed filter or
     * sort starts the listing over, and growing it asks again from the top for more.
     */
    fun runSearch(q: UserSearchQuery, limit: Int = listingBatch) {
        queryRef.current = q
        lastRunArgs.current = userSearchArgs(q)
        shown = limit
        searchSeq.current = (searchSeq.current ?: 0) + 1
        val seq = searchSeq.current
        usersScope.launch {
            try {
                val result = AdminApi.searchUsers(q, limit)
                // A slower earlier request must not overwrite a newer one's results.
                if (seq == searchSeq.current) {
                    users = result.users
                    numAvailable = result.numAvailable
                    loaded = true
                    error = null
                }
            } catch (e: Throwable) {
                if (seq == searchSeq.current) {
                    error = userFacingError(e)
                    // A failed search is not one already run: the same terms asked again should ask again.
                    lastRunArgs.current = null
                }
            }
        }
    }

    /**
     * Debounces [runSearch] so typing does not fire a request per character. Records [q] as the latest intent
     * and, when the timer fires, runs whatever the latest intent is by then -- so a sort click during the
     * debounce is honoured rather than overwritten by this text change's stale snapshot. Skipped when that intent
     * is the search last run, as when a pick from a type-ahead was searched at once (issue #1481).
     */
    fun scheduleSearch(q: UserSearchQuery) {
        queryRef.current = q
        searchTimer.current?.let { clearTimer(it) }
        searchTimer.current = setTimer(
            { queryRef.current?.takeIf { userSearchArgs(it) != lastRunArgs.current }?.let { runSearch(it) } },
            searchDebounceMs,
        )
    }

    /** Sets the visible filter/sort controls from [q] -- used to reflect a URL (a shared link, Back/Forward). */
    fun seedFilters(q: UserSearchQuery) {
        textFilters = q.textTerms
        rangeFilters = q.ranges
        sortBy = q.sortBy
        descending = q.descending
        anyText = q.anyText ?: ""
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

    /** Updates the free-text term and runs, debounced as a typed filter is. */
    fun setAnyText(value: String) {
        anyText = value
        scheduleSearch(query(any = value))
    }

    /** Updates one date-range filter and runs at once (a picker is a deliberate choice). An empty range is dropped. */
    fun setRange(field: String, range: DateRange) {
        val next = if (range.isEmpty) rangeFilters - field else rangeFilters + (field to range)
        rangeFilters = next
        runSearch(query(ranges = next))
    }

    /** Resets every filter and the sort to their defaults and re-runs; the address sync then clears the URL. */
    fun clearFilters() {
        val q = UserSearchQuery()
        seedFilters(q)
        runSearch(q)
    }

    // The menu chose this page again (issue #1315): an explicit navigation, so start over as its bare link says --
    // no filters, the default sort, the first batch, re-read. Back and Forward restore from the address instead.
    val revisits = useRevisitSinceMount()
    useEffect(revisits) {
        if (revisits == 0 || !mayBrowseUsers(config)) return@useEffect
        clearFilters()
    }

    useEffect(generation) {
        usersScope.launch {
            val c = apiResult { HomeApi.fetchConfig() }.valueOrNull()
            config = c
            if (mayBrowseUsers(c)) {
                // The caller's own address, for "Create a user for me" (issue #797). Before the search, so it is
                // known by the time the rows arrive and a reload inside the self form restores that form. A
                // failure leaves the button off rather than showing an error: it is a convenience over the
                // ordinary create.
                val own = apiResult { ProfileApi.fetchConfig().loginId }.valueOrNull()?.ifBlank { null }
                ownAddress = own
                ownAddressRef.current = own
                // Seed the controls from the URL and run *that* search, so a shared/bookmarked link reproduces
                // the sender's filters and sort rather than the default view (issue #411).
                val q = searchQueryFromRoute(routeParams())
                seedFilters(q)
                // As many users as the address asks for (issue #1315), so returning to the list shows the same rows.
                runSearch(q, shownFromRoute(routeParams()[HP.shown]))
                // Only a full-scope administrator is offered a choice of client, and only they can ask for the
                // list -- it is a cross-client question. A failure leaves the list empty, which falls back to
                // the read-only field rather than an error: the client is not the reason they came here.
                if (c?.user?.roles?.contains(ROLE.allClients) == true) {
                    clientChoices = apiResult { AdminApi.listClients() }.valueOr(emptyList())
                }
            }
        }
    }

    /**
     * Sets the draft's fields from [user]'s current state, or empties them for a user being created -- at [email] when
     * it is the caller's own -- and records what a create opened with ([createSeed]).
     */
    fun seedFields(user: AdminUser?, email: String = "") {
        // A new user starts in the editor's own organization and client; an existing one shows theirs, blank
        // included. The client is the one field with no edit form at all: fixed once the user exists, so read-only.
        val seed = CreateDraft(
            email = user?.primaryId ?: email, name = user?.name ?: "", isEntity = user?.isEntity == true,
            level = user?.level ?: ROLE.user, editor = user?.roles?.contains(ROLE.editor) == true,
            allClients = user?.roles?.contains(ROLE.allClients) == true,
            org = (if (user != null) user.org else config?.user?.org) ?: "",
            client = (if (user != null) user.client else config?.user?.client) ?: "",
            persona = user?.persona ?: PERSONA.member, personaSuffix = user?.personaSuffix ?: "",
            labels = user?.labels ?: emptyList(), enabled = user?.enabled ?: true,
        )
        draftEmail = seed.email
        draftLevel = seed.level
        draftAllClients = seed.allClients
        draftEditor = seed.editor
        draftOrg = seed.org
        draftClient = seed.client
        draftIsEntity = seed.isEntity
        draftName = seed.name
        draftEnabled = seed.enabled
        draftInvite = seed.invite
        draftLabels = seed.labels
        draftPersona = seed.persona
        draftPersonaSuffix = seed.personaSuffix
        personaSuffixCollided = false
        personaChosen = false
        createSeed = seed.takeIf { user == null }
    }

    /** Seeds the editor for [user] (or a new one, at [email]): its fields, and no result yet in any panel. */
    fun seedDraft(user: AdminUser?, email: String = "") {
        seedFields(user, email)
        saveError = null
        saveErrorLead = null
        inviteNote = null
        inviteError = null
        deleteError = null
        note = null
        noteFailed = false
        error = null
    }

    /**
     * Fetches [client]'s label suggestions the first time something asks for them: the editor of one of its users, the
     * create form once it names the client, or the Labels filter. Only an administrator may read them; an editor's
     * label controls offer nothing and take what is typed. A failure leaves the control offering only the labels
     * already in it -- any other can still be typed, since labels are free-form -- and is not recorded, so the next
     * ask tries again.
     */
    fun ensureLabelSuggestions(client: String) {
        if (client.isBlank() || config?.canManageUsers != true) return
        if (labelSuggestionsRef.current?.containsKey(client) == true) return
        val generation = labelGenerations.current?.get(client) ?: 0
        usersScope.launch {
            val fetched = apiResult { AdminApi.labelSuggestions(client) }.valueOrNull() ?: return@launch
            if ((labelGenerations.current?.get(client) ?: 0) != generation) {
                ensureLabelSuggestions(client)
                return@launch
            }
            // A functional update: this runs after the await, when the map captured above may be stale -- two
            // clients fetched at once would otherwise each write back a map missing the other.
            setLabelSuggestions { current -> current + (client to fetched) }
        }
    }

    // The editor's Labels field offers the suggestions of the user's client -- on a new user, the client the form names,
    // the caller's own until it names one. Fetched from an effect rather than from `startEdit`/`startCreate`, which the
    // once-registered route listener reaches with the first render's (empty) config, and which would read a just-set
    // client back as the one it replaced.
    val editorLabelClient = editing?.client ?: draftClient.ifEmpty { null } ?: config?.user?.client ?: ""
    val editorOpen = editing != null || creating
    // Keyed on whether the client's entry is there too, so suggestions dropped after a save are fetched again.
    useEffect(editorOpen, editorLabelClient, config?.canManageUsers, editorLabelClient in labelSuggestions) {
        if (editorOpen) ensureLabelSuggestions(editorLabelClient)
    }

    // The Labels filter offers the suggestions of the client being searched: the one the Client filter names -- which
    // only an `allClients` caller is offered, so a shared link naming another client asks nothing of anyone else --
    // else the caller's own. For an `allClients` caller searching every client, that is their own client's list, and
    // any other label is typed. Fetched once the well is open, so a page nobody filters asks for nothing.
    val searchesAllClients = config?.user?.roles?.contains(ROLE.allClients) == true
    val labelFilterClient = textFilters[USF.client]?.trim()?.ifEmpty { null }?.takeIf { searchesAllClients }
        ?: config?.user?.client ?: ""
    useEffect(filtersOpen, labelFilterClient, config?.canManageUsers, labelFilterClient in labelSuggestions) {
        if (filtersOpen) ensureLabelSuggestions(labelFilterClient)
    }

    /**
     * Drops [client]'s suggestions after a save that wrote labels, so the next label control to ask re-reads them --
     * they include the labels the client's users carry (issue #1481), and a label just typed should be offered from
     * then on.
     */
    fun forgetLabelSuggestions(client: String) {
        labelGenerations.current?.let { it[client] = (it[client] ?: 0) + 1 }
        setLabelSuggestions { current -> current - client }
    }

    /** Opens the editor on [user], seeding the draft from their current state. */
    fun startEdit(user: AdminUser) {
        editing = user
        creating = false
        seedDraft(user)
    }

    /** Opens the editor on a new user -- for the caller's own address when [forSelf] (issue #797). */
    fun startCreate(forSelf: Boolean = false) {
        editing = null
        creating = true
        creatingForSelf = forSelf
        seedDraft(null, if (forSelf) ownAddressRef.current ?: "" else "")
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
    // editor skipped the whole page and landed wherever you were before arriving at Users. The address now says
    // which record is open, exactly as the endpoint catalog says which endpoint is.

    /** Who the editor is open on, as the address should say it: a user id, `new`, or nothing in the list view. */
    fun openRecord(): String? = when {
        creating -> if (creatingForSelf) HP.selfRecord else HP.newRecord
        else -> editing?.userId?.toString()
    }

    // The record open now, read through a ref by the listener: an address naming what is already open -- the one the
    // leave guard puts back after a "stay" (issue #1478) -- reopens nothing, so it cannot re-seed the draft it kept.
    val openRecordRef = useRef<String>(null)
    openRecordRef.current = openRecord()

    /** Whether the page is read-only (an editor, issue #1432), read through a ref by the listener, as the rows are. */
    val readOnlyRef = useRef(false)
    readOnlyRef.current = usersReadOnly(config)

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
        // The person behind the user is the administrator's to see (`clientAdmin`); an editor browses the user alone.
        if (open != null && !open.deleted && config?.canManageUsers == true) {
            usersScope.launch {
                val result = apiResult { AdminApi.userIdentity(open.userId) }
                result.failureOrNull()?.let {
                    console.error("$errorLogPrefix could not load the person behind user ${open.userId}: ${it.message}")
                }
                val loaded = result.valueOrNull()
                if (identityFor.current == open.userId) identity = loaded
            }
        }
    }

    /** Opens the editor on what the address names, or closes it. Used by the route listener below. */
    fun applyEditorRoute() {
        val open = routeParams()[HP.user]
        if (open != null && open == openRecordRef.current) return
        when {
            open == null -> closeEditor()
            // An editor (issue #1432) creates nobody: an address naming a create opens the list rather than an empty page.
            (open == HP.newRecord || open == HP.selfRecord) && readOnlyRef.current == true -> closeEditor()
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
        // Only the editor is restored on an address change (Back/Forward, a menu link). The search is deliberately
        // NOT re-run here: `onRouteChange` registers a listener that is never removed (see Routing), so one
        // survives per past visit to this page -- harmless while it only calls setState on an unmounted
        // component (React ignores that), but a search re-run would fire a real request from every stale
        // listener on every later address change. The search reaches the URL only two ways, both safe: a fresh
        // mount restores it in the generation effect below (a reload or a pasted/bookmarked link), and within
        // the page it is written with `replace`, so there are no search history entries for Back to return to
        // anyway -- the only in-page history is the editor, which `applyEditorRoute` handles.
        onRouteChange { applyEditorRoute() }
    }

    // Open whatever the address named once the rows are in -- a reload inside the editor, or a link. Until then
    // the address cannot be honored, so `restored` also holds the sync effect below: without that gate it would
    // run first with an empty editor and push the record out of the URL before anything could read it. Only the
    // editor here: the initial search already ran from the address in the generation effect.
    useEffect(loaded) {
        if (loaded && !restored) {
            applyEditorRoute()
            restored = true
        }
    }

    // Keep the address in step with the editor *and the search* (issue #411) -- the same arrangement the endpoint
    // catalog uses, and for the same reason: opening a record is a navigation (a pushed history entry), while
    // the search merely refines where you are (replaced in place), so typing a filter does not spam Back. Only
    // the open record is in [userIdentity], so a filter change never pushes.
    useEffect(
        editing, creating, restored,
        textFilters, rangeFilters, anyText, sortBy, descending, shown,
    ) {
        if (!restored) {
            return@useEffect
        }
        val params = buildList {
            add(HP.page to HMENU.pageUsers)
            openRecord()?.let { add(HP.user to it) }
            addAll(searchRouteParams(query()))
            addAll(shownRouteParams(shown))
        }
        // Reachable means the address as it stands names something this page could show. A `u=` naming a row we
        // do not hold is a URL to correct in place -- otherwise Back onto it would push again and never move.
        val current = routeParams()[HP.user]
        val reachable = current == null || current == HP.newRecord || current == HP.selfRecord ||
            users.any { it.userId.toString() == current } ||
            identity?.users?.any { it.userId.toString() == current } == true
        applyRouteWrite(params, userIdentity, reachable)
    }

    val denied = config != null && !mayBrowseUsers(config)
    // The editor role is not offered in `public` (issue #1432), where no client role widens what a user reads: the user
    // edited, or the client a new one is made in -- the chosen one, else the caller's.
    val editorOffered = (editing?.client ?: draftClient.ifEmpty { null } ?: config?.user?.client) != CL.public

    /** The authority the draft describes, as the editor controls it (the Editor box only where it is drawn). */
    val draftAuthorityNow = draftAuthority(draftLevel, draftEditor, draftAllClients, editorOffered)

    /** The role list this draft describes, given what [current] holds now ([rolesForAuthority]). */
    fun draftRoles(current: List<String>): List<String> = rolesForAuthority(current, draftAuthorityNow)

    /** Whether the open user is a sandbox user somebody opened (issue #1198), whose roles and organization are a record. */
    val openedNote = editing?.let { openedUserNote(it) }

    /** What the draft changes on the open user: the calls a save makes, and what makes the draft dirty. */
    fun draftChanges(target: AdminUser): List<UserChange> = userDraftChanges(
        target, draftAuthorityNow, editorOffered, draftOrg, draftName, draftIsEntity, draftEnabled, draftLabels,
        opened = openedUserNote(target) != null,
    )

    // Whether a create may mail an invitation at all: not for the caller's own address, however it came to be typed.
    val invitationOffered = creating && invitationOfferedFor(creatingForSelf, draftEmail, ownAddress)
    // Whether the User panel holds edits nobody has saved (issue #1478): its heading says so, its Save is offered, and
    // leaving -- Back, another of the person's users, or any other address -- asks first. Never on a page the caller may
    // no longer use (signed out, or switched to a user who may not), whose draft is out of sight.
    val readOnlyView = usersReadOnly(config)
    val draftDirty = !readOnlyView && !denied && when {
        creating -> createSeed?.let { seeded ->
            val current = CreateDraft(
                draftEmail, draftName, draftIsEntity, draftLevel, draftEditor, draftAllClients, draftOrg, draftClient,
                draftPersona, draftPersonaSuffix, draftLabels, draftEnabled, draftInvite,
            )
            createDraftDirty(current, seeded)
        } == true
        else -> editing?.takeIf { !it.deleted }?.let { draftChanges(it).isNotEmpty() } == true
    }
    val leaveQuestion = "You have unsaved changes to this user. Leave and lose them?"
    useEffect(draftDirty, openRecord()) {
        if (draftDirty) {
            val here = openRecord()
            LeaveGuard.arm({ h -> h[HP.page] == HMENU.pageUsers && h[HP.user] == here }) {
                LeaveGuard.confirmLeave(leaveQuestion)
            }
        } else {
            LeaveGuard.disarm()
        }
    }
    // And never left armed by a page that has gone: its draft went with it.
    useEffectOnce {
        try {
            awaitCancellation()
        } finally {
            LeaveGuard.disarm()
        }
    }

    /**
     * Reads the open user afresh after a save that wrote some of its changes before one failed: the editor then shows
     * what is stored, so Cancel puts back what the user now holds and a retry sends only what is still unsaved.
     */
    suspend fun refreshOpenUser(userId: Long) {
        val fresh = apiResult { AdminApi.userIdentity(userId) }.valueOrNull() ?: return
        fresh.users.firstOrNull { it.userId == userId }?.let { editing = it }
        if (identityFor.current == userId) identity = fresh
        runSearch(query())
    }

    /** Runs [then] -- a move within the page away from the draft -- once the reader agrees to lose unsaved edits. */
    fun leaveDraft(then: () -> Unit) {
        if (!draftDirty || LeaveGuard.confirmLeave(leaveQuestion)) then()
    }

    /**
     * Applies the draft. Only what actually changed is sent -- the backend has one call per concern, so an untouched
     * field means no request rather than a redundant write -- in [userDraftChanges]' order. Save is offered only when
     * something did change, so there is always something to say was saved. A failure partway leads with what was
     * saved before it ([userSaveFailureLead]): those calls are not undone, and the user is read again
     * ([refreshOpenUser]).
     */
    fun save() = run({ saveError = it }) {
        saveError = null
        saveErrorLead = null
        val target = editing
        if (target == null) {
            // Caught here so a mistyped address is an immediate, local message rather than a round trip -- the
            // backend runs this same check (base/kernel), so it is the authority; this only spares the trip.
            val email = draftEmail.normalizeEmail()
            if (!email.isEmailAddress()) {
                saveError = DisplayError.expected("\"$email\" is not a valid email address.")
                return@run
            }
            val created = try {
                AdminApi.createUser(
                    email, roles = draftRoles(emptyList()),
                    org = draftOrg.trim().ifEmpty { null },
                    isEntity = draftIsEntity, name = draftName.trim().ifEmpty { null },
                    client = draftClient.trim().ifEmpty { null }, enabled = draftEnabled,
                    persona = if (personaChosen) draftPersona else null, personaSuffix = draftPersonaSuffix,
                    labels = normalizeUserLabels(draftLabels), sendInvitation = !invitationOffered || draftInvite,
                )
            } catch (e: Throwable) {
                // A collision on (address, client, persona) is what the personaSuffix is for: offer it, keep the
                // form, and let the refusal show as it is.
                if (isUserKeyCollision(e)) personaSuffixCollided = true
                saveErrorLead = "Couldn't create the user."
                throw e
            }
            // A user registered at once was the caller's own, whatever the form could tell.
            val invitable = invitationOffered && draftEnabled && !created.registered
            val made = createdNote(created.primaryId, invitable, draftInvite, created.invitationSent)
            note = made.text
            noteFailed = made.failed
            if (created.labels.isNotEmpty()) forgetLabelSuggestions(created.client)
        } else {
            val changes = draftChanges(target)
            val saved = mutableListOf<UserChange>()
            for (change in changes) {
                try {
                    when (change) {
                        UserChange.roles -> AdminApi.setRoles(target.userId, draftRoles(target.roles))
                        UserChange.org -> AdminApi.setOrg(target.userId, draftOrg.trim().ifEmpty { null })
                        // The name and what kind of account it is travel together: the flag labels the name rather
                        // than selecting a different field, so one call carries both.
                        UserChange.accountName -> AdminApi.setName(target.userId, draftName.trim().ifEmpty { null }, draftIsEntity)
                        UserChange.enabled -> AdminApi.setEnabled(target.userId, draftEnabled)
                        UserChange.labels -> {
                            AdminApi.setLabels(target.userId, normalizeUserLabels(draftLabels))
                            forgetLabelSuggestions(target.client)
                        }
                    }
                } catch (e: Throwable) {
                    saveErrorLead = userSaveFailureLead(saved, change)
                    if (saved.isNotEmpty()) refreshOpenUser(target.userId)
                    throw e
                }
                saved += change
            }
            note = "Saved ${target.primaryId}."
            noteFailed = false
        }
        closeEditor()
        runSearch(query())
    }

    /**
     * Runs [send], a mail to the open user, with its result in the mail panel. A refusal can mean the user moved on while
     * open -- claimed, or disabled -- so the user is read again, and the panel shows what now fits, with the refusal in it.
     */
    fun sendMail(failureLead: String, sentNote: (AdminUser) -> String, send: suspend (AdminUser) -> Unit) =
        run({ inviteError = it }) {
            val target = editing ?: return@run
            inviteNote = null
            inviteError = null
            inviteErrorLead = failureLead
            try {
                send(target)
            } catch (e: Throwable) {
                refreshOpenUser(target.userId)
                throw e
            }
            inviteNote = sentNote(target)
        }

    /** Sends the open user's invitation (issue #751), saying so in the Invitation panel. */
    fun sendInvitation() = sendMail("Couldn't send the invitation.", { "Invitation sent to ${it.primaryId}." }) {
        AdminApi.inviteUser(it.userId)
    }

    /** Sends the open, claimed user a reminder of where to sign in (issue #1504), saying so in its panel. */
    fun sendReminder() = sendMail("Couldn't send the reminder.", { "Reminder sent to ${it.primaryId}." }) {
        AdminApi.remindUser(it.userId)
    }

    /**
     * Permanently deletes the user being edited: the email and identity are obfuscated irrecoverably. Reached through
     * [DangerConfirm], a deliberate two-step. The recoverable "delete" is the Enabled checkbox, not this.
     */
    fun performDelete() = run({ deleteError = it }) {
        val target = editing ?: return@run
        val result = AdminApi.deleteUser(target.userId, permanent = true)
        note = "Permanently deleted ${target.primaryId}: ${result.primaryId}."
        noteFailed = false
        closeEditor()
        runSearch(query())
    }

    // An editor (issue #1432): every user of its client is shown, and nothing that changes one.
    val readOnly = readOnlyView
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
            // ---- editor (issue #1478) -----------------------------------------
            //
            // Built to the page structure of #1477: a header, the structure line, then panels -- the User draft, whose
            // one Save covers every field in it; the live Invitation or Sign-in reminder, and API keys, which act as
            // they are pressed; the read-only Activity; and the danger zone last. Each says how its own action went.
            val open = editing
            // Editing yourself: the backend refuses to let anyone change their own administrator status or disable
            // their own account, so those controls are locked here rather than offered and then rejected. The
            // backend remains the enforcement point -- this only keeps the UI honest.
            val self = open != null && open.userId == config?.user?.userId
            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    disabled = busy
                    onClick = { leaveDraft { closeEditor() } }
                    +"← Back to users"
                }
            }
            h1 { +(open?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.primaryId } ?: editorTitle(creating, creatingForSelf)) }
            if (open != null) {
                div {
                    className = ClassName("client-header-line")
                    userStatusChips(open).forEach { chip ->
                        span {
                            key = chip.unsafeCast<Key>()
                            className = ClassName("user-mark")
                            +chip
                        }
                    }
                    // Where this user's forms and client are (issue #1081), to the callers the list offers them -- the
                    // client link not to an editor, whom the Clients page refuses, as on the list -- and not for a
                    // deleted user, who has neither any more.
                    val openBecome = open.takeIf { offersBecome(it) }
                    if ((linksOffered || openBecome != null) && !open.deleted) {
                        UserLinks {
                            userId = open.userId
                            client = open.client
                            this.carriedClients = carriedClients
                            showForms = linksOffered
                            showClient = linksOffered && !readOnly
                            become = openBecome
                            // The session changes before the move home, so unsaved edits are asked about first.
                            beforeBecome = { go ->
                                leaveDraft {
                                    LeaveGuard.disarm()
                                    go()
                                }
                            }
                        }
                    }
                }
                // The person behind the user (issue #770): the person's users, each of which opens in its turn.
                if (!open.deleted) {
                    userStructureLine(userStructure(open, identity?.users), disabled = busy) { picked ->
                        leaveDraft { startEdit(picked) }
                    }
                }
            }

            if (open?.deleted == true) {
                // A permanently-deleted tombstone: nothing to edit, and re-enabling it is precisely the bug this
                // read-only view exists to prevent. Show what survives and stop -- the backend refuses an edit either
                // way (loadEditableUser), so this is a courtesy over an enforced rule. The login identity is
                // obfuscated; the name and organization are kept so the record stays recognizable to somebody
                // debugging what the account owned.
                panel(panelUser, "User") {
                    readOnlyField("Email address", draftEmail)
                    readOnlyField("Id", open.userId.toString())
                    readOnlyField(if (open.isEntity) "Business name" else "Full name", open.name?.takeIf { it.isNotBlank() } ?: "—")
                    open.org?.takeIf { it.isNotBlank() }?.let { readOnlyField("Organization", it) }
                    p {
                        className = ClassName("subtitle")
                        +("This account was permanently deleted: its email was obfuscated and freed for reuse, and " +
                            "it cannot be recovered, edited, or re-enabled. The name is kept for reference.")
                    }
                }
            } else if (readOnly) {
                // An editor's view (issue #1432): the same panels, with nothing to save or press.
                open?.let { user ->
                    panel(panelUser, "User") { userDetails(user) }
                    panel(panelActivity, "Activity") {
                        for ((label, value) in identitySummary(null, user)) readOnlyField(label, value)
                    }
                }
            } else {
            // Another user at the caller's own address is theirs too: nobody mails themself a reminder.
            val mailOffer = open?.let {
                userMailOffer(it, own = self || it.primaryId == ownAddress, carried = clientCarried(it.client, carriedClients))
            } ?: UserMailOffer.none
            val keysShown = open != null && config?.user?.let { mayCreateForOthers(it) } != false
            val dangerShown = open != null && !self
            if (open != null) {
                val shown = buildList {
                    add(panelUser to "User")
                    when (mailOffer) {
                        UserMailOffer.invitation -> add(panelInvitation to "Invitation")
                        UserMailOffer.reminder -> add(panelInvitation to "Sign-in reminder")
                        UserMailOffer.none -> {}
                    }
                    add(panelActivity to "Activity")
                    if (keysShown) add(panelKeys to "API keys")
                    if (dangerShown) add(panelDanger to "Danger zone")
                }
                if (shown.size > 2) panelLinks(shown)
            }

            // ---- User: the draft, saved together -----------------------------
            val editorShown = openedNote == null && draftLevel != ROLE.admin && editorOffered
            val allClientsShown = openedNote == null && config?.user?.roles?.contains(ROLE.allClients) == true
            panel(panelUser, "User", kind = PanelKind.draft, dirty = draftDirty) {
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
                readOnlyField("Email address", draftEmail, copyEmailLabel)
                readOnlyField("Id", open?.userId?.toString() ?: "")
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

            // A sandbox user somebody opened (issue #1198) acts with the roles and organization of the user it was
            // opened from, so its own are a record: what it acts with is shown, and nothing is offered to change --
            // the backend refuses either edit, and a control that could only fail is the drift this page avoids.
            if (openedNote != null) {
                readOnlyField("Acts with", open?.actingRoles.orEmpty().joinToString(", ").ifEmpty { "no roles" })
                p {
                    className = ClassName("type-hint")
                    +openedNote
                }
            } else {
                // The Operator rung is deployment-wide since #464 (it requires allClients), so offer it only to a
                // caller who can grant that reach -- one holding allClients -- or when the edited user already is an
                // operator, so the value shows and can be kept (anti-escalation checks adding, not the result set).
                // Withholding a choice that would only 400 is the rule the All-clients checkbox keeps.
                val operatorSelectable = config?.user?.roles?.contains(ROLE.allClients) == true ||
                    open?.roles?.contains(ROLE.operator) == true

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
                        style = choiceStyle()
                        onChange = { v ->
                            val level = v as? String ?: ROLE.user
                            draftLevel = level
                            // Roles provide a default persona (issue #750), until one is chosen outright.
                            if (creating && !personaChosen) draftPersona = personaForRoles(level, draftEditor)
                        }
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +accessLevelHint(operatorSelectable)
                }
            }

            // Chosen at creation and never again (issue #352). A user's content carries their client both in
            // its `client` column and inside every gedra id, so moving one would strand it -- there is no
            // set-client call for an editor to offer, which is why this is a selector on create and plain text
            // afterward. Offered only to a caller holding the capability: a scoped administrator naming another
            // client could only ever produce a 400.
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
                        style = choiceStyle()
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
            // administrator may still change: the persona says which of the person's users this is, the level what they
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
                        options = personaOptions(editorOffered = editorOffered)
                        disabled = busy
                        style = choiceStyle()
                        onChange = { v ->
                            val chosen = v as? String ?: PERSONA.member
                            draftPersona = chosen
                            personaChosen = true
                            draftLevel = levelForPersona(chosen)
                            draftEditor = PERSONA.def(chosen)?.defaultRoles?.contains(ROLE.editor) == true
                        }
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +personaHint
                }
            } else {
                readOnlyField("Persona", personaCell(draftPersona, draftPersonaSuffix, open?.let { it.actingRoles ?: it.roles }))
            }

            // Editable only by someone not confined to an organization: the backend lets a confined
            // administrator assign only their own, so anything else here could only ever produce a 400. Nor on a
            // sandbox user somebody opened, whose organization is the one it was opened from's (issue #1196).
            if (config?.user?.org == null && openedNote == null) {
                textField("Organization", draftOrg, disabled = busy) { draftOrg = it }
                p {
                    className = ClassName("type-hint")
                    +orgHint
                }
            } else {
                readOnlyField("Organization", draftOrg.ifEmpty { "—" })
            }
            if (self) {
                p {
                    className = ClassName("type-hint")
                    +"This is your own account: another administrator has to change your role or disable you."
                }
            }

            // The rest, on request (rule 5 of #1477): open by itself whenever any of it is set away from its default.
            MoreOptions {
                recordKey = openRecord() ?: HP.newRecord
                inForce = userMoreOptionsInForce(
                    editor = editorShown && draftEditor, allClients = allClientsShown && draftAllClients,
                    labels = draftLabels, enabled = draftEnabled, creating = creating,
                    invitationHeld = invitationHeld(invitationOffered, draftEnabled, draftInvite),
                    personaSuffix = if (creating) draftPersonaSuffix else "",
                    suffixNeeded = creating && personaSuffixCollided,
                )
                // The editor role (issue #1432): beside the level rather than one of its rungs -- an operator does not
                // hold it -- and offered below the admin level, since an administrator already holds everything it adds.
                if (editorShown) {
                    div {
                        className = ClassName("row")
                        Checkbox {
                            checked = draftEditor
                            disabled = busy || self
                            onChange = { event ->
                                val on = event.target.checked as Boolean
                                draftEditor = on
                                if (creating && !personaChosen) draftPersona = personaForRoles(draftLevel, on)
                            }
                            +"Editor"
                        }
                    }
                    p {
                        className = ClassName("type-hint")
                        +editorHint
                    }
                }
                // Offered only to a caller who holds the capability, because the backend refuses to let anyone
                // grant reach they do not have themselves -- a control that could only ever produce a 400 is the
                // advertise-versus-serve drift this codebase keeps removing, just relocated into a form.
                if (allClientsShown) {
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
                    // reach it is not conferring; here the state is legal and worth keeping.
                    if (isAllClientsDormant(draftLevel, draftAllClients)) {
                        p {
                            className = ClassName("type-hint")
                            +allClientsDormantHint
                        }
                    }
                }
                // The persona suffix (issue #797): on every create form -- a first "Member B" needs it as much as a
                // second one does -- and asked for outright after a collision.
                if (creating) {
                    textField("Persona suffix", draftPersonaSuffix, disabled = busy) { draftPersonaSuffix = it }
                    p {
                        className = ClassName("type-hint")
                        +personaSuffixHint(collided = personaSuffixCollided)
                    }
                }
                // Labels (issues #786, #1481), on a new user as on an existing one. A tags select: the suggestions of
                // the user's client -- on a new user, the client the form names -- are offered, and anything else may
                // be typed, because a label is free-form by design.
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
                            userLabelChoices(labelSuggestions[editorLabelClient].orEmpty(), draftLabels),
                        )
                        placeholder = "(none)"
                        disabled = busy
                        style = choiceStyle()
                        onChange = { v -> draftLabels = (v.unsafeCast<Array<String>>()).toList() }
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +labelsHint
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
                    +enabledHint(creating)
                }
                // Not at the caller's own address, where nothing is mailed. A disabled user is never invited, so the
                // box waits, saying why, rather than promising a mail that will not go.
                if (invitationOffered) {
                    div {
                        className = ClassName("row")
                        Checkbox {
                            checked = draftInvite && draftEnabled
                            disabled = busy || !draftEnabled
                            onChange = { event -> draftInvite = event.target.checked as Boolean }
                            +"Send invitation"
                        }
                    }
                    p {
                        className = ClassName("type-hint")
                        +sendInvitationHint
                    }
                    if (!draftEnabled) {
                        p {
                            className = ClassName("type-hint")
                            +sendInvitationWaitsHint
                        }
                    }
                }
            }

            SaveBar {
                saveText = if (creating) "Create user" else "Save user"
                // How a save takes effect (rule 2 of #1477): one call per change, not one transaction.
                effectText = if (creating) null else "Each change is saved on its own, in turn: if one fails, those " +
                    "before it stay saved, and the panel says which."
                saving = busy
                canSave = if (creating) draftEmail.isNotBlank() else draftDirty
                failure = saveError
                failureLead = saveErrorLead
                onSave = { save() }
                // A new user's Cancel leaves the form, asking first when anything was typed; an existing one's
                // takes the fields back to what is saved, and stays on the user.
                onDiscard = {
                    if (creating) {
                        leaveDraft { closeEditor() }
                    } else {
                        seedFields(open)
                        saveError = null
                        saveErrorLead = null
                    }
                }
            }
            }

            if (open != null) {
                // ---- Invitation: live (issue #751) ---------------------------
                //
                // Nobody has claimed the user. Offer to send an invitation -- a lapsed link, a lost mail, one held back
                // at the create, a user created disabled and enabled since. The backend refuses a claimed or disabled
                // one, so the panel is shown only where it can succeed.
                if (mailOffer == UserMailOffer.invitation) {
                    panel(panelInvitation, "Invitation", kind = PanelKind.live) {
                        p {
                            className = ClassName("type-hint")
                            +"Nobody has claimed this account yet. Sending an invitation mails a fresh link, good for seven days."
                        }
                        div {
                            className = ClassName("row")
                            Button {
                                disabled = busy
                                onClick = { sendInvitation() }
                                +"Send invitation"
                            }
                        }
                        panelNote(inviteError, inviteNote, inviteErrorLead)
                    }
                }

                // ---- Sign-in reminder: live (issue #1504) --------------------
                //
                // For a claimed user, whom an invitation is refused for: its link would sign in whoever held the mail.
                if (mailOffer == UserMailOffer.reminder) {
                    panel(panelInvitation, "Sign-in reminder", kind = PanelKind.live) {
                        p {
                            className = ClassName("type-hint")
                            +("This person has already claimed the account. A reminder mails them where to sign in, " +
                                "with a link to the sign-in page, which still asks for a code or their password.")
                        }
                        div {
                            className = ClassName("row")
                            Button {
                                disabled = busy
                                onClick = { sendReminder() }
                                +"Send sign-in reminder"
                            }
                        }
                        panelNote(inviteError, inviteNote, inviteErrorLead)
                    }
                }

                // ---- Activity: read-only (issue #770) ------------------------
                panel(panelActivity, "Activity") {
                    for ((label, value) in identitySummary(identity, open)) readOnlyField(label, value)
                }

                // ---- API keys: live (issue #1402) ----------------------------
                //
                // A key is made or revoked as it is asked for. A sandbox's users too (issue #1453). Not from `public`,
                // where a self-administrator manages their own keys on their profile and the administrator's surface
                // refuses them.
                if (keysShown) {
                    panel(panelKeys, "API keys", kind = PanelKind.live) {
                        ApiKeysSection {
                            key = "keys-${open.userId}".unsafeCast<Key>()
                            // One's own keys are managed as on Profile, through the self endpoints.
                            ownerId = open.userId.takeIf { it != config?.user?.userId }
                        }
                    }
                }

                // ---- Danger zone: the permanent delete, never your own account ---
                //
                // Deliberately *only* the irreversible delete: the recoverable "delete" is the Enabled box under
                // More options, so offering it a second time here as a plain disable would be the same action twice.
                if (dangerShown) {
                    panel(panelDanger, "Danger zone", kind = PanelKind.danger) {
                        p {
                            className = ClassName("type-hint")
                            +("Delete permanently: the email is obfuscated and freed for reuse, and the account cannot " +
                                "be recovered. To only suspend the user, uncheck Enabled under More options instead.")
                        }
                        DangerConfirm {
                            recordKey = open.userId.toString()
                            actionText = "Delete user…"
                            question = deleteUserQuestion(open.client)
                            confirmText = "Confirm permanent delete"
                            keepText = "Keep user"
                            inProgress = busy
                            onConfirm = { performDelete() }
                        }
                        panelNote(deleteError, null, "Couldn't delete the user.")
                    }
                }
            }
            }
        } else {
            // ---- find -------------------------------------------------------
            h1 { +"Users" }
            p {
                className = ClassName("subtitle")
                +(if (readOnly) {
                    "Filter and sort the active users of your client, then select one to see their details."
                } else {
                    "Filter and sort active users, then select one to edit — or create a new user."
                })
            }

            error?.let { errorText(it) }
            note?.let {
                p {
                    className = ClassName(if (noteFailed) "error-text" else "form-ok")
                    +it
                }
            }

            // Only a full-scope caller sees the client filter and column -- the same rule the create selector
            // follows, since the client is a distinction only to somebody who can see more than one.
            val showClient = config?.user?.roles?.contains(ROLE.allClients) == true

            // A whitespace-only term is kept in the map (so it can be typed) but filters nothing, so it does not
            // count as an active filter here -- matching what the query serialization actually sends.
            val anyFilter = textFilters.values.any { it.isNotBlank() } || rangeFilters.values.any { !it.isEmpty } ||
                anyText.isNotBlank()
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
                if (!readOnly && config?.user?.let { mayCreateForOthers(it) } != false) {
                    Button {
                        onClick = { startCreate() }
                        +"Create user"
                    }
                }
                // A further user for the caller's own address (issue #797): registered on creation and switchable
                // at once from the badge, with no invitation -- the case that otherwise meant typing your own address.
                if (!readOnly && ownAddress != null) {
                    Button {
                        onClick = { startCreate(forSelf = true) }
                        +"Create a user for me"
                    }
                }
                // The free-text search (issue #1432), on screen as the forms list's is: the quickest way to find someone
                // without knowing which field holds what you remember, and the one way to a batch member by suffix.
                Input {
                    placeholder = "Search email, name, username, persona, or label"
                    value = anyText
                    allowClear = true
                    style = js("({ width: 300 })")
                    onChange = { event -> setAnyText(event.target.value as? String ?: "") }
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
                // The date ranges are taller than the text boxes (two pickers and a hint), and a row is as tall as its
                // tallest cell: the first of them starts a row of its own when that saves height ([breakBeforeTall]).
                val shown = userSearchFieldSpecs.filter { (!it.allClientsOnly || showClient) && it.filterKind != null }
                val dateCount = shown.count { it.filterKind == UserFilterKind.dateRange }
                val breakForDates = breakBeforeTall(shown.size - dateCount, dateCount, filterWellColumns(wellWidth))
                var datesStarted = false
                for (spec in shown) {
                    val kind = spec.filterKind ?: continue
                    val startsRow = breakForDates && kind == UserFilterKind.dateRange && !datesStarted
                    if (kind == UserFilterKind.dateRange) datesStarted = true
                    filterGroup(spec.label, startsRow) {
                        when (kind) {
                            UserFilterKind.substring -> Input {
                                value = textFilters[spec.name] ?: ""
                                placeholder = "${spec.label} contains…"
                                // Fills its track up to a cap, so a narrow browser still shrinks it (issue #462).
                                style = js("({ width: '100%', maxWidth: 420 })")
                                onChange = { event -> setText(spec.name, event.target.value as String, immediate = false) }
                            }
                            // Exact fields render as a picker, its choices from where the spec says (issue #1432).
                            UserFilterKind.exact -> when (spec.choices) {
                                // The labels (issues #786, #1481): the client's suggestions, or any label typed, since
                                // a label need not be one of them. Every suggestion stays offered whatever is in the
                                // box (see "Choice widgets" in the webapp's CLAUDE.md). A pick or a clear searches at
                                // once; typing waits, as a text box does, even when the word typed so far is itself
                                // a label (`lead` on the way to `leader`). antd reports a pick to `onChange` as well
                                // as `onSelect`; the debounced half then finds that search already run, and skips.
                                UserFilterChoices.labels -> AutoComplete {
                                    value = textFilters[spec.name] ?: ""
                                    options = labelSelectOptions(labelSuggestions[labelFilterClient].orEmpty())
                                    filterOption = false
                                    placeholder = "Any"
                                    allowClear = true
                                    style = js("({ minWidth: 180 })")
                                    onChange = { v ->
                                        val label = v as? String ?: ""
                                        setText(spec.name, label, immediate = label.isEmpty())
                                    }
                                    onSelect = { v -> setText(spec.name, v as? String ?: "", immediate = true) }
                                }
                                // The spec's own options (the persona, the roles), else the clients this caller may
                                // choose among.
                                UserFilterChoices.listed, UserFilterChoices.clients, null -> Select {
                                    value = (textFilters[spec.name] ?: "").ifEmpty { null }
                                    options = if (spec.choices == UserFilterChoices.listed) {
                                        filterOptions(spec.options.orEmpty(), showClient)
                                    } else {
                                        clientOptions(clientChoices)
                                    }
                                    // "Any roles" would misread a list-valued field; its label above says which.
                                    placeholder = if (spec.listValued) "Any" else "Any ${spec.label.lowercase()}"
                                    allowClear = true
                                    style = js("({ minWidth: 180 })")
                                    onChange = { v -> setText(spec.name, v as? String ?: "", immediate = true) }
                                }
                            }
                            UserFilterKind.dateRange -> {
                                val range = rangeFilters[spec.name] ?: DateRange()
                                // The bounds are read in the client's zone, as the listed times are shown (issue
                                // #1235), so "after 5:00 PM" means what the column says 5:00 PM is.
                                val zone = entryZone()
                                // Both pickers are described by the one note naming the zone.
                                val zoneNote = "zone-${spec.name}"
                                div {
                                    className = ClassName("filter-range")
                                    DatePicker {
                                        value = zonedPickerValue(range.after, zone)
                                        showTime = true
                                        showNow = false
                                        asDynamic()["aria-describedby"] = zoneNote
                                        // Bounded: a date-time picker has a known amount to show, so it gains
                                        // nothing from being wider.
                                        style = js("({ maxWidth: 220 })")
                                        onChange = { date, _ -> setRange(spec.name, DateRange(zonedPickerIso(date, zone), range.before)) }
                                    }
                                    span {
                                        className = ClassName("type-hint")
                                        +"to"
                                    }
                                    DatePicker {
                                        value = zonedPickerValue(range.before, zone)
                                        showTime = true
                                        showNow = false
                                        asDynamic()["aria-describedby"] = zoneNote
                                        style = js("({ maxWidth: 220 })")
                                        onChange = { date, _ -> setRange(spec.name, DateRange(range.after, zonedPickerIso(date, zone))) }
                                    }
                                }
                                span {
                                    asDynamic()["id"] = zoneNote
                                    className = ClassName("type-hint")
                                    +"Times in ${entryZoneNote(zone)}. Leave either end empty for open-ended."
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
                        +listingCountText(users.size, numAvailable, "matching user", "matching users")
                    }
                }
                UserTable {
                    this.showClient = showClient
                    this.showLinks = linksOffered
                    this.offersBecome = ::offersBecome.takeIf { appConfig().hasTestAccess }
                    this.showClientLinks = !readOnly
                    this.carriedClients = carriedClients
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
                // More of the same search on request (issue #1315), under the rows it adds to.
                if (loaded) {
                    showMoreBar(users.size, shown, numAvailable, "matching user", "matching users", false) { next ->
                        runSearch(queryRef.current ?: query(), next)
                    }
                }
            }
        }
    }
}

/**
 * A label plus a static value, for the identity fields the backend does not let an administrator change, with a
 * [CopyButton] saying [copyLabel] when the value is one a person wants elsewhere (issue #1511).
 */
internal fun react.ChildrenBuilder.readOnlyField(label: String, value: String, copyLabel: String? = null) {
    div {
        className = ClassName("row")
        span {
            className = ClassName("field-label")
            +label
        }
        span { +value }
        copyLabel?.let {
            CopyButton {
                text = value
                this.label = it
            }
        }
    }
}

/** What a read-only email address's [CopyButton] says it copies (issue #1511). */
private const val copyEmailLabel = "Copy this user's email address"

/**
 * The [UserSearchQuery] a shared/bookmarked URL encodes (issue #411), read generically from the shared spec:
 * the address carries the **same keys as the wire** (a field's own name for a text filter, the spec's range keys
 * for a date range, plus sort), so this and [searchRouteParams] mirror `AdminApi.userSearchArgs`. An absent key
 * is its default; an unrecognized sort key (a hand-edited or stale link) drops to the default so a pasted link
 * can never ask the endpoint for a field it would reject. Pure, and covered under `jsNodeTest`.
 */
fun searchQueryFromRoute(hp: Map<String, String>): UserSearchQuery {
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
        // The any-text term (issue #581) round-trips through the address like the other filters -- read back so a
        // shared or bookmarked search keeps it, rather than silently dropping it on decode.
        anyText = hp[EI.q]?.takeIf { it.isNotBlank() },
    )
}

/**
 * The route params for a search [query] (issue #411): the same key/value pairs `AdminApi.userSearchArgs` puts
 * on the wire (minus the limit), emitting only what differs from the default so a plain search stays a bare
 * `page=users`. Pure, and covered under `jsNodeTest` -- it round-trips with [searchQueryFromRoute].
 */
fun searchRouteParams(query: UserSearchQuery): List<Pair<String, String>> =
    userSearchArgs(query).mapNotNull { (k, v) ->
        when {
            // The sort defaults are omitted so an untouched search carries no params.
            k == USF.sortBy && v == USF.lastEdited.at -> null
            k == USF.descending && v == true -> null
            k == USF.descending -> k to "false"
            else -> k to v.toString()
        }
    }

/** The editor's panels' element ids (issue #1478), which its panel links lead to. */
private const val panelUser = "user-panel"
private const val panelInvitation = "user-invitation"
private const val panelActivity = "user-activity"
private const val panelKeys = "user-api-keys"
private const val panelDanger = "user-danger"

/** The filter well's element id, so the toggle can name what it controls (`aria-controls`). */
private const val usersFiltersWellId = "users-filters"

/**
 * The filters in force said in words, one chip each, for the summary the closed well shows (issue #683):
 * `Email contains "ada"`, `Client is "acme"`, `Last login Aug 1, 2026, 5:00 AM CT – Sep 1, 2026, 5:00 AM CT`,
 * `Edited ≥ …`. Built from the shared spec in its own order, so a field added there gets a chip with no further
 * change, in the words the forms list's chips use ([textChip], [rangeChip]) so the two pages say the same thing
 * -- and a blank value is no chip, matching `anyFilter` and what the query sends.
 *
 * A date bound reads as the column it filters says its exact times on hover -- [formatTimestamp], in the client's
 * zone to the minute (issue #1115) -- so the chip and the rows it admitted are on one clock; a chip in another zone
 * beside the column would show one instant two ways. It stays absolute while the cells are relative (issue #1141): a bound is a
 * fixed instant, and `12m ago` would move under the filter it names.
 *
 * Every term in force is a chip, including one the caller's own well does not offer (the
 * client, for a caller without `allClients`, carried in by a shared link): it still counts as a filter to Clear
 * and to the count line, so it must be visible somewhere, and the chip is then the only somewhere. Pure, and
 * covered under `jsNodeTest`.
 */
fun userFilterChips(texts: Map<String, String>, ranges: Map<String, DateRange>): List<String> =
    userSearchFieldSpecs.mapNotNull { spec ->
        when (spec.filterKind) {
            UserFilterKind.substring -> textChip(spec.label, texts[spec.name], contains = true)
            UserFilterKind.exact -> exactChip(spec, texts[spec.name])
            UserFilterKind.dateRange -> ranges[spec.name]?.let { r ->
                rangeChip(spec.label, r.after?.let(::formatTimestamp), r.before?.let(::formatTimestamp))
            }
            null -> null
        }
    }

/**
 * An exact filter as a chip (issue #1432): its option's label where the spec lists options (`Persona is "Editor"`),
 * and `include` for a list-valued field (`Roles include "Editor"`, `Labels include "reviewer"`). A value no option
 * names -- a label, or a hand-edited link -- shows as it came. Pure, and covered under `jsNodeTest`.
 */
fun exactChip(spec: UserSearchFieldSpec, value: String?): String? {
    val v = value?.trim()?.ifEmpty { null } ?: return null
    val shown = spec.options?.firstOrNull { it.value.equals(v, ignoreCase = true) }?.label ?: v
    return if (spec.listValued) "${spec.label} include \"$shown\"" else textChip(spec.label, shown, contains = false)
}

/**
 * The choices an exact filter's picker offers (issue #1432), without those for a cross-client caller alone unless
 * [showAllClients].
 */
fun filterOptions(options: List<UserFilterOption>, showAllClients: Boolean): Array<dynamic> =
    options.filter { showAllClients || !it.allClientsOnly }.map { option ->
        val obj: dynamic = js("({})")
        obj.label = option.label
        obj.value = option.value
        obj
    }.toTypedArray()

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
private val accessLevelLabels = RoleLadder.ordered.associateWith { PERSONA.accessLabel(it) }

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

/**
 * The persona registry as antd `{ label, value }` option objects (issue #750), in the registry's order -- without the
 * editor unless [editorOffered] (issue #1432): not in `public`, where it would confer nothing.
 */
fun personaOptions(editorOffered: Boolean = true): Array<dynamic> =
    PERSONA.defs.filter { editorOffered || it.name != PERSONA.editor }.map { def ->
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
    "Which of this person's users this is: with the client and the persona suffix, it tells their users apart. " +
        "Chosen once, at creation, and it grants nothing by itself. Until you pick one it follows the access level; " +
        "picking one sets the level to its usual value, which you may still change. A later change of access leaves " +
        "the persona as it is, and the user's persona then says the access it has beside it."

/**
 * Whether [user] may create users at other people's addresses (issue #805): everyone who reaches the page except
 * an administrator in `public` without `allClients`, whose reach is their own users only -- the backend refuses
 * them the ordinary create, which would mail an invitation to somebody else. Pure, jsNodeTest-covered.
 */
fun mayCreateForOthers(user: UserProfile): Boolean = user.client != CL.public || ROLE.allClients in user.roles

/** The editor's heading: which record it is open on (issue #797 adds the caller's own). Pure, jsNodeTest-covered. */
/**
 * One user as an editor sees them (issue #1432): who they are, how they act, and their labels -- read-only, since an
 * editor changes no user. Nothing here is fetched beyond the row the search answered.
 */
private fun react.ChildrenBuilder.userDetails(user: AdminUser) {
    readOnlyField("Email address", user.primaryId, copyEmailLabel)
    readOnlyField(if (user.isEntity) "Business name" else "Full name", user.name?.takeIf { it.isNotBlank() } ?: "\u2014")
    readOnlyField("Persona", personaCell(user.persona, user.personaSuffix, user.actingRoles ?: user.roles))
    readOnlyField("Access", accessText(user.actingRoles ?: user.roles))
    user.org?.takeIf { it.isNotBlank() }?.let { readOnlyField("Organization", it) }
    readOnlyField("Labels", user.labels.joinToString(", ").ifEmpty { "\u2014" })
    readOnlyField("Status", if (user.enabled) "Enabled" else "Disabled")
    p {
        className = ClassName("subtitle")
        +"You can see this user, but changing them takes an administrator of the client."
    }
}

/**
 * Whether the shell says [config]'s caller may browse the Users page at all (issue #1432): an administrator, who edits
 * users there, or an editor, who reads them. Pure, covered under `jsNodeTest`.
 */
fun mayBrowseUsers(config: HomeConfig?): Boolean = config?.canManageUsers == true || config?.browsesUsers == true

/**
 * Whether the Users page is read-only for [config]'s caller (issue #1432): an editor, who sees every user of its client
 * and changes none. Never for an administrator, who holds the editor role too. Pure, covered under `jsNodeTest`.
 */
fun usersReadOnly(config: HomeConfig?): Boolean = config?.canManageUsers != true && config?.browsesUsers == true

/** [roles] with the editor role (issue #1432) granted or not: [rolesWithCapability] for a role that is not a capability. */
fun rolesWithEditor(roles: List<String>, granted: Boolean): List<String> = rolesWithCapability(roles, ROLE.editor, granted)

/**
 * The persona a user created at [level], an editor or not, takes when none is chosen -- [personaForLevel], with the
 * editor role (issue #1432), which the level alone cannot say. Pure, covered under `jsNodeTest`.
 */
fun personaForRoles(level: String, editor: Boolean): String =
    PERSONA.defaultFor(rolesWithEditor(RoleLadder.rolesAtLevel(emptyList(), level), editor))

/** What the Editor checkbox gives (issue #1432). */
const val editorHint =
    "Use this when someone should see every user and form of the client and edit forms, but administer nothing: no " +
        "changes to users, no new users, no client configuration. An administrator already has all of this."

/** How a role list reads as one access, for the read-only details an editor sees (issue #1432): the kernel's rule (issue #1450). */
fun accessText(roles: List<String>): String = PERSONA.accessLabel(PERSONA.accessOf(roles))

fun editorTitle(creating: Boolean, forSelf: Boolean): String = when {
    creating && forSelf -> "Create a user for me"
    creating -> "Create a user"
    else -> "Edit user"
}

/**
 * What happens at the address a create names (issues #751, #797, #1504) -- the backend's rule, said before the click:
 * the caller's own address gives a user registered at once, and anyone else's gets an invitation unless it is held
 * back or the user starts disabled. Pure, jsNodeTest-covered.
 */
fun createAddressHint(forSelf: Boolean): String = if (forSelf) {
    "Your own address. The new user is registered as soon as it is created, no invitation is sent, and it " +
        "appears in your account menu to switch to."
} else {
    "An invitation is mailed to this address, unless you turn it off or disable the user under More options, and the " +
        "user is claimed when its owner accepts it. If it is your own address, the user is registered at once " +
        "instead, and no invitation is sent."
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
    "Use this when the person needs a further user of the same address, client and persona: a short suffix (up to " +
        "${PERSONASUFFIX.maxLength} letters or digits: 1, 2, A, B), shown as \"Member B\". Leave blank for the " +
        "first one."
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
    "Use this when an administrator should manage every client, not only their own: the access level says what " +
        "someone may do, this says whose data. It is also what the full-scope admin endpoints require."

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
    "Use this when a workflow tests for a label -- \"reviewer\" can make someone a reviewer of a workflow's task. " +
        "Labels grant no access. The client's suggestions are offered, then the labels its users already carry; " +
        "type to add any other."

/**
 * What the Enabled box under More options is for (issue #1478): on a create, starting the account disabled; on an
 * existing user, the recoverable half of deleting one, as distinct from -- not a duplicate of -- the danger zone's.
 */
fun enabledHint(creating: Boolean): String = if (creating) {
    "Use this when the account should exist before it can sign in: uncheck to create it disabled, and enable it " +
        "here later."
} else {
    "Use this when the user should stop signing in for now: unchecking disables the account, a recoverable delete " +
        "you can undo here. To remove the account for good, use the danger zone."
}

/** What the Send invitation box under More options is for (issue #1504). */
const val sendInvitationHint =
    "Use this when you will tell the person yourself: uncheck to create the user without mailing anyone, and send the " +
        "invitation later from this page."

/** Beside [sendInvitationHint] while Enabled is off, why the box waits. */
private const val sendInvitationWaitsHint = "Waits for Enabled: a disabled user is not invited."

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

/**
 * The users table's Roles cell: the row's roles, and in a sandbox, where the two can differ (issue #1198), what the
 * user acts with beside them -- so a row reading "admin" does not pass for an administrator the sandbox's rules
 * withhold. Pure, covered under `jsNodeTest`.
 */
fun rolesCellText(user: AdminUser): String {
    val own = user.roles.joinToString(", ")
    val acting = user.actingRoles ?: return own
    if (acting.toSet() == user.roles.toSet()) return own
    return "$own (acts with: ${acting.joinToString(", ").ifEmpty { "none" }})"
}

/**
 * What the user editor says in place of the access and organization controls for a sandbox user somebody opened
 * (issue #1198) -- which user it acts as, or that it acts with nothing -- or null for any other user. The user it
 * acts as goes unnamed when it is one the caller does not administer, as the backend leaves it. Pure, covered
 * under `jsNodeTest`.
 */
fun openedUserNote(user: AdminUser): String? {
    if (!user.opened) return null
    val record = " Its own roles here are a record and change nothing; to change what it may do, change that user."
    if (!user.enabled) return "Opened from outside the sandbox, and disabled here: it acts with no roles."
    if (user.actingRoles.isNullOrEmpty()) {
        return "Opened from outside the sandbox by a user who no longer gives it access: it acts with no roles."
    }
    val client = user.actsAsClient
        ?: return "Opened from outside the sandbox by an administrator of another client: it acts with that user's roles.$record"
    // The organization comes with it only from the sandbox's parent: one of another client names nothing here.
    val what = if (client == sandboxParentOf(user.client)) "that user's roles and organization" else "that user's roles"
    val who = user.openedBy?.let { "user $it" } ?: "a user"
    return "Opened from outside the sandbox: this user acts as $who in $client, with $what.$record"
}
