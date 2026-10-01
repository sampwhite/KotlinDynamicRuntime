package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException

/**
 * What [analyzeMarkdown] finds in a Markdown document (issue #909). The renderer almost never fails -- it
 * degrades: a construct it does not support renders as text, an unsafe link renders inert, a ragged table row is
 * padded. Each of those is delivered and silent, so these are mostly **warnings**; the one error is nesting past
 * the depth cap, which does stop the render.
 */
@Suppress("EnumEntryName")
enum class MarkdownIssue : ProblemCode {
    /** Raw HTML, which is escaped and shows as written -- a fragment or document can never inject markup. */
    rawHtml,

    /** A setext heading (a line of `===` or `---` under text), which renders as a paragraph (and a rule). */
    setextHeading,

    /** An indented list item, which renders as a sibling of the item above rather than nested under it. */
    nestedList,

    /** A reference link (`[text][ref]`) or its definition (`[ref]: url`), which render as text. */
    referenceLink,

    /** A link or image whose target is not http, https, mailto or relative, so it renders with an empty target. */
    inertLink,

    /** A same-document `#anchor` link that no heading in the document has the slug of. */
    brokenAnchor,

    /** A relative link the surface's resolver left as written, so it points nowhere from inside the app. */
    unresolvedLink,

    /** A table row with more or fewer cells than its header; it is padded or cut to fit. */
    raggedRow,

    /**
     * Inline constructs nested past the cap -- the one fault that stops the render. A guard: each inline construct
     * closes at the first closing mark of its kind, so today's grammar cannot nest deep enough to reach it.
     */
    tooDeep,
}

/** A heading: its [level] (1..6), its plain [text], the [slug] its anchor id has, and its 1-based [line]. */
class MdHeading(val level: Int, val text: String, val slug: String, val line: Int)

/**
 * A link or image ([image]) target as written ([target]), what the surface's resolver made of it ([resolved]),
 * and its 1-based [line] -- the line of the block it is in. [inert] when the resolved target was refused.
 */
class MdLink(val target: String, val resolved: String, val image: Boolean, val inert: Boolean, val line: Int)

/** A fenced code block: its [language] (empty when the fence names none) and the 1-based [line] it opens on. */
class MdCodeBlock(val language: String, val line: Int)

/**
 * A Markdown document's report (issue #909): the rendered [html] (null only when the render stopped, on
 * [MarkdownIssue.tooDeep]); its structure -- the [headings] (the document's outline, in order), the [links]
 * and images, and the fenced [codeBlocks]; and its [problems], each located at its 1-based line.
 */
class MarkdownAnalysis(
    val html: String?,
    val headings: List<MdHeading>,
    val links: List<MdLink>,
    val codeBlocks: List<MdCodeBlock>,
    val problems: List<Problem>,
)

/**
 * Renders [this] as [renderMarkdown] does, with [hooks], and reports what it is made of and what it delivered
 * degraded (issue #909) -- for a check that shows an author an unsupported construct against its line, or a page
 * that builds a table of contents from the headings. The HTML is exactly [renderMarkdown]'s.
 *
 * A problem inside a block (an inert link, a broken anchor) is placed on the line the block starts on; a
 * construct found by scanning (raw HTML, a setext underline, an indented item, a reference link) on its own line.
 * Fenced code and code spans are not scanned: what they hold is shown as written on purpose.
 */
fun String.analyzeMarkdown(hooks: MarkdownHooks = MarkdownHooks()): MarkdownAnalysis {
    val sink = ReportingSink(HtmlSink(hooks), hooks)
    val html = try {
        renderBlocks(this, sink)
    } catch (e: KdrException) {
        if (e.extraData[KdrException.errorCodeKey] != MarkdownIssue.tooDeep) throw e
        sink.problems.add(Problem(MarkdownIssue.tooDeep, e.message.orEmpty(), ProblemLocation(line = sink.line + 1)))
        null
    }
    val slugs = sink.headings.mapTo(HashSet()) { it.slug }
    for (link in sink.links) {
        val anchor = link.target.takeIf { !link.image && it.startsWith("#") }?.substring(1) ?: continue
        if (anchor.isNotEmpty() && anchor !in slugs) {
            sink.problems.add(
                mdWarning(
                    MarkdownIssue.brokenAnchor, "The link to '${link.target}' names no heading in this document.",
                    link.line,
                ),
            )
        }
    }
    val problems = (sink.problems + scanUnsupported(this)).sortedBy { it.location?.line ?: 0 }
    return MarkdownAnalysis(html, sink.headings, sink.links, sink.codeBlocks, problems)
}

private fun mdWarning(code: MarkdownIssue, message: String, line: Int) =
    Problem(code, message, ProblemLocation(line = line), ProblemSeverity.warning)

/**
 * The HTML rendering, watched (issue #909): delegates every construct to [html] and records the structure and
 * the degradations the renderer reports through [MarkdownSink.block] and [MarkdownSink.note].
 */
