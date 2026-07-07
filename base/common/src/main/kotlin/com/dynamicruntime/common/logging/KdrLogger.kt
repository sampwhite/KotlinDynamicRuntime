package com.dynamicruntime.common.logging

import com.dynamicruntime.common.annotation.KdrInternal
import com.dynamicruntime.common.context.KdrCxt
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * The logger. Every log statement in the code base goes through a [KdrLogger]
 * bound to a *topic* -- a flat logical name such as `"schema"` -- rather than to a
 * package or class. Topics deliberately replace the classical package-path logging
 * hierarchy: they group log output by concern, not by where the emitting code
 * happens to live.
 *
 * A topic is normally exposed as an `object` that extends this class, giving call
 * sites a named, greppable handle with no wrapper field:
 * ```
 * object LogSchema : KdrLogger("schema")
 * // ...
 * LogSchema.debug(cxt, "Creating read only schema store.")
 * ```
 * A subsystem-owned topic lives in that subsystem's package; a topic used across
 * disparate packages lives in this `logging` package instead (e.g. [LogStartup]).
 *
 * `Logger` is a collision-prone name (log4j2, slf4j, and others all define one), so
 * per the naming guide this carries the `Kdr` prefix. The wrapped log4j2 [Logger]
 * is an implementation detail and is never exposed on the public surface -- callers
 * and downstream modules see only [LogLevel] and plain strings.
 *
 * Rewritten from the prior-art `AppLogger` in the dynamicruntime project.
 */
open class KdrLogger(
    /** The flat topic name this logger emits under. */
    topic: String,
) {
    // Backed by a log4j logger named "<appNamespace>.<topic>", so every application topic shares the
    // `appNamespace` parent. That lets configuration target all of our topics as a group -- e.g., run them
    // at debug while third-party loggers stay at the root level (see LogSetup.init).
    @KdrInternal
    val logger: Logger = LogManager.getLogger("$appNamespace.$topic")

    fun trace(cxt: KdrCxt?, message: String) = log(cxt, LogLevel.trace, message)
    fun debug(cxt: KdrCxt?, message: String) = log(cxt, LogLevel.debug, message)
    fun info(cxt: KdrCxt?, message: String) = log(cxt, LogLevel.info, message)
    fun warn(cxt: KdrCxt?, message: String) = log(cxt, LogLevel.warn, message)

    /**
     * Logs at [LogLevel.error]. Unlike the prior art, the [cause] is a trailing
     * optional argument, so the common "error with no throwable" case reads
     * `error(cxt, "message")` with no positional `null`.
     */
    fun error(cxt: KdrCxt?, message: String, cause: Throwable? = null) =
        log(cxt, LogLevel.error, message, cause)

    // Lazy overloads: the message is only built when the level is enabled, so
    // expensive string construction is skipped for suppressed levels. A String
    // literal is not a function type, so these never collide with the eager
    // overloads above -- `debug(cxt) { "..." }` selects the lambda form.
    fun trace(cxt: KdrCxt?, message: () -> String) = logLazy(cxt, LogLevel.trace, null, message)
    fun debug(cxt: KdrCxt?, message: () -> String) = logLazy(cxt, LogLevel.debug, null, message)
    fun info(cxt: KdrCxt?, message: () -> String) = logLazy(cxt, LogLevel.info, null, message)
    fun warn(cxt: KdrCxt?, message: () -> String) = logLazy(cxt, LogLevel.warn, null, message)
    fun error(cxt: KdrCxt?, cause: Throwable? = null, message: () -> String) =
        logLazy(cxt, LogLevel.error, cause, message)

    /** Whether [level] would actually be emitted by this logger, given current config. */
    fun isEnabled(level: LogLevel): Boolean = logger.isEnabled(level.toLog4j())

    /**
     * The core log entry point (the prior art's `reportMessage`). Emits [message]
     * at [level], optionally with a [cause] throwable.
     *
     * [data] is an accepted-but-unused placeholder: a later structured-logging
     * implementation (e.g., forwarding to a Fluentd-style collector) will carry the
     * key/value pairs through here. It is present now, so call sites that want to
     * attach structured context can already do so without a signature change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun log(
        cxt: KdrCxt?,
        level: LogLevel,
        message: String,
        cause: Throwable? = null,
        data: Map<String, Any?>? = null,
    ) {
        val l = level.toLog4j()
        if (!logger.isEnabled(l)) {
            return
        }
        logger.log(l, format(cxt, message), cause)
    }

    private fun logLazy(cxt: KdrCxt?, level: LogLevel, cause: Throwable?, message: () -> String) {
        val l = level.toLog4j()
        if (!logger.isEnabled(l)) {
            return
        }
        logger.log(l, format(cxt, message()), cause)
    }

    /**
     * Renders the line the backend receives. When a [cxt] is present the message is
     * prefixed with `[instanceName:logInfo]` so every line is attributable to an
     * instance, context, and acting user; otherwise the bare message is used (for
     * logging that happens outside any context, such as early startup).
     */
    fun format(cxt: KdrCxt?, message: String): String {
        if (cxt == null) {
            return message
        }
        // A request's `_debug` tag, when present, is prefixed onto the message.
        val debugPrefix = cxt.debug?.let { "$it " } ?: ""
        return "[${cxt.instanceConfig.instanceName}:${cxt.logInfo()}] $debugPrefix$message"
    }

    @Suppress("ConstPropertyName")
    companion object {
        /**
         * Parent logger name shared by all application topics: a topic `t` is logged under `kdr.t`.
         * Configuration can then set this one logger's level to govern every application topic at once,
         * independently of third-party (root-governed) loggers.
         */
        const val appNamespace = "kdr"
    }
}

/**
 * Translates a [LogLevel] to the backing log4j2 [Level]. This is the single point
 * of contact with the library's level type; keeping it here (rather than on
 * [LogLevel] itself) keeps the enum free of any log4j import. Shared by both
 * [KdrLogger] and [LogSetup].
 */
@KdrInternal
fun LogLevel.toLog4j(): Level = when (this) {
    LogLevel.trace -> Level.TRACE
    LogLevel.debug -> Level.DEBUG
    LogLevel.info -> Level.INFO
    LogLevel.warn -> Level.WARN
    LogLevel.error -> Level.ERROR
    LogLevel.off -> Level.OFF
}
