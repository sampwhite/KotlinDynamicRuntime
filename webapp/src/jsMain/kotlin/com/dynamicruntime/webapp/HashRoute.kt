package com.dynamicruntime.webapp

/**
 * Shared URL-hash routing helpers. The app's navigation state lives in the location hash (`page=…&m=…&p=…&v=…`)
 * — client-only, survives a refresh, and needs no server-side routing (works under any static host, including
 * the appui bundle).
 *
 * Three ways to write it, and the difference is what the Back button does afterwards:
 *
 * - [navigateHash] assigns `location.hash`. It **fires `hashchange`**, which is how cross-page navigation
 *   (home ⇄ catalog) reaches the [App] router, and the browser adds the history entry itself.
 * - [pushHash] adds a history entry without firing `hashchange` — for a component navigating *within* a page
 *   it already owns, where nothing needs telling because the component is the one that moved.
 * - [replaceHash] rewrites the current entry, also without firing `hashchange` — for state that refines where
 *   you are rather than moving you (text typed into a form).
 *
 * That neither `pushState` nor `replaceState` fires `hashchange` is the load-bearing part: it is what lets a
 * component keep the URL in step with its own state without its own `hashchange` handler re-entering on it.
 * Choosing between the last two is the writer's decision — see `EndpointCatalog`'s `hashWrite`, and #324 for
 * what a page that only ever replaced cost its Back button.
 */

/**
 * The hash's parameter names — the app's client-side routing vocabulary, shared by everything that reads or
 * writes the URL. They live here rather than beside either reader because they are genuinely shared: [App]
 * routes on [page] and treats a bare [method] as a catalog deep-link, while `EndpointCatalog` writes all four,
 * and the two agreeing is what makes a pasted link land where it says.
 */
@Suppress("ConstPropertyName")
object HP {
    /** The page to show, e.g. `HMENU.pageCatalog`. */
    const val page = "page"

    /** Catalog deep-link: the selected endpoint's HTTP method. */
    const val method = "m"

    /** Catalog deep-link: the selected endpoint's path. */
    const val path = "p"

    /** Catalog deep-link: the entered input values, as compact JSON. */
    const val values = "v"

    /**
     * Users page: the record open in the editor — a user id, or [newRecord] for one being created.
     *
     * One slot rather than two, because "which record is open" is one question and a page cannot be editing
     * someone *and* creating someone. A user id is numeric, so the sentinel cannot collide with one.
     *
     * The draft fields deliberately stay out of the hash, unlike the catalog's [values]: those are someone's
     * unsaved edits to another person's account, and a URL that carries them is a link that pre-fills an admin
     * form. What the hash says is which user you have open, not what you were about to do to them.
     */
    const val user = "u"

    /** [user]'s value when the editor is open on a user who does not exist yet. */
    const val newRecord = "new"

    /**
     * [user]'s value when the editor is open on a new user **for the caller's own address** (issue #797): the
     * same editor as [newRecord] with the address filled in and locked, so Back and Forward reopen the right one.
     */
    const val selfRecord = "me"

    /** Forms page: the gedra id of the form document open in the read-only view, or absent in the list view. */
    const val gedra = "g"

    /**
     * Survey edit page: present (`edit=1`) to open the survey **in edit mode** rather than the read-only
     * "View Info" view (issue #694) -- how the forms list's status chip lands a user straight on the fields
     * needing attention, bypassing the read-only stop.
     */
    const val edit = "edit"

    /** Survey edit page: the task the rail shows (issue #700); absent opens the earliest task needing action. */
    const val task = "task"

    /**
     * Survey edit page: a **normal workflow** to open against the form instead of its survey (issue #791) -- where
     * the forms list's workflow column links. Absent opens the survey, as before.
     */
    const val workflow = "wf"

    /**
     * Forms page: the gedra id of a form to **highlight** briefly in the list (issue #592) -- how a save
     * hands the just-edited form back to the listing. Transient: read once on arrival and dropped from the
     * hash by the page's own hash-write, so a reload does not re-flash it and the URL stays clean to share.
     */
    const val highlight = "hl"

    /**
     * Forms page: present (`hlc=1`) beside [highlight] when the highlighted form was just **created** rather
     * than edited (issue #758), so the listing's note for a row that is not on screen can say the form was
     * created -- the confirmation a create must never lose. Transient, exactly as [highlight] is.
     *
     * Spelled `hlc`, not `created` (#758 review): every forms hash key that is not a navigation key is read as a
     * search parameter, and a client's string usage mints one named for its **trait id** -- a trait called
     * `created` is a plausible one, and its filter would have been stripped on every round-trip.
     */
    const val created = "hlc"

