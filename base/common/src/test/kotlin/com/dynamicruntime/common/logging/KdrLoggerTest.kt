package com.dynamicruntime.common.logging

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/** A [LogSink] that just records what it is handed, for asserting dispatch. */
private class CaptureSink : LogSink {
    val records = mutableListOf<LogRecord>()
    override fun emit(record: LogRecord) {
        records.add(record)
    }
}

/**
 * Exercises the two-way logger (issue #79): level gating via [LogConfig], dispatch to the installed
 * [LogSinks], the shared [LogFormat] line (including a multiline stack trace), and the backend context
 * snapshot. Global logging state is saved and restored around each capturing test.
 */
class KdrLoggerTest : StringSpec({

    fun withCapture(level: LogLevel = LogLevel.debug, block: (CaptureSink) -> Unit) {
        val savedSinks = LogSinks.sinks.toList()
        val savedLevel = LogConfig.appLevel
        val savedSnapshot = LogConfig.contextSnapshot
        val capture = CaptureSink()
        LogSinks.clear(); LogSinks.add(capture)
        LogConfig.appLevel = level
        LogConfig.contextSnapshot = { null }
        try {
            block(capture)
        } finally {
            LogConfig.appLevel = savedLevel
            LogConfig.contextSnapshot = savedSnapshot
            LogSinks.clear(); savedSinks.forEach { LogSinks.add(it) }
        }
    }

    "an enabled event dispatches a record; a suppressed one does not" {
        withCapture(LogLevel.info) { cap ->
            val log = KdrLogger("test.dispatch")
            log.info(null, "shown")
            log.debug(null, "hidden") // below the info threshold
            cap.records.map { it.message } shouldBe listOf("shown")
            cap.records.single().let {
                it.level shouldBe LogLevel.info
                it.topic shouldBe "test.dispatch"
            }
        }
    }

    "isEnabled and the lazy overload honor the per-topic level" {
        withCapture(LogLevel.debug) {
            LogConfig.setTopicLevel("test.lazy", LogLevel.error)
            val log = KdrLogger("test.lazy")
            log.isEnabled(LogLevel.debug) shouldBe false
            log.isEnabled(LogLevel.error) shouldBe true

            var built = false
            log.debug(null) { built = true; "expensive" }
            built shouldBe false

            LogConfig.setTopicLevel("test.lazy", LogLevel.debug)
            log.debug(null) { built = true; "expensive" }
            built shouldBe true
        }
    }

    "formatLine renders level, topic, message, and a multiline stack trace" {
        val rec = LogRecord(
            LogLevel.warn, "billing", 0L, "watch out",
            LogContext(label = "[inst:cx(7)]", thread = "main"), RuntimeException("boom"), null,
        )
        val line = LogFormat.formatLine(rec, color = false)
        line shouldStartWith "[WARN ] "
        line shouldContain " [main] billing [inst:cx(7)] - watch out"
        line shouldContain "\njava.lang.RuntimeException: boom" // the throwable is appended as its stack trace
    }

    "the backend context snapshot labels a KdrCxt with instance and actor, plus the calling thread" {
        LogSetup.init(appLevel = LogLevel.debug) // installs the backend context snapshot
        val cxt = KdrCxt.mkSimpleCxt("start")
        val ctx = LogConfig.contextSnapshot(cxt)!!
        ctx.label!! shouldContain "codeTest:"
        ctx.thread shouldBe Thread.currentThread().name // captured synchronously on the calling thread
    }

    "logLevelOf parses names case-insensitively and rejects unknowns" {
        LogSetup.logLevelOf("DEBUG") shouldBe LogLevel.debug
        LogSetup.logLevelOf(" info ") shouldBe LogLevel.info
        LogSetup.logLevelOf("bogus") shouldBe null
    }
})
