package com.dynamicruntime.webapp

/**
 * Shared URL-hash routing helpers. The app's navigation state lives in the location hash (`page=…&m=…&p=…&v=…`)
 * — client-only, survives a refresh, and needs no server-side routing (works under any static host, including
 * the appui bundle). Cross-page navigation (home ⇄ catalog) changes the hash so a `hashchange` fires and the
 * [App] router reacts; in-page state sync (the selected endpoint + input values) uses [replaceHash], which does
 * not add history or fire `hashchange`.
 */

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
    val base = locationBase()
    val url = if (params.isEmpty()) {
        base
    } else {
        base + "#" + params.joinToString("&") { (k, v) -> "$k=${encodeUri(v)}" }
    }
    replaceUrl(url)
}

/**
 * Registers [handler] for `hashchange` — i.e. for hash changes made from OUTSIDE the calling component (an
 * app-bar menu link, the back/forward buttons, the address bar). [replaceHash] deliberately does not fire it,
 * so a component's own in-page state sync never re-enters its own handler.
 */
fun onHashChange(handler: () -> Unit) {
    js("window.addEventListener('hashchange', handler)")
}

private fun rawHash(): String = js("window.location.hash") as String
private fun locationBase(): String = js("window.location.pathname + window.location.search") as String
private fun replaceUrl(url: String) {
    js("window.history.replaceState(null, '', url)")
}

private fun encodeUri(s: String): String = js("encodeURIComponent(s)") as String
private fun decodeUri(s: String): String = js("decodeURIComponent(s)") as String