    /** Home page: the id of the open Markdown document, or absent for the welcome copy. */
    const val doc = "doc"

    /**
     * The listing page a child was opened from (issue #554), so its back link can return there; absent when
     * the child was reached some other way, in which case the child's natural parent is used. Honoured only
     * when it names a known listing -- see `backTarget`.
     */
    const val from = "from"

    // The Users page's *search* (filters, range, sort) is also kept in the hash so a search is shareable, a
    // pasted link reproduces it, and Back/Forward step through it (issue #411). It has no constants here on
    // purpose: those keys ARE the endpoint's own arg names (`email`, `updatedAfter`, `sortBy`, … from `USF`),
    // so the hash and the wire carry the same keys and one spec-driven encoding serves both. Unlike the
    // catalog's [values] and the editor draft (deliberately *out* of the URL), the search carries no unsaved
    // edits to anyone's account -- it says what you searched for, which is the thing worth sending someone.
}

/** The current hash parsed into `key=value` params, values percent-decoded. */
fun hashParams(): Map<String, String> {
    val raw = rawHash().removePrefix("#")
    if (raw.isEmpty()) {
        return emptyMap()
    }
    val out = LinkedHashMap<String, String>()
    for (segment in raw.split("&")) {
        val eq = segment.indexOf('=')
        if (eq > 0) {
            out[segment.substring(0, eq)] = decodeUri(segment.substring(eq + 1))
        }
    }
    return out
}

/** Replaces the hash from [params] via `history.replaceState` (no new history entry, no `hashchange`); empty
 *  [params] clears the hash. Used to keep the URL in sync with in-page state. */
fun replaceHash(params: List<Pair<String, String>>) {
    replaceUrl(hashUrl(params))
}

/**
 * Pushes the hash from [params] via `history.pushState` — a new history entry, so the Back button returns to
 * where the caller was (issue #324). Like [replaceHash] and unlike [navigateHash], it does **not** fire
 * `hashchange`: `pushState` never does. That is what lets a component push its own navigation without its own
 * `hashchange` handler re-entering on it.
 *
 * Use it for a move between destinations, and [replaceHash] for state that merely refines the current one.
 */
fun pushHash(params: List<Pair<String, String>>) {
    pushUrl(hashUrl(params))
}

/**
 * The three ways a component's state can reach the URL: leave history alone, rewrite the current entry
 * ([replaceHash]), or add one ([pushHash]). [hashWrite] decides.
 */
@Suppress("EnumEntryName")
enum class HashWrite { none, replace, push }

/**
 * How writing [next] over the hash [current] should reach browser history (issue #324).
 *
 * A page that keeps its state in the hash is keeping two kinds of thing there, and they want opposite
 * treatment. Some of it says **where you are** — which endpoint is open, which user is being edited. The rest
 * merely *refines* where you are: text typed into the form. Only the first is somewhere Back should return
 * from; treating the second as navigation would mean a history entry per keystroke, which is why both pages
 * originally wrote everything with `replaceState`. The cost was the opposite failure — a whole page occupying
 * a single history entry, so Back left it altogether and landed wherever the last genuinely pushed entry was.
 *
 * [identity] names the params that say *where*, so each page states its own: the catalog's endpoint, the user
 * page's open record. Everything else is refinement. The decision reads the URL rather than any record of what
 * the component did last, which is what makes the back/forward case fall out for free:
 *
 * - **[HashWrite.none]** when the hash already says what we would write. That is precisely a write provoked
 *   *by* Back — the page derives its state from the hash, so it recomputes what is already there, and history
 *   must not be touched at all.
 * - **[HashWrite.push]** when an [identity] param differs: a move between destinations.
 * - **[HashWrite.replace]** otherwise: same destination, refined.
 *
 * [currentReachable] is false when the hash names something the page cannot show — an endpoint no longer in
 * the catalog, a user not among the loaded rows. The page resolves that to *nothing selected*, so the identity
 * differs and the rule above would push; landing back on that entry would push again, and Back could never get
 * past it. A URL we cannot honour is one to correct in place, not a place to leave.
 */
fun hashWrite(
    current: Map<String, String>,
    next: Map<String, String>,
    identity: Set<String>,
    currentReachable: Boolean,
): HashWrite = when {
    current == next -> HashWrite.none
    !currentReachable -> HashWrite.replace
    identity.any { current[it] != next[it] } -> HashWrite.push
    else -> HashWrite.replace
}

