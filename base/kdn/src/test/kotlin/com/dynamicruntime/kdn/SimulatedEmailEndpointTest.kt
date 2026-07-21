package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MAIL
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.test.TSE
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The relocated test-only `/test/simulatedEmails` endpoint and the two-axis email gating (issue #158): a test
 * instance captures the mail it would send so a caller can read a verification code back, and simulated email
 * is only allowed on a test instance (it is retained in memory purely to be read back through this endpoint).
 */
class SimulatedEmailEndpointTest : StringSpec({

    "a test instance captures sent mail and serves it from /test/simulatedEmails" {
        val cxt = Startup.mkTestBootCxt("simEmails", "simEmailsInst")
        // A unit instance simulates by default, so this is captured rather than transmitted.
        MailService.get(cxt)!!.sendEmail(cxt, "carol@example.com", "Hi", "Your verification code is 424242.")
        val client = TestHttpClient(cxt.instanceConfig)
        val results = client.sendJsonGetRequest(TEP.simulatedEmails).getValue(EP.results)!!.toJsonMap()
        val emails = results.getValue(TSE.emails) as List<*>
        emails.isEmpty() shouldBe false
    }

    "a non-test instance refuses to start when useSimulatedEmail is on" {
        // local + no inMemoryOnly + no env var => not a test instance; simulated email has no read-back path.
        val config = KdrInstanceConfig("mailGuard", ENV.local, ENV.liveSource)
            .apply { put(MAIL.useSimulatedEmail, true) }
        val ex = shouldThrow<KdrException> { MailService().onCreate(KdrCxt.mkSimpleCxt("mailGuard", config)) }
        ex.message.orEmpty() shouldContain "test instance"
    }
})
