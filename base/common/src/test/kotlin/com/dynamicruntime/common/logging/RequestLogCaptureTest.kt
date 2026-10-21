package com.dynamicruntime.common.logging

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** A [LogSink] that records what it is handed. */
private class RecordingSink : LogSink {
    val records = mutableListOf<LogRecord>()
    override fun emit(record: LogRecord) {
        records.add(record)
    }
}

/**
 * Capturing a context's log lines (issue #1173): kept as well as logged, down to debug whatever the topic is set to,
 * shared with sub contexts, and bounded. Through a request, see `CaptureLogsTest` in kdn.
 */
class RequestLogCaptureTest : StringSpec({

    fun withSink(level: LogLevel, block: (RecordingSink) -> Unit) {
        val savedSinks = LogSinks.sinks.toList()
        val savedLevel = LogConfig.appLevel
        val sink = RecordingSink()
        LogSinks.clear(); LogSinks.add(sink)
        LogConfig.appLevel = level
        try {
            block(sink)
        } finally {
            LogConfig.appLevel = savedLevel
            LogSinks.clear(); savedSinks.forEach { LogSinks.add(it) }
        }
    }

    val log = KdrLogger("test.capture")

    "a line below the topic's level is captured and not emitted; one at it is both" {
        withSink(LogLevel.info) { sink ->
            val cxt = KdrCxt.mkSimpleCxt("capture")
            val capture = RequestLogCapture(inResponse = true)
            cxt.logCapture = capture
            log.debug(cxt) { "below" }
            log.info(cxt, "at")
            log.trace(cxt, "below what a capture keeps")

            sink.records.map { it.message } shouldBe listOf("at")
            capture.lines().map { it[RLOG.message] } shouldBe listOf("below", "at")
            capture.lines().first()[RLOG.level] shouldBe "debug"
            capture.lines().first()[RLOG.topic] shouldBe "test.capture"
        }
    }

    "a context without a capture logs as it always did, and builds no suppressed line" {
        withSink(LogLevel.info) { sink ->
            var built = false
            log.debug(KdrCxt.mkSimpleCxt("plain")) { built = true; "never" }
            built shouldBe false
            sink.records shouldHaveSize 0
        }
    }

    "a sub context's lines go to the same capture" {
        withSink(LogLevel.info) {
            val cxt = KdrCxt.mkSimpleCxt("parent")
            val capture = RequestLogCapture(inResponse = true)
            cxt.logCapture = capture
            log.debug(cxt.mkSubContext("child")) { "from the sub context" }
            log.debug(cxt.mkTransactionSubContext("tx")) { "from a transaction" }
            capture.lines().map { it[RLOG.message] } shouldBe listOf("from the sub context", "from a transaction")
        }
    }

    "an exception keeps its class and message, not its stack" {
        withSink(LogLevel.info) {
            val cxt = KdrCxt.mkSimpleCxt("error")
            val capture = RequestLogCapture(inResponse = true)
            cxt.logCapture = capture
            log.error(cxt, "it failed", IllegalStateException("the reason"))
            val line = capture.lines().single()
            line[RLOG.errorClass] shouldBe "java.lang.IllegalStateException"
            line[RLOG.errorMessage] shouldBe "the reason"
            capture.text() shouldContain "error test.capture - it failed [java.lang.IllegalStateException: the reason]"
        }
    }

    "the cap keeps the first lines and counts the rest" {
        withSink(LogLevel.info) {
            val cxt = KdrCxt.mkSimpleCxt("cap")
            val capture = RequestLogCapture(inResponse = true, maxLines = 2)
            cxt.logCapture = capture
            for (i in 1..5) log.debug(cxt, "line $i")
            capture.meta()[RLOG.logs].toJsonListOfMaps().map { it[RLOG.message] } shouldBe listOf("line 1", "line 2")
            capture.meta()[RLOG.logsOmitted] shouldBe 3
            capture.text() shouldContain "... 3 more line(s) left out"

            // The size cap too: a long message is clipped, and what would pass the total is left out.
            val sized = RequestLogCapture(inResponse = true, maxChars = 3_000)
            cxt.logCapture = sized
            log.debug(cxt, "x".repeat(10_000))
            log.debug(cxt, "y".repeat(1_500))
            sized.lines().single()[RLOG.message].toString().length shouldBe RequestLogCapture.maxLineChars
            sized.omitted() shouldBe 1
        }
    }

    "uncut, the meta says nothing of omissions" {
        val capture = RequestLogCapture(inResponse = true)
        capture.meta().keys shouldBe setOf(RLOG.logs)
    }
})
