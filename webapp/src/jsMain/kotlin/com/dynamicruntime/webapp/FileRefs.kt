package com.dynamicruntime.webapp

import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.joinAsPhrase
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WAS

/**
 * A client's file refs on its page (issue #1176): the named images its configuration holds, which its
 * administrator lists, uploads, names where the deployment keeps one, and removes (`FileRefsSection`).
 *
 * Everything here is pure, and covered under `jsNodeTest`: how the listing is read, and every sentence the section
 * says. Where a ref's file is, is its `source` -- uploaded into the file store, shipped with the client as a file
 * on the server, or (issue #1164) a file the deployment keeps for the client -- and the wording follows it.
 */

/** One file ref as the listing answers it. [file] is the record of its file, absent when the file is [missing]. */
class FileRefView(
    val refId: String,
    /** Where its file is: one of `FREF.sources`. */
    val source: String,
    /** For a file on the server: the root it is under, and its path there. */
    val root: String?,
    val path: String?,
    /** The hash of its bytes, and `<refId>:<hash>`, which its address carries; both absent when there are no bytes. */
    val hash: String?,
    val name: String?,
    val description: String?,
    /** The configuration holding its entry. */
    val configName: String?,
    /** Whether the client's own people are served it as listed; false for a draft only the sandbox shows. */
    val live: Boolean,
    /** Whether a stored configuration holds it. One that is not was shipped with the client, and cannot be removed. */
    val stored: Boolean,
    val missing: Boolean,
    val file: WebAssetFile?,
    val uploadedByName: String?,
)

/** The listing's items as refs; one without an id is not one. */
fun parseFileRefs(items: List<Map<String, Any?>>): List<FileRefView> = items.mapNotNull(::parseFileRef)

/** One listed ref, or null for an item with no id. */
fun parseFileRef(item: Map<String, Any?>): FileRefView? {
    val refId = item[CCT.refId].toOptStr() ?: return null
    return FileRefView(
        refId = refId,
        // A listing from before a ref said where its file is names only uploads.
        source = item[CCT.source].toOptStr() ?: FREF.sourceStore,
        root = item[CCT.root].toOptStr(),
        path = item[CCT.path].toOptStr(),
        hash = item[CCT.hash].toOptStr(),
        name = item[FREF.name].toOptStr(),
        description = item[CCT.description].toOptStr(),
        configName = item[FREF.configName].toOptStr(),
        live = item[FREF.live] == true,
        // Absent from a backend that does not say: an upload is stored, and nothing else can be told to be.
        stored = (item[FREF.stored] as? Boolean) ?: (item[CCT.source].toOptStr() != FREF.sourceLocal),
        missing = item[FREF.missing] == true,
        file = storedFileFrom(item[FREF.file]),
        uploadedByName = item[FREF.uploadedByName].toOptStr(),
    )
}

/** What a change -- an upload, a naming, a removal -- answered: where it landed, how, and the ref as it now is. */
class FileRefChange(
    val refId: String,
    val configName: String,
    /** `EDM.live`, or `EDM.draft` for a client with a sandbox. */
    val mode: String,
    /** The ref as its administrator is now shown it; null once it is gone. */
    val ref: FileRefView?,
)

/** A change's `results`; the mode is live from a backend that says none. */
fun parseFileRefChange(results: Map<String, Any?>): FileRefChange = FileRefChange(
    refId = results[CCT.refId].toOptStr().orEmpty(),
    configName = results[FREF.configName].toOptStr().orEmpty(),
    mode = results[FREF.mode].toOptStr() ?: EDM.live,
    ref = results[FREF.ref].toJsonMapOrEmpty().takeIf { it.isNotEmpty() }?.let(::parseFileRef),
)

/** What a client's file refs take, as the backend says it: so the page offers what will be accepted. */
class FileRefTakes(
    val maxSize: Long,
    /** The content types a file picker should offer. */
    val mimeTypes: List<String>,
    /** The kinds of image, as a person names them. */
    val kinds: List<String>,
    /** How many refs a client is served. */
    val refLimit: Int,
    /** The roots open to clients, where a file may be named and not uploaded. Empty on most deployments. */
    val roots: List<String>,
)

fun parseFileRefTakes(item: Map<String, Any?>): FileRefTakes = FileRefTakes(
    maxSize = (item[WAS.maxSize] as? Number)?.toLong() ?: 0L,
    mimeTypes = item[WAS.mimeTypes].toJsonListOfStrings(),
    kinds = item[WAS.kinds].toJsonListOfStrings(),
    refLimit = (item[FREF.refLimit] as? Number)?.toInt() ?: 0,
    roots = item[FREF.roots].toJsonListOfStrings(),
)

