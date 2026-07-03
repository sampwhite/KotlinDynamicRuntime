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
 * Because topics are flat logical names (see [KdrLogger]) rather than a package
 * hierarchy, there is a single rootLevel governing all topics; the prior art's
 * per-package `org.dynamicruntime` override does not carry over. Verbosity is tuned
 * with `KDR_LOG_LEVEL` instead.
 */
@Suppress("ConstPropertyName")
object LogSetup {
    /** Env var naming the root [LogLevel] (e.g. `debug`); falls back to the [init] default. */
    const val logLevelEnvVar = "KDR_LOG_LEVEL"

    /** Env var naming the directory for the rolling log file; falls back to the [init] default. */
    const val logPathEnvVar = "KDR_LOG_PATH"

    /** The line format shared by every appender. Matches the prior art's pattern. */
    const val pattern = "[%-5level] %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %c{3} - %msg %throwable%n"

    /**
     * Reads [logLevelEnvVar] / [logPathEnvVar] from the environment and applies
     * [init], using the supplied defaults where an env var is absent.
     */
    fun initFromEnv(defaultLevel: LogLevel = LogLevel.info, defaultLogPath: String = "logs") {
        val level = System.getenv(logLevelEnvVar)?.let { logLevelOf(it) } ?: defaultLevel
        val logPath = System.getenv(logPathEnvVar) ?: defaultLogPath
        init(level, logPath)
    }

    /**
     * Builds and installs the logging configuration: a console appender and a
     * rolling file appender under [logPath], both using [pattern], with the root
     * logger set to [rootLevel]. Replaces any existing configuration.
     */
    fun init(rootLevel: LogLevel = LogLevel.info, logPath: String = "logs") {
        val builder = ConfigurationBuilderFactory.newConfigurationBuilder()
        builder.setConfigurationName("KdrLogging")
        // Keep log4j's own internal status logging quiet unless something is wrong.
        builder.setStatusLevel(Level.WARN)

        val console = builder.newAppender("console", "Console")
            .addAttribute("target", "SYSTEM_OUT")
            .add(builder.newLayout("PatternLayout").addAttribute("pattern", pattern))
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
