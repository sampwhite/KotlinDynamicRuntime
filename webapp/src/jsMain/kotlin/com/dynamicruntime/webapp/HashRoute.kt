package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.home.HMENU

/**
 * The app's URL routing (issue #1125). Where you are is the **path** (`/wa/forms/<id>/edit`), refinements of where
 * you are are the **query** (`?task=…`), and the **fragment** is left to in-page anchors and one-time secrets (an
 * invitation's token). Every read and write of the URL goes through the functions here, which still speak the
 * params map the app was written against when its state lived in the hash: [hashParams] answers the same map for a
 * path that it answered for a hash, so the pages did not change. The names are kept for now; issue #1371 renames
 * them. One table, `routes`, maps page names to paths both ways.
 *
 * Three ways to write it, and the difference is what the Back button does afterwards:
 *
 * - [navigateHash] adds a history entry **and tells the router**, which is how cross-page navigation (home ⇄
 *   catalog) reaches the [App] router.
 * - [pushHash] adds a history entry without telling anyone -- for a component navigating *within* a page it
 *   already owns, where nothing needs telling because the component is the one that moved.
 * - [replaceHash] rewrites the current entry, also without telling anyone -- for state that refines where you are
 *   rather than moving you (text typed into a form).
 *
 * That neither `pushState` nor `replaceState` fires an event is the load-bearing part: it is what lets a component
 * keep the URL in step with its own state without its own handler re-entering on it. Choosing between the last two
 * is the writer's decision -- see `EndpointCatalog`'s `hashWrite`, and #324 for what a page that only ever replaced
 * cost its Back button.
 *
 * The app's own links are real `href`s, which a crawler follows and a ctrl-click opens in a new tab; one click
 * handler turns a plain click on one into a [navigateHash] (see `followAppLink`). An address from before
 * (`/wa#page=docs&doc=x`) is rewritten to its path when the app loads and whenever such a fragment is followed, so
 * bookmarks, mailed links and `#page=` links in a client's stored copy keep working.
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

    /** File store page (issue #1297): the bucket open, and the folder in it -- so Back steps out through the folders. */
    const val bucket = "bucket"
    const val folder = "folder"

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
     * Documents page: the heading of the open document to scroll to (issue #1136) -- the `#section` a link into the
     * document named, carried here because the hash itself is the app's route.
     */
    const val section = "section"

    /**
     * Clients page: the client open in the detail view (issue #906), or absent for the listing. Join page (issue #1248):
     * the demo client to join.
     */
    const val client = "c"

    /** Customers page: the customer open (issue #1114), or absent for the list -- or, for a root's administrator, their own. */
    const val customer = "cu"

    /** Clients page: present for the overrides across every client (issue #917), the `allClients` administrator's view. */
    const val overrides = "ov"

    /**
     * Reports page (issue #1007): the report that is open, and how it is shown -- `grouped` or `detail`; absent, the
     * report opens the way it declares. The client whose reports these are rides as [client], as on the Clients page.
     * Not `m`: that is the catalog's, and a hash carrying it is routed there. The names are the kernel's
     * (`HMENU`), since a simulation's start page is built with them on the server (issue #1036).
     */
    const val report = HMENU.reportParam

    /**
     * How many rows a growing listing shows (issue #1315): the forms listing, the Users page and a report's run. Absent
     * means the first batch; written only past it, so an untouched listing's address stays bare.
     */
    const val shown = "shown"
    const val reportView = HMENU.reportViewParam

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

/**
 * The page a path no route matches is shown as (issue #1125): [hashParams] answers it for such a path, and the
 * router shows `NotFoundPage`, which tells a crawler not to index the address.
 */
const val pageNotFound = "notFound"

/**
 * Params that ride the fragment rather than the query, whoever writes them: one-time secrets (issue #1125). A
 * fragment is never sent to a server, so it reaches no access log and no `Referer` header. The invitation's token
 * is the one today.
 */
private val fragmentKeys = setOf(HMENU.inviteTokenParam)

/** The keys that make a fragment an address from before paths (`#page=…`, `#m=…`, `#doc=…`) rather than an anchor. */
private val legacyRouteKeys = setOf(HP.page, HP.method, HP.doc)

/**
 * One route: the page a path shows, the path's pattern -- literal segments, and `:name` for a param carried in the
 * path -- and any params the path fixes (`forms/:g/edit` is the survey page with `edit=1`).
 */
private class RouteDef(val page: String, pattern: String, val fixed: Map<String, String> = emptyMap()) {
    val segments: List<String> = pattern.split('/').filter { it.isNotEmpty() }
    val captured: List<String> = segments.filter { it.startsWith(':') }.map { it.substring(1) }
}

/**
 * The route table (issue #1125): page names to paths, read both ways. The page names are the app's, and the server's
 * menus carry them; the paths are public: lowercase, hyphens between words, nested under the section a page belongs
 * to, the thing a page is about after the section, and a mode of that thing after its id. A path takes the first
 * route it matches, so a literal is listed before a pattern that would also match it (`forms/new` before
 * `forms/:g`). Home is the bare app root and has no entry.
 */
private val routes: List<RouteDef> = listOf(
    RouteDef(HMENU.pageCatalog, "catalog"),
    RouteDef(HMENU.pageDocs, "docs"),
    RouteDef(HMENU.pageDocs, "docs/:${HP.doc}"),
    RouteDef(HMENU.pageLogin, "login"),
    RouteDef(HMENU.pageRegister, "register"),
    RouteDef(HMENU.pageClaim, "claim"),
    RouteDef(HMENU.pageInvite, "invite"),
    RouteDef(HMENU.pageProfile, "profile"),
    RouteDef(HMENU.pageUsers, "users"),
    RouteDef(HMENU.pageUsers, "users/:${HP.user}"),
    RouteDef(HMENU.pageClients, "clients"),
    RouteDef(HMENU.pageClients, "clients/:${HP.client}"),
    RouteDef(HMENU.pageCustomers, "customers"),
    RouteDef(HMENU.pageCustomers, "customers/:${HP.customer}"),
    RouteDef(HMENU.pageReports, "reports"),
    RouteDef(HMENU.pageReports, "reports/:${HP.report}"),
    RouteDef(HMENU.pageForms, "forms"),
    RouteDef(HMENU.pageNewForm, "forms/new"),
    RouteDef(pageCreateForUser, "forms/new-for-user"),
    RouteDef(pageSurveyEdit, "forms/:${HP.gedra}"),
    RouteDef(pageSurveyEdit, "forms/:${HP.gedra}/edit", mapOf(HP.edit to "1")),
    RouteDef(pageEditForm, "forms/:${HP.gedra}/raw"),
    RouteDef(HMENU.pageWorkflows, "workflows"),
    RouteDef(pageWorkflowForms, "workflows/:${WAGG.workflowId}/forms"),
    RouteDef(HMENU.pageJoin, "join"),
    RouteDef(HMENU.pageJoin, "join/:${HP.client}"),
    RouteDef(HMENU.pageOperator, "operator"),
    RouteDef(HMENU.pageBootChecks, "operator/boot-checks"),
    RouteDef(HMENU.pageCacheState, "operator/cache"),
    RouteDef(HMENU.pageDbTables, "operator/tables"),
    RouteDef(HMENU.pageEnv, "operator/env"),
    RouteDef(HMENU.pageSystemInfo, "operator/system"),
    RouteDef(HMENU.pageFileStore, "operator/files"),
    RouteDef(HMENU.pageFragmentsCheck, "operator/fragments"),
    RouteDef(HMENU.pageCfacts, "operator/cfacts"),
    RouteDef(HMENU.pageBecomeUser, "test/become-user"),
    RouteDef(HMENU.pageSimulations, "test/simulations"),
    RouteDef(HMENU.pageDebug, "debug"),
    RouteDef(HMENU.pageDebug, "debug/:$debugToolParam"),
)

/** The first route [segments] (still percent-encoded) match, with the params it carries in the path; null for none. */
private fun matchRoute(segments: List<String>): Pair<RouteDef, Map<String, String>>? {
    for (route in routes) {
        if (route.segments.size != segments.size) continue
        val values = LinkedHashMap<String, String>()
        val matches = route.segments.indices.all { i ->
            val pattern = route.segments[i]
            if (pattern.startsWith(':')) {
                values[pattern.substring(1)] = decodeUri(segments[i])
                true
            } else {
                pattern == segments[i]
            }
        }
        if (matches) return route to values
    }
    return null
}

/** Splits `k=v&…` into params, values percent-decoded. A part with no `=` -- an anchor's name -- is not a param. */
private fun splitParams(raw: String): LinkedHashMap<String, String> {
    val out = LinkedHashMap<String, String>()
    if (raw.isEmpty()) {
        return out
    }
    for (part in raw.split("&")) {
        val eq = part.indexOf('=')
        if (eq > 0) {
            out[part.substring(0, eq)] = decodeUri(part.substring(eq + 1))
        }
    }
    return out
}

/**
 * The params an address within the app says (issue #1125), as [hashParams] has always answered them: the page and
 * its identity from [path] (relative to the app root, `/forms/<id>/edit`, or empty for home), refinements from
 * [query], and a one-time secret from [fragment] -- both without their `?` or `#`. A path no route matches is
 * [pageNotFound]. Pure, so the table is covered under `jsNodeTest`.
 */
fun routeParamsOf(path: String, query: String, fragment: String): Map<String, String> {
    val out = splitParams(query)
    for ((k, v) in splitParams(fragment)) {
        if (k in fragmentKeys) out[k] = v
    }
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) {
        return out
    }
    val (route, values) = matchRoute(segments) ?: return out.apply { put(HP.page, pageNotFound) }
    out[HP.page] = route.page
    out.putAll(route.fixed)
    out.putAll(values)
    return out
}

