package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.test.TVB
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Two endpoints on one path, differing only by verb (issue #335).
 *
 * `KdrEndpoint.collationKey` is `path:method` and the endpoint store is keyed by it, so dispatch always found
 * the right endpoint. What did not was the dispatcher's memo of **compiled input and output types**, which was
 * keyed by path alone -- so whichever verb was called first compiled its schemas and the other was then
 * validated against them. It went unnoticed because nothing shared a URL until this issue.
 *
 * These assertions fail loudly if that memo is ever keyed by path again: the two verbs take disjoint input
 * fields and answer with different output types, and endpoint input is closed to undeclared properties.
 */
class SamePathVerbTest : StringSpec({

    fun client() = TestHttpClient(Startup.mkTestBootCxt("samePathVerb", "samePathVerbTest").instanceConfig)

    "each verb on a shared path validates against its own input and output schema" {
        val c = client()

        // Deliberately DELETE first: the bug was order-dependent -- whichever verb compiled first won -- so
        // exercising the second-registered one ahead of the first is what makes this a real check.
        val deleted = c.sendJsonDeleteRequest(TVB.path, mapOf(TVB.deleteOnly to "d1"))[EP.results].toJsonMapOrEmpty()
        deleted[TVB.verb] shouldBe HttpMethod.DELETE.name
        deleted[TVB.deleteOnly] shouldBe "d1"

        val got = c.sendJsonGetRequest(TVB.path, mapOf(TVB.getOnly to "g1"))[EP.results].toJsonMapOrEmpty()
        got[TVB.verb] shouldBe HttpMethod.GET.name
        got[TVB.getOnly] shouldBe "g1"
    }

    "neither verb accepts the other's field, which is what proves the schemas did not collide" {
        val c = client()

        // Each field is required by its own verb and undeclared on the other, so a collision shows up twice
        // over: the wrong field is refused, and the right one goes missing.
        c.sendGetRequest(TVB.path, mapOf(TVB.deleteOnly to "d1")).rptStatusCode shouldBe EXC.badInput
        c.sendDeleteRequest(TVB.path, mapOf(TVB.getOnly to "g1")).rptStatusCode shouldBe EXC.badInput
    }
})
