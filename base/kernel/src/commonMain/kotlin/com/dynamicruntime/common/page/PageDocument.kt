package com.dynamicruntime.common.page

import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.util.jsonResult
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The words of a page document (issue #1792): copy -- one key of a fragment file -- holding Markdown and **widget
 * fences**, each a code block opened with the info string [infoString] whose body is a JSON object saying what to draw
 * there. The keys of that object are these; anything else in it is the widget kind's own parameters.
 */
@Suppress("ConstPropertyName")
object PGW {
    /** The info string that makes a code block a widget fence: ```` ```kdr-ui ````, in column 0. */
    const val infoString = "kdr-ui"

    /** The fence's id, unique in its page: its element id ([elementIdPrefix]`<id>`), and what a jump link names. */
    const val id = "id"

    /** The widget kind ([PageWidgetKind]). Absent, the fence shows only its [copy]. */
    const val widget = "widget"

    /** `namespace.key` in the page's own fragment file: Markdown drawn before the widget, as other copy is. */
    const val copy = "copy"

    /** Whether the fence is drawn, as a UiBlock entry's is: a cfact expression over the cfacts sent to the frontend. */
    const val cfactExpression = UIB.cfactExpression

    /** Puts a link to the fence in the page's jump links, with these words. */
    const val jumpLabel = "jumpLabel"

    /** The fence keys every kind shares, which are never a kind's parameters. */
    val fenceKeys: Set<String> = setOf(id, widget, copy, cfactExpression, jumpLabel)

    /** What a drawn fence's element id starts with: `page.<id>`. The dot keeps it apart from any heading's slug. */
    const val elementIdPrefix = "page."

    /** A `cardsBlock` widget's parameters: the UiBlock it draws, the cards it draws alone, and those it leaves out. */
    const val block = "block"
    const val ids = "ids"
    const val except = "except"

    /** The schema namespace the widget kinds' parameter types are declared in. */
    const val namespace = "kdr.page"
}

/**
 * One piece of a page document as [splitPageDocument] cuts it (issue #1792): Markdown, or a widget fence. Cut before the
 * Markdown renderer or either template pass sees the text, so neither can mistake a fence's JSON for copy.
 */
sealed interface PageSegment

/** Copy between fences, drawn as any other copy is. */
class PageMarkdown(val text: String) : PageSegment

/**
 * A widget fence as written: its [body] (the JSON), the 1-based [line] its opening is on, and whether it was [closed].
 * An unclosed fence runs to the end of the document, as CommonMark has it, and is a problem of the copy.
 */
class PageFenceSource(val body: String, val line: Int, val closed: Boolean) : PageSegment

/** Whether [this] holds a widget fence -- cheap, for the many copy values that hold none. */
fun String.hasPageFences(): Boolean = contains(PGW.infoString) && splitPageDocument(this).any { it is PageFenceSource }

/**
 * [text] cut into its segments, in order (issue #1792). A widget fence is a line in column 0 opening a backtick code
 * block whose info string is [PGW.infoString], through the first line closing it: backticks at least as many as opened
 * it, and nothing else. So a fence opened with four backticks may hold three-backtick code. It is found at the top level
 * only: an indented fence is inside a list or another block, and one inside another code block is that block's text --
 * a document showing a fence as an example does not draw it. Markdown segments that are only blank lines are left out.
 *
 * Theme: #TPLPS.
 */
fun splitPageDocument(text: String): List<PageSegment> {
    val lines = text.split('\n')
    val out = mutableListOf<PageSegment>()
    val markdown = mutableListOf<String>()
    fun flushMarkdown() {
        if (markdown.any { it.isNotBlank() }) out.add(PageMarkdown(markdown.joinToString("\n")))
        markdown.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val open = codeFenceOpening(line)
        if (open == null) {
            markdown.add(line)
            i++
            continue
        }
        // Where its closing line is, if anywhere: what ends this block, whichever kind it is.
        val close = (i + 1 until lines.size).firstOrNull { closesCodeFence(lines[it], open) }
        if (!open.isPageFence) {
            // An ordinary code block: copy, kept whole, so a fence shown inside it is never drawn.
            val end = close ?: (lines.size - 1)
            markdown.addAll(lines.subList(i, end + 1))
            i = end + 1
            continue
        }
        flushMarkdown()
        val bodyEnd = close ?: lines.size
        out.add(PageFenceSource(lines.subList(i + 1, bodyEnd).joinToString("\n"), line = i + 1, closed = close != null))
        i = bodyEnd + 1
    }
    flushMarkdown()
    return out
}

/** A code fence's opening line: its character, how many, and whether it opens a widget fence. */
private class CodeFenceOpening(val char: Char, val length: Int, val isPageFence: Boolean)

/**
 * [line] as CommonMark's code fence opening -- up to three spaces, three or more backticks or tildes, and an info string
 * (a backtick fence's never holding a backtick) -- or null. A widget fence is a backtick fence in column 0 whose info
 * string's first word is [PGW.infoString].
 */
private fun codeFenceOpening(line: String): CodeFenceOpening? {
    val rest = line.trimStart(' ')
    val indent = line.length - rest.length
    if (indent > 3) return null
    val char = rest.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = rest.takeWhile { it == char }.length
    if (length < 3) return null
    val info = rest.substring(length).trim()
    if (char == '`' && '`' in info) return null
    val isPageFence = indent == 0 && char == '`' && info.substringBefore(' ') == PGW.infoString
    return CodeFenceOpening(char, length, isPageFence)
}

/** Whether [line] closes the code fence [open]: up to three spaces, at least as many of its character, then nothing. */
private fun closesCodeFence(line: String, open: CodeFenceOpening): Boolean {
    val rest = line.trimStart(' ')
    if (line.length - rest.length > 3) return false
    val length = rest.takeWhile { it == open.char }.length
    return length >= open.length && rest.substring(length).isBlank()
}

/**
 * A widget fence read (issue #1792): its [id], its [widget] kind (null for a fence showing only its [copy]), its
 * [copy] key, its [cfactExpression], its [jumpLabel], and the kind's own [params] -- every other key of its JSON. As
 * written: whether any of it is right is [pageDocumentProblems]'s to say.
 */
class PageFence(
    val id: String?,
    val widget: String?,
    val copy: String?,
    val cfactExpression: String?,
    val jumpLabel: String?,
    val params: Map<String, Any?>,
    /** The 1-based line of the document the fence opens on. */
    val line: Int,
    /** The fence keys ([PGW.fenceKeys]) it gives a value that is not text, read as absent above. */
    val notText: Set<String> = emptySet(),
)

/**
 * [source]'s JSON read as a [PageFence], or why it cannot be: it does not parse, or is not a JSON object. The fence keys
 * are read as text where they are text; a key holding another kind of value is left for the checks to name, read as
 * absent here.
 */
fun readPageFence(source: PageFenceSource): Result<PageFence> {
    val parsed = source.body.jsonResult()
    val value = parsed.valueOrNull()
    if (value !is Map<*, *>) {
        val why = parsed.problems.firstOrNull()?.message?.let { "its JSON does not parse: $it" } ?: "its JSON is not an object"
        return Result.failure(IllegalArgumentException(why))
    }
    val map = value.toJsonMapOrEmpty()
    fun text(key: String) = (map[key] as? String)?.toOptStr()
    return Result.success(
        PageFence(
            id = text(PGW.id),
            widget = text(PGW.widget),
            copy = text(PGW.copy),
            cfactExpression = text(PGW.cfactExpression),
            jumpLabel = text(PGW.jumpLabel),
            params = map.filterKeys { it !in PGW.fenceKeys },
            line = source.line,
            notText = PGW.fenceKeys.filterTo(LinkedHashSet()) { it in map && map[it] !is String },
        ),
    )
}
