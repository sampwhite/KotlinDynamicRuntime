package com.dynamicruntime.common.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers [resolveDocLink] (issue #492): how a served document's interior links are retargeted -- to another
 * in-app document, into the source repository, or left alone -- as a pure function of the link, the document's
 * source path, the doc registry, and the (optional) source-repo base.
 */
class DocLinkUtilTest : StringSpec({

    // README.md at the repo root links to two docs; a repo blob base is configured.
    val docsByPath = mapOf("README.md" to "readme", "code-guide.md" to "code-guide", "examples/x.md" to "ex")
    val base = "https://github.com/o/r/blob/main"

    // The app's in-app document href, as the frontend builds it (a same-page hash link).
    fun resolve(url: String, from: String = "README.md", repo: String? = base) =
        resolveDocLink(url, from, docsByPath, repo) { key -> "#doc=$key" }

    "a relative link to a registered document becomes an in-app link" {
        resolve("code-guide.md") shouldBe "#doc=code-guide"
        // Resolved against the current document's directory, so a subdir target still matches.
        resolve("examples/x.md") shouldBe "#doc=ex"
        // A fragment on an in-app doc link is dropped -- the app cannot scroll a fetched doc to a heading.
        resolve("code-guide.md#usage") shouldBe "#doc=code-guide"
    }

    "a relative link to an unregistered repo file goes to the source repo" {
        resolve("LICENSE") shouldBe "$base/LICENSE"
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
        resolve("./LICENSE") shouldBe "$base/LICENSE"
        // A `..` that climbs above the root cannot resolve; the link is left as written.
        resolve("../../etc/passwd") shouldBe "../../etc/passwd"
    }

    "without a configured repo base, non-document links are left as written" {
        // A registered document still resolves in-app -- that needs no repo base.
        resolve("code-guide.md", repo = null) shouldBe "#doc=code-guide"
        // A plain repo file has nowhere to go, so it is unchanged (the pre-#492 behavior, no regression).
        resolve("LICENSE", repo = null) shouldBe "LICENSE"
    }

    "an empty target stays empty" {
        resolve("") shouldBe ""
        resolve("   ") shouldBe ""
    }
})
