package com.dynamicruntime.common.http.request

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Small HTTP query-string helpers. JVM-only (this whole layer is backend-only). */
object HttpUtil {
    /** Encodes a map of arguments into a URL query string (skips null values). */
    fun encodeArgs(args: Map<String, Any?>): String =
        args.entries
            .filter { it.value != null }
            .joinToString("&") { (k, v) -> "${enc(k)}=${enc(v.toString())}" }

    /**
     * Parses a URL query string into a map. Repeated keys are comma-joined, matching the
     * behavior the request builders and validators expect.
     */
    fun parseQuery(query: String?): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        if (query.isNullOrEmpty()) {
            return result
        }
        for (pair in query.split("&")) {
            if (pair.isEmpty()) {
                continue
            }
            val eq = pair.indexOf('=')
            val name = if (eq < 0) dec(pair) else dec(pair.substring(0, eq))
            val value = if (eq < 0) "" else dec(pair.substring(eq + 1))
            val existing = result[name]
            result[name] = if (existing is CharSequence) "$existing,$value" else value
        }
        return result
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
    private fun dec(s: String): String = URLDecoder.decode(s, StandardCharsets.UTF_8)
}
