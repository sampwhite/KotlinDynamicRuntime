package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.PolledCallLog
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.logging.RLOG
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * A polled endpoint's request log, aggregated (issue #1395): a successful call writes no line saying it was served
 * and is counted instead, a summary per endpoint called in a window gives the count and average duration, and a
 * failed call, or one carrying a `_debug` tag, logs as any other does.
 */
class PolledCallLogTest : StringSpec({
    val cxt = TestInstances.default("polledCallLog")
    val polledCalls = RequestService.get(cxt).polledCalls
    val user = TestUser.create(cxt, "person@polled1395.test")
    val appConfigCall = "GET:/${TestHttpClient(cxt.instanceConfig).contextRoot}${APP.uiConfig}"

    /** The request log's own lines a call wrote, as the test client captured them. */
    fun requestLines(handler: RequestHandler): List<String> =
        handler.logCapture.shouldNotBeNull().lines()
            .filter { it[RLOG.topic] == "request" }
            .map { it[RLOG.message].toString() }

    fun servedLine(handler: RequestHandler): Boolean = requestLines(handler).any { it.startsWith("200 ") }

    "a polled call writes no served line and is counted; the window's summary gives its count and average" {
        polledCalls.flush(cxt)
        val handlers = (1..3).map { user.client.sendGetRequest(APP.uiConfig) }
        handlers.forEach { servedLine(it) shouldBe false }

        val tally = polledCalls.flush(cxt).first { it.call == appConfigCall }
        tally.count shouldBeGreaterThanOrEqual 3L
        tally.averageMs shouldBe tally.totalMs / tally.count
        tally.logLine(300_000) shouldStartWith "$appConfigCall polled ${tally.count} times in 300 s, average "
    }

    "an endpoint not called in a window writes no summary" {
        polledCalls.flush(cxt)
        polledCalls.flush(cxt).map { it.call } shouldNotContain appConfigCall
    }

    "a call to an endpoint that is not polled writes its served line as before" {
        servedLine(user.client.sendGetRequest(AEP.selfInfo)) shouldBe true
    }

    "a polled call with a _debug tag writes its served line and is not counted" {
        polledCalls.flush(cxt)
        servedLine(user.client.sendGetRequest(APP.uiConfig, mapOf(EP.debug to RLOG.captureLogs.name))) shouldBe true
        polledCalls.flush(cxt).map { it.call } shouldNotContain appConfigCall
    }

    "a failed polled call logs its error as before and is not counted" {
        polledCalls.flush(cxt)
        val handler = user.client.sendGetRequest(APP.uiConfig, mapOf("notAnInput" to "x"))
        handler.rptStatusCode shouldBe EXC.badInput
        requestLines(handler).any { it.startsWith("Error for request $appConfigCall") } shouldBe true
        polledCalls.flush(cxt).map { it.call } shouldNotContain appConfigCall
    }

    "a summary names a single call in the singular" {
        val log = PolledCallLog()
        log.record(cxt, "GET:/kda/health", 4.0)
        val tallies = log.flush(cxt)
        tallies.single().logLine(60_000) shouldBe "GET:/kda/health polled 1 time in 60 s, average 4 ms."
        log.flush(cxt).shouldBeEmpty()
    }
})
