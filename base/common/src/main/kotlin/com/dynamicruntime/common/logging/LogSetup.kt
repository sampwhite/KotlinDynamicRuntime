package com.dynamicruntime.common.logging

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrCxtBase
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory

/**
 * Wires up the backend logging (issue #79): our own topics go through the two-way [KdrLogger] to a
 * [StdoutLogSink] (optionally async), and third-party libraries (Jetty, etc.) keep logging through log4j2 --
 * so the two paths only need their *formats* to agree, which they do by design ([LogFormat] mirrors the log4j
 * pattern below). Everything is configured in Kotlin, no config resource files; env vars are the tuning
 * channel. An application calls [initFromEnv] once at startup.
 *
 * There is deliberately no rolling-file appender: the app writes to stdout, and a deployment tool captures and
 * rolls it, while durable/queryable storage will arrive as a separate (OpenSearch) sink.
 */
@Suppress("ConstPropertyName")
object LogSetup {
    /** Env var naming the [LogLevel] for our application topics; falls back to the [initFromEnv] default. */
    const val appLogLevelEnvVar = "KDR_LOG_LEVEL"

    /** Env var naming the root [LogLevel] for everything else (third-party log4j2 loggers). */
    const val rootLogLevelEnvVar = "KDR_ROOT_LOG_LEVEL"

    /** Env var toggling async delivery of our logs (`true`/`false`); default sync for immediate, ordered output. */
    const val asyncEnvVar = "KDR_LOG_ASYNC"

    /** The log4j2 console pattern for third-party loggers -- shaped to match [LogFormat]'s line. */
    const val thirdPartyPattern =
        "[%highlight{%-5level}{FATAL=bright red, ERROR=red, WARN=yellow, INFO=green, DEBUG=cyan, TRACE=white}] " +
            "%style{%d{yyyy-MM-dd HH:mm:ss.SSS}}{dim} [%t] %style{%c{3}}{cyan} - %msg %throwable%n"

    /** The async sink currently installed (kept so a re-init or shutdown can flush it). */
    private var asyncSink: AsyncLogSink? = null

    /**
     * Reads [appLogLevelEnvVar] / [rootLogLevelEnvVar] / [asyncEnvVar] via [getEnv] and applies [init], using
     * the supplied defaults where a variable is absent. [getEnv] defaults to the process environment; the
     * booting application passes `cxt::getEnvVar` so instance-config defaults are honored too.
     */
    fun initFromEnv(
        defaultAppLevel: LogLevel = LogLevel.debug,
        defaultRootLevel: LogLevel = LogLevel.info,
        defaultAsync: Boolean = false,
        getEnv: (String) -> String? = System::getenv,
    ) {
        val appLevel = getEnv(appLogLevelEnvVar)?.let { logLevelOf(it) } ?: defaultAppLevel
        val rootLevel = getEnv(rootLogLevelEnvVar)?.let { logLevelOf(it) } ?: defaultRootLevel
        val async = getEnv(asyncEnvVar)?.trim()?.equals("true", ignoreCase = true) ?: defaultAsync
        init(appLevel, rootLevel, async)
    }

    /**
     * Installs the logging configuration: our topics at [appLevel] through a [StdoutLogSink] (wrapped in an
     * [AsyncLogSink] when [async]), and a log4j2 console at [rootLevel] for third-party loggers. Color is on in
     * sync mode (interactive dev) and off in async mode (captured production output). Replaces any prior setup.
     */
    fun init(appLevel: LogLevel = LogLevel.debug, rootLevel: LogLevel = LogLevel.info, async: Boolean = false) {
        // --- our own topics: kernel logger -> stdout sink ---
        asyncSink?.flush()
        asyncSink = null
        LogSinks.clear()
        LogConfig.appLevel = appLevel
        LogConfig.contextSnapshot = ::snapshotContext

        val stdout = StdoutLogSink(color = !async)
        if (async) {
            val wrapped = AsyncLogSink(stdout)
            asyncSink = wrapped
            Runtime.getRuntime().addShutdownHook(Thread { wrapped.flush() })
            LogSinks.add(wrapped)
        } else {
            LogSinks.add(stdout)
        }

        // --- third-party (Jetty, etc.): a minimal log4j2 console at the root level, colored to match ours ---
        configureThirdPartyLog4j(rootLevel, color = !async)
    }

    /** Snapshots a [KdrCxt] into the label/thread a record carries; a non-KdrCxt context yields just the thread. */
    private fun snapshotContext(cxt: KdrCxtBase?): LogContext {
        val label = (cxt as? KdrCxt)?.let { c ->
            val debug = c.debug?.let { " $it" } ?: ""
            "[${c.instanceConfig.instanceName}:${c.logInfo()}]$debug"
        }
        return LogContext(label = label, thread = Thread.currentThread().name)
    }

    private fun configureThirdPartyLog4j(rootLevel: LogLevel, color: Boolean) {
        val builder = ConfigurationBuilderFactory.newConfigurationBuilder()
        builder.setConfigurationName("KdrThirdPartyLogging")
        builder.setStatusLevel(Level.WARN)
        val console = builder.newAppender("console", "Console")
            .addAttribute("target", "SYSTEM_OUT")
            .add(
                builder.newLayout("PatternLayout")
                    .addAttribute("pattern", thirdPartyPattern)
                    .addAttribute("disableAnsi", !color),
            )
        builder.add(console)
        builder.add(
            builder.newRootLogger(rootLevel.toLog4jLevel()).add(builder.newAppenderRef("console")),
        )
        Configurator.initialize(builder.build())
    }

    /** Parses a [LogLevel] from its (case-insensitive) name, or null if unrecognized. */
    fun logLevelOf(name: String): LogLevel? =
        LogLevel.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Maps our [LogLevel] to a log4j2 [Level] -- used only to set the third-party root logger. */
    private fun LogLevel.toLog4jLevel(): Level = when (this) {
        LogLevel.trace -> Level.TRACE
        LogLevel.debug -> Level.DEBUG
        LogLevel.info -> Level.INFO
        LogLevel.warn -> Level.WARN
        LogLevel.error -> Level.ERROR
        LogLevel.off -> Level.OFF
    }
}
