package com.dynamicruntime.common.logging

/**
 * The backend's text sink (issue #79): renders each event with the shared [LogFormat] and writes the line to
 * stdout. A deployment typically captures stdout (and an external tool rolls/ships it), so the application no
 * longer owns a rolling-file appender; durable/queryable storage will be a separate (OpenSearch) sink.
 *
 * [color] applies ANSI coloring for a terminal; turn it off when the output is captured to a plain log.
 * `emit` never throws -- a failing logger must not break its caller.
 */
class StdoutLogSink(private val color: Boolean = true) : LogSink {
    override fun emit(record: LogRecord) {
        try {
            println(LogFormat.formatLine(record, color))
        } catch (_: Throwable) {
            // A logging failure must never propagate.
        }
    }
}