/**
 * The address [params] are written as, relative to the app root -- the inverse of [routeParamsOf]: the path of the
 * route that carries the most of them, the rest as the query, and a secret as the fragment. Params with no page
 * are home's, except that an endpoint (`m`) is the catalog's and a document (`doc`) is the documents page's, as the
 * router has always read them. A page with no route, or missing a param its path needs, falls back to home's path
 * with the page in the query, which the router shows as it always showed a page it did not know.
 */
private fun routeUrlOf(params: List<Pair<String, String>>): Triple<String, String, String> {
    val all = LinkedHashMap<String, String>()
    for ((k, v) in params) all[k] = v
    val page = all[HP.page] ?: when {
        HP.method in all -> HMENU.pageCatalog
        HP.doc in all -> HMENU.pageDocs
        else -> null
    }
    val route = page?.let { p ->
        routes.filter { r -> r.page == p && r.captured.all { !all[it].isNullOrEmpty() } && r.fixed.all { (k, v) -> all[k] == v } }
            .maxByOrNull { it.captured.size + it.fixed.size }
    }
    val inPath = if (route == null) emptySet() else setOf(HP.page) + route.captured + route.fixed.keys
    val path = route?.segments?.joinToString("") { s ->
        "/" + if (s.startsWith(':')) encodePathSegment(all.getValue(s.substring(1))) else s
    }.orEmpty()
    fun joined(keep: (String) -> Boolean) =
        all.entries.filter { keep(it.key) }.joinToString("&") { (k, v) -> "$k=${encodeUriComponent(v)}" }
    return Triple(path, joined { it !in inPath && it !in fragmentKeys }, joined { it in fragmentKeys })
}

