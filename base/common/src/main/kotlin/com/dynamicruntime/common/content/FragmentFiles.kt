package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.parseMarkdownFragments
import com.dynamicruntime.common.util.parseOmnibusFragments

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

/** The suffix marking a classpath fragment file as an omnibus overlay: `<id>_omnibus.md` (issue #1217). */
const val fragmentOmnibusSuffix = "_omnibus"

/**
 * The overlay layers of the **omnibus** file `md-fragments/<id>`[fragmentOmnibusSuffix]`.md` (issue #1217): one file
 * overlaying several fragment files, one layer per `# !<fileId>` section ([parseOmnibusFragments]).
 *
 * Each section is an ordinary overlay of its file, so everything that guards an overlay guards it too: a key its
 * base does not declare is an orphan, and a section naming a file no component declares is a file declared but
 * absent. Its origin is the omnibus file, so a report says which file a section came from.
 *
 * **[id] is the component's own name for the file**, and not, say, a flavor's: the resource directory is one
 * namespace across every jar, so two components shipping one name would collide, and one file would silently shadow
 * the other. The convention is `<owner><Purpose>` -- `kdrReference`. The boot refuses one id declared twice, which
 * is what such a collision looks like from here.
 *
 * Read **when declared**, unlike the other layers: the sections cannot be known without reading the file. So a
 * missing or malformed file fails the boot as it is collected, wherever it runs -- a declared resource that is
 * absent or does not parse is a packaging defect, not a piece of copy to degrade around.
 */
fun omnibusOverlay(id: String): List<FragmentSource> {
    val resourceId = "$id$fragmentOmnibusSuffix"
    val origin = omnibusOrigin(id)
    val text = ContentResources.readText(MarkdownFragmentService.resourceDir, resourceId)
        ?: throw KdrException("The omnibus fragment file '$origin' is declared but absent.")
    val sections = try {
        text.parseOmnibusFragments()
    } catch (e: KdrException) {
        throw KdrException("The omnibus fragment file '$origin' does not parse: ${e.message}", e)
    }
    return sections.map { (fileId, content) ->
        FragmentSource(fileId, isOverlay = true, client = null, origin = origin, load = { content })
    }
}

/** Where the layers of the omnibus file [id] say they came from -- also how a duplicate declaration is recognized. */
fun omnibusOrigin(id: String): String = "${MarkdownFragmentService.resourceDir}/$id$fragmentOmnibusSuffix.md"

/**
 * Omnibus files declared more than once among [sources], each as a finding (issue #1217). One file's layers repeat
 * (same origin, same file) only when its id was declared twice, which is how two components shipping the same
 * resource name look from here: both declarations read whichever copy the classpath finds first.
 */
fun duplicateOmnibusDeclarations(sources: List<FragmentSource>): List<String> =
    sources.filter { it.client == null && it.origin.endsWith("$fragmentOmnibusSuffix.md") }
        .groupBy { it.origin to it.fileId }
        .filterValues { it.size > 1 }
        .keys.map { it.first }.distinct()
        .map { "the omnibus fragment file '$it' is declared more than once" }

/** Reads and parses `md-fragments/<resourceId>.md`, or null when the resource is absent. */
private fun readFragmentResource(resourceId: String): Map<String, Map<String, String>>? =
    ContentResources.readText(MarkdownFragmentService.resourceDir, resourceId)?.parseMarkdownFragments()
