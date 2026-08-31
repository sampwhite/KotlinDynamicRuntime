package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder

/**
 * The shared shape of a **UI-config** response (issue #70): the "construction manifest" a frontend
 * widget-group fetches to build itself. Every UI-config endpoint returns the same envelope --
 * `{ fragments, features, state }` -- so the shape is consistent and generatable, while `features` and
 * `state` stay specific to each group (declared per endpoint):
 *
 *  - `fragments` -- the Markdown fragment files this group draws its copy from, each as a `fileId` plus a
 *    cache-busting `buildId`. The frontend fetches each at `/st/<appId>/md/<fileId:buildId>` (see
 *    [MarkdownFragmentService]). This is how a component learns its `fileId:buildId`.
 *  - `features` -- which optional capabilities are enabled for this group (per-group boolean flags).
 *  - `state` -- the dynamic state the group needs to construct itself (e.g., the caller's user info).
 *
 * These calls are intentionally inexpensive and re-fetched on navigation, so a page composing several groups
 * (nav + auth, say) firing several small config calls is the intended shape, not a cost.
 *
 * The envelope's JSON keys live in [UIC] (in the KMP kernel, so the frontend shares them).
 */

/**
 * Builds the `fragments` list for a UI-config payload: each of [fileIds] with the build id naming the content
 * **this caller** will be served. A missing fragment file is a deployment/packaging error (these are core
 * resources), so it throws rather than emitting a ref the frontend cannot fetch.
 *
 * Takes [cxt] since issue #456, and that is the load-bearing part rather than a signature tidy-up: a build id
 * now names *merged* content, which a client's overlays change. Handing every caller the same id would leave
 * two clients fetching one URL for two different documents, and the permanent `Cache-Control` on that
 * response would let whichever was cached first be served to both.
 */
fun fragmentRefs(cxt: KdrCxt, vararg fileIds: String): List<Map<String, Any?>> = fileIds.map { fileId ->
    val buildId = MarkdownFragmentService.fragmentBuildId(cxt, fileId) ?: run {
        // Two different deployment errors, and the message has to tell them apart. A **backend** file has no
        // build id on purpose (issue #514) -- it is never delivered -- so a UI-config naming one is an audience
        // mistake, not a missing resource: either this config names a private file, or some other component
        // declared the same fileId as backend and took the whole file private. "Is not available" would send
        // the reader hunting for a file that is sitting right there, fully loaded. The boot check reports the
        // second case at its cause (`audienceConflict`); this is what the reader sees if it reaches here.
        val effective = MarkdownFragmentService.get(cxt).effectiveFragments(cxt, fileId)
        val why = if (effective != null && effective.found && effective.audience == FragmentAudience.backend) {
            "is a backend (private) fragment file, which is never delivered to a frontend"
        } else {
            "is not available"
        }
        throw KdrException("Markdown fragment file '$fileId' $why.", code = EXC.internalError)
    }
    mapOf(UIC.fileId to fileId, UIC.buildId to buildId)
}

/**
 * Declares the standard `fragments` property (an array of `{ fileId, buildId }`) on a UI-config output type,
 * so every group's config type carries the identical fragments block without repeating the schema.
 */
fun SchTypeBuilder.uiFragmentsProperty() {
    property(UIC.fragments, "The Markdown fragment files this widget group's copy is drawn from.", required = true) {
        type = SCT.array
        items {
            type = SCT.kObject
            property(UIC.fileId, "The fragment file id (fetched at /st/<appId>/md/<fileId:buildId>).", required = true)
            property(UIC.buildId, "Cache-busting content hash for the fragment file.", required = true)
        }
    }
}
