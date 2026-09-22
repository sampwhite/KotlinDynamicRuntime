package com.dynamicruntime.common.mail

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.renderMarkdown
import com.dynamicruntime.common.util.renderMarkdownInline
import com.dynamicruntime.common.util.sanitizeForDisplay

/**
 * The `mail` fragment file's vocabulary (issue #773): one namespace per mail, each holding a [subject] and a
 * [body]; the [common] namespace every mail shares; and the `${...}` param names the bodies read.
 */
@Suppress("ConstPropertyName")
object MCOPY {
    /** The keys every mail's namespace holds. */
    const val subject = "subject"
    const val body = "body"

    /** Shared by every mail: the [footer] each one ends with, and the [htmlStyle] of the HTML part's body. */
    const val common = "common"
    const val footer = "footer"
    const val htmlStyle = "htmlStyle"

    // The mails, one namespace each.
    const val verifyCode = "verifyCode"
    const val verifyCodePassword = "verifyCodePassword"
    const val invitation = "invitation"
    const val claimCode = "claimCode"
    const val claimNoMatch = "claimNoMatch"
    const val claimNoMatchInClient = "claimNoMatchInClient"
    const val claimNoMatchNamed = "claimNoMatchNamed"

    /** Every mail namespace, for a check that each has its subject and body. */
    val mails: List<String> = listOf(
        verifyCode, verifyCodePassword, invitation, claimCode, claimNoMatch, claimNoMatchInClient, claimNoMatchNamed,
    )

    // The params.
    const val codeParam = "code"
    const val addressParam = "address"
    const val clientParam = "client"
    /** The persona as the claim page wants it typed: `admin`, `member B`. */
    const val personaParam = "persona"
    /** The persona as displayed: `Admin`, `Member B`. */
    const val personaLabelParam = "personaLabel"
    const val urlParam = "url"

    /**
     * The most a string param may be after sanitizing. Well above the UI default (an invitation link runs to
     * a few hundred characters) and still a bound: a param is a value, never a document.
     */
    const val maxParamLength = 2000
}

/** A mail rendered from its fragment copy: the subject, the `text/plain` part and the `text/html` part. */
class RenderedMail(val subject: String, val text: String, val html: String)

/**
 * Renders a mail from the `mail` fragment file (issue #773), so the copy is authored like every other
 * user-facing sentence -- in Markdown, overlayable per client -- and goes out as one message with a text part
 * and an HTML part from that one source.
 *
 * **Whose copy.** The file is resolved for the [client][render] the mail is *about* -- an invitation's user,
 * a claim's key -- never the caller's, since the sender may be an `allClients` administrator in another
 * client. A client that overlays the file rewords its mails and signs them as itself (the `common.footer`);
 * one that does not gets the shared copy, which names nobody.
 *
 * **Two parts from one source.** The body as written, with its params substituted, is the text part: the
 * copy is kept plain enough that Markdown reads as prose. The kernel's [renderMarkdown] -- shared with the
 * frontend -- gives the HTML part: paragraphs, inline styles only, no images, so it survives every mail
 * client. A param whose value is an `http(s)` URL becomes a link in the HTML part on its own, and stays the
 * bare URL in the text part, where mail clients linkify it.
 *
 * **Params are sanitized** before substitution, exactly as an error message's are (`RequestHandler.renderMsg`):
 * the characters that structure a Markdown link or code span are removed and whitespace is collapsed, so a
 * request-supplied value cannot inject a link or markup into a message sent from the deployment's domain.
 * The HTML renderer escapes all text besides, so the two defenses stack.
 */
object MailCopy {
    /**
     * The mail [mail] (one of `MCOPY.mails`) as [client] reads it, with [params] substituted. Throws when the
     * copy is missing: a mail with no copy is a defect, not a message to send empty.
     */
    fun render(cxt: KdrCxt, client: String?, mail: String, params: Map<String, Any?>): RenderedMail {
        val content = MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, AFRAG.mail, client)?.content
            ?: throw KdrException("The mail fragment file '${AFRAG.mail}' is not declared on this node.")
        fun copy(namespace: String, key: String): String = content[namespace]?.get(key)
            ?: throw KdrException("No mail copy '$namespace.$key' in the fragment file '${AFRAG.mail}'.")

        val safe = params.mapValues { (_, v) -> if (v is String) v.sanitizeForDisplay(MCOPY.maxParamLength) else v }
        val subject = copy(mail, MCOPY.subject).evalTemplate(safe)
        val body = copy(mail, MCOPY.body)
        val footer = copy(MCOPY.common, MCOPY.footer)
        val text = body.evalTemplate(safe) + "\n\n" + footer.evalTemplate(safe)

        // The same source again for the HTML part, with a URL-valued param written as a Markdown link so the
        // renderer makes it an anchor; the text part keeps the bare URL.
        val linked = safe.mapValues { (_, v) -> if (v is String && isHttpUrl(v)) "[$v]($v)" else v }
        val html = wrapHtml(
            body.evalTemplate(linked).renderMarkdown() +
                "<p style=\"$footerStyle\">" + footer.evalTemplate(linked).renderMarkdownInline() + "</p>",
            copy(MCOPY.common, MCOPY.htmlStyle),
        )
        return RenderedMail(subject, text, html)
    }

    private fun isHttpUrl(v: String): Boolean = v.startsWith("http://") || v.startsWith("https://")

    /** The minimal document around a rendered body: one style on `body`, one bounded column, nothing else. */
    private fun wrapHtml(rendered: String, bodyStyle: String): String =
        "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n</head>\n" +
            "<body style=\"${escapeAttribute(bodyStyle)}\">\n<div style=\"max-width: 600px;\">\n$rendered</div>\n</body>\n</html>\n"

    /** The footer is set off from the message it closes: smaller, and grey. */
    private const val footerStyle = "font-size: 13px; color: #777777;"

    /** Escapes a fragment value for an attribute: the style is copy a client may overlay, not markup. */
    private fun escapeAttribute(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
