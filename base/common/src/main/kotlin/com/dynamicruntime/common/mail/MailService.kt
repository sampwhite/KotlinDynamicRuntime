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
import com.dynamicruntime.common.user.PublicSignIn
import com.dynamicruntime.common.user.PublicTestDeployment
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
    /** Mailgun messages endpoint URI. Overrides the one derived from `KDR_MAIL_DOMAIN` (issue #1250). */
    const val mailgunUri = "mailgunUri"

    /** From-address for application-generated mail. Overrides the one derived from `KDR_MAIL_DOMAIN` (issue #1250). */
    const val appFromAddress = "appFromAddress"

    /**
     * The from-address of mail no domain was configured for -- only ever simulated, since mail that can be sent
     * needs a domain (issue #1250). Under `.invalid` (RFC 2606), so it can never be a real address.
     */
    const val unsentFromAddress = "kdr-automail@mail.invalid"

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
     * loaded; the captured mail is read back through the `forTestingOnly` `/fixture/simulatedEmails` endpoint (so a
     * test or the local frontend can pick up a code). Defaults to the instance's `isTestInstance`, and is
     * refused on a non-test instance (there would be no way to read the captured mail); a real deployment sends
     * for real. On a public test deployment it is the env-authed caller's mail that is simulated: a caller without
     * env auth is mailed as `KDR_PUBLIC_SIGN_IN` says (issue #1150, `MailService.deliveryFor`).
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
    /** Env var naming the domain mail is sent from (issue #1250); see [MailSending]. */
    val mailDomain = EnvVarDef(
        "KDR_MAIL_DOMAIN", group = ENVGRP.application, defaultDoc = "unset (required when mail can be sent)",
        description = "The domain mail is sent from, through Mailgun -- e.g. `mg.dynamicruntime.org` (issue #1250). The " +
            "endpoint (`https://api.mailgun.net/v3/<domain>/messages`) and the from-address (`kdr-automail@<domain>`) " +
            "are derived from it; the config keys `mailgunUri` and `appFromAddress` override either. Required when " +
            "the node loads a Mailgun key -- whenever something may be sent for real -- and the boot is refused " +
            "without it. With no key loaded, as on every test and local run, nothing is sent and it is not needed.",
    )

    /** Env var that defaults [MAIL.transmitToAdminDomain] when the config option is unset. */
    val transmitToAdminDomain = EnvVarDef(
        "KDR_MAIL_TRANSMIT_ADMIN_DOMAIN", group = ENVGRP.application, defaultDoc = "false",
        description = "On a test instance that simulates email, also transmit mail addressed to the admin " +
            "domain (KDR_ADMIN_EMAIL_DOMAIN or a subdomain) for real, through the provider, when its API key " +
            "is configured -- the mail is still captured for read-back. For trying real invitations and codes " +
            "locally. Off by default; has no effect on a non-simulating instance, which transmits everything.",
    )
}

/**
 * What [MailService.deliveryFor] decides for one mail: whether it is [capture]d for read-back, and whether it is to be
 * [transmit]ted -- which happens only when the provider's key is configured.
 */
data class MailDelivery(val capture: Boolean, val transmit: Boolean)

/** A "sent" (or simulated) email, retained briefly so tests and troubleshooting can inspect what went out. */
class SentEmail(
    val id: String,
    val to: String,
    val from: String,
    val subject: String,
    val text: String,
    /** True when the mail was recorded in the simulated sink (a simulating instance). */
    val simulated: Boolean,
    /** The `text/html` part, when the mail carried one (issue #773); the mails from `MailCopy` always do. */
    val html: String? = null,
    /**
     * True when the mail went out through the provider. The two are not exclusive since [MAIL.transmitToAdminDomain]:
     * a simulating instance may both capture a mail and transmit it.
     */
    val transmitted: Boolean = !simulated,
)

/**
 * Where mail is sent through and whom it is from (issue #1250): [mailgunUri] is null when nothing can be sent.
 */
class MailSending(val mailgunUri: String?, val fromAddress: String)

/**
 * Where mail goes, for a node whose Mailgun key is loaded or not ([keyLoaded]) -- the one decision behind
 * [MAILENV.mailDomain]. The config keys [MAIL.mailgunUri] and [MAIL.appFromAddress] win; otherwise both derive from
 * the mail domain. There is no built-in domain: a deployment sending as one it does not own would be refused by the
 * provider at the first sign-in, so a node that **can** send -- one whose key is loaded -- without a way to name its
 * endpoint and from-address is refused at the boot instead. One that cannot send needs nothing, and its simulated
 * mail is from [MAIL.unsentFromAddress].
 */
