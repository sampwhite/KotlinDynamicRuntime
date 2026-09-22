package com.dynamicruntime.common.mail

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
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
    /** The client's display name (`Acme`), supplied by `MailCopy.render` beside [clientParam] whenever a mail has a client. */
    const val clientNameParam = "clientName"

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
 * **Every other param is emphasized, and kept from being linkified.** A value is what a reader copies out --
 * the address, the client, the persona, the code -- so in the HTML part each one is set in bold, and the code
 * large and monospaced. The emphasis is applied here rather than written into the copy as `**...**`, so the
 * text part carries no marks at all and the phrase the code is read out of stays exactly as written. The
 * same wrap solves a second problem: Gmail's web client turns any address it finds into a `mailto:` link (and a
 * run of digits into a phone number) and offers no opt-out, which makes copying the address fiddly -- the
 * click opens a compose window. Nothing can be done in the text part (breaking the pattern with an invisible
 * character would make the pasted value fail our own address check), but Gmail does not re-linkify inside an
 * existing anchor, so each value's emphasis is an anchor with no `href` and `color: inherit; text-decoration:
 * none`: it renders as styled text and selects normally. Partial by nature (Gmail sometimes rewrites inline
 * styles), and harmless where no linkifier runs. The wrap happens through a placeholder that survives the
 * Markdown pass, so the renderer's escaping is untouched and no word of the copy can be caught by it.
 *
 * **The client's name** is supplied as a param beside its id whenever a mail has a client, so the copy can
 * say "at Acme" where the recipe has to say `acme`.
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

        val named = if (client == null || MCOPY.clientNameParam in params) params else {
            params + (MCOPY.clientNameParam to (ClientService.get(cxt).known(client)?.name ?: client))
        }
        val safe = named.mapValues { (_, v) -> if (v is String) v.sanitizeForDisplay(MCOPY.maxParamLength) else v }
        val subject = copy(mail, MCOPY.subject).evalTemplate(safe)
        val body = copy(mail, MCOPY.body)
        val footer = copy(MCOPY.common, MCOPY.footer)
        val text = body.evalTemplate(safe) + "\n\n" + footer.evalTemplate(safe)

        // The same source again for the HTML part: a URL-valued param written as a Markdown link so the
        // renderer makes it an anchor (the text part keeps the bare URL), and every other string value replaced
        // by a placeholder, wrapped in its plain-text anchor once the Markdown has been rendered and escaped.
        val plain = ArrayList<Pair<String, String>>()
        val forHtml = safe.mapValues { (name, v) ->
            when {
                v !is String -> v
                isHttpUrl(v) -> "[$v]($v)"
                else -> { plain.add(name to v); placeholder(plain.size - 1) }
            }
        }
        var rendered = body.evalTemplate(forHtml).renderMarkdown() +
            "<p style=\"$footerStyle\">" + footer.evalTemplate(forHtml).renderMarkdownInline() + "</p>"
        plain.forEachIndexed { i, (name, v) ->
            rendered = rendered.replace(placeholder(i), "<a style=\"${valueStyle(name)}\">${escapeHtml(v)}</a>")
        }
        return RenderedMail(subject, text, wrapHtml(rendered, copy(MCOPY.common, MCOPY.htmlStyle)))
    }

    /**
     * Stands in for the [i]th plain value through the Markdown pass. Private-use code points, which neither the
     * renderer nor its escaping touch, and which no copy or sanitized value contains.
     */
    private fun placeholder(i: Int): String = "\uE000$i\uE001"

    /** How a substituted value is set in the HTML part: the code to be read off and typed, everything else bold. */
    private fun valueStyle(param: String): String = if (param == MCOPY.codeParam) codeStyle else valueStyle

    /** A value's emphasis, as an anchor with no `href` so it is never linkified (see the class note). */
    private const val valueStyle = "font-weight: bold; color: inherit; text-decoration: none;"

    /** The code: large, monospaced, spaced out, for reading off a phone and typing elsewhere. */
    private const val codeStyle = "font-family: Menlo, Consolas, monospace; font-size: 18px; font-weight: bold; " +
        "letter-spacing: 2px; color: inherit; text-decoration: none;"

    private fun isHttpUrl(v: String): Boolean = v.startsWith("http://") || v.startsWith("https://")

    /** The minimal document around a rendered body: one style on `body`, one bounded column, nothing else. */
    private fun wrapHtml(rendered: String, bodyStyle: String): String =
        "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n</head>\n" +
            "<body style=\"${escapeHtml(bodyStyle)}\">\n<div style=\"max-width: 600px;\">\n$rendered</div>\n</body>\n</html>\n"

    /** The footer is set off from the message it closes: smaller, and grey. */
    private const val footerStyle = "font-size: 13px; color: #777777;"

    /** Escapes a value for element text or an attribute, as the renderer does: none of it is markup. */
    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