/**
 * What the section says above its list: what a file ref is, and how a change to one takes effect. Live at once
 * for a client that runs its latest configuration; a draft for one with a sandbox ([hasSandbox]) -- unlike a web
 * asset, which is never a draft -- and for a sandbox's own page ([sandboxOf] is its parent), where what is listed
 * is the parent's latest.
 */
fun fileRefsIntro(sandboxOf: String?, hasSandbox: Boolean): String {
    val what = "Named images this client's configuration holds, each served at an address of its own."
    return when {
        sandboxOf != null ->
            "The files $sandboxOf's sandbox shows: $sandboxOf's latest configuration, drafts included. A change " +
                "made here is a draft of $sandboxOf's, and goes live for its people once that is published."
        hasSandbox ->
            "$what This client has a sandbox, so a change here is a draft: the sandbox shows it, and it goes live " +
                "for the client's people once its configuration is published."
        else -> "$what A change takes effect at once for everyone in the client."
    }
}

/** What a ref's file may be, for the hint beside an upload: `PNG, SVG, ICO, JPEG or WebP, up to 2 MB.` */
fun fileRefTakesText(takes: FileRefTakes): String {
    val kinds = joinAsPhrase(takes.kinds, last = "or").ifEmpty { "An image" }
    return if (takes.maxSize > 0) "$kinds, up to ${byteSizeText(takes.maxSize)}." else "$kinds."
}

/**
 * What is wrong with a picked file of [size] bytes before it is sent, or null: only what the browser can know
 * without reading it. Whether it is the image it claims to be is the backend's to say, in its own words.
 */
fun fileRefFileProblem(takes: FileRefTakes?, size: Long): String? {
    val max = takes?.maxSize ?: return null
    return if (max in 1 until size) "This file is ${byteSizeText(size)}; a file ref's file may be at most ${byteSizeText(max)}." else null
}

/**
 * What stops a new ref being added under [refId], or null: an id that is no name (the kernel's own rule, which the
 * backend holds it to), or one the client already has -- an existing ref is replaced from its own row, where it
 * can be seen, and not by typing its id here.
 */
fun fileRefNewIdProblem(refId: String, existing: List<FileRefView>): String? = when {
    refId.isEmpty() -> "Give the file an id: a name for it, such as heroImage."
    existing.any { it.refId == refId } -> "This client already has a file '$refId'. Replace it from its own row."
    else -> FREF.refIdProblem(refId)
}

/** The short marks beside a ref's id: what is unusual about it. */
fun fileRefTags(ref: FileRefView): List<String> = listOfNotNull(
    "Draft".takeIf { !ref.live },
    "Missing".takeIf { ref.missing },
    "Shipped".takeIf { !ref.stored },
)

/**
 * What a ref's file is, in a line: its type, its size and its own name -- or, for one whose file is not there,
 * that it is not, which is all the backend says of it whatever the reason.
 */
fun fileRefHoldsText(ref: FileRefView): String {
    val file = ref.file
    if (ref.missing || file == null) return "Its file is not there to be served: the address answers as not found."
    return listOfNotNull(file.mimeType.ifEmpty { null }, byteSizeText(file.size), file.fileName).joinToString(" · ")
}

/**
 * Where a ref's file is, and how it came to be there, by its source. An upload has a who and a when; a file on
 * the server has a root and a path, and a when that is the file's own last change.
 */
fun fileRefWhereText(ref: FileRefView): String {
    val at = ref.file?.uploadedAt?.takeIf { it.isNotEmpty() }?.let(::formatTimestamp)
    val place = "${ref.root.orEmpty()}/${ref.path.orEmpty()}"
    val changed = at?.let { ", last changed $it" }.orEmpty()
    return when (ref.source) {
        FREF.sourceLocal ->
            if (ref.stored) "A file on the server: $place$changed." else "Shipped with the client, as a file on the server: $place$changed."
        FREF.sourceClientLocal -> "A file the deployment keeps for this client: ${ref.path.orEmpty()} in ${ref.root.orEmpty()}$changed."
        else -> "Uploaded" + at?.let { " $it" }.orEmpty() + ref.uploadedByName?.let { " by $it" }.orEmpty() + "."
    }
}

/**
 * What goes before a ref's address. A draft's is real -- the sandbox is served it there -- and is not yet what the
 * client's own people get, which is what somebody copying an address needs to know.
 */
