package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.logging.RLOG
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `_debug=captureLogs` (issue #1173): a request's log lines, returned under `_meta.logs` on a success and on an error,
 * to a caller the diagnostic fence admits, and ignored for anyone else. The capture itself -- below the configured
 * level, sub contexts, the cap -- is `RequestLogCaptureTest`'s.
 */
class CaptureLogsTest : StringSpec({

    val cxt = TestInstances.default("captureLogs")
    val admin = TestUser.createFullAdmin(cxt, "capture-admin@example.com")
    val target = TestUser.create(cxt, "capture-target@example.com", userClient = CL.hub)
    val capture = mapOf(EP.debug to RLOG.captureLogs)

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
})
