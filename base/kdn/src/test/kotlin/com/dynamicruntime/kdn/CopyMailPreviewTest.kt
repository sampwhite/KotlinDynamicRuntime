package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MAIL
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.mail.MailPreview
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.kdn.grantsdemo.GrantsDemo
import com.dynamicruntime.kdn.grantsdemo.provisionGrantsDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Previewing a mail as a client's copy renders it (issue #1799): `/clientAdmin/client/copy/mailPreview` renders it by the
 * code that sends mail, with sample values, in the client's words -- its sandbox's for a client with one, where a save of
 * its copy is a draft -- and refuses a namespace that is not a mail.
 */
class CopyMailPreviewTest : StringSpec({
    val cxt = TestInstances.default("copyMailPreview1799")
    val svc = GedraConfigService.get(cxt)

    fun preview(user: TestUser, mail: String) = user.getData(CPY.mailPreviewPath, mapOf(CPY.mail to mail))

    "a mail is the client's, rendered as it is sent, with sample values" {
        provisionGrantsDemo(cxt)
        val admin = TestUser.create(cxt, "previewer@grantsdemo.test", level = ROLE.admin, userClient = GrantsDemo.client)
        val code = preview(admin, MCOPY.verifyCode)
        code[COV.client] shouldBe GrantsDemo.client
        code[CPY.mode] shouldBe EDM.live
        code[CPY.subject] shouldBe "Your Riverbend sign-in code"
        // From the fund (issue #1804), at the deployment's address.
        code[CPY.from] shouldBe "\"Riverbend Community Fund\" <${MAIL.unsentFromAddress}>"
        // The text part, its code read out as the tests and the dev autofill read a real one's, then the closing line.
        val text = code[CPY.text] as String
        text shouldContain "verification code is ${MailPreview.sampleCode}."
        text shouldContain "From the grants team at the Riverbend Community Fund."
        // The HTML part, in the fund's style.
        val html = code[CPY.html] as String
        html shouldContain "font-family: Georgia, serif"
        html shouldContain MailPreview.sampleCode
        // An invitation carries its sample link, and names the client by its id for the claim page.
        val invitation = preview(admin, MCOPY.invitation)[CPY.text] as String
        invitation shouldContain MailPreview.sampleToken
        invitation shouldContain "Client ID: ${GrantsDemo.client}"
        invitation shouldContain MailPreview.sampleAddress
    }

    "for a client with a sandbox, the words are its sandbox's, and the mail is still the client's" {
        val client = "mailpreviewdraft"
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 17990L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Draft Co", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                    ),
                )
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        // A draft of the footer, which the client's own people do not read yet.
        admin.postData(
            CPY.applyPath,
            mapOf(
                COV.fileId to AFRAG.mail,
                CPY.changes to listOf(mapOf(COV.namespaceField to MCOPY.common, COV.key to MCOPY.footer, COV.value to "Draft Co, signing off.")),
            ),
        )[CPY.mode] shouldBe EDM.draft

        val invitation = preview(admin, MCOPY.invitation)
        invitation[CPY.mode] shouldBe EDM.draft
        invitation[COV.client] shouldBe client
        val text = invitation[CPY.text] as String
        text shouldContain "Draft Co, signing off."
        // Named as the client, never its sandbox.
        text shouldContain "at Draft Co,"
        text shouldContain "Client ID: $client"
        text shouldNotContain "sandbox"
    }

    "what is not a mail is refused" {
        val admin = TestUser.create(cxt, "refused@grantsdemo.test", level = ROLE.admin, userClient = GrantsDemo.client)
        admin.expectError(EXC.badInput, CPY.mailPreviewPath, args = mapOf(CPY.mail to MCOPY.common))
    }
})