/**
 * A path segment for [value]: percent-encoded, but keeping `:` and `@`, which a path segment may carry as they are
 * (RFC 3986) -- so a sandbox's form id reads as itself in the address bar.
 */
private fun encodePathSegment(value: String): String = encodeUriComponent(value).replace("%3A", ":").replace("%40", "@")

/** The current address parsed into params -- see [routeParamsOf] for how. */
fun hashParams(): Map<String, String> = paramsOfUrl(locationUrl())

/** The params of [url], an address on this origin (path, query and fragment); outside the app root it is not found. */
private fun paramsOfUrl(url: String): Map<String, String> {
    val fragment = url.substringAfter('#', "")
    val beforeFragment = url.substringBefore('#')
    val query = beforeFragment.substringAfter('?', "")
    val path = appPathOf(beforeFragment.substringBefore('?')) ?: return mapOf(HP.page to pageNotFound)
    return routeParamsOf(path, query, fragment)
}

/** [pathname] relative to the app root (`/wa` under the backend, nothing under the dev server), or null outside it. */
private fun appPathOf(pathname: String): String? {
    val root = appRootPrefix()
    return when {
        root.isEmpty() -> pathname
        pathname == root || pathname.startsWith("$root/") -> pathname.substring(root.length)
        else -> null
    }
}

