package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.renderMarkdown
import com.dynamicruntime.common.util.renderMarkdownInline
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
 */
external interface MarkdownProps : Props {
    var source: String
}

val Markdown = FC<MarkdownProps> { props ->
    div {
        className = ClassName("markdown")
        dangerouslySetInnerHTML = innerHtml(props.source.renderMarkdown())
    }
}

/**
 * Renders a *phrase* of Markdown inline, as a `<span>` carrying no styling of its own -- so it inherits from
 * whatever it is dropped into and stays valid inside a paragraph or label, where [Markdown]'s `<div>` would
 * not be. Used for copy that emphasises a substituted value, e.g. an address in `` `${user.email}` ``.
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
