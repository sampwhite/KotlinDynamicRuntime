package com.dynamicruntime.webapp

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.ChildrenBuilder
import react.useState
import web.cssom.ClassName
import web.dom.ElementId

/** The props of [WhatIsThis]. */
external interface WhatIsThisProps : Props {
    /** The heading the explanation belongs to, drawn as an `h2` with the link on its line; null for the link alone. */
    var heading: String?

    /**
     * The heading's level (issue #1337): 1 for the page's own heading, 2 (`h2`, the default) for a page's section, 3
     * for a section within one.
     */
    var headingLevel: Int?

    /** Whether the heading is inline Markdown, as a workflow's label is, rather than plain text (issue #1337). */
    var headingMarkdown: Boolean?

    /** The heading's element id, for a section a link elsewhere on the page leads to (issue #1337); null for none. */
    var headingId: String?

    /**
     * The explanation, as Markdown, already resolved -- from the fragment that serves the content it explains, so a
     * deployment's or a client's copy overlay can reword it. Blank draws no link at all: what nobody has explained is
     * not offered.
     */
    var text: String

    /**
     * How links in the explanation resolve, as the rest of the page's copy does. Absent, as form copy's do (issue
     * #1476): a link to a served document -- a reference the explanation points to -- opens it in the app.
     */
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
 * drawn when [WhatIsThisProps.text] is blank, so an explanation is removed by emptying its copy. The link's own words
 * ("What is this?", "Hide") are the shell's copy, the same on every page ([ShellFacts.whatIsThisLink]).
 */
val WhatIsThis = FC<WhatIsThisProps> { props ->
    var open by useState(false)
    // The link's words are the app's, not the page's: the shell's copy, told to every page (ShellFacts).
    val facts = useShellFacts()
    val panelId = useState { "what-is-this-${++whatIsThisSeq}" }.component1()
    val text = props.text.trim()

    // The heading as the section draws it, at its level and with its id.
    fun ChildrenBuilder.heading(text: String) {
        val tag = when (props.headingLevel) {
            1 -> h1
            3 -> h3
            else -> h2
        }
        tag {
            props.headingId?.let { id = ElementId(it) }
            if (props.headingMarkdown == true) MarkdownInline { source = text } else +text
        }
    }

    if (text.isEmpty()) {
        props.heading?.let { heading(it) }
        return@FC
    }

    div {
        // A page's own heading keeps the spacing an `h1` has; a section's, the space above a section.
        className = ClassName(
            when {
                props.heading == null -> "what-is-this-line"
                props.headingLevel == 1 -> "what-is-this-heading what-is-this-title"
                else -> "what-is-this-heading"
            },
        )
        props.heading?.let { heading(it) }
        button {
            className = ClassName("what-is-this-link")
            asDynamic()["aria-expanded"] = open
            asDynamic()["aria-controls"] = panelId
            onClick = { open = !open }
            +if (open) facts.whatIsThisHide ?: "Hide" else facts.whatIsThisLink ?: "What is this?"
        }
    }
    if (open) {
        div {
            id = ElementId(panelId)
            className = ClassName("what-is-this-text")
            Markdown {
                source = text
                linkResolver = props.linkResolver ?: facts.copyLinkResolver()
            }
        }
    }
}
