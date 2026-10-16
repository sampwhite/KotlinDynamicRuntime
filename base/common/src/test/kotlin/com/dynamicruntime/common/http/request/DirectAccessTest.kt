package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Whether a request reached the node directly (issue #1255), as pure logic: the setting, and the one test asked of a
 * request's proxy headers and source. Through the dispatcher in `DirectOperatorReadsTest`.
 */
class DirectAccessTest : StringSpec({

    fun config(value: String?): KdrInstanceConfig = KdrInstanceConfig("direct1255", ENV.unit, ENV.liveSource).also {
        if (value != null) it.put(DirectAccess.directAccessEnvVar.name, value)
    }

    "loopback is the default, and a value naming no mode refuses the start" {
        DirectAccess.mode(config(null)) shouldBe DirectAccessMode.loopback
        DirectAccess.mode(config(" ")) shouldBe DirectAccessMode.loopback
        DirectAccess.mode(config("network")) shouldBe DirectAccessMode.network
        DirectAccess.mode(config("off")) shouldBe DirectAccessMode.off
        // A misspelled `off` must not quietly become the default, which grants more.
        shouldThrow<KdrException> { DirectAccess.mode(config("of")) }.message shouldContain "KDR_DIRECT_ACCESS"
    }

    "either proxy header makes a request not direct, in every mode" {
        for (mode in DirectAccessMode.entries) {
            DirectAccess.isDirect(mode, "203.0.113.7", null, "127.0.0.1") shouldBe false
            // What Jetty's proxy, and so our edge's, writes on its own.
            DirectAccess.isDirect(mode, null, "for=203.0.113.7;proto=https", "127.0.0.1") shouldBe false
        }
    }

    "each mode decides which sources count" {
        DirectAccess.isDirect(DirectAccessMode.loopback, null, null, "127.0.0.1") shouldBe true
        DirectAccess.isDirect(DirectAccessMode.loopback, null, null, "::1") shouldBe true
        DirectAccess.isDirect(DirectAccessMode.loopback, null, null, "10.0.0.5") shouldBe false
        DirectAccess.isDirect(DirectAccessMode.network, null, null, "10.0.0.5") shouldBe true
        DirectAccess.isDirect(DirectAccessMode.off, null, null, "127.0.0.1") shouldBe false
        // No source address at all -- an in-process request that named none -- is never direct.
        DirectAccess.isDirect(DirectAccessMode.network, null, null, null) shouldBe false
    }
})
