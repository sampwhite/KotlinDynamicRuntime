package com.dynamicruntime.common.exception

/**
 * Points an error message at fragment copy (issue #108) instead of a hard-coded string: the file
 * `<fileId>.md`, then `[namespace][key]` within its parsed `namespace -> key -> value` map. The value is a
 * template rendered with [KdrException.msgParams] at the *top-level error handler*, so a throw site deep in
 * the code names copy without knowing the caller's locale or touching a fragment file -- and the sentence
 * lives once, in the fragment, not duplicated in Kotlin.
 */
class KdrMsg(val fileId: String, val namespace: String, val key: String) {
    /** The `<fileId>/<namespace>/<key>` path -- the exception's fallback message, and what a failed resolve shows. */
    val path: String get() = "$fileId/$namespace/$key"
}

/**
 * The single, universal exception for the runtime. Per the code guide, the code
 * base defines no other exception classes. Rather than many specialized
 * exceptions, one richly described exception handles the many cases that, in
 * practice, share very similar handling. This lets error handling be centralized
 * and enhanced over time without revisiting every throw site.
 *
 * Attributes:
 *  - [code]: the HTTP status code this error should map to in a REST response,
 *    defaulting to [EXC.internalError] (500). Exceptions are generally viewed
 *    through the lens of the HTTP code they would generate.
 *  - [source]: a generic sign of where the issue showed up (see [SRC]).
 *  - [activity]: a generic sign of what was being done (see [ACT]).
 *  - [extraData]: a map of additional data that may be useful for logging or error handling.
 *  - [msg]/[msgParams]: when set, the *wire* message is rendered from this fragment reference (issue #108) at
 *    the top-level error handler; [message] stays the log/fallback text (see [mkMsg]).
 *
 * Error handling is groomed so that, looking at the full stack of causes, one can
 * tell where an error occurred and what its precise source was. In some cases
 * errors are caught, wrapped, and rethrown purely to inject more information.
 *
 * Inspired by `DnException` from the prior-art dynamicruntime project.
 */
class KdrException(
    message: String,
    cause: Throwable? = null,
    val code: Int = EXC.internalError,
    val source: String = SRC.system,
    val activity: String = ACT.general,
    val extraData: MutableMap<String, Any?> = LinkedHashMap(),
    /** Fragment copy to render for the client message, or null to use [message] verbatim (issue #108). */
    val msg: KdrMsg? = null,
    /** Substitution data for [msg]'s template (`${key}` -> value), used when [msg] is set. */
    val msgParams: Map<String, Any?> = emptyMap(),
) : Exception(message, cause) {

    /**
     * True if retrying the operation may succeed -- a retriable internal error
     * (connection/interruption/io) or a node that was unavailable. Such a retry
     * can reasonably be directed at another cluster node.
     */
    fun canRetry(): Boolean =
        (code == EXC.internalError &&
            (activity == ACT.connection || activity == ACT.interrupted || activity == ACT.io)) ||
            code == EXC.notAvailable

    /**
     * Gathers, for logging and reporting, the messages of this exception and its
     * chain of [KdrException] causes (a few levels deep) into a single string, so
     * one line conveys the sequence of what went wrong.
     */
    fun fullMessage(): String {
        val firstCause = cause
        if (firstCause == null || firstCause === this) {
            return message ?: ""
        }
        var current = firstCause as? KdrException ?: return message ?: ""
        val chain = mutableListOf(this)
        var count = 0
        while (count++ < 3) {
            chain.add(current)
            val next = current.cause as? KdrException
            if (next == null || chain.contains(next)) {
                break
            }
            current = next
        }
        return chain.joinToString(" ") { it.message ?: "" }
    }

    @Suppress("ConstPropertyName")
    companion object {
        /**
         * Standard [extraData] key under which an error-code value should be placed when reporting an error.
         * Always use this one key so a consumer (notably the frontend) has a single, predictable place to
         * read the code regardless of which functional area raised the error. The *set* of possible values
         * still varies by area -- each area defines its own error-code enum -- but the key does not. This
         * shared key exists to avoid the sprawl of per-area key names that made prior work hard to consume.
         */
        const val errorCodeKey = "errorCode"

        /**
         * Standard [extraData] keys locating where in a parsed input an error originates: the 0-based
         * character [offsetKey], the 1-based [lineKey], and the 1-based column-within-line [lineColKey].
         * Shared by every string parser (JSON, the script evaluator, ...) so one convention serves them all.
         */
        const val offsetKey = "offset"
        const val lineKey = "line"
        const val lineColKey = "lineCol"

        /**
         * A bad-input (HTTP 400) error -- typically from schema validation. If a
         * [cause] is itself a [KdrException], its [source]/[activity] are carried
         * over; otherwise the error is attributed to deliberate code logic.
         */
        fun mkInput(message: String, cause: Throwable? = null): KdrException {
            var source = SRC.system
            var activity = ACT.code
            if (cause is KdrException) {
                source = cause.source
                activity = cause.activity
            }
            return KdrException(message, cause, EXC.badInput, source, activity)
        }

        /**
         * An error whose client message comes from fragment copy (issue #108): [msg] names the template,
         * [params] fill its `${...}`, and the top-level handler renders it. The exception's own [message] is
         * [msg]'s key [path] -- diagnostic in a log or stack, and what a failed resolve falls back to, without
         * the sentence living in Kotlin. [code] defaults to 400 (most such errors are bad input); pass 401/429
         * for the auth/rate-limit cases.
         */
        fun mkMsg(
            msg: KdrMsg,
            params: Map<String, Any?> = emptyMap(),
            code: Int = EXC.badInput,
            cause: Throwable? = null,
        ): KdrException = KdrException(msg.path, cause, code, SRC.system, ACT.code, msg = msg, msgParams = params)

        /**
         * A conversion/parsing error. These can often be turned into bad-input
         * errors when a requesting agent may have supplied the bad data.
         */
        fun mkConv(message: String, cause: Throwable? = null): KdrException =
            KdrException(message, cause, EXC.internalError, SRC.system, ACT.conversion)

        /** A problem accessing a file, with an explicit HTTP [code]. */
        fun mkFileIo(message: String, cause: Throwable?, code: Int): KdrException =
            KdrException(message, cause, code, SRC.file, ACT.io)
    }
}
