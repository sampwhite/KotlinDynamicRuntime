package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.toJsonStr

/**
 * One spelling of the two URL-encoding jobs the front end kept re-deriving per file: percent-encoding a value,
 * and building a query string from a map. `AdminApi`, `SchemaCatalogApi` and `HashRoute` each carried their own
 * `encodeURIComponent` wrapper, and the query-string builder existed once as a method and once inline -- so a
 * change to how the app encodes a URL meant finding every copy.
 */

/**
 * The browser's global `encodeURIComponent`, declared external so [encodeUriComponent] passes its argument
 * through rather than a `js(...)` string capturing the local by name.
 */
private external fun encodeURIComponent(s: String): String

/** Percent-encodes a single value via the browser's global `encodeURIComponent`. */
fun encodeUriComponent(s: String): String = encodeURIComponent(s)

/**
 * Serializes [args] as a query string (`?k=v&…`, empty for an empty map). A scalar is stringified; a nested map
 * or list is JSON-encoded (compact), which the runtime's input coercion reparses; a null value becomes an empty
 * `k=`. Both key and value are percent-encoded, and the leading `?` is included so it appends straight onto a path.
 */
fun queryString(args: Map<String, Any?>): String {
    if (args.isEmpty()) {
        return ""
    }
    return "?" + args.entries.joinToString("&") { (k, v) ->
        val s = when (v) {
            null -> ""
            is Map<*, *>, is List<*> -> v.toJsonStr(compact = true)
            else -> v.toString()
        }
        "${encodeUriComponent(k)}=${encodeUriComponent(s)}"
    }
}
