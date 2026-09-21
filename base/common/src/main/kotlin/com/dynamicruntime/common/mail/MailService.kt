package com.dynamicruntime.common.mail

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.http.client.OutboundHttpService
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.sql.SecretsUtil
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.user.AddressRules
import com.dynamicruntime.common.util.toOptBool
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Topic logger for the mail subsystem (placed beside the code that owns the `"mail"` topic). */
object LogMail : KdrLogger("mail")

/** Mail configuration keys (instance config). Each names the value it holds. */
@Suppress("ConstPropertyName")
object MAIL {
    /** Mailgun messages endpoint URI. */
    const val mailgunUri = "mailgunUri"

    /** From-address for application-generated mail. */
    const val appFromAddress = "appFromAddress"

    /**
     * The **indirection** key for the Mailgun API key: its value is the name of the property in
     * `private/secrets.properties` that holds the actual key (so the key itself never lives in config).
     * Named to make clear it is the Mailgun API key; other providers will get their own such key later.
     */
    const val mailgunApiKeySecretKey = "mailgunApiKeySecretKey"

    /** Default secret-property name the [mailgunApiKeySecretKey] indirection points at. */
    const val defaultMailgunApiKeySecret = "mailgunApiKey"

    /**
     * When true, email is **simulated** -- recorded in-memory and not transmitted -- and no Mailgun API key is
     * loaded; the captured mail is read back through the `forTestingOnly` `/test/simulatedEmails` endpoint (so a
     * test or the local frontend can pick up a code). Defaults to the instance's `isTestInstance`, and is
     * refused on a non-test instance (there would be no way to read the captured mail); a real deployment sends
     * for real.
     */
    const val useSimulatedEmail = "useSimulatedEmail"

    /**
     * When true on a **simulating** instance, mail to an address on the deployment's admin domain
     * (`KDR_ADMIN_EMAIL_DOMAIN`, or a subdomain of it) is **also transmitted** -- captured in the sink as
     * every simulated mail is, and sent for real through the provider as well, when an API key is configured.
     * For trying the real mails (an invitation, a claim code) from a local instance without giving up the
     * read-back of everything else. Off by default, and deliberately an opt-in rather than implied by the key's
     * presence: a developer whose secrets hold a key must not have unit tests mailing `boss@acme.com` because
     * a test set that domain. Defaults from [MAILENV.transmitToAdminDomain].
     */
    const val transmitToAdminDomain = "transmitToAdminDomain"
}

/** Environment variables of the mail service. */
object MAILENV {
    /** Env var that defaults [MAIL.transmitToAdminDomain] when the config option is unset. */
    val transmitToAdminDomain = EnvVarDef(
        "KDR_MAIL_TRANSMIT_ADMIN_DOMAIN", group = ENVGRP.application, defaultDoc = "false",
        description = "On a test instance that simulates email, also transmit mail addressed to the admin " +
            "domain (KDR_ADMIN_EMAIL_DOMAIN or a subdomain) for real, through the provider, when its API key " +
            "is configured -- the mail is still captured for read-back. For trying real invitations and codes " +
            "locally. Off by default; has no effect on a non-simulating instance, which transmits everything.",
    )
}

/** A "sent" (or simulated) email, retained briefly so tests and troubleshooting can inspect what went out. */
class SentEmail(
    val id: String,
    val to: String,
    val from: String,
    val subject: String,
    val text: String,
    /** True when the mail was recorded in the simulated sink (a simulating instance). */
    val simulated: Boolean,
    /**
     * True when the mail went out through the provider. The two are not exclusive since [MAIL.transmitToAdminDomain]:
     * a simulating instance may both capture a mail and transmit it.
     */
    val transmitted: Boolean = !simulated,
)

/**
 * Sends application email through Mailgun. Ported from dn's `DnMailService`, trimmed to what auth needs.
 *
 * When no API key is configured (the usual case in tests and local dev), sending is **simulated**: the mail
 * is recorded in [recentSentEmails] but not transmitted -- which is exactly what the auth tests rely on to
 * read back a verification code. When a key is present, the message is POSTed to Mailgun (form-encoded, HTTP
 * basic auth `api:<key>`).
 *
 * The API key is obtained by indirection: config [MAIL.mailgunApiKeySecretKey] names a property in
 * `private/secrets.properties` (default `mailgunApiKey`), and [SecretsUtil] reads the actual key from there,
 * so the key is never stored in -- or logged from -- application config. Eventually other providers will be
 * supported alongside Mailgun.
 */
class MailService : ServiceInitializer {
    override val serviceName: String = MailService.serviceName

    lateinit var mailgunUri: String
    lateinit var fromAddressForApp: String
    private var apiKey: String? = null

    /** Whether email is simulated (captured, recent-emails endpoint enabled). See [MAIL.useSimulatedEmail]. */
    var useSimulatedEmail: Boolean = false
        private set

    /** Whether a simulating instance also transmits mail to the admin domain; see [MAIL.transmitToAdminDomain]. */
    var transmitToAdminDomain: Boolean = false
        private set

    private val mailId = AtomicInteger(1)

    // Most-recent-first, bounded ring of sent/simulated mail, for tests and troubleshooting.
    private val recent = ArrayDeque<SentEmail>()