/** Whether [path] (relative to the app root) is one of the app's own pages: home, or a route's. */
private fun isAppPath(path: String): Boolean {
    val segments = path.split('/').filter { it.isNotEmpty() }
    return segments.isEmpty() || matchRoute(segments) != null
}

/**
 * Replaces the address from [params] via `history.replaceState` (no new history entry, and the router is not told);
 * empty [params] is home. Used to keep the URL in sync with in-page state.
 */
fun replaceHash(params: List<Pair<String, String>>) {
    replaceUrl(hashHref(params))
}

/**
 * Pushes the address from [params] via `history.pushState` -- a new history entry, so the Back button returns to
 * where the caller was (issue #324). Like [replaceHash] and unlike [navigateHash], it does **not** tell the router:
 * that is what lets a component push its own navigation without its own handler re-entering on it.
 *
 * Use it for a move between destinations, and [replaceHash] for state that merely refines the current one.
 */
fun pushHash(params: List<Pair<String, String>>) {
    pushUrl(hashHref(params))
}

/**
 * The three ways a component's state can reach the URL: leave history alone, rewrite the current entry
 * ([replaceHash]), or add one ([pushHash]). [hashWrite] decides.
 */
@Suppress("EnumEntryName")
enum class HashWrite { none, replace, push }

/**
 * How writing [next] over the address [current] should reach browser history (issue #324).
 *
 * A page that keeps its state in the address is keeping two kinds of thing there, and they want opposite
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
 * - **[HashWrite.none]** when the address already says what we would write. That is precisely a write provoked
 *   *by* Back — the page derives its state from the address, so it recomputes what is already there, and history
 *   must not be touched at all.
 * - **[HashWrite.push]** when an [identity] param differs: a move between destinations.
 * - **[HashWrite.replace]** otherwise: same destination, refined.
 *
 * [currentReachable] is false when the address names something the page cannot show — an endpoint no longer in
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

/**
 * Writes [params] the way [hashWrite] says to, given the page's [identity] params and [currentReachable]. [params]
 * are compared as the address would read them back, so a page that leaves out what its path implies (the catalog's
 * `page`, say) is not taken to have moved.
 */
fun applyHashWrite(params: List<Pair<String, String>>, identity: Set<String>, currentReachable: Boolean) {
    val href = hashHref(params)
    when (hashWrite(hashParams(), paramsOfUrl(href), identity, currentReachable)) {
        HashWrite.none -> {}
        HashWrite.replace -> replaceUrl(href)
        HashWrite.push -> pushUrl(href)
    }
}

/**
 * The address for [params] -- `/wa/forms/<id>/edit?task=…`, or the app root for none -- for an `<a href>` that
 * navigates within the app: a plain click is followed without a reload (see `followAppLink`), and a ctrl-click
 * opens it in a new tab. Built by the same table [hashParams] reads, so a link built here and the state read back
 * cannot drift.
 */
