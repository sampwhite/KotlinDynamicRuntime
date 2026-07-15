package com.dynamicruntime.common.logging

import com.dynamicruntime.common.context.KdrCxtBase
import kotlin.time.Clock

/**
 * The logger (issue #79 rewrite: now two-way / KMP, no logging library). Every log statement goes through a
 * [KdrLogger] bound to a *topic* -- a flat logical name such as `"schema"` -- rather than to a package or
 * class. Topics group log output by concern, not by where the emitting code lives.
 *
 * A topic is normally an `object` that extends this class, giving call sites a named, greppable handle:
 * ```
 * object LogSchema : KdrLogger("schema")
 * // ...
 * LogSchema.debug(cxt, "Creating read only schema store.")
 * ```
 *
 * The logger itself only *gates* (via [LogConfig]) and builds an immutable [LogRecord]; where the event
 * actually goes is up to the installed [LogSinks] (stdout on the backend, the console on the frontend, a
 * future OpenSearch sink). Because the core is kernel code, the identical logging runs on both targets.
 *
 * `Logger` is a collision-prone name, so per the naming guide this carries the `Kdr` prefix. The context is
 * typed [KdrCxtBase] so kernel/frontend code can log too; the backend's richer context is picked up by the
 * installed [LogConfig.contextSnapshot].
 */
open class KdrLogger(
    /** The flat topic name this logger emits under. */
    val topic: String,
) {
    fun trace(cxt: KdrCxtBase?, message: String) = log(cxt, LogLevel.trace, message)
    fun debug(cxt: KdrCxtBase?, message: String) = log(cxt, LogLevel.debug, message)
    fun info(cxt: KdrCxtBase?, message: String) = log(cxt, LogLevel.info, message)
    fun warn(cxt: KdrCxtBase?, message: String) = log(cxt, LogLevel.warn, message)

    /**
     * Logs at [LogLevel.error]. The [cause] is a trailing optional argument, so the common "error with no
     * throwable" case reads `error(cxt, "message")` with no positional `null`.
     */
    fun error(cxt: KdrCxtBase?, message: String, cause: Throwable? = null) =
        log(cxt, LogLevel.error, message, cause)

    // Lazy overloads: the message is only built when the level is enabled, so expensive construction is
    // skipped for suppressed levels. A String literal is not a function type, so these never collide with the
    // eager overloads -- `debug(cxt) { "..." }` selects the lambda form.
    fun trace(cxt: KdrCxtBase?, message: () -> String) = logLazy(cxt, LogLevel.trace, null, message)
    fun debug(cxt: KdrCxtBase?, message: () -> String) = logLazy(cxt, LogLevel.debug, null, message)
    fun info(cxt: KdrCxtBase?, message: () -> String) = logLazy(cxt, LogLevel.info, null, message)
    fun warn(cxt: KdrCxtBase?, message: () -> String) = logLazy(cxt, LogLevel.warn, null, message)
    fun error(cxt: KdrCxtBase?, cause: Throwable? = null, message: () -> String) =
        logLazy(cxt, LogLevel.error, cause, message)

    /** Whether [level] would actually be emitted by this topic, given current config. */
    fun isEnabled(level: LogLevel): Boolean = LogConfig.isEnabled(topic, level)

    /** The core log entry point: emits [message] at [level], optionally with a [cause] and structured [data]. */
    fun log(
        cxt: KdrCxtBase?,
        level: LogLevel,
        message: String,
        cause: Throwable? = null,
        data: Map<String, Any?>? = null,
    ) {
        if (!LogConfig.isEnabled(topic, level)) {
            return
        }
        dispatch(cxt, level, message, cause, data)
    }

    private fun logLazy(cxt: KdrCxtBase?, level: LogLevel, cause: Throwable?, message: () -> String) {
        if (!LogConfig.isEnabled(topic, level)) {
            return
        }
        dispatch(cxt, level, message(), cause, null)
    }

    private fun dispatch(cxt: KdrCxtBase?, level: LogLevel, message: String, cause: Throwable?, data: Map<String, Any?>?) {
        // Snapshot everything mutable/platform-specific here, on the calling thread, so an async sink is safe.
        val record = LogRecord(
            level = level,
            topic = topic,
            timeMs = (cxt?.now() ?: Clock.System.now()).toEpochMilliseconds(),
            message = message,
            context = LogConfig.contextSnapshot(cxt),
            cause = cause,
            data = data,
        )
        for (sink in LogSinks.sinks) {
            sink.emit(record)
        }
    }
}
