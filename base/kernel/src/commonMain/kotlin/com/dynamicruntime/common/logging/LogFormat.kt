package com.dynamicruntime.common.logging

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The single, shared (KMP) renderer of a human-readable log line (issue #79) -- so a line looks the same on
 * the backend and the frontend, differing only in what the platform legitimately can't provide (the frontend
 * has no thread) and whether ANSI color is applied. The shape deliberately mirrors the prior log4j pattern
 * (`LEVEL timestamp thread topic label - message`) so our lines stay consistent with third-party library
 * lines that still go through log4j. A throwable is appended as its full multiline stack trace.
 */
@Suppress("ConstPropertyName")
object LogFormat {
    /** Log timestamp in the platform's local zone (matching the third-party log4j `%d`), `yyyy-MM-dd HH:mm:ss.SSS`. */
    @Suppress("DuplicatedCode")
    private val timeFormat = LocalDateTime.Format {
        year(); char('-'); monthNumber(); char('-'); day()
        char(' ')
        hour(); char(':'); minute(); char(':'); second()
        char('.'); secondFraction(3)
    }

    private const val reset = "\u001B[0m"

    private fun colorCode(level: LogLevel): String = when (level) {
        LogLevel.error -> "\u001B[31m" // red
        LogLevel.warn -> "\u001B[33m" // yellow
        LogLevel.info -> "\u001B[32m" // green
        LogLevel.debug -> "\u001B[36m" // cyan
        LogLevel.trace -> "\u001B[37m" // white
        LogLevel.off -> ""
    }

    /** Renders [record] to a log line. [color] wraps the level token in ANSI color (terminals only). */
    fun formatLine(record: LogRecord, color: Boolean): String {
        val ts = Instant.fromEpochMilliseconds(record.timeMs).toLocalDateTime(TimeZone.currentSystemDefault()).format(timeFormat)
        val levelName = record.level.name.uppercase().padEnd(5)
        val levelToken = if (color) "[${colorCode(record.level)}$levelName$reset]" else "[$levelName]"
        val thread = record.context?.thread?.let { " [$it]" } ?: ""
        val label = record.context?.label?.let { " $it" } ?: ""
        val head = "$levelToken $ts$thread ${record.topic}$label - ${record.message}"
        return record.cause?.let { "$head\n${it.stackTraceToString()}" } ?: head
    }
}
