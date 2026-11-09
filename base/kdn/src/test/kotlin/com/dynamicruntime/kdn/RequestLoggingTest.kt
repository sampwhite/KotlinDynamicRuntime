package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.app.DBGK
import com.dynamicruntime.common.app.DBGT
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.filestore.FSDBG
import com.dynamicruntime.common.http.request.PolledCallLog
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.logging.RLOG
import com.dynamicruntime.common.sql.SQLDBG
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.webasset.ClientWebAssetService
import com.dynamicruntime.common.webasset.WAS
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

/**
 * What a request leaves in the log and what a caller may ask of it (issue #1739 gathered these): the `_debug` words
 * (issue #1173 and before), a request's captured log lines, the polled endpoints' aggregated log (issue #1395), and the
 * query and body a real request writes, its sensitive fields redacted (issue #1168). Each part below is one of the specs
 * that used to be a file of its own, kept whole.
 */
class RequestLoggingTest : StringSpec({
    include(debugTagsTests)
    include(captureLogsTests)
    include(polledCallLogTests)
    include(requestLogSummaryTests)
})

/**
 * The `_debug` words declared once (issue #1334): the app config lists them to a caller they answer, `logSql` adds a log
 * line per statement for one request, and `logFileStore` one per file store call, with its time (issue #1650). The
 * declaration rules themselves are `KdrCxtTest`'s.
 */
private val debugTagsTests = stringSpec {

    val cxt = TestInstances.default("debugTags")
    val admin = TestUser.createFullAdmin(cxt, "debugtags-admin@example.com")
    val target = TestUser.create(cxt, "debugtags-target@example.com", userClient = CL.hub)

    fun debugTagsFor(client: TestHttpClient): List<Map<String, Any?>> =
        client.sendJsonGetRequest(APP.uiConfig)[EP.results].toJsonMapOrEmpty()[UIC.state].toJsonMapOrEmpty()[APP.debugTags]
            .toJsonListOfMaps()

    "the app config lists the declared words, with what they do, to a caller they answer" {
        val listed = debugTagsFor(TestHttpClient(cxt.instanceConfig).alwaysWithTestAccess())
        listed.map { it[DBGT.name] } shouldContainAll listOf(
            RLOG.captureLogs.name, SQLDBG.logSql.name, FSDBG.logFileStore.name, RequestHandler.explainError.name,
        )
        for (word in listOf(SQLDBG.logSql, FSDBG.logFileStore)) {
            val entry = listed.single { it[DBGT.name] == word.name }
            entry[DBGT.kind] shouldBe DBGK.log
            entry[DBGT.description].toString() shouldContain "captureLogs"
        }
    }

    "and to nobody else" {
        debugTagsFor(TestHttpClient(cxt.instanceConfig)).shouldBeEmpty()
    }

    /** What [admin]'s rename of the target logged under the `sql` topic, asking for [debug]. */
    fun sqlLinesOfRename(debug: String): List<String> {
        val handler = admin.client.sendEditRequest(
            ADEP.userSetName, mapOf(EP.debug to debug), mapOf(ADF.userId to target.userId, ADF.name to "Renamed"), HttpMethod.POST,
        )
        handler.rptResponseData?.jsonMap().orEmpty()[EP.status] shouldBe null
        return handler.logCapture.shouldNotBeNull().lines().filter { it[RLOG.topic] == "sql" }.map { it[RLOG.message].toString() }
    }

    "logSql logs each statement the request runs, for a caller the fence admits" {
        val lines = admin.withTestAccess { sqlLinesOfRename(SQLDBG.logSql.name) }
        lines.shouldNotBeEmpty()
        lines.any { it.startsWith("SQL ") && it.contains("update", ignoreCase = true) } shouldBe true
    }

    "and nothing for anyone else" {
        sqlLinesOfRename(SQLDBG.logSql.name).shouldBeEmpty()
    }

    /** What [admin]'s listing of the `hub` client's web assets logged under the `fileStore` topic, asking for [debug]. */
    fun fileStoreLinesOfWebAssets(debug: String): List<String> {
        val handler = admin.client.sendGetRequest(UADEP.clientWeb, mapOf(EP.debug to debug, WAS.client to CL.hub))
        handler.rptResponseData?.jsonMap().orEmpty()[EP.status] shouldBe null
        return handler.logCapture.shouldNotBeNull().lines().filter { it[RLOG.topic] == "fileStore" }.map { it[RLOG.message].toString() }
    }

    "logFileStore logs each file store call the request makes, with what it found and how long it took" {
        val lines = admin.withTestAccess { fileStoreLinesOfWebAssets(FSDBG.logFileStore.name) }
        // The listing reads each slot's metadata from the store itself, not from a memo: one call apiece.
        lines.size shouldBe ClientWebAssetService.slotDefs.size
        for (def in ClientWebAssetService.slotDefs) {
            val key = ClientWebAssetService.keyOf(CL.hub, def.slot)
            lines.single { it.contains("$key:") } shouldMatch
                Regex("""File store \(memory\) meta ${ClientWebAssetService.bucket}/$key: (absent|found, \d+ bytes), \d+\.\d ms""")
        }
    }

    "and nothing for a caller the fence does not admit" {
        fileStoreLinesOfWebAssets(FSDBG.logFileStore.name).shouldBeEmpty()
    }

    "with captureLogs the lines come back in the response, from a node logging at info" {
        val env = admin.withTestAccess {
            admin.client.sendEditRequest(
                ADEP.userSetName, mapOf(EP.debug to "${SQLDBG.logSql.name},${RLOG.captureLogs.name}"),
                mapOf(ADF.userId to target.userId, ADF.name to "Again"), HttpMethod.POST,
            ).rptResponseData?.jsonMap().orEmpty()
        }
        val logs = env[EP.meta].toJsonMapOrEmpty()[RLOG.logs].toJsonListOfMaps()
        logs.map { it[RLOG.topic] } shouldContain "sql"
        logs.first { it[RLOG.topic] == "sql" }[RLOG.level] shouldBe "debug"
    }
}

