package com.dynamicruntime.kdn

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.app.DBGK
import com.dynamicruntime.common.app.DBGT
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.filestore.FSDBG
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.logging.RLOG
import com.dynamicruntime.common.sql.SQLDBG
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.webasset.ClientWebAssetService
import com.dynamicruntime.common.webasset.WAS
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch

/**
 * The `_debug` words declared once (issue #1334): the app config lists them to a caller they answer, `logSql` adds a log
 * line per statement for one request, and `logFileStore` one per file store call, with its time (issue #1650). The
 * declaration rules themselves are `KdrCxtTest`'s.
 */
class DebugTagsTest : StringSpec({

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
})
