package com.dynamicruntime.webapp

/*
 * The browser's `localStorage`, for what one viewer's browser should remember past the tab: a remembered menu
 * state, a page's recent runs. It throws in a private window or when site data is blocked, so every access is
 * caught and *reported* -- never swallowed (webapp/CLAUDE.md) -- and the caller falls back to having nothing
 * remembered rather than breaking. [what] names the remembered thing in the report.
 */

/** The value stored under [key], or null when there is none or the store cannot be read. */
fun localStorageGet(key: String, what: String): String? =
    try {
        js("window.localStorage.getItem(key)") as? String
    } catch (e: Throwable) {
        console.warn("$errorLogPrefix could not read $what from localStorage: ${e.message}")
        null
    }

/** Stores [value] under [key]; a store that cannot be written leaves nothing remembered. */
fun localStorageSet(key: String, value: String, what: String) {
    try {
        js("window.localStorage.setItem(key, value)")
    } catch (e: Throwable) {
        console.warn("$errorLogPrefix could not persist $what to localStorage: ${e.message}")
    }
}

/** Removes whatever is stored under [key]. */
fun localStorageRemove(key: String, what: String) {
    try {
        js("window.localStorage.removeItem(key)")
    } catch (e: Throwable) {
        console.warn("$errorLogPrefix could not clear $what from localStorage: ${e.message}")
    }
}