/** Writes [params] the way [hashWrite] says to, given the page's [identity] params and [currentReachable]. */
fun applyHashWrite(params: List<Pair<String, String>>, identity: Set<String>, currentReachable: Boolean) {
    when (hashWrite(hashParams(), params.toMap(), identity, currentReachable)) {
        HashWrite.none -> {}
        HashWrite.replace -> replaceHash(params)
        HashWrite.push -> pushHash(params)
    }
}

/**
 * A **relative** hash href for [params] -- `#k=v&…`, or `#` when empty -- for an `<a href>` that navigates
 * within the app (a same-document fragment link, so no reload). Uses the same key=value percent-encoding as
 * [hashUrl]/[navigateHash], so a link built here and the state [hashParams] reads back cannot drift.
 */
fun hashHref(params: List<Pair<String, String>>): String =
    "#" + params.joinToString("&") { (k, v) -> "$k=${encodeUriComponent(v)}" }

/** The absolute URL for [params] as a hash; empty [params] clears the hash. */
private fun hashUrl(params: List<Pair<String, String>>): String {
    val base = locationBase()
    return if (params.isEmpty()) {
        base
    } else {
        base + "#" + params.joinToString("&") { (k, v) -> "$k=${encodeUriComponent(v)}" }
    }
}

/**
 * Registers [handler] for `hashchange` — i.e. for hash changes made from OUTSIDE the calling component (an
 * app-bar menu link, the back/forward buttons, the address bar). Neither [replaceHash] nor [pushHash] fires
 * it, so a component's own in-page state sync never re-enters its own handler.
 *
 * **Nothing removes the listener**, and with a third page now registering one that is worth stating rather
 * than leaving to be discovered: a page here mounts and unmounts on every navigation to and from it, so what
 * accumulates is one live closure per visit, each still running on every later hash change. They are inert —
 * React ignores a state update from an unmounted component — so this is a leak rather than a defect. Undoing
 * it means changing the effect idiom, since the wrappers' effect body is a cancellable coroutine and the
 * cleanup is therefore a `try`/`finally` around `awaitCancellation`, which nothing here does yet. Recorded in
 * `deferred-work.md`.
 */
fun onHashChange(handler: () -> Unit) {
    ensureHashDispatcher()
    pageHashHandlers.add(handler)
}

/**
 * The router's hash-change hook (issue #700): like [onHashChange], but handed the URL the hash changed **from**
 * (`HashChangeEvent.oldURL`), so a vetoed move can be put back exactly -- whatever the move was (a click, Back,
 * a typed URL) -- rather than guessed from recorded state. Runs **before every page's handler**, by construction
 * (see [ensureHashDispatcher]), which is what a leave veto depends on.
 */
fun onHashChangeFrom(handler: (oldUrl: String) -> Unit) {
    ensureHashDispatcher()
    routerHashHandlers.add(handler)
}

private val routerHashHandlers = mutableListOf<(String) -> Unit>()
private val pageHashHandlers = mutableListOf<() -> Unit>()
private var hashDispatcherInstalled = false

/**
 * One `hashchange` listener for the whole app, installed on the first registration, which calls the router's
 * handlers and then every page's **from one JavaScript stack**. Two things follow that separate listeners could
 * not promise:
 *
 *  - **Order does not depend on who registered first.** React runs a child's effects before its parent's, so on
 *    a fresh load a page's own listener would register *ahead of* the router's; with one listener the router
 *    always goes first, whatever the mount order.
 *  - **Nothing runs between them.** The browser performs a microtask checkpoint after each *separate* listener,
 *    and React re-renders in it. Measured (#700): a page listener that adopted another form's id there had its
 *    form remounted under the new key, reported clean, and the guard disarmed -- all before the router's own
 *    listener ran to ask. Called back to back from one stack, the router vetoes and puts the hash back first,
 *    and the page's handler only ever sees the restored hash.
 *
 * (Registering the router's listener in the capture phase was tried first and was not enough here.)
 */
private fun ensureHashDispatcher() {
    if (hashDispatcherInstalled) return
    hashDispatcherInstalled = true
    val dispatcher: (dynamic) -> Unit = { e ->
        val oldUrl = e.oldURL as String
        for (h in routerHashHandlers.toList()) h(oldUrl)
        for (h in pageHashHandlers.toList()) h()
    }
    js("window.addEventListener('hashchange', dispatcher)")
}

