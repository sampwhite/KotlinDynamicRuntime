package com.dynamicruntime.common.mail

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Where mail is sent from (issue #1250): `KDR_MAIL_DOMAIN`, the config overrides, and the refusal for a node that
 * can send but does not say from where. Decided by a pure function, so no key is ever loaded to test it.
 */
class MailSendingTest : StringSpec({

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
})
