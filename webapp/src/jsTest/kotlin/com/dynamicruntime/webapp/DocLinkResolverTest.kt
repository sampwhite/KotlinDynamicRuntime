package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

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
        // Before the shell has been told, a document link is a repo link, or left as written with no repo configured.
        assertEquals("/docs/demo-client/review.md", ShellFacts().copyLinkResolver()("/docs/demo-client/review.md"))
    }

    @Test
    fun resolverWithoutABaseLeavesRepoFilesAsWrittenButStillLinksDocs() {
        val resolve = docLinkResolver("README.md", links, null)
        assertEquals("LICENSE", resolve("LICENSE"))
        assertEquals("/docs/code-guide", resolve("code-guide.md"))
    }
}
