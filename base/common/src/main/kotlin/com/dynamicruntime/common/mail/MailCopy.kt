package com.dynamicruntime.common.mail

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.util.MDR
import com.dynamicruntime.common.util.MarkdownHooks
import com.dynamicruntime.common.util.escapeMarkdown
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.renderMarkdown
import com.dynamicruntime.common.util.renderMarkdownInline
import com.dynamicruntime.common.util.renderMarkdownText
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
     * Bound by every render (issue #795): `true` for the HTML part, `false` for the text part, so a body that
     * genuinely wants different *words* in the two -- "click the button below" against "open this link" -- can
     * branch with `${forHtml ? "…" : "…"}`. Kept rare: the text renderer covers styling, so a `forHtml` in a mail
     * marks a real divergence.
     */
    const val forHtmlParam = "forHtml"

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
 * **Two parts from one source.** The body with its params substituted is rendered twice by the kernel's Markdown
 * renderer, which the frontend shares: [renderMarkdown] gives the HTML part -- paragraphs, inline styles only,
 * no stylesheet, so it survives every mail client -- and [renderMarkdownText] the text part, so the copy may
 * carry emphasis and roles and neither part shows a mark of syntax (issue #795). A param whose value is an
 * `http(s)` URL becomes a link in the HTML part on its own, and stays the bare URL in the text part, where
 * mail clients linkify it.
 *
 * **A substituted value is marked in the copy, and realized here.** The copy sets a value a reader copies out
 * -- the address, the client, the persona, the code -- in a bracketed span with a role: `[${address}]{.value}`,
 * `[${code}]{.code}`. In the HTML part the role is realized through the renderer's span hook as bold (the
 * code large and monospaced), inside an anchor with no `href` and `color: inherit; text-decoration: none`:
 * Gmail's web client turns any address it finds into a `mailto:` link (and a run of digits into a phone number)
 * and offers no opt-out, which makes copying the value fiddly -- but it does not re-linkify inside an existing
 * anchor, so the value renders as styled text and selects normally. Partial by nature (Gmail sometimes rewrites
 * inline styles), and harmless where no linkifier runs. In the text part the span is simply its text, so the
 * phrase the code is read out of stays exactly as written.
 *
 * **The client's name** is supplied as a param beside its id whenever a mail has a client, so the copy can
 * say "at Acme" where the recipe has to say `acme`.
 *
 * **Params are sanitized** before substitution, exactly as an error message's are (`RequestHandler.renderMsg`):
 * the characters that structure a Markdown link or code span are removed and whitespace is collapsed, so a
 * request-supplied value cannot inject a link or markup into a message sent from the deployment's domain.
 * The HTML renderer escapes all text besides, so the two defenses stack.
 */
@Suppress("ConstPropertyName")
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
        // Sanitized, then Markdown-escaped (issue #795): a value is substituted into copy the renderer then reads,
        // so an address like `_ops_@acme.test` must reach both parts verbatim rather than as emphasis. The two
        // defenses stack -- the sanitizer removes what would structure a link, the escape neutralizes the rest.
        val sanitized = named.mapValues { (_, v) -> if (v is String) v.sanitizeForDisplay(MCOPY.maxParamLength) else v }
        val safe = sanitized.mapValues { (_, v) -> if (v is String) v.escapeMarkdown() else v }
        val subject = copy(mail, MCOPY.subject).evalTemplate(sanitized)
        val body = copy(mail, MCOPY.body)
        val footer = copy(MCOPY.common, MCOPY.footer)

        // The text part: the same source, rendered to plain text (issue #795).
        val forText = safe + (MCOPY.forHtmlParam to false)
        val text = (body.evalTemplate(forText) + "\n\n" + footer.evalTemplate(forText)).renderMarkdownText()

        // The HTML part: a URL-valued param written as a Markdown link so the renderer makes it an anchor (the
        // text part keeps the bare URL) -- the label escaped like any value, the target the URL itself; a role a
        // span carries realized as the value styles below.
        val forHtml = sanitized.mapValues { (name, v) ->
            if (v is String && isHttpUrl(v)) "[${v.escapeMarkdown()}]($v)" else safe[name]
        } + (MCOPY.forHtmlParam to true)
        val hooks = MarkdownHooks(decorateSpan = ::decorateValue)
        val rendered = body.evalTemplate(forHtml).renderMarkdown(hooks) +
            "<p style=\"$footerStyle\">" + footer.evalTemplate(forHtml).renderMarkdownInline(hooks) + "</p>"
        return RenderedMail(subject, text, wrapHtml(rendered, copy(MCOPY.common, MCOPY.htmlStyle)))
    }

    /**
     * How a role is realized in a mail (issue #795): the [MDR.code] role as the code style, [MDR.value] as bold, each
     * an anchor with no `href` so it is never linkified (see the class note); any other role takes the renderer's
     * default. Receives rendered, escaped HTML and wraps it, so nothing here can re-open the escaping.
     */
    private fun decorateValue(roles: List<String>, innerHtml: String): String? = when {
        MDR.code in roles -> "<a style=\"$codeStyle\">$innerHtml</a>"
        MDR.value in roles -> "<a style=\"$valueStyle\">$innerHtml</a>"
        else -> null
    }

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
