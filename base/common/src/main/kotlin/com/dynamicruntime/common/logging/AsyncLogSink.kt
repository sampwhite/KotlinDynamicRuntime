package com.dynamicruntime.common.logging

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A backend delivery decorator (issue #79) that hands log events off to a background daemon thread, so the
 * expensive part (the actual write/network I/O) never runs on the caller's thread. It wraps any [LogSink]
 * (`AsyncLogSink(StdoutLogSink())`), and is the reusable async primitive a future OpenSearch sink will sit
 * behind too. Async is opt-in per deployment: local/dev/test log synchronously (immediate, ordered,
 * crash-safe output); production wraps in this.
 *
 * Overflow is **non-blocking**: on a full queue an event is dropped and counted rather than blocking the
 * application or growing unbounded -- logging must never stall or OOM its host. A dropped-count notice is
 * emitted through the delegate once the queue drains. A shutdown [flush] drains what remains.
 *
 * Batching (e.g. for an OpenSearch `_bulk`) is deliberately left to the wrapped sink: this decorator only
 * decouples threads; a sink that wants batches accumulates them internally.
 */
class AsyncLogSink(
    private val delegate: LogSink,
    capacity: Int = defaultCapacity,
) : LogSink {
    private val queue = ArrayBlockingQueue<LogRecord>(capacity)
    private val dropped = AtomicLong(0)

    @Volatile
    private var running = true

    private val worker = thread(name = workerThreadName, isDaemon = true) { drainLoop() }

    override fun emit(record: LogRecord) {
        if (!queue.offer(record)) {
            dropped.incrementAndGet()
        }
    }

    private fun drainLoop() {
        while (running || queue.isNotEmpty()) {
            val record = queue.poll(pollMillis, TimeUnit.MILLISECONDS) ?: continue
            deliver(record)
            reportDropsIfAny()
        }
    }

    private fun deliver(record: LogRecord) {
        try {
            delegate.emit(record)
        } catch (_: Throwable) {
            // Never let a sink failure kill the worker.
        }
    }

    /** After the queue drains, surface how many events were dropped under pressure, then reset the counter. */
    private fun reportDropsIfAny() {
        if (queue.isNotEmpty()) return
        val n = dropped.getAndSet(0)
        if (n > 0) {
            deliver(
                LogRecord(
                    level = LogLevel.warn, topic = logTopic,
                    timeMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                    message = "Dropped $n log event(s) under load (async queue full).",
                    context = null, cause = null, data = null,
                ),
            )
        }
    }

    /** Stops the worker and drains remaining events synchronously -- call from a shutdown hook. */
    fun flush() {
        running = false
        worker.join(joinMillis)
        while (queue.isNotEmpty()) {
            queue.poll()?.let { deliver(it) }
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val defaultCapacity = 8192
        const val workerThreadName = "kdr-log"
        const val logTopic = "logging"
        private const val pollMillis = 200L
        private const val joinMillis = 2000L
    }
}