fun hashHref(params: List<Pair<String, String>>): String {
    val (path, query, fragment) = routeUrlOf(params)
    return (appRootPrefix() + path).ifEmpty { "/" } +
        (if (query.isEmpty()) "" else "?$query") + (if (fragment.isEmpty()) "" else "#$fragment")
}

/**
 * The path form of an address from before paths (issue #1125), given its [fragment] without the `#` --
 * `page=docs&doc=x` is `/wa/docs/x` -- or null when the fragment is not one (an anchor, an invitation's token).
 */
fun legacyHashUrl(fragment: String): String? {
    val params = splitParams(fragment)
    if (params.keys.none { it in legacyRouteKeys }) {
        return null
    }
    return hashHref(params.toList())
}

/**
 * Registers [handler] for a move made from OUTSIDE the calling component (an app-bar menu link, the back/forward
 * buttons, a link followed, the address bar). Neither [replaceHash] nor [pushHash] triggers it, so a component's
 * own in-page state sync never re-enters its own handler.
 *
 * **Nothing removes the listener**, and with several pages registering one that is worth stating rather than
 * leaving to be discovered: a page here mounts and unmounts on every navigation to and from it, so what
 * accumulates is one live closure per visit, each still running on every later move. They are inert —
 * React ignores a state update from an unmounted component — so this is a leak rather than a defect. Undoing
 * it means changing the effect idiom, since the wrappers' effect body is a cancellable coroutine and the
 * cleanup is therefore a `try`/`finally` around `awaitCancellation` -- the idiom `useIdleBump` already uses.
 * Recorded in `docs/development/deferred-work.md`.
 */
fun onHashChange(handler: () -> Unit) {
    ensureHashDispatcher()
    pageHashHandlers.add(handler)
}

/**
 * The router's hook (issue #700): like [onHashChange], but handed the address the move was **from**, so a vetoed
 * move can be put back exactly -- whatever the move was (a click, Back, a typed URL) -- rather than guessed from
 * recorded state. Runs **before every page's handler**, by construction (see [ensureHashDispatcher]), which is what
 * a leave veto depends on.
 */
fun onHashChangeFrom(handler: (oldUrl: String) -> Unit) {
    ensureHashDispatcher()
    routerHashHandlers.add(handler)
}

private val routerHashHandlers = mutableListOf<(String) -> Unit>()
private val pageHashHandlers = mutableListOf<() -> Unit>()
private var hashDispatcherInstalled = false

/**
 * The address the router last saw: what a move is a move *from*. `hashchange` said so (`oldURL`); `popstate` does
 * not, so every write here keeps it, and every move told to the handlers ends by reading it afresh.
 */
private var lastUrl = ""

/**
 * One listener for the whole app, for `popstate` and `hashchange` alike, installed on the first registration, which
 * calls the router's handlers and then every page's **from one JavaScript stack**. Two things follow that separate
 * listeners could not promise:
 *
 *  - **Order does not depend on who registered first.** React runs a child's effects before its parent's, so on
 *    a fresh load a page's own listener would register *ahead of* the router's; with one listener the router
 *    always goes first, whatever the mount order.
 *  - **Nothing runs between them.** The browser performs a microtask checkpoint after each *separate* listener,
 *    and React re-renders in it. Measured (#700): a page listener that adopted another form's id there had its
 *    form remounted under the new key, reported clean, and the guard disarmed -- all before the router's own
 *    listener ran to ask. Called back to back from one stack, the router vetoes and puts the address back first,
 *    and the page's handler only ever sees the restored address.
 *
 * (Registering the router's listener in the capture phase was tried first and was not enough here.)
 */