/** Puts the address bar back to [url] via `history.replaceState`: no history entry, and no `hashchange`. */
fun restoreUrl(url: String) {
    js("history.replaceState(null, '', url)")
}

/**
 * A page's veto on being left while it holds unsaved work (issue #700). The survey editor **arms** it when a
 * task has edits not yet saved and **disarms** it when they are saved or reverted; the router asks it on every
 * hash change, **before** switching, so a "stay" changes nothing -- the page never unmounts and its working
 * state is intact. That ordering is the whole point: reverting from the leaving page's own `hashchange`
 * listener is too late, because the router has already switched by the time a second hashchange could arrive.
 *
 * What counts as "still here" is the armed page's to say, through the `stays` predicate it arms with: the
 * survey editor answers true for a hash naming its page *and its form*, so a task switch or Back between
 * tasks is never a leave, while a move to another form's survey -- the same page, whose keyed remount would
 * drop the edits just as surely -- is one. One slot, not a list: only the page on screen can hold unsaved work.
 *
 * Arming also sets `onbeforeunload`, so a reload, a closed tab or a typed address get the browser's own
 * "leave site?" prompt -- the one exit a hash listener cannot see. A permitted in-app leave clears both.
 *
 * **A known limit.** A hash listener cannot tell a Back from a click. Vetoing a leave that Back initiated puts
 * the address back with `replaceState` on the entry Back had moved to, so that entry now reads as this page:
 * the page before it is gone from history, and a later Back skips it. A click-initiated leave has no such side
 * effect (the history pointer never moved). Telling the two apart needs a `popstate` listener beside
 * `hashchange`; not done until it matters.
 */
object LeaveGuard {
    private var stays: ((Map<String, String>) -> Boolean)? = null
    private var check: (() -> Boolean)? = null

    /**
     * Arms the guard: [stays] says whether a hash (as [hashParams] reads it) still belongs to the armed work;
     * [check] answers true to allow a leave, false to stay.
     */
    fun arm(stays: (Map<String, String>) -> Boolean, check: () -> Boolean) {
        this.stays = stays
        this.check = check
        js("window.onbeforeunload = function (e) { e.preventDefault(); e.returnValue = ''; return ''; }")
    }

    /** Clears the guard and the browser prompt. Safe to call when not armed. */
    fun disarm() {
        stays = null
        check = null
        js("window.onbeforeunload = null")
    }

    /**
     * The router's question on a hash change to [next]: **true to stay** (the armed page vetoed the leave). A hash
     * the armed page still owns is never asked; a permitted leave disarms first, so the browser prompt does not
     * outlive the page it belonged to.
     */
    fun vetoesMove(next: Map<String, String>): Boolean {
        val here = stays ?: return false
        if (here(next)) return false
        if (check?.invoke() == false) return true
        disarm()
        return false
    }

    /**
     * The browser's blocking confirm, for a guard's `check` -- and for a control that discards the same edits in
     * place (the survey editor's Done, issue #716), so the two ask through one dialog.
     */
    fun confirmLeave(message: String): Boolean = js("window.confirm(message)") as Boolean
}

/**
 * Navigates by setting `window.location.hash` from [params] -- unlike [replaceHash], this **does** fire
 * `hashchange`, so the [App] router switches pages and the [AppBar] re-reads its auth state. Empty [params]
 * clears the hash (home).
 *
 * Values are percent-encoded, exactly as [hashUrl] does, so a value carrying data (a gedra id, say) round-trips
 * through the [hashParams] `decodeURIComponent` unharmed rather than corrupting the hash -- and so an id with a
 * stray `%` cannot reach the decode as a malformed escape. A page-name value encodes to itself, so the callers
 * that pass only those are unchanged.
 */
fun navigateHash(params: List<Pair<String, String>>) {
    val hash = params.joinToString("&") { (k, v) -> "$k=${encodeUriComponent(v)}" }
    setHash(hash)
}

private fun setHash(hash: String) {
    js("window.location.hash = hash")
}

private fun rawHash(): String = js("window.location.hash") as String
private fun locationBase(): String = js("window.location.pathname + window.location.search") as String
private fun replaceUrl(url: String) {
    js("window.history.replaceState(null, '', url)")
}

private fun pushUrl(url: String) {
    js("window.history.pushState(null, '', url)")
}

private fun decodeUri(s: String): String = js("decodeURIComponent(s)") as String
