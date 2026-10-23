package com.dynamicruntime.common.mail

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sending a mail now or in the background (issue #1299), with a stand-in send: no provider, no key, nothing leaves the
 * process. In the background the call returns before the send runs, the send runs on a context of its own, and its
 * failure does not reach the caller.
 */
class MailDeliverTest : StringSpec({
    val mail = MailService()
    val cxt = KdrCxt.mkSimpleCxt("deliver").also { it.traceId = "trace-1299" }

    "now, the send runs on the caller's context, and its failure is the caller's" {
        var ranOn: KdrCxt? = null
        mail.deliver(cxt, "a@example.com", inBackground = false) { ranOn = it }
        ranOn shouldBe cxt
        shouldThrow<IllegalStateException> {
            mail.deliver(cxt, "a@example.com", inBackground = false) { throw IllegalStateException("provider down") }
        }
    }

    "in the background, the call returns first, on a context of its own with the trace id, and a failure is logged" {
        val release = CountDownLatch(1)
        val ran = CountDownLatch(1)
        var ranOn: KdrCxt? = null
        // The send waits until the caller has returned, so it can only pass if the call did not wait on it.
        mail.deliver(cxt, "b@example.com", inBackground = true) {
            release.await(5, TimeUnit.SECONDS)
            ranOn = it
            ran.countDown()
            throw IllegalStateException("provider down")
        }
        release.countDown()
        ran.await(5, TimeUnit.SECONDS) shouldBe true
        ranOn shouldNotBe cxt
        ranOn?.traceId shouldBe "trace-1299"
    }
})
