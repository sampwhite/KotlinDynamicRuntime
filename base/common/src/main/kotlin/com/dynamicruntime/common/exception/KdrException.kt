package com.dynamicruntime.common.exception

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

    companion object {
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
