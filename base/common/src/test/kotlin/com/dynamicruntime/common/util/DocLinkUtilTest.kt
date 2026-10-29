package com.dynamicruntime.common.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers [resolveDocLink] (issue #492): how a served document's interior links are retargeted -- to another
 * in-app document, into the source repository, or left alone -- as a pure function of the link, the document's
 * source path, the doc registry, and the (optional) source-repo base.
 */
class DocLinkUtilTest : StringSpec({

    // README.md at the repo root links to several docs -- including the extension-less LICENSE, served in-app
    // as an ordinary document (issue #555); a repo blob base is configured.
    val docsByPath = mapOf(
        "README.md" to "readme", "code-guide.md" to "code-guide", "examples/x.md" to "ex", "LICENSE" to "license",
        "docs/demo-client/review.md" to "demo-client-review",
    )
    val base = "https://github.com/o/r/blob/main"
    // An image a component ships, kept in the repo at this path (issue #1463).
    val imagesByPath = mapOf("docs/images/part1.svg" to "/st/app/img/kdn:part1:abc")

    // The app's in-app document href, as the frontend builds it (a same-page hash link), with the section a link
    // names (issue #1136).
    fun resolve(url: String, from: String = "README.md", repo: String? = base) =
        resolveDocLink(url, from, docsByPath, repo, imagesByPath) { key, anchor -> "#doc=$key" + (anchor?.let { "&section=$it" } ?: "") }

    "a relative link to a registered document becomes an in-app link" {
        resolve("code-guide.md") shouldBe "#doc=code-guide"
        // Resolved against the current document's directory, so a subdir target still matches.
        resolve("examples/x.md") shouldBe "#doc=ex"
        // A fragment on an in-app doc link is carried as the section to scroll to (issue #1136); an empty one is none.
        resolve("code-guide.md#usage") shouldBe "#doc=code-guide&section=usage"
        resolve("code-guide.md#") shouldBe "#doc=code-guide"
        // The README's `[MIT](LICENSE)` link: an extension-less registered document resolves in-app (issue #555).
        resolve("LICENSE") shouldBe "#doc=license"
    }

    "a relative path to an image a component ships becomes its served address, with or without a source repo" {
        resolve("images/part1.svg", from = "docs/walk-through.md") shouldBe "/st/app/img/kdn:part1:abc"
        resolve("/docs/images/part1.svg") shouldBe "/st/app/img/kdn:part1:abc"
        resolve("docs/images/part1.svg", repo = null) shouldBe "/st/app/img/kdn:part1:abc"
        // Another image in the same directory is a repo file like any other.
        resolve("docs/images/part2.svg") shouldBe "$base/docs/images/part2.svg"
    }

    "a relative link to an unregistered repo file goes to the source repo" {
        resolve("CHANGELOG") shouldBe "$base/CHANGELOG"
        resolve("examples/settings.gradle.kts.example") shouldBe "$base/examples/settings.gradle.kts.example"
        // A fragment/anchor on a repo file is preserved.
        resolve("deferred-work.md#a-heading") shouldBe "$base/deferred-work.md#a-heading"
    }

    "a same-document anchor is left as written, to scroll in-page" {
        // It targets a heading id renderMarkdown emits; the Markdown component scrolls to it. Unchanged whether
        // or not a repo base is configured -- a doc's table of contents stays in the doc.
        resolve("#configuration") shouldBe "#configuration"
        resolve("#top", from = "gedra-entry.md") shouldBe "#top"
        resolve("#the-shape", repo = null) shouldBe "#the-shape"
    }

    "an absolute or scheme-bearing link is left untouched" {
        resolve("https://example.com/a") shouldBe "https://example.com/a"
        resolve("mailto:x@y.z") shouldBe "mailto:x@y.z"
        resolve("//cdn.example.com/x") shouldBe "//cdn.example.com/x"
    }

    "relative paths normalize . and .. against the document's directory" {
        // From a doc one level down, `..` climbs back to the root where code-guide.md lives.
        resolve("../code-guide.md", from = "examples/guide.md") shouldBe "#doc=code-guide"
        resolve("./CHANGELOG") shouldBe "$base/CHANGELOG"
        // A `..` that climbs above the root cannot resolve, so it has nowhere to go: empty, drawn as its text.
        resolve("../../etc/passwd") shouldBe ""
    }

    "without a repo base, a non-document link has nowhere to go" {
        // A registered document still resolves in-app -- that needs no repo base.
        resolve("code-guide.md", repo = null) shouldBe "#doc=code-guide"
        // A plain repo file resolves to empty, which the renderer draws as the link's text (issue #1380): left as
        // written, it would resolve against the app's own path and open a page that does not exist.
        resolve("CHANGELOG", repo = null) shouldBe ""
        resolve("examples/custom-config.md#branding-the-webapp", repo = null) shouldBe ""
        // What needs no repo is unaffected.
        resolve("https://example.com/a", repo = null) shouldBe "https://example.com/a"
    }

    "a link with nowhere to go is drawn as its text, and an image as its alt text" {
        val hooks = MarkdownHooks(resolveUrl = { resolve(it, repo = null) })
        "See [the example](examples/a.kts) and [the guide](code-guide.md).".renderMarkdown(hooks) shouldBe
            "<p>See the example and <a href=\"#doc=code-guide\">the guide</a>.</p>\n"
        "A [`code` span](examples/a.kts).".renderMarkdown(hooks) shouldBe "<p>A <code>code</code> span.</p>\n"
        "![A diagram](examples/d.png)".renderMarkdown(hooks) shouldBe "<p>A diagram</p>\n"
        // With no resolver at all an empty target is still a link, as written.
        "[x]()".renderMarkdown() shouldBe "<p><a href=\"\">x</a></p>\n"
    }

    // Form copy has no document of its own (issue #1136): it names a document by its path from the repo root, which
    // a leading `/` says whatever the current path is.
    "a link from the repo root resolves from any page, form copy included" {
        resolve("/docs/demo-client/review.md", from = "") shouldBe "#doc=demo-client-review"
        resolve("/docs/demo-client/review.md#tasks", from = "") shouldBe "#doc=demo-client-review&section=tasks"
        resolve("/code-guide.md", from = "examples/guide.md") shouldBe "#doc=code-guide"
    }

    "an empty target stays empty" {
        resolve("") shouldBe ""
        resolve("   ") shouldBe ""
    }
})
