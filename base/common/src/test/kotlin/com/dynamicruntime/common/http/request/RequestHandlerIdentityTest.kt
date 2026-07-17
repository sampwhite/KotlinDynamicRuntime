package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.endpoint.RID
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Coverage for how a request's client identity (appId / traceId, issue #105) is read: the header is primary,
 * the `_` param is the alternate, and an unsafe value is dropped rather than trusted since it goes into log
 * lines and content lookups.
 */
class RequestHandlerIdentityTest : StringSpec({

    fun handler(headers: Map<String, List<String>> = emptyMap()) =
        RequestHandler("idTest", "GET", "/kda/x", headers, mutableMapOf())

    "appId and traceId come from their headers" {
        val h = handler(
            mapOf(RID.appIdHeader to listOf("kdr.en"), RID.traceIdHeader to listOf("2026071712000012307")),
        )
        h.appId() shouldBe "kdr.en"
        h.traceId() shouldBe "2026071712000012307"
    }

    "the _param alternate is read when the header is absent" {
        val h = handler()
        h.queryParams[RID.appIdParam] = "kdr.fr"
        h.queryParams[RID.traceIdParam] = "abc12307"
        h.appId() shouldBe "kdr.fr"
        h.traceId() shouldBe "abc12307"
    }

    "the header wins over the param" {
        val h = handler(mapOf(RID.appIdHeader to listOf("kdr.en")))
        h.queryParams[RID.appIdParam] = "kdr.fr"
        h.appId() shouldBe "kdr.en"
    }

    "absent everywhere is null" {
        handler().appId() shouldBe null
        handler().traceId() shouldBe null
    }

    "an unsafe id is dropped -- it would otherwise land in a log line verbatim" {
        // Spaces, control characters, and over-length are all rejected (the id degrades correlation, not the
        // request). The safe set is letters/digits plus `. _ : -`, which covers a real appId and trace id.
        handler(mapOf(RID.traceIdHeader to listOf("id with spaces"))).traceId() shouldBe null
        handler(mapOf(RID.traceIdHeader to listOf("line\nbreak"))).traceId() shouldBe null
        handler(mapOf(RID.traceIdHeader to listOf("x".repeat(65)))).traceId() shouldBe null
        // A real trace id and a real appId pass.
        handler(mapOf(RID.traceIdHeader to listOf("2026071712000012307"))).traceId() shouldBe "2026071712000012307"
        handler(mapOf(RID.appIdHeader to listOf("kdr.en-US"))).appId() shouldBe "kdr.en-US"
    }
})
