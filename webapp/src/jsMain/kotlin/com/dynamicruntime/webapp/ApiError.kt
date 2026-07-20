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
 * What to show the user for an error: the [text], and whether it is a raw [internal] error. An internal error is
 * styled distinctly and shown as plain text (it is not designed copy); an expected one is shown as Markdown.
 */
class DisplayError(val text: String, val internal: Boolean) {
    companion object {
        /** An expected, frontend-authored message (a validation hint, a "could not load" note): shown normally. */
        fun expected(text: String): DisplayError = DisplayError(text, internal = false)
    }
}

/**
 * Turns a caught throwable into what to show the user (issue #111). A **fragment**-sourced message is designed
 * copy -> shown as an expected error (the caller Markdown-renders it). Anything else is **raw/internal**: the
 * deployment's policy ([AppConfig.obfuscateSensitiveErrors], from the app config) decides whether the user sees
 * a generic stand-in (an obfuscating/prod deployment) or the raw message clearly marked (dev). Either way the
 * raw detail is logged to the browser console with the trace id, so a developer can still diagnose it when the
 * user is shown only the generic message.
 */
fun userFacingError(e: Throwable): DisplayError {
    val api = e as? ApiError
    if (api?.fromFragment == true) {
        return DisplayError.expected(api.message)
    }
    logToConsole(e, api)
    val ref = api?.traceId?.let { " (ref: $it)" } ?: ""
    return if (appConfig().obfuscateSensitiveErrors) {
        DisplayError("Something went wrong. Please try again.$ref", internal = true)
    } else {
        DisplayError((api?.message ?: e.message ?: "Something went wrong.") + ref, internal = true)
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
