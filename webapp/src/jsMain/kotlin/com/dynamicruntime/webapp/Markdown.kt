package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.MarkdownHooks
import com.dynamicruntime.common.util.renderMarkdown
import com.dynamicruntime.common.util.renderMarkdownInline
import kotlinx.browser.document
import kotlinx.browser.window
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
 *
 * An image naming a **file ref** (`![Our logo](ref:logo)`, issue #1186) is drawn from the caller's client's ref as
 * the shell was told it ([ShellFacts.copyRefAddress]) -- every copy surface gets that with no prop of its own -- or,
 * where [MarkdownProps.refAddress] is set, from where that says; a ref neither knows is drawn as its alt text.
 */
external interface MarkdownProps : Props {
    var source: String
    var linkResolver: ((String) -> String)?
    /**
     * Where an image naming a file ref is drawn from, given the ref's id, or null for one it cannot draw (issue
     * #1186). Unset, the caller's own client's refs, from the shell: what every page wants. Set by a surface drawing
     * *another* client's copy -- the copy editor, for the client on its page -- which the static root, answering the
     * caller's own client, would draw wrong.
     */
    var refAddress: ((String) -> String?)?
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
        // A click on a block's copy button copies the block (issue #1233); any other click may be an anchor's.
        onClick = { e -> if (!copyCodeBlock(e)) scrollSameDocAnchorIntoView(e) }
        dangerouslySetInnerHTML = innerHtml(props.source.renderMarkdown(markdownHooksFor(props.linkResolver, props.refAddress, useShellFacts())))
    }
}

/**
 * What the renderer is told (issue #1186): the [linkResolver], and where a ref image is drawn from -- [refAddress]
 * where the surface set one, else the caller's own client's refs as the shell was told them ([facts]), each made an
 * address by [addressOf]. A component image (issue #1339) is drawn from the images the shell was told, on every
 * surface, each made an address by [imageAddressOf]. Pure, covered under `jsNodeTest`.
 */
fun markdownHooksFor(
    linkResolver: ((String) -> String)?,
    refAddress: ((String) -> String?)?,
    facts: ShellFacts,
    addressOf: (String) -> String = Http::fileRefUrl,
    imageAddressOf: (String) -> String = Http::componentImageUrl,
): MarkdownHooks = MarkdownHooks(
    resolveUrl = linkResolver,
    refAddress = refAddress ?: facts.copyRefAddress(addressOf),
    imageAddress = facts.copyImageAddress(imageAddressOf),
    decorateCodeBlock = ::copyButtonWrap,
)

/** The class of the container a copyable code block is wrapped in, and of its button (issue #1233). */
const val codeBlockClass = "md-codeblock"
const val copyButtonClass = "md-copy"
const val copiedClass = "md-copied"

/** The fence languages that get a copy button: what a reader pastes elsewhere, not prose-like text. */
val copyableLanguages: Set<String> = setOf("json")

/** The button's content: a clipboard glyph; the label for a screen reader is on the button itself. */
const val copyButtonIcon = "<svg viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" aria-hidden=\"true\">" +
    "<rect x=\"5.5\" y=\"5.5\" width=\"8\" height=\"8\" rx=\"1.5\"/>" +
    "<path d=\"M10.5 5.5V3.5a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2\"/></svg>"

/**
 * The renderer's code-block hook for the app (issue #1233): a block in one of the [copyableLanguages] is wrapped
 * in a container with a copy button before it, so the button can sit in the block's corner; any other block is
 * left as the renderer made it. Pure, covered under `jsNodeTest`.
 */
fun copyButtonWrap(language: String, preHtml: String): String? {
    if (language !in copyableLanguages) return null
    return "<div class=\"$codeBlockClass\"><button type=\"button\" class=\"$copyButtonClass\" title=\"Copy\" " +
        "aria-label=\"Copy to clipboard\">$copyButtonIcon</button>$preHtml</div>"
}

/** How long the button reads "Copied" after a click, in milliseconds. */
const val copiedShownMs = 1500

/**
 * If a click landed on a block's copy button, puts the block's text on the clipboard and says so on the button
 * for a moment (issue #1233); returns whether the click was the button's. The text is the `pre`'s text content,
 * which is the block exactly: the highlighter's spans add no characters. The HTML is set with
 * `dangerouslySetInnerHTML`, so nothing can sit on the button itself and the container's click handler does it.
 */
private fun copyCodeBlock(e: dynamic): Boolean {
    val button = e.target.closest(".$copyButtonClass") ?: return false
    e.preventDefault()
    val pre = button.closest(".$codeBlockClass")?.querySelector("pre") ?: return true
    val text = pre.textContent as? String ?: return true
    val original = button.innerHTML
    writeToClipboard(
        text,
        done = {
            button.textContent = "Copied"
            button.classList.add(copiedClass)
            window.setTimeout({
                button.innerHTML = original
                button.classList.remove(copiedClass)
            }, copiedShownMs)
        },
        // Refused -- the document not focused, or the permission denied: the button stays as it was, and the
        // reader can still select the text.
        refused = {},
    )
    return true
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
 * Its links go through [MarkdownProps.linkResolver] as [Markdown]'s do (issue #1136) -- form copy's, to a document --
 * and an image naming a file ref is drawn as [Markdown] draws one (issue #1186).
 *
 * Safe on the same terms as [Markdown]: the kernel renderer escapes all text and neutralizes unsafe URLs. Note
 * the substitution (`evalTemplate`) must run *before* this, so a value carrying Markdown or HTML is escaped as
 * text rather than interpreted.
 */
val MarkdownInline = FC<MarkdownProps> { props ->
    span {
        className = ClassName("markdown-inline")
        dangerouslySetInnerHTML = innerHtml(props.source.renderMarkdownInline(markdownHooksFor(props.linkResolver, props.refAddress, useShellFacts())))
    }
}

/** React's `{ __html }` wrapper for already-rendered HTML. */
private fun innerHtml(html: String): dynamic {
    val wrapper: dynamic = js("({})")
    wrapper.__html = html
    return wrapper
}
