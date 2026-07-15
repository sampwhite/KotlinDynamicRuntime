package com.dynamicruntime.webapp

import com.dynamicruntime.common.logging.LogConfig
import com.dynamicruntime.common.logging.LogFormat
import com.dynamicruntime.common.logging.LogLevel
import com.dynamicruntime.common.logging.LogRecord
import com.dynamicruntime.common.logging.LogSink
import com.dynamicruntime.common.logging.LogSinks

/**
 * The frontend logging sink (issue #79): renders each event with the *shared* [LogFormat] and writes it to the
 * browser console, routed to `error`/`warn`/`log` by level so devtools filtering and coloring work. No ANSI
 * (the browser would show escape codes) and no thread (JS is single-threaded) -- the only legitimate
 * differences from the backend's stdout line.
 */
class ConsoleLogSink : LogSink {
    override fun emit(record: LogRecord) {
        val line = LogFormat.formatLine(record, color = false)
        when (record.level) {
            LogLevel.error -> console.error(line)
            LogLevel.warn -> console.warn(line)
            else -> console.log(line)
        }
    }
}

/**
 * Installs frontend logging: the console sink at [level], and a null context snapshot (the frontend has no
 * rich per-request context yet, and no threads). Call once at startup, before anything logs.
 */
fun initLogging(level: LogLevel = LogLevel.debug) {
    LogConfig.appLevel = level
    LogConfig.contextSnapshot = { null }
    LogSinks.clear()
    LogSinks.add(ConsoleLogSink())
}
