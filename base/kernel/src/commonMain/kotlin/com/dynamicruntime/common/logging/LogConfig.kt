package com.dynamicruntime.common.logging

import com.dynamicruntime.common.context.KdrCxtBase

/**
 * The shared (KMP) logging configuration (issue #79): the level threshold that gates every topic, and the
 * platform hook that snapshots a context into a [LogContext]. The backend sets these in `LogSetup`; the
 * frontend sets them at app init. Kept minimal and set once at startup.
 */
object LogConfig {
    /** Default threshold for all topics without an explicit override; a message is emitted at or above it. */
    var appLevel: LogLevel = LogLevel.debug

    private val topicLevels = HashMap<String, LogLevel>()

    /**
     * Snapshots the emitting context into the plain strings a record carries -- run **synchronously at log
     * time** so nothing mutable is read later. Defaults to "no context"; the backend installs one that reads
     * `KdrCxt` (via `is KdrCxt`) for the instance/actor label and thread, the frontend a minimal one.
     */
    var contextSnapshot: (KdrCxtBase?) -> LogContext? = { null }

    /** Overrides the threshold for a single [topic] (e.g., run `"sql"` at trace while others stay at info). */
    fun setTopicLevel(topic: String, level: LogLevel) {
        topicLevels[topic] = level
    }

    /** Whether [topic] would emit at [level] under the current thresholds. [LogLevel.off] never emits. */
    fun isEnabled(topic: String, level: LogLevel): Boolean {
        if (level == LogLevel.off) return false
        val threshold = topicLevels[topic] ?: appLevel
        return threshold != LogLevel.off && level.ordinal >= threshold.ordinal
    }
}
