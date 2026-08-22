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

    /** Forms page: the gedra id of the form document open in the read-only view, or absent in the list view. */
    const val gedra = "g"
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
    js("window.addEventListener('hashchange', handler)")
}

/**
 * Navigates by setting `window.location.hash` from [params] -- unlike [replaceHash], this **does** fire
 * `hashchange`, so the [App] router switches pages and the [AppBar] re-reads its auth state. Empty [params]
 * clears the hash (home). The keys/values here are page names, so no percent-encoding is needed.
 */
fun navigateHash(params: List<Pair<String, String>>) {
    val hash = params.joinToString("&") { (k, v) -> "$k=$v" }
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
