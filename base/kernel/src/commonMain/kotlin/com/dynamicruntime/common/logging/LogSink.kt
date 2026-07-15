package com.dynamicruntime.common.logging

/**
 * A destination for log events (issue #79). Implementations render/deliver a [LogRecord] however their target
 * wants: the backend `StdoutLogSink` writes a formatted line to stdout, the frontend `ConsoleLogSink` writes to
 * the browser console, and async delivery is itself just a sink (`AsyncLogSink`) that wraps another. A future
 * OpenSearch sink is the same shape.
 *
 * [emit] must not throw -- a logger must never break its caller. It should also be cheap or hand off to a
 * background worker (see the backend `AsyncLogSink`), since it runs on the calling thread otherwise.
 */
interface LogSink {
    fun emit(record: LogRecord)
}

/**
 * The registry of installed [LogSink]s that every [KdrLogger] fans an enabled event out to. Configured once at
 * startup (the backend installs its stdout sink in `LogSetup`; the frontend installs a console sink at app
 * init), then read on the logging hot path.
 */
object LogSinks {
    private val installed = ArrayList<LogSink>()

    /** The current sinks. Snapshotted per call so a startup-time registration never races an in-flight emit. */
    val sinks: List<LogSink> get() = installed

    fun add(sink: LogSink) {
        installed.add(sink)
    }

    /** Removes all sinks (mainly for tests / re-init). */
    fun clear() {
        installed.clear()
    }
}
