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
        "# Title".renderMarkdown() shouldBe "<h1>Title</h1>\n"
        "### Sub ###".renderMarkdown() shouldBe "<h3>Sub</h3>\n"
        // Seven hashes is not a heading, and `#foo` (no space) is ordinary text.
        "####### Nope".renderMarkdown() shouldContain "<p>"
        "#NoSpace".renderMarkdown() shouldBe "<p>#NoSpace</p>\n"
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
})
