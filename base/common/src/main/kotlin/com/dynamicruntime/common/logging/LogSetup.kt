package com.dynamicruntime.common.logging

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory

/**
 * Configures the logging backend entirely in Kotlin code, replacing the prior
 * art's `log4j2.yaml` resource. Per the code guide, what would conventionally be a
 * config resource file is instead built programmatically here, so the whole logging
 * configuration lives in the same language and build as everything else.
 *
 * An application calls [init] once at startup, before it begins logging in earnest.
 * Defaults can be overridden per deployment through environment variables -- the
 * code base's preferred configuration channel -- via [initFromEnv].
 *
 * Application topics all live under the [KdrLogger.appNamespace] parent logger, so they can be
 * configured as a group: by default they log at debug (`appLevel`) while everything else -- third-party
 * libraries such as Jetty, which log under their own package names -- follows the root level
 * (`rootLevel`, info). Both are tunable via environment variables.
 */
@Suppress("ConstPropertyName")
object LogSetup {
    /** Env var naming the [LogLevel] for application topics; falls back to the [init] default (debug). */
    const val appLogLevelEnvVar = "KDR_LOG_LEVEL"

    /** Env var naming the root [LogLevel] for everything else (third-party); falls back to the default (info). */
    const val rootLogLevelEnvVar = "KDR_ROOT_LOG_LEVEL"

    /** Env var naming the directory for the rolling log file; falls back to the [init] default. */
    const val logPathEnvVar = "KDR_LOG_PATH"

    /** The plain line format, used by the file appender (no ANSI codes belong in a log file). */
    const val pattern = "[%-5level] %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %c{3} - %msg %throwable%n"

    /**
     * The console line format: like [pattern] but with ANSI coloration a terminal renders. The level is
     * colored by severity (red errors, yellow warnings, …), the timestamp dimmed, and the topic cyan;
     * the message keeps the default color. `%highlight`/`%style` emit the ANSI escapes.
     */
    const val consolePattern =
        "[%highlight{%-5level}{FATAL=bright red, ERROR=red, WARN=yellow, INFO=green, DEBUG=cyan, TRACE=white}] " +
            "%style{%d{yyyy-MM-dd HH:mm:ss.SSS}}{dim} [%t] %style{%c{3}}{cyan} - %msg %throwable%n"

    /**
     * Reads [appLogLevelEnvVar] / [rootLogLevelEnvVar] / [logPathEnvVar] from the environment and applies
     * [init], using the supplied defaults where an env var is absent.
     */
    fun initFromEnv(
        defaultAppLevel: LogLevel = LogLevel.debug,
        defaultRootLevel: LogLevel = LogLevel.info,
        defaultLogPath: String = "logs",
    ) {
        val appLevel = System.getenv(appLogLevelEnvVar)?.let { logLevelOf(it) } ?: defaultAppLevel
        val rootLevel = System.getenv(rootLogLevelEnvVar)?.let { logLevelOf(it) } ?: defaultRootLevel
        val logPath = System.getenv(logPathEnvVar) ?: defaultLogPath
        init(rootLevel, appLevel, logPath)
    }

    /**
     * Builds and installs the logging configuration: a console appender and a rolling file appender under
     * [logPath], both using [pattern]. The root logger is set to [rootLevel] (governing third-party
     * loggers), and the [KdrLogger.appNamespace] logger to [appLevel] (governing all application topics).
     * Replaces any existing configuration.
     */
    fun init(rootLevel: LogLevel = LogLevel.info, appLevel: LogLevel = LogLevel.debug, logPath: String = "logs") {
        val builder = ConfigurationBuilderFactory.newConfigurationBuilder()
        builder.setConfigurationName("KdrLogging")
        // Keep log4j's own internal status logging quiet unless something is wrong.
        builder.setStatusLevel(Level.WARN)

        val console = builder.newAppender("console", "Console")
            .addAttribute("target", "SYSTEM_OUT")
            .add(
                builder.newLayout("PatternLayout")
                    .addAttribute("pattern", consolePattern)
                    // Force ANSI on: emit the color escapes (the file appender uses the plain pattern instead).
                    .addAttribute("disableAnsi", false),
            )
        builder.add(console)

        val rolling = builder.newAppender("rolling", "RollingFile")
            .addAttribute("fileName", "$logPath/rollfile.log")
            .addAttribute("filePattern", "$logPath/rollfile.log.%d{yyyy-MM-dd-HH-mm}.gz")
            .add(builder.newLayout("PatternLayout").addAttribute("pattern", pattern))
            .addComponent(
                builder.newComponent("Policies").addComponent(
                    builder.newComponent("SizeBasedTriggeringPolicy").addAttribute("size", "1 MB")
                )
            )
            .addComponent(builder.newComponent("DefaultRolloverStrategy").addAttribute("max", "5"))
        builder.add(rolling)

        val root = builder.newRootLogger(rootLevel.toLog4j())
            .add(builder.newAppenderRef("console"))
            .add(builder.newAppenderRef("rolling"))
        builder.add(root)

        // Application topics (every logger under KdrLogger.appNamespace, e.g. "kdr.schema") log at
        // appLevel, independent of the root level that governs third-party loggers. It is additive (the
        // default), so its events flow up to the root's appenders -- no separate appender refs needed.
        builder.add(builder.newLogger(KdrLogger.appNamespace, appLevel.toLog4j()))

        // `initialize` (rather than `reconfigure`) so that, when the logger context
        // has not been started yet, it starts directly with this configuration --
        // avoiding log4j2 first spinning up its default configuration and printing a
        // "no configuration file found" status warning. If a context is already
        // running (e.g., across tests), it is reconfigured in place.
        Configurator.initialize(builder.build())
    }

    /** Parses a [LogLevel] from its (case-insensitive) name, or null if unrecognized. */
    fun logLevelOf(name: String): LogLevel? =
        LogLevel.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
}
