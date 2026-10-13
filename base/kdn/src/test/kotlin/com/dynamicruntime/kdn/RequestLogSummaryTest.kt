package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * What the request log writes for a real request (issue #1168): the query and the body through `RequestArgsLog`, with
 * the endpoint's `g-sensitive` fields redacted.
 */
class RequestLogSummaryTest : StringSpec({
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
})
