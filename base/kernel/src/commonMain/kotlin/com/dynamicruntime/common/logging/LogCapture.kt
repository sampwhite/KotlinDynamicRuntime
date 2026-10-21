package com.dynamicruntime.common.logging

import com.dynamicruntime.common.context.KdrCxtBase

/**
 * Somewhere one context's log records are kept **as well as** sent to the sinks (issue #1173): what a request
 * logged, returned with its response, so one request's debug output can be read without turning debug on for the
 * node. A [KdrLogger] reads the emitting context's capture, if any, from [KdrCxtBase.logCapture].
 *
 * A capture takes records down to [captureLevel] whatever the topic's threshold says. A record below the threshold
 * is built for the capture alone and never reaches a sink, so ordinary logging is unchanged by capture.
 *
 * [capture] must not throw, as [LogSink.emit] must not, and must be safe to call from several threads: a context's
 * sub contexts share their parent's capture, and may run concurrently.
 */
interface LogCapture {
    /** The least level kept, whatever the topic's threshold. */
    val captureLevel: LogLevel

    fun capture(record: LogRecord)
}
