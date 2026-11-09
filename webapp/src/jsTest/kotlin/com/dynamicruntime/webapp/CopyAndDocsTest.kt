package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.util.MarkdownHooks
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.renderMarkdown
import com.dynamicruntime.common.util.renderMarkdownInline
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Copy and documents on the page (issue #1740 gathered these into one file): the copy lookup, a code block's copy
// button, the images components ship, document links, which documents show their own title, and the site's policies.
// Each class is the file it used to be, kept whole with its own helpers.

/**
 * Pure-logic coverage (issue #161) for [Copy], the widget-group copy lookup: `t` returns the value or the
 * given default, `opt` returns the value or null, and [Copy.empty] falls back on every lookup. Plus
 * [Copy.fragmentResolver], the frontend `@t` resolver over a delivered copy (issue #505).
 */
class CopyTest {

    private val copy = Copy(
        mapOf(
            "auth" to mapOf("title" to "Sign in", "subtitle" to "Welcome back"),
        ),
    )

    @Test
    fun tReturnsPresentValue() {
        assertEquals("Sign in", copy.t("auth", "title", "fallback"))
    }

    @Test
    fun tFallsBackWhenKeyOrNamespaceAbsent() {
        assertEquals("fallback", copy.t("auth", "missing", "fallback"))
        assertEquals("fallback", copy.t("absent", "title", "fallback"))
    }

    @Test
    fun optReturnsPresentValueOrNull() {
        assertEquals("Welcome back", copy.opt("auth", "subtitle"))
        assertNull(copy.opt("auth", "missing"))
        assertNull(copy.opt("absent", "title"))
    }

    @Test
    fun emptyCopyAlwaysFallsBack() {
        assertEquals("fallback", Copy.empty.t("auth", "title", "fallback"))
        assertNull(Copy.empty.opt("auth", "title"))
    }

    @Test
    fun fragmentResolverResolvesTwoPartKeysOverTheCopy() {
        val r = copy.fragmentResolver()
        assertEquals("Sign in", r.resolve("auth.title"))
        assertNull(r.resolve("auth.missing"))
        assertNull(r.resolve("absent.title"))
        assertNull(r.resolve("title"), "a one-part key names no value")
    }

    @Test
    fun aTemplateAtPullResolvesThroughTheResolver() {
        // The end-to-end frontend path in miniature: a `${@t("ns.key")}` string resolved against this copy.
        val rendered = $$"""Copy says: ${@t("auth.subtitle")}""".evalTemplate(emptyMap(), resolver = copy.fragmentResolver())
        assertEquals("Copy says: Welcome back", rendered)
    }
}

/** The copy button on a code block (issue #1233): which blocks get one, and what the wrapping is. */
class MarkdownCopyTest {
    private val pre = "<pre><code class=\"language-json\">{}</code></pre>"

    @Test
    fun aJsonBlockIsWrappedWithTheButtonBeforeIt() {
        val html = copyButtonWrap("json", pre)!!
        assertTrue(html.startsWith("<div class=\"$codeBlockClass\"><button type=\"button\" class=\"$copyButtonClass\""), html)
        assertTrue(html.endsWith("</button>$pre</div>"), html)
        assertTrue(html.contains("aria-label=\"Copy to clipboard\""), html)
    }

    @Test
    fun otherLanguagesAndAnUnlabelledFenceAreLeftAlone() {
        assertNull(copyButtonWrap("kotlin", pre))
        assertNull(copyButtonWrap("", pre))
    }

    @Test
    fun theRendererAppliesItThroughTheHook() {
        val html = "```json\n[]\n```".renderMarkdown(MarkdownHooks(decorateCodeBlock = ::copyButtonWrap))
        assertTrue(html.startsWith("<div class=\"$codeBlockClass\"><button"), html)
        assertTrue(html.endsWith("</code></pre></div>\n"), html)
        assertEquals("<pre><code class=\"language-kotlin\">x</code></pre>\n", "```kotlin\nx\n```".renderMarkdown(MarkdownHooks(decorateCodeBlock = ::copyButtonWrap)))
    }
}

/** The images components ship (issue #1339), as the webapp takes them from the shell config and draws them from copy. */
class ComponentImagesTest {
    private val hash = "a".repeat(64)

    @Test
    fun theShellConfigsImagesAreKeptOnlyWhenTheyAreWhatTheySay() {
        val names = componentImageNamesFrom(
            mapOf(
                "sample:diagram" to "sample:diagram:$hash",
                // Not rooted, a served name not the name's, and no hash: left out.
                "diagram" to "diagram:$hash",
                "sample:other" to "sample:diagram:$hash",
                "sample:bare" to "sample:bare",
            ),
        )
        assertEquals(mapOf("sample:diagram" to "sample:diagram:$hash"), names)
        // The home config carries them into the shell's facts.
        val facts = shellFactsOf(homeConfigFrom(UiConfig(FragmentRef("home", "b1"), emptyMap(), emptyMap(), mapOf(HFLD.images to names))))
        assertEquals(names, facts.images)
    }

    @Test
    fun everyCopySurfaceDrawsComponentImagesFromTheShellEvenOneDrawingAnotherClientsRefs() {
        val facts = ShellFacts(images = mapOf("sample:diagram" to "sample:diagram:$hash"))
        val imageAddressOf: (String) -> String = { "/st/kdr.en/img/$it" }
        val drawn = "<img src=\"/st/kdr.en/img/sample:diagram:$hash\" alt=\"D\">"
        assertEquals(drawn, "![D](img:sample:diagram)".renderMarkdownInline(markdownHooksFor(null, null, facts, imageAddressOf = imageAddressOf)))
        // The copy editor says where another client's refs are drawn from; the images are the shell's still.
        val editor = markdownHooksFor(null, { id -> "download/$id" }, facts, imageAddressOf = imageAddressOf)
        assertEquals(drawn, "![D](img:sample:diagram)".renderMarkdownInline(editor))
        // One no component ships is its alt text.
        assertEquals("N", "![N](img:sample:none)".renderMarkdownInline(editor))
    }
}

/**
 * Pure-logic coverage (issue #161) for the frontend half of document link resolution (issue #492): building the
 * repo-path -> in-app-doc map from the home links, and the resolver that turns a document's interior link into
 * an in-app `/docs/<id>` link (via the shared route builder) or a source-repo link. The path-resolution rules
 * themselves are the kernel's [com.dynamicruntime.common.util.resolveDocLink], covered on the JVM.
 */
class DocLinkResolverTest {

    private fun link(id: String, sourcePath: String) = HomeLink(id, id, id, "b", sourcePath)

    private val links = listOf(
        link("readme", "README.md"),
        link("code-guide", "code-guide.md"),
        link("blank", ""),
    )

    private val base = "https://github.com/o/r/blob/main"

    @Test
    fun docKeyByPathMapsSourcePathsToIdsSkippingBlanks() {
        assertEquals(mapOf("README.md" to "readme", "code-guide.md" to "code-guide"), docKeyByPath(links))
    }

    @Test
    fun resolverLinksRegisteredDocsInAppViaTheSharedRouteFormat() {
        val resolve = docLinkResolver("README.md", links, base)
        assertEquals("/docs/code-guide", resolve("code-guide.md"))
    }

    @Test
    fun resolverSendsUnregisteredFilesToTheSourceRepo() {
        val resolve = docLinkResolver("README.md", links, base)
        assertEquals("$base/LICENSE", resolve("LICENSE"))
    }

    @Test
    fun aLinkToAPageOfTheAppIsPutUnderTheLiveAppRoot() {
        // Issue #1371: a document spells an app page as the address bar shows it, under `/wa`, which is not a repo
        // path. Under Node there is no app root, as under the dev server, so the page's own path is what is left.
        val resolve = docLinkResolver("docs/demo-client/README.md", links, base)
        assertEquals("/test/simulations", resolve("/wa/test/simulations"))
        assertEquals("/join/confdemo", resolve("/wa/join/confdemo"))
        // A repo file that merely starts with the same letters is still a repo file.
        assertEquals("$base/wax.md", resolve("/wax.md"))
    }

    @Test
    fun resolverCarriesASectionIntoTheInAppLink() {
        // Issue #1136: the heading a link names reaches the documents page, which scrolls to it.
        val resolve = docLinkResolver("README.md", links, base)
        assertEquals("/docs/code-guide#naming", resolve("code-guide.md#naming"))
    }

    @Test
    fun formCopyResolvesDocumentLinksFromTheRepoRoot() {
        // Issue #1136: form copy belongs to no document, so its links resolve from the repo root -- written with a
        // leading slash or without one -- and an unlisted document resolves like any other.
        val suite = links + HomeLink("demo-client-review", "Review", "demo-client-review", "b", "docs/demo-client/review.md", listed = false)
        val resolve = ShellFacts(docs = suite, sourceRepoBase = base).copyLinkResolver()
        assertEquals("/docs/demo-client-review", resolve("/docs/demo-client/review.md"))
        assertEquals("/docs/demo-client-review#tasks", resolve("docs/demo-client/review.md#tasks"))
        assertEquals("https://example.com/x", resolve("https://example.com/x"))
        // Before the shell has been told, a document link has nowhere to go, and is drawn as its text (issue #1380).
        assertEquals("", ShellFacts().copyLinkResolver()("/docs/demo-client/review.md"))
    }

    @Test
    fun resolverWithoutABaseHasNowhereForRepoFilesButStillLinksDocs() {
        // Issue #1380: empty, which the renderer draws as the link's text.
        val resolve = docLinkResolver("README.md", links, null)
        assertEquals("", resolve("LICENSE"))
        assertEquals("/docs/code-guide", resolve("code-guide.md"))
        assertEquals("/test/simulations", resolve("/wa/test/simulations"))
    }

    @Test
    fun aLinkToARepoPathAComponentImageIsKeptAtDrawsThatImage() {
        // Issue #1463: one plain image shows on GitHub from the file and in the app from the image served from it.
        val names = mapOf("kdn:part1" to "kdn:part1:abc", "kdn:part2" to "kdn:part2:def")
        val paths = mapOf("docs/images/part1.svg" to "kdn:part1", "docs/images/gone.svg" to "kdn:gone")
        val addresses = componentImageAddressByPath(paths, names) { "/st/img/$it" }
        // A path naming an image not served is left out, so it resolves as any repo file's would.
        assertEquals(mapOf("docs/images/part1.svg" to "/st/img/kdn:part1:abc"), addresses)
        val resolve = docLinkResolver("docs/walk-through.md", links, base, addresses)
        assertEquals("/st/img/kdn:part1:abc", resolve("images/part1.svg"))
        assertEquals("$base/docs/images/gone.svg", resolve("images/gone.svg"))
        // Drawn by the renderer as the image, at its served address.
        assertContains("![Part 1](images/part1.svg)".renderMarkdown(resolve), "<img src=\"/st/img/kdn:part1:abc\" alt=\"Part 1\"")
        // Without a source repository too: the image is the app's own.
        assertEquals("/st/img/kdn:part1:abc", docLinkResolver("docs/walk-through.md", links, null, addresses)("images/part1.svg"))
    }

    @Test
    fun imagePathsAreReadAsTheShellConfigServesThem() {
        assertEquals(
            mapOf("docs/images/part1.svg" to "kdn:part1"),
            componentImagePathsFrom(mapOf("docs/images/part1.svg" to "kdn:part1", "docs/images/odd.svg" to 3)),
        )
        assertEquals(emptyMap(), componentImagePathsFrom(null))
    }
}

/** Which documents show their own title (issue #1238). */
class DocTitleTest {
    @Test
    fun aLeadingLevelOneHeadingIsTheTitle() {
        assertTrue(docHasOwnTitle("# Reporting\n\nText."))
        assertTrue(docHasOwnTitle("\n<!-- a note -->\n# Reporting\n"))
        assertTrue(docHasOwnTitle("<!-- a note\n     over lines -->\n\n# Reporting\n"))
        // A navigation line before the heading, as the demo's pages have.
        assertTrue(docHasOwnTitle("[← Back](a.md) · [Contents](b.md)\n\n# Reports\n"))
    }

    @Test
    fun anythingElseLeavesTheLabelAsTheTitle() {
        assertFalse(docHasOwnTitle("MIT License\n\nCopyright"))
        assertFalse(docHasOwnTitle("## Overview\n"))
        assertFalse(docHasOwnTitle("#Heading without a space"))
        assertFalse(docHasOwnTitle(""))
        assertFalse(docHasOwnTitle("<!-- never closed\n# Reporting\n"))
        // A heading inside a code fence is not a heading.
        assertFalse(docHasOwnTitle("```\n# not a heading\n```\n## Overview\n"))
    }
}

/** A deployment's privacy policy and terms (issue #1711): carried from the shell config, and made into links. */
class SitePoliciesTest {
    private fun factsFrom(state: Map<String, Any?>) = shellFactsOf(homeConfigFrom(UiConfig(FragmentRef("home", "b1"), emptyMap(), emptyMap(), state)))

    @Test
    fun theShellConfigsPoliciesBecomeLinksToTheirDocumentPagesPrivacyFirst() {
        val facts = factsFrom(mapOf(HFLD.privacyPolicy to "site-privacy", HFLD.termsOfService to "site-terms"))
        val links = sitePolicyLinks(facts)
        assertEquals(listOf(SITEW.privacyPolicy, SITEW.termsOfService), links.map { it.label })
        assertEquals(listOf(docHref("site-privacy"), docHref("site-terms")), links.map { it.href })
    }

    @Test
    fun aDeploymentWithOneOrNoneLinksOnlyWhatItHas() {
        assertEquals(listOf(SITEW.termsOfService), sitePolicyLinks(factsFrom(mapOf(HFLD.termsOfService to "site-terms"))).map { it.label })
        assertTrue(sitePolicyLinks(factsFrom(emptyMap())).isEmpty())
        // Before the shell has been told, as on a page's first render: nothing to draw.
        assertTrue(sitePolicyLinks(ShellFacts()).isEmpty())
        assertTrue(sitePolicyLinks(factsFrom(mapOf(HFLD.privacyPolicy to ""))).isEmpty())
    }
}