private fun ensureHashDispatcher() {
    if (hashDispatcherInstalled) return
    hashDispatcherInstalled = true
    lastUrl = locationUrl()
    val dispatcher: (dynamic) -> Unit = { addressChanged() }
    js("window.addEventListener('popstate', dispatcher)")
    js("window.addEventListener('hashchange', dispatcher)")
}

/**
 * The browser moved the address (Back, Forward, a fragment followed). An address from before paths is rewritten to
 * its path first, so a `#page=` link followed lands where it says; a move that changes no param -- a jump to an
 * anchor, or the `hashchange` that follows the `popstate` of one move -- tells nobody, as a hash router was never
 * told of an in-page jump either.
 */
private fun addressChanged() {
    legacyHashUrl(rawHash().removePrefix("#"))?.let { historyReplace(it) }
    if (paramsOfUrl(lastUrl) == hashParams()) {
        lastUrl = locationUrl()
        return
    }
    tellMove(lastUrl)
}

/** Tells the router, then every page, that the address moved from [oldUrl]. */
private fun tellMove(oldUrl: String) {
    for (h in routerHashHandlers.toList()) h(oldUrl)
    for (h in pageHashHandlers.toList()) h()
    lastUrl = locationUrl()
}

/**
 * Installs routing before the app first renders (issue #1125): an address from before paths is rewritten to its
 * path, so the first render reads the page it names, and the router's listener and the click handler for the app's
 * own links go in.
 *
 * The click handler listens on React's root [container] as well as on the document, and the first is the one that
 * matters. A React handler's `stopPropagation` -- the forms table's cells use it, so a link in a row does not also
 * open the row -- stops the browser's event at the root container, where React listens, so it never reaches the
 * document; measured, the link was then followed as a full page load. A listener on the same node still runs, and
 * runs after React's handlers, since React's listener went in first (call this after `createRoot`). The document's
 * listener is for a link React drew outside the root (a portal: a dropdown, a dialog); for a link inside it, the
 * root's has already called `preventDefault`, which the document's then sees.
 */
fun installRouting(container: dynamic) {
    legacyHashUrl(rawHash().removePrefix("#"))?.let { historyReplace(it) }
    ensureHashDispatcher()
    val onClick: (dynamic) -> Unit = { e -> followAppLink(e) }
    container.addEventListener("click", onClick)
    js("document.addEventListener('click', onClick)")
}

/**
 * The click handler for the app's own links (issue #1125): a plain click on a link to one of the app's pages
 * becomes a [navigateHash] -- no reload -- and anything else is left to the browser. Left alone: a click with a
 * modifier or another button (a new tab or window), a link with a `target` or `download`, one to another origin or
 * outside the app's pages (an API download, an unknown path, which then loads as not found), a bare `#anchor`
 * (in-page; a `#page=` one is rewritten when followed), and a link to this very page with a fragment (an anchor on
 * it). A handler that already called `preventDefault` -- the Markdown anchor scroll -- is left its click.
 */
private fun followAppLink(e: dynamic) {
    if (e.defaultPrevented == true || e.button != 0) return
    if (e.metaKey == true || e.ctrlKey == true || e.shiftKey == true || e.altKey == true) return
    val target = e.target
    if (target == null || jsTypeOf(target.closest) != "function") return
    val link = target.closest("a[href]") ?: return
    // An SVG link has no `pathname`; none of the app's own links is one.
    if (jsTypeOf(link.pathname) != "string") return
    val linkTarget = link.getAttribute("target") as String?
    if (!linkTarget.isNullOrEmpty() && linkTarget != "_self") return
    if (link.hasAttribute("download") == true) return
    if ((link.getAttribute("href") as String).startsWith("#")) return
    if (link.origin != js("window.location.origin")) return
    val pathname = link.pathname as String
    val path = appPathOf(pathname) ?: return
    if (!isAppPath(path)) return
    val url = pathname + (link.search as String) + (link.hash as String)
    if ((link.hash as String).isNotEmpty() && paramsOfUrl(url) == hashParams()) return
    e.preventDefault()
    navigateTo(url)
}

