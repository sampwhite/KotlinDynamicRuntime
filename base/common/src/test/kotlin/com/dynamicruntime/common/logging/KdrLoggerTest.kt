package com.dynamicruntime.common.logging

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator

/**
 * Exercises the parts of the logging layer we own: the `[instance:cxt]` line
 * formatting, the [LogLevel] -> log4j mapping, level gating, and the lazy overload's
 * message-skipping. Level gating is pinned per-topic with [Configurator.setLevel] so
 * the assertions are independent of any global configuration another spec installs.
 */
class KdrLoggerTest : StringSpec({

    "format prefixes with instance and context info when a cxt is present" {
        val log = KdrLogger("test.format")
        val cxt = KdrCxt.mkSimpleCxt("start")
        log.format(cxt, "hello") shouldBe "[codeTest:${cxt.logInfo()}] hello"
        // The system user has no authId, so the context label ends with %sys.
        cxt.logInfo().endsWith("%sys") shouldBe true
    }

    "format passes the bare message through when there is no cxt" {
        KdrLogger("test.format").format(null, "hi") shouldBe "hi"
    }

    "LogLevel maps to the backing log4j level" {
        LogLevel.trace.toLog4j() shouldBe Level.TRACE
        LogLevel.debug.toLog4j() shouldBe Level.DEBUG
        LogLevel.info.toLog4j() shouldBe Level.INFO
        LogLevel.warn.toLog4j() shouldBe Level.WARN
        LogLevel.error.toLog4j() shouldBe Level.ERROR
        LogLevel.off.toLog4j() shouldBe Level.OFF
    }

    "isEnabled reflects the configured level" {
        val topic = "test.enabled"
        val log = KdrLogger(topic)

        Configurator.setLevel(topic, Level.ERROR)
        log.isEnabled(LogLevel.debug) shouldBe false
        log.isEnabled(LogLevel.error) shouldBe true

        Configurator.setLevel(topic, Level.DEBUG)
        log.isEnabled(LogLevel.debug) shouldBe true
    }

    "the lazy overload does not build its message when the level is disabled" {
        val topic = "test.lazy"
        val log = KdrLogger(topic)

        Configurator.setLevel(topic, Level.ERROR)
        var built = false
        log.debug(null) { built = true; "expensive" }
        built shouldBe false

        Configurator.setLevel(topic, Level.DEBUG)
        log.debug(null) { built = true; "expensive" }
        built shouldBe true
    }

    "logLevelOf parses names case-insensitively and rejects unknowns" {
        LogSetup.logLevelOf("DEBUG") shouldBe LogLevel.debug
        LogSetup.logLevelOf(" info ") shouldBe LogLevel.info
        LogSetup.logLevelOf("bogus") shouldBe null
    }
})
