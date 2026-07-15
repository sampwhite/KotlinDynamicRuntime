package com.dynamicruntime.common

import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.logging.LogConfig
import com.dynamicruntime.common.logging.LogContext
import com.dynamicruntime.common.logging.LogLevel
import com.dynamicruntime.common.logging.LogRecord
import com.dynamicruntime.common.logging.LogSink
import com.dynamicruntime.common.logging.LogSinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CaptureSink : LogSink {
    val records = mutableListOf<LogRecord>()
    override fun emit(record: LogRecord) {
        records.add(record)
    }
}

/**
 * Proves the two-way logger core (issue #79) runs identically on BOTH targets (JVM + JS): level gating,
 * dispatch to the installed sinks, and the shared line format all live in the kernel, so this exact source
 * compiles and executes on the backend and the frontend.
 */
class TwoWayLoggerTest {

    @Test
    fun gatesAndDispatchesToSinks() {
        val saved = LogSinks.sinks.toList()
        val savedLevel = LogConfig.appLevel
        val cap = CaptureSink()
        LogSinks.clear(); LogSinks.add(cap)
        LogConfig.appLevel = LogLevel.info
        try {
            val log = KdrLogger("kernel.test")
            log.info(null, "shown")
            log.debug(null, "hidden") // below threshold
            assertEquals(listOf("shown"), cap.records.map { it.message })
            assertEquals(LogLevel.info, cap.records.single().level)
        } finally {
            LogConfig.appLevel = savedLevel
            LogSinks.clear(); saved.forEach { LogSinks.add(it) }
        }
    }

    @Test
    fun formatsTheSharedLine() {
        val line = com.dynamicruntime.common.logging.LogFormat.formatLine(
            LogRecord(LogLevel.info, "kernel.test", 0L, "hi", LogContext(label = null, thread = null), null, null),
            color = false,
        )
        assertTrue(line.startsWith("[INFO ] "), line)
        assertTrue(line.endsWith(" kernel.test - hi"), line)
    }
}
