package com.dynamicruntime.common.mail

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.sql.SecretsUtil
import com.dynamicruntime.common.startup.ServiceInitializer
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
     * loaded; the recent-emails endpoint is available (so a dev UI or test can read back a code). Defaults to
     * the instance's `inMemoryOnly`, so local/in-memory runs simulate while a real deployment sends for real.
     */
    const val useSimulatedEmail = "useSimulatedEmail"
}

/** A "sent" (or simulated) email, retained briefly so tests and troubleshooting can inspect what went out. */
class SentEmail(
    val id: String,
    val to: String,
    val from: String,
    val subject: String,
    val text: String,
    /** True when no API key was configured, so the mail was recorded but not actually transmitted. */
    val simulated: Boolean,
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

    /** Whether email is simulated (no transmit, no API key, recent-emails endpoint enabled). See [MAIL.useSimulatedEmail]. */
    var useSimulatedEmail: Boolean = false
        private set

    private val httpClient: HttpClient by lazy { HttpClient.newHttpClient() }

    private val mailId = AtomicInteger(1)

    // Most-recent-first, bounded ring of sent/simulated mail, for tests and troubleshooting.
    private val recent = ArrayDeque<SentEmail>()

    override fun onCreate(cxt: KdrCxt) {
        mailgunUri = (cxt.instanceConfig.get(MAIL.mailgunUri) as? String)
            ?: "https://api.mailgun.net/v3/mg.dynamicruntime.org/messages"
        fromAddressForApp = (cxt.instanceConfig.get(MAIL.appFromAddress) as? String)
            ?: "kdr-automail@mg.dynamicruntime.org"
        // Simulate by default when the instance is in-memory (local/dev/tests); a real deployment overrides it.
        useSimulatedEmail = (cxt.instanceConfig.get(MAIL.useSimulatedEmail) as? Boolean)
            ?: ((cxt.instanceConfig.get(ACFG.inMemoryOnly) as? Boolean) ?: true)
        // Only load the (secret) API key when we actually transmit; simulated mail never needs it.
        apiKey = if (useSimulatedEmail) {
            null
        } else {
            val secretName = (cxt.instanceConfig.get(MAIL.mailgunApiKeySecretKey) as? String)
                ?: MAIL.defaultMailgunApiKeySecret
            SecretsUtil.getSecret(secretName)
        }
    }

    /**
     * Sends [text] to [to] with [subject]. Returns the [SentEmail] record (also retained in
     * [recentSentEmails]). With no configured API key, the "send" is simulated (recorded, not transmitted).
     */
    fun sendEmail(cxt: KdrCxt, to: String, subject: String, text: String, from: String = fromAddressForApp): SentEmail {
        val key = apiKey
        val sent = if (!useSimulatedEmail && key != null) {
            transmit(cxt, to, from, subject, text, key)
            SentEmail(mailId.getAndIncrement().toString(), to, from, subject, text, simulated = false)
        } else {
            LogMail.debug(cxt) { "Simulated email to $to: '$subject'." }
            SentEmail(mailId.getAndIncrement().toString(), to, from, subject, text, simulated = true)
        }
        synchronized(recent) {
            recent.addFirst(sent)
            while (recent.size > maxRetained) recent.removeLast()
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
        val request = HttpRequest.newBuilder(URI.create(mailgunUri))
            .header("Authorization", "Basic $basic")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw KdrException("Could not send email to $to.", e, EXC.internalError, SRC.network, ACT.io)
        }
        if (response.statusCode() != 200) {
            val msg = if (response.statusCode() == 401) "Mail request could not authenticate."
            else "Mailgun rejected the email to $to (status ${response.statusCode()})."
            throw KdrException(msg, null, EXC.internalError, SRC.network, ACT.io)
        }
        LogMail.debug(cxt) { "Sent email to $to via Mailgun." }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "MailService"

        /** How many recent emails to retain for inspection. */
        const val maxRetained = 200

        fun get(cxt: KdrCxt): MailService? = cxt.instanceConfig.get(serviceName) as? MailService
    }
}
