package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #161) for the frontend half of document link resolution (issue #492): building the
 * repo-path -> in-app-doc map from the home links, and the resolver that turns a document's interior link into
 * an in-app `#page=docs&doc=<id>` link (via the shared hash builder) or a source-repo link. The path-resolution rules
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
    fun resolverLinksRegisteredDocsInAppViaTheSharedHashFormat() {
        val resolve = docLinkResolver("README.md", links, base)
        assertEquals("#page=docs&doc=code-guide", resolve("code-guide.md"))
    }

    @Test
    fun resolverSendsUnregisteredFilesToTheSourceRepo() {
        val resolve = docLinkResolver("README.md", links, base)
        assertEquals("$base/LICENSE", resolve("LICENSE"))
    }

    @Test
    fun resolverWithoutABaseLeavesRepoFilesAsWrittenButStillLinksDocs() {
        val resolve = docLinkResolver("README.md", links, null)
        assertEquals("LICENSE", resolve("LICENSE"))
        assertEquals("#page=docs&doc=code-guide", resolve("code-guide.md"))
    }
}