    override fun onCreate(cxt: KdrCxt) {
        mailgunUri = (cxt.instanceConfig.get(MAIL.mailgunUri) as? String)
            ?: "https://api.mailgun.net/v3/mg.dynamicruntime.org/messages"
        fromAddressForApp = (cxt.instanceConfig.get(MAIL.appFromAddress) as? String)
            ?: "kdr-automail@mg.dynamicruntime.org"
        // Two independent axes: `isTestInstance` says the test surface exists; `useSimulatedEmail` says capture
        // instead of transmit. Default the latter to the former -- simulate exactly when the read-back endpoint
        // is present -- but keep it independently settable, so a developer can send *real* mail on a test
        // instance (useSimulatedEmail = false) to exercise actual delivery.
        val testInstance = cxt.instanceConfig.isTestInstance
        useSimulatedEmail = (cxt.instanceConfig.get(MAIL.useSimulatedEmail) as? Boolean) ?: testInstance
        // Simulated mail is captured in memory and read back only through a test-only endpoint, so it requires a
        // test instance. The default can't violate this; only an explicit useSimulatedEmail=true on a non-test
        // instance can -- a misconfiguration we refuse to run with.
        if (useSimulatedEmail && !testInstance) {
            throw KdrException(
                "Refusing to start: useSimulatedEmail is on but this is not a test instance. Simulated mail is " +
                    "captured in memory and read back through a test-only endpoint -- unset useSimulatedEmail, or " +
                    "make this a test instance (${KdrInstanceConfig.testInstanceEnvVar}, inMemoryOnly, or the unit environment).",
            )
        }
        transmitToAdminDomain = (cxt.instanceConfig.get(MAIL.transmitToAdminDomain) as? Boolean)
            ?: (cxt.getEnvVar(MAILENV.transmitToAdminDomain)?.toOptBool() == true)
        // Only load the (secret) API key when something may transmit: always on a real instance, and on a
        // simulating one only when the admin-domain opt-in is on. Simulated mail otherwise never needs it.
        apiKey = if (useSimulatedEmail && !transmitToAdminDomain) {
            null
        } else {
            val secretName = (cxt.instanceConfig.get(MAIL.mailgunApiKeySecretKey) as? String)
                ?: MAIL.defaultMailgunApiKeySecret
            SecretsUtil.getSecret(secretName)
        }
    }

    /** Whether a mail to [to] goes out for real: every mail on a transmitting instance, and on a simulating one only the admin-domain opt-in's. */
    private fun transmits(cxt: KdrCxt, to: String): Boolean =
        apiKey != null && (!useSimulatedEmail || (transmitToAdminDomain && AddressRules.isAdminDomain(cxt, to)))

    /**
     * Sends [text] to [to] with [subject]. Returns the [SentEmail] record (also retained in
     * [recentSentEmails]). On a simulating instance the "send" is recorded rather than transmitted -- unless
     * the admin-domain opt-in ([MAIL.transmitToAdminDomain]) applies to [to], in which case it is both.
     */
    fun sendEmail(cxt: KdrCxt, to: String, subject: String, text: String, from: String = fromAddressForApp): SentEmail {
        val transmitted = transmits(cxt, to)
        if (transmitted) transmit(cxt, to, from, subject, text, apiKey!!)
        if (useSimulatedEmail) LogMail.debug(cxt) { "Simulated email to $to: '$subject'" + (if (transmitted) " (also transmitted)." else ".") }
        val sent = SentEmail(mailId.getAndIncrement().toString(), to, from, subject, text, simulated = useSimulatedEmail, transmitted = transmitted)
        // Retain only on a simulating (test) instance -- the kept copy exists solely to be read back through the
        // test-only endpoint. A real transmission is never held in memory (issue #158).
        if (useSimulatedEmail) {
            synchronized(recent) {
                recent.addFirst(sent)
                while (recent.size > maxRetained) recent.removeLast()
            }
        }
        return sent
    }

    /** Recently sent (or simulated) emails, with the most recent first. For tests and troubleshooting. */
    fun recentSentEmails(): List<SentEmail> = synchronized(recent) { recent.toList() }

    /** The most recent email sent to [to], or null. */
    fun lastEmailTo(to: String): SentEmail? = synchronized(recent) { recent.firstOrNull { it.to == to } }

    private fun transmit(cxt: KdrCxt, to: String, from: String, subject: String, text: String, key: String) {
        val body = listOf("from" to from, "to" to to, "subject" to subject, "text" to text)
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
        val basic = Base64.getEncoder().encodeToString("api:$key".toByteArray(StandardCharsets.UTF_8))
        // The one outbound client (issue #420). The content type rides on the body, so only the auth header is
        // passed here. The client's own connect/timeout error names the endpoint, not the recipient, so keep
        // "$to" by re-wrapping -- the context an operator wants when a send fails.
        val response = try {
            OutboundHttpService.get(cxt).fast(cxt).send(
                "POST", mailgunUri,
                headers = mapOf("Authorization" to "Basic $basic"),
                contentType = "application/x-www-form-urlencoded",
                body = body,
            )
        } catch (e: KdrException) {
            throw KdrException("Could not send email to $to.", e, EXC.internalError, SRC.network, ACT.io)
        }
        if (response.status != 200) {
            val msg = if (response.status == 401) "Mail request could not authenticate."
            else "Mailgun rejected the email to $to (status ${response.status})."
            throw KdrException(msg, null, EXC.internalError, SRC.network, ACT.io)
        }
        LogMail.debug(cxt) { "Sent email to $to via Mailgun." }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "MailService"

        /** How many recent emails to retain for inspection. */
        const val maxRetained = 200

        fun get(cxt: KdrCxt): MailService = cxt.instanceConfig.get(serviceName) as? MailService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
