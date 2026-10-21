package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.useState
import web.cssom.ClassName
import web.dom.ElementId

/** The props of [WhatIsThis]. */
external interface WhatIsThisProps : Props {
    /** The heading the explanation belongs to, drawn as an `h2` with the link on its line; null for the link alone. */
    var heading: String?

    /**
     * The explanation, as Markdown, already resolved -- from the fragment that serves the content it explains, so a
     * deployment's or a client's copy overlay can reword it. Blank draws no link at all: what nobody has explained is
     * not offered.
     */
    var text: String

    /** The link's words, from the same copy; "What is this?" when the copy has none. */
    var linkLabel: String?

    /** The words that close it again; "Hide" when the copy has none. */
    var hideLabel: String?

    /** How links in the explanation resolve, as the rest of the page's copy does. */
    var linkResolver: ((String) -> String)?
}

/** A sequence for the ids that tie a link to the text it opens, unique for the page's lifetime. */
private var whatIsThisSeq = 0

/**
 * A "What is this?" link (issue #1330): a small, muted link beside a heading -- or on its own -- that opens an
 * explanation **inline**, beneath it, and closes it again. Inline rather than as a pop-up because an explanation is a
 * paragraph or two, read at length and on a phone, where a pop-up suits one sentence; and a disclosure is what a
 * keyboard and a screen reader already know (`aria-expanded`, `aria-controls`).
 *
 * The status a section shows stays always visible; this holds the concept behind it, for whoever wants it. Nothing is
 * drawn when [WhatIsThisProps.text] is blank, so an explanation is removed by emptying its copy.
 */
val WhatIsThis = FC<WhatIsThisProps> { props ->
    var open by useState(false)
    val panelId = useState { "what-is-this-${++whatIsThisSeq}" }.component1()
    val text = props.text.trim()

    if (text.isEmpty()) {
        props.heading?.let { h2 { +it } }
        return@FC
    }

    div {
        className = ClassName(if (props.heading != null) "what-is-this-heading" else "what-is-this-line")
        props.heading?.let { h2 { +it } }
        button {
            className = ClassName("what-is-this-link")
            asDynamic()["aria-expanded"] = open
            asDynamic()["aria-controls"] = panelId
            onClick = { open = !open }
            +if (open) props.hideLabel ?: "Hide" else props.linkLabel ?: "What is this?"
        }
    }
    if (open) {
        div {
            id = ElementId(panelId)
            className = ClassName("what-is-this-text")
            Markdown {
                source = text
                props.linkResolver?.let { linkResolver = it }
            }
        }
    }
}
