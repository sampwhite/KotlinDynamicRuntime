package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.startup.InstanceRegistry
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** What one polled endpoint was called for in a window (issue #1395): how often, and for how long in all. */
data class PolledTally(
    /** The call as the request line writes it, method and path: `GET:/kda/home/ui/config`. */
    val call: String,
    val count: Long,
    val totalMs: Double,
) {
    val averageMs: Double get() = if (count == 0L) 0.0 else totalMs / count

    /** The summary line: one per endpoint called in the window. */
    fun logLine(windowMs: Long): String =
        "$call polled $count ${if (count == 1L) "time" else "times"} in ${windowMs / 1000} s, " +
            "average ${averageMs.roundToLong()} ms."
}

/**
 * The request log of a [KdrEndpoint.polled] endpoint, aggregated (issue #1395). A successful call to one writes no
 * line saying it was served -- that one line is what such a call writes when it behaves, and with a tab per person
 * re-reading the shell's configs every minute, those lines are most of a request log. Instead each call is counted
 * here, and every [intervalMs] one info line per endpoint called in the window gives its count and average duration,
 * and the counts start again. An endpoint not called writes nothing. A failed call is not counted: it logs as any
 * other does, which is what keeps an error in a polled endpoint visible.
 *
 * The timer is a single daemon thread started by the first call counted, so a node nothing polls starts none. On a
 * JVM shutdown the window so far is written, so the last minutes before a stop are not lost (best effort: the log's
 * own shutdown may already have flushed).
 */
class PolledCallLog(val intervalMs: Long = defaultIntervalMs) : AutoCloseable {
    private class Counter(var count: Long = 0, var totalMs: Double = 0.0)

    private val lock = Any()
    private val window = LinkedHashMap<String, Counter>()
    private var windowStartNanos = System.nanoTime()

    @Volatile
    private var timer: ScheduledExecutorService? = null
    private var timerCxt: KdrCxt? = null

    /** Counts one successful call to a polled endpoint, [call] as [PolledTally.call] names it, taking [durationMs]. */
    fun record(cxt: KdrCxt, call: String, durationMs: Double) {
        synchronized(lock) {
            val counter = window.getOrPut(call) { Counter() }
            counter.count++
            counter.totalMs += durationMs
        }
        if (timer == null) startTimer(cxt)
    }

    /**
     * Ends the window: writes its summary lines and starts the next with no counts. What the timer does; a test
     * calls it directly. Answers the window's tallies, in the order the endpoints were first called.
     */
    fun flush(cxt: KdrCxt): List<PolledTally> {
        val (tallies, windowMs) = synchronized(lock) {
            val now = System.nanoTime()
            val drained = window.map { (call, c) -> PolledTally(call, c.count, c.totalMs) }
            val elapsed = (now - windowStartNanos) / 1_000_000
            window.clear()
            windowStartNanos = now
            drained to elapsed
        }
        for (tally in tallies) {
            LogRequest.info(cxt) { tally.logLine(windowMs) }
        }
        return tallies
    }

    private fun startTimer(cxt: KdrCxt) {
        synchronized(lock) {
            if (timer != null) return
            val flushCxt = KdrCxt.mkSimpleCxt("polledCallLog", cxt.instanceConfig)
            val created = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "kdr-polled-call-log").also { it.isDaemon = true }
            }
            created.scheduleWithFixedDelay({
                // A flush that fails is logged and the next one tries again; the thread must outlive one failure.
                try {
                    flush(flushCxt)
                } catch (e: Exception) {
                    LogRequest.error(flushCxt, "Writing the polled endpoints' summary failed.", e)
                }
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
            timerCxt = flushCxt
            timer = created
        }
        InstanceRegistry.registerForShutdown(this)
    }

    /** Stops the timer and writes the window so far; called on JVM shutdown. */
    override fun close() {
        val stopping = timer ?: return
        stopping.shutdownNow()
        timerCxt?.let { runCatching { flush(it) } }
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** How long a window is: five minutes. */
        const val defaultIntervalMs = 5L * 60 * 1000
    }
}