/**
 * `_debug=captureLogs` (issue #1173): a request's log lines, returned under `_meta.logs` on a success and on an error,
 * to a caller the diagnostic fence admits, and ignored for anyone else. The capture itself -- below the configured
 * level, sub contexts, the cap -- is `RequestLogCaptureTest`'s.
 */
private val captureLogsTests = stringSpec {

    val cxt = TestInstances.default("captureLogs")
    val admin = TestUser.createFullAdmin(cxt, "capture-admin@example.com")
    val target = TestUser.create(cxt, "capture-target@example.com", userClient = CL.hub)
    val capture = mapOf(EP.debug to RLOG.captureLogs.name)

    /** Renames [userId] as [admin], asking for the request's log lines; the whole envelope, a success's or an error's. */
    fun rename(userId: Long): Map<String, Any?> =
        admin.client.sendEditRequest(ADEP.userSetName, capture, mapOf(ADF.userId to userId, ADF.name to "Renamed"), HttpMethod.POST)
            .rptResponseData?.jsonMap().orEmpty()

    fun logsOf(env: Map<String, Any?>): List<Map<String, Any?>> = env[EP.meta].toJsonMapOrEmpty()[RLOG.logs].toJsonListOfMaps()

    "a success carries what the request logged" {
        val env = admin.withTestAccess { rename(target.userId) }
        env[EP.status].shouldBeNull()
        logsOf(env).map { it[RLOG.message] } shouldContain "Admin ${admin.userId} set user ${target.userId} name/isEntity=false."
    }

    "an error carries the lines that led to it, the error's own included" {
        val env = admin.withTestAccess { rename(-5) }
        env[EP.status] shouldBe EXC.notFound
        val error = logsOf(env).last()
        error[RLOG.level] shouldBe "error"
        error[RLOG.topic] shouldBe "request"
        error[RLOG.errorMessage] shouldBe "No user with id -5."
    }

    "without the diagnostic fence the word is ignored" {
        val success = rename(target.userId)
        success[EP.meta].shouldBeNull()
        val error = rename(-5)
        error[EP.status] shouldBe EXC.notFound
        error[EP.meta].shouldBeNull()
    }

    "the test client's own capture stays out of the response and goes into a failure message" {
        val handler = admin.client.sendEditRequest(
            ADEP.userSetName, null, mapOf(ADF.userId to target.userId, ADF.name to "Again"), HttpMethod.POST,
        )
        handler.rptResponseData?.jsonMap().orEmpty()[EP.meta].shouldBeNull()
        handler.logCapture.shouldNotBeNull().text() shouldContain "set user ${target.userId}"

        val failure = shouldThrow<AssertionError> { admin.postData(ADEP.userSetName, mapOf(ADF.userId to -5, ADF.name to "x")) }
        failure.message.shouldNotBeNull() shouldContain "What the request logged:"
        failure.message.shouldNotBeNull() shouldContain "No user with id -5."
    }
}

/**
 * A polled endpoint's request log, aggregated (issue #1395): a successful call writes no line saying it was served
 * and is counted instead, a summary per endpoint called in a window gives the count and average duration, and a
 * failed call, or one carrying a `_debug` tag, logs as any other does.
 */
private val polledCallLogTests = stringSpec {
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
}

/**
 * What the request log writes for a real request (issue #1168): the query and the body through `RequestArgsLog`, with
 * the endpoint's `g-sensitive` fields redacted.
 */
private val requestLogSummaryTests = stringSpec {
    val cxt = TestInstances.default("requestLogSummary1168")

    "a body is logged with its secrets redacted and its addresses masked" {
        val handler = TestHttpClient(cxt.instanceConfig).sendEditRequest(
            AEP.loginByPassword, null, mapOf(AFLD.loginId to "pat@acme.test", AFLD.password to "hunter2"), HttpMethod.POST,
        )
        // Declared by the endpoint, not only caught by its name.
        handler.declaredSensitive shouldBe setOf(AFLD.password)
        val summary = handler.logRequestSummary()
        summary shouldContain "POST:/kda${AEP.loginByPassword}"
        summary shouldContain "loginId=p***@acme.test"
        summary shouldContain "password=<redacted>"
        summary shouldNotContain "hunter2"
        summary shouldNotContain "pat@acme.test"
    }

    "a query is logged the same way" {
        val summary = TestHttpClient(cxt.instanceConfig).sendGetRequest("/health", mapOf("note" to "pat@acme.test")).logRequestSummary()
        summary shouldContain "query={note=p***@acme.test}"
        summary shouldNotContain "pat@acme.test"
    }
}
