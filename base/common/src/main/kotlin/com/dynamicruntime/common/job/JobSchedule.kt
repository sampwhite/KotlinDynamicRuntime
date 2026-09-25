package com.dynamicruntime.common.job

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.fmt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * When a job runs on its own (issue #870): a rule producing **slots** -- scheduled start times -- each with a
 * **window** in which the job may run, from the slot's start to the point where an unfinished run is final. A
 * node inside a slot's window launches the job under the slot's [launchName], so every node names the same slot
 * the same way and a node that picks the work up after a failure adopts it rather than starting over.
 *
 * Deliberately a small declared type rather than cron syntax: jobs need "daily at these times" and "every so
 * often", and a rule that reads as what it does is one nobody has to decode. All times are UTC.
 */
sealed class JobSchedule(
    /** How long after a slot's start the job may still run (or be resumed) for that slot. */
    val window: Duration,
) {
    /** The latest slot starting at or before [now]. */
    abstract fun slotAt(now: Instant): Instant

    /** The shortest time between two consecutive slots. */
    abstract fun minSpacing(): Duration

    /** The slot whose window [now] is in, or null when the latest slot's window has closed. */
    fun openSlot(now: Instant): Instant? = slotAt(now).takeIf { now < it + window }

    /** The launch name every node gives [slot]. */
    fun launchName(slot: Instant): String = "slot-${slot.fmt()}"

    /** At the given times of each day, UTC. */
    class Daily(val minutesOfDay: List<Int>, window: Duration) : JobSchedule(window) {
        init {
            if (minutesOfDay.isEmpty()) throw KdrException("A daily job schedule needs at least one time.")
        }

        override fun slotAt(now: Instant): Instant {
            val ms = now.toEpochMilliseconds()
            val dayStart = Math.floorDiv(ms, dayMs) * dayMs
            val times = minutesOfDay.sorted().map { it * minuteMs }
            val today = times.lastOrNull { dayStart + it <= ms }
            return Instant.fromEpochMilliseconds(if (today != null) dayStart + today else dayStart - dayMs + times.last())
        }

        override fun minSpacing(): Duration {
            val times = minutesOfDay.distinct().sorted()
            // The gaps between the day's times, and the one wrapping round midnight to the first.
            val gaps = times.zipWithNext { a, b -> b - a } + (24 * 60 - times.last() + times.first())
            return gaps.min().minutes
        }
    }

    /**
     * Every [interval], counted from the epoch, so every node computes the same slots. Any interval of a second or
     * more can be declared; one under a minute is for tests, and the boot's job-config check refuses it on a node
     * that is not a test instance (issue #870) -- a deployment's floor, not the rule's.
     */
    class Every(val interval: Duration, window: Duration) : JobSchedule(window) {
        init {
            if (interval < 1.seconds) throw KdrException("A job schedule's interval must be at least a second.")
        }

        override fun minSpacing(): Duration = interval

        override fun slotAt(now: Instant): Instant {
            val step = interval.inWholeMilliseconds
            return Instant.fromEpochMilliseconds(Math.floorDiv(now.toEpochMilliseconds(), step) * step)
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        private const val minuteMs = 60_000L
        private const val dayMs = 24 * 60 * minuteMs

        /** Daily at each of [times], written `HH:mm` in UTC, with a [window] of four hours unless given. */
        fun daily(vararg times: String, window: Duration = 4.hours): JobSchedule = Daily(times.map { minuteOfDay(it) }, window)

        /** Every [interval], with a [window] of the whole interval unless given. */
        fun every(interval: Duration, window: Duration = interval): JobSchedule = Every(interval, window)

        private fun minuteOfDay(hhmm: String): Int {
            val parts = hhmm.split(':')
            val h = parts.getOrNull(0)?.toIntOrNull()
            val m = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || h == null || m == null || h !in 0..23 || m !in 0..59) {
                throw KdrException("'$hhmm' is not a time of day; write it HH:mm, in UTC.")
            }
            return h * 60 + m
        }
    }
}
