package com.dynamicruntime.webapp

/**
 * A structured error from an API call (issue #111): the fields the backend's error envelope carries, so the
 * frontend can decide how to *present* an error rather than seeing only a bare message string. [Http] throws
 * this on a non-2xx response.
 *
 * [fromFragment] (the envelope's `errorFromFragment`) is the pivot: `true` means the message is designed,
 * user-facing copy rendered from a Markdown fragment -- safe to show, and Markdown-render, as an *expected*
 * error. `false` means a raw/internal message, to be suppressed or clearly marked as raw (see [userFacingError]).
 */
class ApiError(
    override val message: String,
    val fromFragment: Boolean,
    val status: Int?,
    val errorCode: String?,
    val traceId: String?,
) : Throwable(message)

/**
 * What to show the user for an error (issues #111, #519): the [text] and how to present it ([kind]).
 *
 *  - [Kind.designed] -- frontend-authored or fragment-sourced copy: Markdown-rendered, ordinary error styling.
 *  - [Kind.message]  -- a real backend message that is safe to show (a 4xx, or any error where the deployment
 *    does not obfuscate): plain text (it is *not* designed copy, so it is never Markdown-interpreted) under the
 *    same ordinary styling, so the user reads what failed without it looking like a crash.
 *  - [Kind.fault]    -- a raw internal message on a non-obfuscating (dev) deployment: plain text, set apart in a
 *    boxed/monospaced style so it reads as an unexpected, developer-facing detail rather than designed copy.
 */
class DisplayError(val text: String, val kind: Kind) {
    @Suppress("EnumEntryName")
    enum class Kind { designed, message, fault }

    companion object {
        /** An expected, frontend-authored message (a validation hint, a "could not load" note): shown normally. */
        fun expected(text: String): DisplayError = DisplayError(text, Kind.designed)
    }
}

/**
 * Turns a caught throwable into what to show the user (issues #111, #519). A **fragment**-sourced message is
 * designed copy -> shown as an expected error (the caller Markdown-renders it). Anything else is **raw**, and by
 * default its message *is shown* so the user can see what failed: the backend is the authority on what is safe,
 * and it has already replaced a `sensitive` error's message with a generic one before it reached us (see
 * `RequestHandler`).
 *
 * The one thing still withheld is an **internal (5xx) message under an obfuscating deployment** ([obfuscate]):
 * those are not yet redacted server-side (a later phase of #97), so a raw 500 could carry a stack detail. A 4xx
 * -- bad input, not found, not authorized -- is the caller's own fault and safe to show, obfuscating or not. A
 * throwable that is not an [ApiError] (a network failure, a client-side bug) has no status, so it is treated as
 * internal and withheld under obfuscation too.
 *
 * Either way the raw detail is logged to the browser console with the trace id, so a developer can diagnose it
 * even when the user is shown only the generic stand-in.
 */
fun userFacingError(e: Throwable, obfuscate: Boolean = appConfig().obfuscateSensitiveErrors): DisplayError {
    val api = e as? ApiError
    if (api?.fromFragment == true) {
        return DisplayError.expected(api.message)
    }
    logToConsole(e, api)
    val ref = api?.traceId?.let { " (ref: $it)" } ?: ""
    // A throwable that never reached the backend -- a network failure, a client-side bug -- has no ApiError and
    // no useful message to show (typically "Failed to fetch"); say so plainly. The raw cause is on the console.
    if (api == null) {
        return DisplayError("The server could not be reached. Please try again.$ref", DisplayError.Kind.message)
    }
    // A 4xx is the caller's own fault -- bad input, not found, not authorized -- and safe to show, obfuscating or
    // not: the backend has already replaced any `sensitive` message with a generic one (issue #519).
    val isInternal = (api.status ?: 500) >= 500
    if (!isInternal) {
        return DisplayError(api.message + ref, DisplayError.Kind.message)
    }
    // An internal (5xx) message is not yet redacted server-side (a later phase of #97). Where the deployment
    // obfuscates, withhold it behind a generic apology; otherwise (dev) show the raw detail, set apart as a fault.
    return if (obfuscate) {
        DisplayError("Something went wrong. Please try again.$ref", DisplayError.Kind.message)
    } else {
        DisplayError(api.message + ref, DisplayError.Kind.fault)
    }
}

/** Logs the raw detail of an internal error to the console, so it is diagnosable even when the UI shows generic. */
private fun logToConsole(e: Throwable, api: ApiError?) {
    console.error(
        "API error: " + (api?.message ?: e.message ?: "(no message)") +
            (api?.status?.let { " [status=$it]" } ?: "") +
            (api?.errorCode?.let { " [code=$it]" } ?: "") +
            (api?.traceId?.let { " [trace=$it]" } ?: ""),
    )
}
