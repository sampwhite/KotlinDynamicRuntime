package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.renderMarkdown
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
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

/** React's `{ __html }` wrapper for already-rendered HTML. */
private fun innerHtml(html: String): dynamic {
    val wrapper: dynamic = js("({})")
    wrapper.__html = html
    return wrapper
}
