package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.renderMarkdown
import com.dynamicruntime.common.util.renderMarkdownInline
import kotlinx.browser.document
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Renders Markdown [MarkdownProps.source] as HTML with the kernel's `renderMarkdown()` -- the same renderer
 * the backend uses, so a fragment's copy and a whole document look identical wherever they are rendered, with
 * no npm Markdown dependency.
 *
 * Injecting the result is safe by construction: the renderer escapes every piece of text, never passes raw
 * HTML through, and neutralizes non-http(s)/mailto link URLs.
 *
 * [MarkdownProps.linkResolver], when set, rewrites each link's target as it is rendered -- how a *document*
 * served to the frontend retargets its repo-relative interior links (issue #492; see [docLinkResolver]). The
 * renderer still runs the result through its URL safety check, so a resolver can only retarget a link.
 */
external interface MarkdownProps : Props {
    var source: String
    var linkResolver: ((String) -> String)?
    /**
     * **Inline-UI** rendering (issue #631): when true the block renders `class="markdown markdown-inline-ui"`,
     * whose CSS makes Markdown read as a piece of *UI* rather than a document -- collapsed lead-in and
     * inter-block margins so a header-plus-line is one compact unit, with heading and body both in the muted section-heading colour.
     * Absent/false keeps the roomy document look every other caller wants. Ignored by [MarkdownInline], which
     * is already a bare phrase. (Named `inlineUi` -- the house `Ui`, as in `UiBlock` -- so more inline-UI
     * tweaks can gather under it.)
     */
    var inlineUi: Boolean?
}

val Markdown = FC<MarkdownProps> { props ->
    div {
        // `markdown-inline-ui` (issue #631) is the inline-UI opt-in; see [MarkdownProps.inlineUi].
        className = ClassName(if (props.inlineUi == true) "markdown markdown-inline-ui" else "markdown")
        // A same-document `#anchor` (a table-of-contents link) scrolls in-page rather than letting the app's
        // hash router consume it (issue #492); everything else, including an in-app `#doc=` route link, is
        // left to the browser.
        onClick = { e -> scrollSameDocAnchorIntoView(e) }
        dangerouslySetInnerHTML = innerHtml(props.source.renderMarkdown(props.linkResolver))
    }
}

/**
 * If a click landed on a same-document anchor link, scroll its target heading into view and stop the browser
 * navigating (issue #492). A same-document anchor is a `#…` href that is not one of the app's route links --
 * those carry `key=value` params -- so a `#doc=…` in-app document link and every off-page link fall through
 * untouched. A slug that matches no heading simply scrolls nowhere, never reloads.
 */
private fun scrollSameDocAnchorIntoView(e: dynamic) {
    val anchor = e.target.closest("a") ?: return
    val href = anchor.getAttribute("href") as? String ?: return
    if (!href.startsWith("#") || href.contains("=")) {
        return
    }
    e.preventDefault()
    val id = href.substring(1)
    if (id.isNotEmpty()) {
        document.getElementById(id)?.asDynamic()?.scrollIntoView(js("({ block: 'start' })"))
    }
}

/**
 * Renders a *phrase* of Markdown inline, as a `<span>` carrying no styling of its own -- so it inherits from
 * whatever it is dropped into and stays valid inside a paragraph or label, where [Markdown]'s `<div>` would
 * not be. Used for copy that emphasizes a substituted value, e.g., an address in `` `${user.email}` ``.
 *
 * Safe on the same terms as [Markdown]: the kernel renderer escapes all text and neutralizes unsafe URLs. Note
 * the substitution (`evalTemplate`) must run *before* this, so a value carrying Markdown or HTML is escaped as
 * text rather than interpreted.
 */
val MarkdownInline = FC<MarkdownProps> { props ->
    span {
        className = ClassName("markdown-inline")
        dangerouslySetInnerHTML = innerHtml(props.source.renderMarkdownInline())
    }
}

/** React's `{ __html }` wrapper for already-rendered HTML. */
private fun innerHtml(html: String): dynamic {
    val wrapper: dynamic = js("({})")
    wrapper.__html = html
    return wrapper
}
