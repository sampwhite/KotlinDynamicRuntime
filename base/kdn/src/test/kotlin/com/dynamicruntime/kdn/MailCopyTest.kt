package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.mail.MailCopy
import com.dynamicruntime.common.mail.MailService
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.test.TSE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.AFLD
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.INVITE
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The auth mails as fragment copy (issue #773): authored in `mail.md`, sent as a text part and an HTML part
 * from that one source, rendered for the client of the user the mail is about, with the params sanitized.
 */
class MailCopyTest : StringSpec({
    // A public URL, so the invitation's link is absolute -- a relative one is not a link anywhere, and the
    // HTML part rightly leaves it as text.
    val cxt = Startup.mkTestBootCxt("mailCopy", "mailCopyTest", mapOf(INVITE.publicUrl.name to "https://mail.test"))
    val mail = MailService.get(cxt)

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp[EP.results]?.toJsonMap()
        ?: throw AssertionError("Expected a success but got ${resp[EP.status]}: ${resp[EP.errorMessage]}")

    fun tokenOf(browser: TestHttpClient): String = results(browser.sendJsonGetRequest(AEP.createToken))[AFLD.formAuthToken] as String

    // A client of its own that rewords the invitation and signs the footer as itself. Written through the
    // config path a client's administrator would use, and reloaded, so this is the overlay as deployed.
    val overlayClient = "mailco"
    val config = gedraConfig(cxt, "${overlayClient}cfg", "${overlayClient}config", overlayClient) {
        defineClient(
            ClientDef(
                clientId = overlayClient, name = "Mail Co", usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        fragmentOverlay(AFRAG.mail) {
            namespace(MCOPY.invitation) {
                key(MCOPY.body, $$"Welcome to Mail Co, ${address}. Accept here: ${url}")
            }
            namespace(MCOPY.common) {
                key(MCOPY.footer, "Sent by the Mail Co desk.")
            }
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", overlayClient).also { it.userId = 9000L }, config)
    GedraConfigReload.reloadClient(cxt, overlayClient)

    "every mail has its subject and body, and the shared footer and style exist" {
        val content = MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, AFRAG.mail, null).shouldNotBeNull().content
        for (m in MCOPY.mails) {
            content[m]?.get(MCOPY.subject).shouldNotBeNull()
            content[m]?.get(MCOPY.body).shouldNotBeNull()
        }
        content[MCOPY.common]?.get(MCOPY.footer).shouldNotBeNull()
        content[MCOPY.common]?.get(MCOPY.htmlStyle).shouldNotBeNull()
    }

    "the code mail goes out as text and HTML, and the text still carries the code where its readers look" {
        val address = "mailcopy-code@example.com"
        TestUser.register(cxt, address, "mailcopycode")
        val browser = TestHttpClient(cxt.instanceConfig)
        browser.sendJsonPostRequest(
            AEP.userSendVerify, mapOf(AFLD.loginId to address, AFLD.formAuthToken to tokenOf(browser)),
        )[EP.status] shouldBe null
        val sent = mail.lastEmailTo(address).shouldNotBeNull()
        // The frontend's dev autofill and AuthFlowTest read the code out of exactly this phrase.
        val code = Regex("verification code is (\\S+?)[.\\s]").find(sent.text).shouldNotBeNull().groupValues[1]
        code.isNotEmpty() shouldBe true
        sent.html.shouldNotBeNull() shouldContain "<p>Your verification code is $code."
        sent.html!! shouldContain "<body style=\""
        // Both parts reach the test-only read-back the frontend uses.
        val emails = results(browser.sendJsonGetRequest(TEP.simulatedEmails, mapOf(TSE.to to address)))[TSE.emails].toJsonListOfMaps()
        emails.first()[TSE.html].toString() shouldContain "<p>Your verification code is"
        // Every mail ends with the shared footer, in both parts.
        sent.text shouldContain "sent automatically"
        sent.html!! shouldContain "sent automatically"
    }

    "the invitation's link is an anchor in the HTML part and a bare URL in the text part" {
        val admin = TestUser.createFullAdmin(cxt, "mailcopy-admin@other.test")
        val address = "mailcopy-invited@other.test"
        admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to address, ADF.client to CL.hub, ADF.persona to PERSONA.admin))
        val sent = mail.lastEmailTo(address).shouldNotBeNull()
        val url = sent.text.substringAfter("sign in: ").takeWhile { !it.isWhitespace() }
        url shouldContain "token="
        sent.text shouldNotContain "]($url)"
        sent.html.shouldNotBeNull() shouldContain "<a href=\"${url.replace("&", "&amp;")}\""
    }

    "a client's overlay rewords its invitation and signs the footer, and another client's stays as shipped" {
        val admin = TestUser.createFullAdmin(cxt, "mailcopy-admin2@other.test")
        val overlaid = "mailcopy-overlaid@other.test"
        admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to overlaid, ADF.client to overlayClient))
        val sent = mail.lastEmailTo(overlaid).shouldNotBeNull()
        sent.text shouldContain "Welcome to Mail Co, $overlaid."
        sent.text shouldContain "Sent by the Mail Co desk."
        sent.text shouldNotContain "sent automatically"
        sent.html.shouldNotBeNull() shouldContain "Sent by the Mail Co desk."
        // The overlay named only the invitation, so the client's other mails keep the shipped wording -- with
        // the client's own footer, since that is shared.
        val rendered = MailCopy.render(cxt, overlayClient, MCOPY.verifyCode, mapOf(MCOPY.codeParam to "123456"))
        rendered.text shouldContain "Your verification code is 123456."
        rendered.text shouldContain "Sent by the Mail Co desk."
        // The overlay is the client's own: a user invited into another client reads the shipped copy, whoever
        // created them.
        val plain = "mailcopy-plain@other.test"
        admin.postData(ADEP.userCreate, mapOf(ADF.primaryId to plain, ADF.client to CL.hub))
        mail.lastEmailTo(plain).shouldNotBeNull().text shouldContain "An account has been created for $plain"
        mail.lastEmailTo(plain)!!.text shouldNotContain "Mail Co"
    }

    "a param cannot carry a link or markup into a mail" {
        val planted = "[restore your account](http://evil.test) <b>now</b>"
        val rendered = MailCopy.render(
            cxt, null, MCOPY.claimNoMatch, mapOf(MCOPY.addressParam to planted),
        )
        // The link and tag characters are removed before substitution, so neither part carries either; the
        // words survive as words.
        rendered.text shouldNotContain "](http://evil.test)"
        rendered.text shouldNotContain "<b>"
        rendered.text shouldContain "restore your account"
        rendered.html shouldNotContain "<a href=\"http://evil.test\""
        rendered.html shouldNotContain "<b>"
        rendered.subject shouldNotBe ""
    }
})