@KdrPrivate
class ReportingSink(private val html: HtmlSink, private val hooks: MarkdownHooks) : MarkdownSink by html {
    /** The 0-based line of the block being rendered. */
    var line: Int = 0
    val headings = mutableListOf<MdHeading>()
    val links = mutableListOf<MdLink>()
    val codeBlocks = mutableListOf<MdCodeBlock>()
    val problems = mutableListOf<Problem>()

    override fun block(line: Int) {
        this.line = line
    }

    override fun note(code: MarkdownIssue, message: String, line: Int) {
        problems.add(mdWarning(code, message, line + 1))
    }

    override fun heading(level: Int, raw: String, inner: String): String {
        headings.add(MdHeading(level, renderInline(raw, 0, TextSink), headingSlug(raw), line + 1))
        return html.heading(level, raw, inner)
    }

    override fun code(language: String, body: String): String {
        codeBlocks.add(MdCodeBlock(language, line + 1))
        return html.code(language, body)
    }

    override fun link(label: String, url: String): String {
        record(url, image = false)
        return html.link(label, url)
    }

    override fun image(alt: String, src: String, attrs: MdAttrs?): String {
        record(src, image = true)
        return html.image(alt, src, attrs)
    }

    private fun record(target: String, image: Boolean) {
        val resolved = hooks.resolveUrl?.invoke(target) ?: target
        val inert = resolved.isNotBlank() && safeUrl(resolved).isEmpty()
        links.add(MdLink(target, resolved, image, inert, line + 1))
        val what = if (image) "An image source" else "A link"
        if (inert) {
            problems.add(
                mdWarning(
                    MarkdownIssue.inertLink,
                    "$what '$target' is not http, https, mailto or relative, so it renders with an empty target.",
                    line + 1,
                ),
            )
        } else if (hooks.resolveUrl != null && resolved == target && isRelativeTarget(target)) {
            problems.add(
                mdWarning(
                    MarkdownIssue.unresolvedLink,
                    "$what '$target' is relative and was not resolved, so it points nowhere from inside the app.",
                    line + 1,
                ),
            )
        }
    }
}

/** A target with no scheme that is not a same-document anchor or protocol-relative: a path the resolver maps. */
private fun isRelativeTarget(target: String): Boolean {
    val t = target.trim()
    if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) return false
    val colon = t.indexOf(':')
    val slash = t.indexOf('/')
    return colon < 0 || slash in 0 until colon
}

private val rawHtmlTag = Regex("""<(/?[A-Za-z][A-Za-z0-9-]*[\s/>]|!--)""")
// Every `]` outside a class is escaped: Kotlin/JS compiles a Regex in Unicode mode, which refuses a bare one.
private val referenceDefinition = Regex("""^ {0,3}\[[^\]]+\]:\s*\S""")
private val referenceUse = Regex("""\]\[[^\]]*\]""")
private val codeSpan = Regex("`[^`]*`")

/**
 * The constructs the renderer shows as text, found by line (issue #909): raw HTML, a setext underline, an indented
 * list item, and a reference link or definition. Lines inside a fenced block are skipped, and code spans are
 * removed from a line before it is looked at, so a `` `<div>` `` in prose is not reported.
 */
private fun scanUnsupported(text: String): List<Problem> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = mutableListOf<Problem>()
    var fence: String? = null
    var previousIsText = false
    for ((i, raw) in lines.withIndex()) {
        val marker = fenceMarker(raw)
        if (fence != null) {
            if (marker == fence) fence = null
            continue
        }
        if (marker != null) {
            fence = marker
            previousIsText = false
            continue
        }
        val line = raw.replace(codeSpan, "")
        val n = i + 1
        val t = line.trim()
        if (previousIsText && t.isNotEmpty() && (t.all { it == '=' } || t.all { it == '-' })) {
            out.add(
                mdWarning(
                    MarkdownIssue.setextHeading,
                    "A setext heading (text underlined with '${t[0]}') renders as a paragraph; write it as an ATX " +
                        "heading ('${if (t[0] == '=') "#" else "##"} Title').",
                    n,
                ),
            )
        }
        val indent = line.length - line.trimStart().length
        if (indent >= 2 && (bulletContent(line) != null || orderedContent(line) != null)) {
            out.add(
                mdWarning(
                    MarkdownIssue.nestedList,
                    "An indented list item renders flat, as a sibling of the item above; nested lists are not " +
                        "supported.",
                    n,
                ),
            )
        }
        if (rawHtmlTag.containsMatchIn(line)) {
            out.add(mdWarning(MarkdownIssue.rawHtml, "Raw HTML is escaped and shows as written.", n))
        }
        if (referenceDefinition.containsMatchIn(line) || referenceUse.containsMatchIn(line)) {
            out.add(
                mdWarning(
                    MarkdownIssue.referenceLink,
                    "A reference link renders as text; write the target inline, as '[text](url)'.",
                    n,
                ),
            )
        }
        previousIsText = t.isNotEmpty() && !startsBlock(line) && !isDelimiterRow(line)
    }
    return out
}