/** Puts the address bar back to [url] via `history.replaceState`: no history entry, and nobody is told. */
fun restoreUrl(url: String) {
    replaceUrl(url)
}

/**
 * A page's veto on being left while it holds unsaved work (issue #700). The survey editor **arms** it when a
 * task has edits not yet saved and **disarms** it when they are saved or reverted; the router asks it on every
 * move, **before** switching, so a "stay" changes nothing -- the page never unmounts and its working state is
 * intact. That ordering is the whole point: reverting from the leaving page's own listener is too late, because the
 * router has already switched by the time it runs.
 *
 * What counts as "still here" is the armed page's to say, through the `stays` predicate it arms with: the
 * survey editor answers true for an address naming its page *and its form*, so a task switch or Back between
 * tasks is never a leave, while a move to another form's survey -- the same page, whose keyed remount would
 * drop the edits just as surely -- is one. One slot, not a list: only the page on screen can hold unsaved work.
 *
 * Arming also sets `onbeforeunload`, so a reload, a closed tab or a typed address get the browser's own
 * "leave site?" prompt -- the one exit the app cannot see. A permitted in-app leave clears both.
 *
 * **A known limit.** A move the app makes ([navigateHash], a link followed) is asked before anything is written, so
 * a "stay" leaves history as it was. A move the browser made -- Back, Forward -- has already moved the history
 * pointer by the time anyone hears of it (`popstate` cannot be cancelled), so a veto puts the address back with
 * `replaceState` on the entry Back had moved to, and that entry now reads as this page: the page before it is gone
 * from history, and a later Back skips it. Not mended until it matters.
 */
object LeaveGuard {
    private var stays: ((Map<String, String>) -> Boolean)? = null
    private var check: (() -> Boolean)? = null

    /**
     * Arms the guard: [stays] says whether an address (as [hashParams] reads it) still belongs to the armed work;
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
     * The router's question on a move to [next]: **true to stay** (the armed page vetoed the leave). An address
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
 * Navigates to the address for [params] -- unlike [replaceHash], this **does** tell the router, so the [App]
 * router switches pages and the [AppBar] re-reads its auth state. Empty [params] is home.
 *
 * An address that says what this one already does moves nothing and tells nobody, as setting a hash to itself
 * never fired `hashchange` -- which is why a menu item for the page on screen has its own revisit signal.
 */
fun navigateHash(params: List<Pair<String, String>>) {
    navigateTo(hashHref(params))
}

/**
 * Moves to [url] (path, query and fragment): asks the leave guard first, so a "stay" writes nothing, then pushes the
 * entry and tells the router and the pages, as `hashchange` once did.
 */
private fun navigateTo(url: String) {
    ensureHashDispatcher()
    val next = paramsOfUrl(url)
    if (next == hashParams()) return
    if (LeaveGuard.vetoesMove(next)) return
    val from = locationUrl()
    pushUrl(url)
    tellMove(from)
}

private fun rawHash(): String = js("window.location.hash") as String
private fun locationUrl(): String = js("window.location.pathname + window.location.search + window.location.hash") as String

/** Rewrites the current entry, keeping [lastUrl] in step: the address is where the app now is. */
private fun replaceUrl(url: String) {
    historyReplace(url)
    lastUrl = locationUrl()
}

/** Adds an entry, keeping [lastUrl] in step. */
private fun pushUrl(url: String) {
    js("window.history.pushState(null, '', url)")
    lastUrl = locationUrl()
}

/** `history.replaceState` alone, leaving [lastUrl] as it was: an old address's rewrite, which is not a move. */
private fun historyReplace(url: String) {
    js("window.history.replaceState(null, '', url)")
}

/** `decodeURIComponent`, or [s] as it is when it holds a malformed escape -- a typed address must not throw. */
private fun decodeUri(s: String): String = try {
    js("decodeURIComponent(s)") as String
} catch (_: Throwable) {
    s
}