fun mailSendingFor(config: KdrInstanceConfig, keyLoaded: Boolean): MailSending {
    val domain = config.getEnvVar(MAILENV.mailDomain)?.trim()?.ifEmpty { null }
    val uri = (config.get(MAIL.mailgunUri) as? String) ?: domain?.let { "https://api.mailgun.net/v3/$it/messages" }
    val from = (config.get(MAIL.appFromAddress) as? String) ?: domain?.let { "kdr-automail@$it" }
    if (keyLoaded && (uri == null || from == null)) {
        throw KdrException(
            "Refusing to start: this node can send mail (its Mailgun key is loaded) but does not say from where. Set " +
                "${MAILENV.mailDomain} to the sending domain -- the one the key is for -- or set both " +
                "'${MAIL.mailgunUri}' and '${MAIL.appFromAddress}' in configuration.",
        )
    }
    return MailSending(uri, from ?: MAIL.unsentFromAddress)
}

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
 *
 * ### Deliverability is the deployment's
 *
 * Whether a mail this sends reaches an inbox rather than a spam folder is decided by the **sending domain's**
 * DNS, not by anything here: SPF and DKIM records that authorize the provider to send for the domain the
 * from-address ([MAIL.appFromAddress]) is on, and a DMARC policy over both. The provider's dashboard shows
 * what a domain still lacks. Nothing in the message shape helps until those are in place; once they are, the
 * text-and-HTML pair `MailCopy` produces (issue #773) is the ordinary, well-received form.
 */
class MailService : ServiceInitializer {
    override val serviceName: String = MailService.serviceName

    /** Where mail is sent through; null when nothing can be sent ([mailSendingFor]). */
    var mailgunUri: String? = null
        private set
    lateinit var fromAddressForApp: String
    private var apiKey: String? = null

    /** Whether email is simulated (captured, recent-emails endpoint enabled). See [MAIL.useSimulatedEmail]. */
    var useSimulatedEmail: Boolean = false
        private set

    /**
     * Whether the caller of [cxt] may read captured mail back (issue #1149) -- what the auth and profile configs'
     * `codeReadBack` feature tells the code pre-fill. Mail is captured, and the caller has test access, without
     * which the read-back fixture does not exist for them: the pre-fill must not ask for what it cannot get.
     */
    fun readBackOffered(cxt: KdrCxt): Boolean = useSimulatedEmail && cxt.hasTestAccess

    /** Whether a simulating instance also transmits mail to the admin domain; see [MAIL.transmitToAdminDomain]. */
    var transmitToAdminDomain: Boolean = false
        private set

    private val mailId = AtomicInteger(1)

    // Most-recent-first, bounded ring of sent/simulated mail, for tests and troubleshooting.
    private val recent = ArrayDeque<SentEmail>()

    override fun onCreate(cxt: KdrCxt) {
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
        // simulating one only when the admin-domain opt-in is on -- or, on a public test deployment, when a caller
        // without env auth may be mailed for real (issue #1150). Simulated mail otherwise never needs it.
        val publicMailsForReal = PublicTestDeployment.isOn(cxt.instanceConfig) &&
            (PublicTestDeployment.publicSignIn(cxt.instanceConfig) == PublicSignIn.realMail ||
                PublicTestDeployment.envAuthFromAdminLogin(cxt.instanceConfig))
        apiKey = if (useSimulatedEmail && !transmitToAdminDomain && !publicMailsForReal) {
            null
        } else {
            val secretName = (cxt.instanceConfig.get(MAIL.mailgunApiKeySecretKey) as? String)
                ?: MAIL.defaultMailgunApiKeySecret
            SecretsUtil.getSecret(secretName)
        }
        // Where mail goes and whom it is from (issue #1250): decided once the key is known, since a loaded key is
        // what makes a missing domain a refusal rather than nothing.
        val sending = mailSendingFor(cxt.instanceConfig, keyLoaded = apiKey != null)
        mailgunUri = sending.mailgunUri
        fromAddressForApp = sending.fromAddress
    }

    /**
     * What becomes of a mail to [to] that the caller of [cxt] causes: **captured** (held for read-back through the
     * test-only fixture), **transmitted** through the provider, or both -- before the provider's key is consulted,
     * so a test can ask without anything being sent.
     *
     * - A real instance transmits everything and captures nothing.
     * - A simulating one captures everything, and also transmits a mail to the admin domain under the admin-domain
     *   opt-in ([MAIL.transmitToAdminDomain]).
     * - On a **public test deployment** (issue #1150) mail follows the **caller** -- whose request caused it, not who
     *   it is addressed to. An env-authed caller's is as on a simulating instance. A caller without env auth is
     *   mailed for real when the deployment lets the public sign in ([PublicSignIn.realMail]), or -- under env auth
     *   from an administrator's login -- at an admin-domain address, signing in to become env-authed; and that mail
     *   is **never captured**, so nobody, an env-authed caller included, can read their code back. Anything else
     *   such a caller causes -- the sign-in flows refuse them first, so nothing should -- is captured and not sent.
     */
    fun deliveryFor(cxt: KdrCxt, to: String): MailDelivery {
        if (!useSimulatedEmail) return MailDelivery(capture = false, transmit = true)
        val config = cxt.instanceConfig
        val toAdminDomain = AddressRules.isAdminDomain(cxt, to)
        if (!PublicTestDeployment.isOn(config) || cxt.isEnvAuthEffective) {
            return MailDelivery(capture = true, transmit = transmitToAdminDomain && toAdminDomain)
        }
        val forReal = PublicTestDeployment.publicSignIn(config) == PublicSignIn.realMail ||
            (PublicTestDeployment.envAuthFromAdminLogin(config) && toAdminDomain)
        return MailDelivery(capture = !forReal, transmit = forReal)
    }

    /**
     * Sends [text] -- and [html], the `text/html` part of the same message, when given -- to [to] with
     * [subject]. Returns the [SentEmail] record (also retained in [recentSentEmails]). On a simulating
     * instance the "send" is recorded rather than transmitted -- unless the admin-domain opt-in
     * ([MAIL.transmitToAdminDomain]) applies to [to], in which case it is both.
     *
     * The two parts are the message as given: the provider takes both and generates neither, so a mail with
     * no [html] is a text-only mail, not one the provider dresses up. The auth mails come through
     * [sendFragmentMail], which supplies both from one piece of copy.
     */
    fun sendEmail(cxt: KdrCxt, to: String, subject: String, text: String, html: String? = null, from: String = fromAddressForApp): SentEmail {
        val delivery = deliveryFor(cxt, to)
        val key = apiKey
        val transmitted = delivery.transmit && key != null
        if (transmitted) transmit(cxt, to, from, subject, text, html, key)
        if (delivery.capture) LogMail.debug(cxt) { "Simulated email to $to: '$subject'" + (if (transmitted) " (also transmitted)." else ".") }
        val sent = SentEmail(
            mailId.getAndIncrement().toString(), to, from, subject, text, simulated = delivery.capture, html = html, transmitted = transmitted,
        )
        // Retain only a captured mail -- the kept copy exists solely to be read back through the test-only endpoint. A
        // real transmission is never held in memory (issue #158), nor one a public caller was sent for real (#1150).
        if (delivery.capture) {
            synchronized(recent) {
                recent.addFirst(sent)
                while (recent.size > maxRetained) recent.removeLast()
            }
        }
        return sent
    }

    /**
     * Sends the mail [mail] of the `mail` fragment file to [to], rendered by [MailCopy] with [params] as the
     * [client] the mail is about reads it (issue #773): the subject, the text part, and the HTML part all come
     * from that one piece of copy. What the auth flows call instead of composing a body in code.
     */
    fun sendFragmentMail(cxt: KdrCxt, to: String, client: String?, mail: String, params: Map<String, Any?>): SentEmail {
        val rendered = MailCopy.render(cxt, client, mail, params)
        return sendEmail(cxt, to, rendered.subject, rendered.text, rendered.html)
    }

    /** Recently sent (or simulated) emails, with the most recent first. For tests and troubleshooting. */
    fun recentSentEmails(): List<SentEmail> = synchronized(recent) { recent.toList() }

    /** The most recent email sent to [to], or null. */
    fun lastEmailTo(to: String): SentEmail? = synchronized(recent) { recent.firstOrNull { it.to == to } }

    private fun transmit(cxt: KdrCxt, to: String, from: String, subject: String, text: String, html: String?, key: String) {
        // Mailgun takes the two parts as two fields of one message and generates neither.
        val fields = listOf("from" to from, "to" to to, "subject" to subject, "text" to text) +
            (if (html != null) listOf("html" to html) else emptyList())
        val body = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
        val basic = Base64.getEncoder().encodeToString("api:$key".toByteArray(StandardCharsets.UTF_8))
        // The one outbound client (issue #420). The content type rides on the body, so only the auth header is
        // passed here. The client's own connect/timeout error names the endpoint, not the recipient, so keep
        // "$to" by re-wrapping -- the context an operator wants when a send fails.
        // Set whenever a key is: the boot refuses a key without a way to send (mailSendingFor).
        val uri = mailgunUri ?: throw KdrException("No mail endpoint is configured, so the email to $to cannot be sent.")
        val response = try {
            OutboundHttpService.get(cxt).fast(cxt).send(
                "POST", uri,
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
