package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The endpoint-catalog filters (issue #489): the tag options a page offers, and how the publicApi toggle and a
 * multi-tag OR selection narrow the list. Pure -- lists of endpoints in, a filtered list out, no React.
 */
class CatalogFilterTest {

    private fun ep(path: String, publicApi: Boolean = false, tags: List<String> = emptyList()) =
        EndpointInfo(path, "GET", "general", "ns", null, emptyMap(), emptyMap(), publicApi, tags)

    private val endpoints = listOf(
        ep("/health", tags = listOf("internal")),
        ep("/auth/self/info", publicApi = true, tags = listOf("frontend")),
        ep("/auth/ui/config", tags = listOf("frontend")),
        ep("/gedra/formDoc/create", publicApi = true),
        ep("/demo/schema/sample", tags = listOf("demo", "schema")),
    )

    @Test
    fun availableTagsAreTheDistinctTagsSorted() {
        assertEquals(listOf("demo", "frontend", "internal", "schema"), availableTags(endpoints))
    }

    @Test
    fun noFiltersKeepsEverything() {
        assertEquals(endpoints, filterEndpoints(endpoints, publicOnly = false, selectedTags = emptySet()))
    }

    @Test
    fun publicOnlyKeepsOnlyPublishedEndpoints() {
        assertEquals(
            listOf("/auth/self/info", "/gedra/formDoc/create"),
            filterEndpoints(endpoints, publicOnly = true, selectedTags = emptySet()).map { it.path },
        )
    }

    @Test
    fun tagFilterIsOrAcrossTags() {
        // internal OR demo: /health and /demo/schema/sample, not the frontend-only or untagged ones.
        assertEquals(
            listOf("/health", "/demo/schema/sample"),
            filterEndpoints(endpoints, publicOnly = false, selectedTags = setOf("internal", "demo")).map { it.path },
        )
    }

    @Test
    fun publicOnlyAndTagsCombineWithAnd() {
        // Published AND carrying the frontend tag: only /auth/self/info (the gedra one is published but untagged).
        assertEquals(
            listOf("/auth/self/info"),
            filterEndpoints(endpoints, publicOnly = true, selectedTags = setOf("frontend")).map { it.path },
        )
    }
}
