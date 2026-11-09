package com.dynamicruntime.common.mail

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sending mail (issue #1738 gathered these): where it is sent from -- the mail domain, the config overrides, and the
 * refusal for a node that can send but does not say from where (issue #1250) -- and sending now or in the background
 * (issue #1299). Each part below is one of the specs that used to be a file of its own, kept whole. No key is ever
 * loaded, and nothing leaves the process.
 */
class MailTest : StringSpec({
    include(mailSendingTests)
    include(mailDeliverTests)
})

/**
 * Where mail is sent from (issue #1250): `KDR_MAIL_DOMAIN`, the config overrides, and the refusal for a node that
 * can send but does not say from where. Decided by a pure function, so no key is ever loaded to test it.
 */
private val mailSendingTests = stringSpec {

    fun config(vararg settings: Pair<String, Any?>): KdrInstanceConfig =
        KdrInstanceConfig("mail1250", ENV.unit, ENV.liveSource).also { c -> settings.forEach { (k, v) -> c.put(k, v) } }

    "the endpoint and the from-address derive from the mail domain" {
        val sending = mailSendingFor(config(MAILENV.mailDomain.name to "mg.example.org"), keyLoaded = true)
        sending.mailgunUri shouldBe "https://api.mailgun.net/v3/mg.example.org/messages"
        sending.fromAddress shouldBe "kdr-automail@mg.example.org"
    }

    "the config keys override what the domain would give" {
        val sending = mailSendingFor(
            config(
                MAILENV.mailDomain.name to "mg.example.org",
                MAIL.mailgunUri to "https://api.eu.mailgun.net/v3/mg.example.org/messages",
                MAIL.appFromAddress to "hello@example.org",
            ),
            keyLoaded = true,
        )
        sending.mailgunUri shouldBe "https://api.eu.mailgun.net/v3/mg.example.org/messages"
        sending.fromAddress shouldBe "hello@example.org"
        // Both overrides alone are enough, with no domain.
        mailSendingFor(
            config(MAIL.mailgunUri to "https://api.mailgun.net/v3/x/messages", MAIL.appFromAddress to "a@x"),
            keyLoaded = true,
        ).fromAddress shouldBe "a@x"
    }

    "a node that can send but does not say from where is refused, naming the variable" {
        shouldThrow<KdrException> { mailSendingFor(config(), keyLoaded = true) }.message.orEmpty() shouldContain
            MAILENV.mailDomain.name
        // One override is not enough: the other still has nowhere to come from.
        shouldThrow<KdrException> { mailSendingFor(config(MAIL.appFromAddress to "a@x"), keyLoaded = true) }
    }

    "a node that cannot send needs nothing, and is never from a real address" {
        val sending = mailSendingFor(config(), keyLoaded = false)
        sending.mailgunUri shouldBe null
        sending.fromAddress shouldBe MAIL.unsentFromAddress
    }
}

/**
 * Sending a mail now or in the background (issue #1299), with a stand-in send: no provider, no key, nothing leaves the
 * process. In the background the call returns before the send runs, the send runs on a context of its own, and its
 * failure does not reach the caller.
 */
private val mailDeliverTests = stringSpec {
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
}
