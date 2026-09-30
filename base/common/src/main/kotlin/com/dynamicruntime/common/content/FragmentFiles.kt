package com.dynamicruntime.common.content

import com.dynamicruntime.common.util.parseMarkdownFragments

/**
 * The base layers of [fileIds], each read from `md-fragments/<fileId>.md` -- the ordinary case, and what
 * every component that ships fragments declares. These are [frontend][FragmentAudience.frontend] files: the
 * content server delivers them and the frontend renders them.
 *
 * [shownOn] says where the application shows the files' copy (issue #933) -- "the home page and app bar" -- and
 * files shown in different places are declared in separate calls, each with its own. Leave it out only for a file
 * the application does not show (a fixture, a file a deployment's own frontend reads by URL): an editor then says
 * so, and an administrator is not left changing copy that appears nowhere. A file one client's configuration alone
 * reads names that client in [shownFor], so another client's editor does not offer it as shown.
 *
 * For the private counterpart -- a file that exists only to be pulled by a backend `%{@t(...)}` and is never
 * served -- use [backendFragmentFiles]. A component that ships both concatenates the two lists.
 */
fun fragmentFiles(vararg fileIds: String, shownOn: String? = null, shownFor: String? = null): List<FragmentSource> =
    baseFragmentFiles(fileIds, FragmentAudience.frontend, shownOn, shownFor)

/**
 * The base layers of [fileIds] as [backend][FragmentAudience.backend] files (issue #514): **private**
 * fragments the content server never delivers, existing only to be pulled server-side by a
 * `%{@t("fileId.namespace.key")}` in the backend `@t` pass (issue #505).
 *
 * The same `md-fragments/<fileId>.md` resource as [fragmentFiles] -- audience is a property of the file's use,
 * not its storage -- so the only difference at the declaration is which method names it. Kept a sibling rather
 * than a flag on [fragmentFiles] so the declaration reads as what it is; the all-frontend case, which is
 * almost all of them, stays exactly as it was.
 */
fun backendFragmentFiles(vararg fileIds: String, shownOn: String? = null, shownFor: String? = null): List<FragmentSource> =
    baseFragmentFiles(fileIds, FragmentAudience.backend, shownOn, shownFor)

/**
 * Base file layers for [fileIds] with a declared [audience] and [shownOn] -- for every client, or, with [shownFor],
 * for that one client alone (a file its configuration alone reads); the shared body of the two public builders.
 */
private fun baseFragmentFiles(fileIds: Array<out String>, audience: FragmentAudience, shownOn: String?, shownFor: String?): List<FragmentSource> =
    fileIds.map { fileId ->
        FragmentSource(
            fileId, isOverlay = false, client = null,
            origin = "${MarkdownFragmentService.resourceDir}/$fileId.md",
            audience = audience,
            shownOn = shownOn,
            shownFor = shownFor,
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
