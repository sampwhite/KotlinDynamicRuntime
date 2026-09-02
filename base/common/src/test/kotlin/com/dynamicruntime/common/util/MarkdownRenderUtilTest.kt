package com.dynamicruntime.common.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Covers [renderMarkdown]: the block and inline constructs our copy and documents use, and the escaping /
 * URL rules that keep rendered content from injecting markup.
 */
class MarkdownRenderUtilTest : StringSpec({

    "renders ATX headings at their level, ignoring a closing hash run" {
        // Each heading carries a slug id, so a same-document `#anchor` link can target it (issue #492).
        "# Title".renderMarkdown() shouldBe "<h1 id=\"title\">Title</h1>\n"
        "### Sub ###".renderMarkdown() shouldBe "<h3 id=\"sub\">Sub</h3>\n"
        // Seven hashes is not a heading, and `#foo` (no space) is ordinary text.
        "####### Nope".renderMarkdown() shouldContain "<p>"
        "#NoSpace".renderMarkdown() shouldBe "<p>#NoSpace</p>\n"
    }

    "gives a heading a github-style slug id matching an authored anchor (issue #492)" {
        // Spaces to hyphens, punctuation and emphasis markers dropped, lower-cased -- so the id lines up with
        // the `#the-shape` / `#soft-validation...` links a table of contents already carries.
        headingSlug("The shape") shouldBe "the-shape"
        headingSlug("Soft validation, and the trap next to it") shouldBe "soft-validation-and-the-trap-next-to-it"
        headingSlug("A **bold** word") shouldBe "a-bold-word"
        "## The **shape**".renderMarkdown() shouldContain "id=\"the-shape\""
        // A heading that slugs to nothing gets no id rather than an empty one.
        "## ...".renderMarkdown() shouldBe "<h2>...</h2>\n"
    }

    "joins wrapped lines into one paragraph and separates on a blank line" {
        "one\ntwo\n\nthree".renderMarkdown() shouldBe "<p>one two</p>\n<p>three</p>\n"
    }

    "renders inline code, bold, italic, and links" {
        "a `x = 1` b".renderMarkdown() shouldBe "<p>a <code>x = 1</code> b</p>\n"
        "**bold** and *it*".renderMarkdown() shouldBe "<p><strong>bold</strong> and <em>it</em></p>\n"
        "see [docs](code-guide.md)".renderMarkdown() shouldBe "<p>see <a href=\"code-guide.md\">docs</a></p>\n"
    }

    "leaves an intraword underscore alone" {
        // Emphasis must not mangle identifiers like KDR_WORKSPACE_DIR.
        "KDR_WORKSPACE_DIR is set".renderMarkdown() shouldBe "<p>KDR_WORKSPACE_DIR is set</p>\n"
        "_real_ emphasis".renderMarkdown() shouldBe "<p><em>real</em> emphasis</p>\n"
    }

    "does not treat markup inside a code span as inline constructs" {
        "`**not bold**`".renderMarkdown() shouldBe "<p><code>**not bold**</code></p>\n"
    }

    "renders a fenced code block verbatim, with its language" {
        val md = "```bash\n./gradlew build\n**not bold**\n```"
        val html = md.renderMarkdown()
        html shouldContain "<pre><code class=\"language-bash\">"
        html shouldContain "./gradlew build\n**not bold**"
        html shouldNotContain "<strong>"
    }

    "renders flat bullet and ordered lists" {
        "- one\n- two".renderMarkdown() shouldBe "<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n"
        "1. one\n2. two".renderMarkdown() shouldBe "<ol>\n<li>one</li>\n<li>two</li>\n</ol>\n"
    }

    "renders blockquotes and horizontal rules" {
        "> quoted".renderMarkdown() shouldBe "<blockquote>quoted</blockquote>\n"
        "---".renderMarkdown() shouldBe "<hr/>\n"
        // A rule wins over a bullet item for `* * *`-style input.
        "***".renderMarkdown() shouldBe "<hr/>\n"
    }

    "escapes HTML rather than passing it through" {
        "<script>alert('x')</script>".renderMarkdown() shouldContain "&lt;script&gt;"
        "<script>alert('x')</script>".renderMarkdown() shouldNotContain "<script>"
        "a & b".renderMarkdown() shouldContain "a &amp; b"
    }

    "renders a javascript: link inert but keeps its text" {
        val html = "[click](javascript:alert(1))".renderMarkdown()
        html shouldContain ">click</a>"
        html shouldNotContain "javascript:"
        // Relative, http(s), and mailto targets are kept.
        "[a](/docs/x.md)".renderMarkdown() shouldContain "href=\"/docs/x.md\""
        "[a](https://example.com)".renderMarkdown() shouldContain "href=\"https://example.com\""
        "[a](mailto:x@y.z)".renderMarkdown() shouldContain "href=\"mailto:x@y.z\""
    }

    "renders emphasis nested inside a link label" {
        "[**b**](x.md)".renderMarkdown() shouldBe "<p><a href=\"x.md\"><strong>b</strong></a></p>\n"
    }

    "leaves an unclosed construct as literal text" {
        "a * b".renderMarkdown() shouldBe "<p>a * b</p>\n"
        "an [unclosed link".renderMarkdown() shouldBe "<p>an [unclosed link</p>\n"
        "a ` tick".renderMarkdown() shouldBe "<p>a ` tick</p>\n"
    }

    "applies a link resolver to targets, then still runs safeUrl on the result (issue #492)" {
        // The resolver rewrites the target; unrelated text and other inline constructs are unaffected.
        val upper = { url: String -> "https://x/" + url.uppercase() }
        "see [d](code-guide.md)".renderMarkdown(upper) shouldBe
            "<p>see <a href=\"https://x/CODE-GUIDE.MD\">d</a></p>\n"
        // The resolver runs at every link site, including inside a list item.
        "- [a](x)\n- [b](y)".renderMarkdown(upper) shouldContain "href=\"https://x/X\""
        // safeUrl still guards the resolver's output, so a resolver cannot introduce an executable scheme.
        val toJs = { _: String -> "javascript:alert(1)" }
        val html = "[click](anything.md)".renderMarkdown(toJs)
        html shouldContain ">click</a>"
        html shouldNotContain "javascript:"
    }

    // --- tables (issue #547) ------------------------------------------------------------------------------

    "renders a github-style pipe table with a header and body rows" {
        val md = """
            | a | b |
            | --- | --- |
            | 1 | 2 |
            | 3 | 4 |
        """.trimIndent()
        val html = md.renderMarkdown()
        html shouldContain "<table>"
        html shouldContain "<thead>"
        html shouldContain "<th>a</th>"
        html shouldContain "<th>b</th>"
        html shouldContain "<tbody>"
        html shouldContain "<td>1</td>"
        html shouldContain "<td>4</td>"
        // Wrapped in the scroll box so a wide table scrolls inside the page.
        html shouldContain "md-table-scroll"
        // Not the pre-#547 failure mode: the delimiter row must not survive as text.
        html shouldNotContain "---"
        html shouldNotContain "<p>| a"
    }

    "works without outer pipes" {
        val md = """
            h1 | h2
            --- | ---
            x | y
        """.trimIndent()
        val html = md.renderMarkdown()
        html shouldContain "<th>h1</th>"
        html shouldContain "<td>y</td>"
    }

    "takes column alignment from the delimiter row's colons" {
        val md = """
            | l | c | r |
            | :-- | :-: | --: |
            | 1 | 2 | 3 |
        """.trimIndent()
        val html = md.renderMarkdown()
        html shouldContain "<th style=\"text-align:left\">l</th>"
        html shouldContain "<th style=\"text-align:center\">c</th>"
        html shouldContain "<th style=\"text-align:right\">r</th>"
        // The alignment carries to the body cells of the same column.
        html shouldContain "<td style=\"text-align:right\">3</td>"
        // A plain `---` column gets no alignment style.
        val plain = """
            | a |
            | --- |
            | x |
        """.trimIndent().renderMarkdown()
        plain shouldContain "<th>a</th>"
    }

    "pads a short row and truncates a long one rather than throwing" {
        val md = """
            | a | b | c |
            | - | - | - |
            | 1 |
            | 1 | 2 | 3 | 4 |
        """.trimIndent()
        val html = md.renderMarkdown()
        // Short row: missing cells synthesized empty, so an empty <td> appears.
        html shouldContain "<td></td>"
        // Long row: the fourth cell is dropped, never rendered.
        html shouldNotContain "<td>4</td>"
    }

    "runs inline constructs inside a cell, and honours an escaped pipe" {
        val md = """
            | name | note |
            | --- | --- |
            | **id** | a `x` and [d](x.md) |
        """.trimIndent()
        val html = md.renderMarkdown()
        html shouldContain "<td><strong>id</strong></td>"
        html shouldContain "<code>x</code>"
        html shouldContain "href=\"x.md\""
        // A `\|` inside a cell is a literal pipe, not a column separator (raw string: `\|` is two chars).
        val esc = """
            | a | b |
            | --- | --- |
            | one \| two | three |
        """.trimIndent().renderMarkdown()
        esc shouldContain "<td>one | two</td>"
        esc shouldContain "<td>three</td>"
    }

    "leaves a paragraph that merely contains a pipe as a paragraph (the regression that matters)" {
        // No delimiter row follows, so this is prose, not a table.
        "a | b | c".renderMarkdown() shouldBe "<p>a | b | c</p>\n"
        // A header-like line with no delimiter under it stays a paragraph too.
        val noDelim = """
            | x | y |
            plain text
        """.trimIndent().renderMarkdown()
        noDelim shouldContain "<p>| x | y |"
        // A bare `---` rule is still a horizontal rule, not a one-column table delimiter.
        val rule = """
            text

            ---
        """.trimIndent().renderMarkdown()
        rule shouldContain "<hr/>"
    }

    "a table can interrupt a paragraph and follow a heading" {
        val afterPara = """
            intro line
            | a | b |
            | --- | --- |
            | 1 | 2 |
        """.trimIndent().renderMarkdown()
        afterPara shouldContain "<p>intro line</p>"
        afterPara shouldContain "<table>"
        val afterHeading = """
            ## Title
            | a | b |
            | --- | --- |
            | 1 | 2 |
        """.trimIndent().renderMarkdown()
        afterHeading shouldContain "</h2>"
        afterHeading shouldContain "<table>"
    }
})
