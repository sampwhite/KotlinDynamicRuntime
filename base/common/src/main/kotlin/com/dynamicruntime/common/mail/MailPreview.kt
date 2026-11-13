package com.dynamicruntime.common.mail

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.user.INVITE
import com.dynamicruntime.common.user.PERSONA

/**
 * A mail as a client's copy renders it, for its administrator to look at before anyone is sent one (issue #1799): the
 * subject, the text part, and the HTML part, made by [MailCopy.render] -- the code that sends a mail -- so a preview
 * cannot drift from what is sent. Its values are samples: a made-up address and code, and the links a real mail would
 * carry, built as a real one builds them.
 *
 * **Whose words.** What a save in the copy editor would now show the client's people: for a client with a sandbox, its
 * sandbox's, where that save is a draft (issue #930), and for any other client, its own. A preview of a sandbox is the
 * sandbox's. Either way the mail names the client, never the sandbox: the sandbox is where the words are tried, not who
 * a mail is from.
 *
 * Theme: #CUSTX.
 */
object MailPreview {
    /** The address a preview's mail is about: made up, so no preview shows a real person's. */
    const val sampleAddress = "pat@example.com"

    /** A preview's code, shaped like a real one: six characters, capitals and digits. */
    const val sampleCode = "K7M2QX"

    /** The token a preview's invitation link carries, saying what it is in case anyone follows it. */
    const val sampleToken = "preview-not-a-real-invitation"

    /** What a preview shows: the [mail] for [client] as [readsAs]'s copy renders it, and whether that is a [mode] draft. */
    class Preview(val client: String, val mail: String, val readsAs: String, val mode: String, val rendered: RenderedMail)

    /** The client whose words a preview for [client] shows: its sandbox's, where it has one; see the class note. */
    fun readsAs(cxt: KdrCxt, client: String): String {
        if (isSandboxClient(client)) return client
        val clients = ClientService.get(cxt)
        return if (clients.known(client)?.sandbox == true && clients.isPresent(sandboxOf(client))) sandboxOf(client) else client
    }

    /** [mail], one of [MCOPY.mails] (the endpoint's choices hold a caller to them), for [client]'s people, with sample values. */
    fun preview(cxt: KdrCxt, client: String, mail: String): Preview {
        val readsAs = readsAs(cxt, client)
        val owner = sandboxParentOf(readsAs) ?: readsAs
        val name = ClientService.get(cxt).known(owner)?.name ?: owner
        // The deployment's URL as a mail's links are built: configured, else a test instance's own request.
        val publicUrl = INVITE.configuredPublicUrl(cxt) ?: INVITE.publicUrlFor(cxt).takeIf { cxt.instanceConfig.isTestInstance }
        val params = mapOf(
            MCOPY.codeParam to sampleCode,
            MCOPY.addressParam to sampleAddress,
            MCOPY.clientParam to owner,
            MCOPY.clientNameParam to name,
            MCOPY.personaParam to PERSONA.member,
            MCOPY.personaLabelParam to PERSONA.label(PERSONA.member),
            MCOPY.urlParam to if (mail == MCOPY.invitation) INVITE.invitationUrl(publicUrl, sampleToken) else INVITE.signInUrl(publicUrl),
        )
        // A sandbox's words are drafts its parent's people do not read yet.
        val mode = if (isSandboxClient(readsAs)) EDM.draft else EDM.live
        return Preview(owner, mail, readsAs, mode, MailCopy.render(cxt, readsAs, mail, params))
    }
}