fun fileRefAddressLead(ref: FileRefView): String = if (ref.live) "Served at " else "Served in the sandbox, and to the client's people once published, at "

/** Whether a ref can be removed: only one a stored configuration holds. A shipped one is uploaded over instead. */
fun fileRefRemovable(ref: FileRefView): Boolean = ref.stored

/**
 * The address a ref is served at, given how a versioned name becomes one ([addressOf]) -- or null for a ref with
 * no bytes to name. What an administrator copies to use the file, until copy can name a ref itself.
 */
fun fileRefAddress(ref: FileRefView, addressOf: (String) -> String): String? = ref.name?.takeIf { !ref.missing }?.let(addressOf)

/**
 * The inputs of the address a ref is previewed from: the **named** download, since the client on the page need not
 * be the caller's own and a draft is served to nobody outside the sandbox -- with the file's hash, so that a
 * replaced file is a new address. Null when there is no file to show.
 */
fun fileRefPreviewArgs(clientId: String, ref: FileRefView): Map<String, Any?>? {
    val hash = ref.hash?.takeIf { !ref.missing } ?: return null
    return mapOf(FREF.client to clientId, CCT.refId to ref.refId, CCT.hash to hash)
}

/**
 * The question asked before a ref of [clientId]'s is removed, naming where that acts as every delete's does
 * (issue #1049) -- or, where it is a draft that is changed ([draft]), that it is.
 */
fun fileRefRemoveQuestion(ref: FileRefView, clientId: String, draft: Boolean): String =
    if (draft) "Remove the file '${ref.refId}' from the draft of ${sandboxParentOfOrSelf(clientId)}?"
    else "Remove the file '${ref.refId}' of ${deleteTargetText(clientId)}?"

/** The client a draft made on [clientId]'s page is a draft of: a sandbox's parent, or the client itself. */
private fun sandboxParentOfOrSelf(clientId: String): String = sandboxParentOf(clientId) ?: clientId

/** What removing a ref does, under [fileRefRemoveQuestion]. */
fun fileRefRemoveConsequence(draft: Boolean): String =
    if (draft) "The sandbox stops showing it. The client's people are served it until the configuration is published."
    else "Its address stops answering for the client's people, at once. An image the client was shipped with under this id is shown again."

/** What a section says once an upload answers. */
fun fileRefUploadedNote(change: FileRefChange): String = savedNote("Uploaded '${change.refId}', in ${change.configName}.", change.mode)

/** What a section says once a file the deployment keeps has been named. */
fun fileRefNamedNote(change: FileRefChange): String =
    savedNote("'${change.refId}' now names ${change.ref?.path ?: "that file"}, in ${change.configName}.", change.mode)

/**
 * What a section says once a removal answers. A ref that is still there afterwards is the one the client was
 * shipped with, which the removed upload stood in front of. A removal made as a draft is worded as one: the
 * note every other draft gets says the sandbox "shows it", and here there is nothing left to show.
 */
fun fileRefRemovedNote(change: FileRefChange): String {
    val done = if (change.ref == null) "Removed '${change.refId}'." else "Removed the upload of '${change.refId}': the image the client was shipped with is shown again."
    return if (change.mode == EDM.draft) "$done A draft: the sandbox shows the change, and the client's people keep what they have until it is published." else done
}

/**
 * [refs] as a change left them: the changed ref in its place, a new one at the end, a removed one gone -- drawn
 * from the answer, before the listing is read again.
 */
fun withFileRefChange(refs: List<FileRefView>?, change: FileRefChange): List<FileRefView>? {
    refs ?: return null
    val changed = change.ref ?: return refs.filter { it.refId != change.refId }
    return if (refs.any { it.refId == changed.refId }) refs.map { if (it.refId == changed.refId) changed else it } else refs + changed
}

/** How many refs the client has of how many it may, once it is near enough the limit to say: `198 of 200`. */
fun fileRefCountText(refs: List<FileRefView>, takes: FileRefTakes?): String? {
    val limit = takes?.refLimit?.takeIf { it > 0 } ?: return null
    return if (refs.size * 10 >= limit * 9) "${refs.size} of the $limit a client is served." else null
}

/** The things done to a ref, as [webAssetBusyKey] names the one under way. */
@Suppress("ConstPropertyName")
object FACT {
    const val upload = "upload"
    const val local = "local"
    const val remove = "remove"

    /** What stands in for a ref's id while one is being added: no id is a name with a space in it. */
    const val adding = "new file"
}
