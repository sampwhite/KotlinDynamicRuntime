package com.dynamicruntime.common.content

import com.dynamicruntime.common.util.parseMarkdownFragments

/**
 * The base layers of [fileIds], each read from `md-fragments/<fileId>.md` -- the ordinary case, and what
 * every component that ships fragments declares.
 */
fun fragmentFiles(vararg fileIds: String): List<FragmentSource> = fileIds.map { fileId ->
    FragmentSource(
        fileId, isOverlay = false, client = null,
        origin = "${MarkdownFragmentService.resourceDir}/$fileId.md",
        load = { readFragmentResource(fileId) },
    )
}

/**
 * An overlay layer read from `md-fragments/<fileId>`[fragmentOverlaySuffix]`.md`.
 *
 * The caller names the **fileId it is overlaying**, and the suffix is applied here rather than written at the
 * declaration. Two reasons: the declaration then says what it means ("I overlay `home`") instead of naming a
 * second file that happens to be related, and the relationship stays machine-readable, which is what lets the
 * check report an overlay whose base no longer has the key.
 */
fun fragmentOverlayFile(fileId: String): FragmentSource {
    val resourceId = "$fileId$fragmentOverlaySuffix"
    return FragmentSource(
        fileId, isOverlay = true, client = null,
        origin = "${MarkdownFragmentService.resourceDir}/$resourceId.md",
        load = { readFragmentResource(resourceId) },
    )
}

/** Reads and parses `md-fragments/<resourceId>.md`, or null when the resource is absent. */
private fun readFragmentResource(resourceId: String): Map<String, Map<String, String>>? =
    ContentResources.readText(MarkdownFragmentService.resourceDir, resourceId)?.parseMarkdownFragments()
