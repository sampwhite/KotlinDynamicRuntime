package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientStoredEdit
import com.dynamicruntime.common.gedra.GedraConfigRow
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WebAssetCheck

/**
 * Changing a client's file refs from the API (issue #1153): uploading a file as a named ref, and removing one. Each
 * is one call that leaves the client's stored configuration and the file store agreeing, by the path the copy and
 * menu editors take ([ClientStoredEdit]).
 *
 * **The bytes first, then the entry.** An upload stores the file under the hash of its bytes -- a key nothing else
 * can be at, so storing it changes nothing anyone is served -- and only then patches the entry that names it into
 * the client's configuration, under the client's lock and through a trial. A patch that is refused leaves an
 * object no entry names, which is debris and not a fault; the other order would leave an entry naming bytes that
 * are not there.
 *
 * **Live, or a draft, by whether the client has a sandbox**, as a copy edit is: without one the entry is published
 * and the client's people are served the file at once; with one it is the parent's draft, which its sandbox shows
 * and a publish makes live. So unlike a web asset, an upload made in a sandbox is not refused.
 *
 * **The entry lands with the copy.** In the configuration already holding that ref, else the editors' own
 * ([ClientStoredEdit.editConfig], created on first use) -- so a copy edit that names a new image and the upload of
 * the image are one configuration's changes, and publish together.
 */
object ClientFileRefEdit {
    /** What a change left: the client whose configuration took it, where it landed, and how it took effect. */
    class Result(val client: String, val refId: String, val configName: String, val mode: String, val shownAs: String)

    /**
     * Stores [content] as the file of [client]'s ref [refId], replacing what the ref named, and makes that take
     * effect. Refused as bad input, **before anything is stored**: an id that is no name, an empty file, one over
     * [FREF.maxSize], one that is not the image it was sent as ([WebAssetCheck]). [description] absent keeps the
     * ref's description; blank clears it.
     */
    fun upload(cxt: KdrCxt, client: String, refId: String, content: ContentData, description: String?): Result {
        FREF.refIdProblem(refId)?.let { throw KdrException.mkInput(it) }
        val bytes = content.contentBytes()
        if (bytes.isEmpty()) throw KdrException.mkInput("The file sent for the ref '$refId' is empty.")
        if (bytes.size > FREF.maxSize) {
            throw KdrException.mkInput(
                "A file ref's file may be at most ${byteSizeText(FREF.maxSize.toLong())}; this one is ${byteSizeText(bytes.size.toLong())}.",
            )
        }
        val kind = WebAssetCheck.check("file ref", content.mimeType, bytes)

        val target = ClientStoredEdit.target(cxt, client, editName)
        val bound = target.bound
        val owner = target.client
        val svc = GedraConfigService.get(bound)
        // Said here rather than by the patch, which would say it after the bytes were stored.
        if (svc.isStaticHere(bound, owner)) {
            throw KdrException.mkInput(
                "Client '$owner' is statically configured: in production its configuration comes from source code, " +
                    "so it takes no file ref here.",
            )
        }
        val hash = bytes.sha256Hex()
        val files = FileStoreService.get(bound)
        val key = FileRefService.keyOf(owner, hash)
        // Already there, when these bytes were uploaded before: nothing to write, and the first upload's record stands.
        if (files.meta(bound, FileRefService.bucket, key) == null) {
            val extra = buildMap<String, Any?> {
                put(CCT.refId, refId)
                cxt.userProfile.takeIf { it.isLoggedIn }?.displayName?.let { put(FREF.uploadedByName, it) }
            }
            // In a context bound to the owner, so the file is recorded as the client's whoever uploaded it.
            files.put(bound, FileRefService.bucket, key, ContentData(bytes, kind.mimeType, saveAsFilename = content.saveAsFilename), extra)
        }

        val holder = holderOf(bound, owner, refId) ?: ClientStoredEdit.editConfig(bound, owner)
        if (holder == null) {
            val written = ClientStoredEdit.createEditConfig(bound, owner) { fileRef(refId, hash, description?.ifBlank { null }) }
            return goLive(cxt, target, written, refId, undo = { patchEntry(svc, bound, written, refId, null) })
        }
        ClientStoredEdit.requireNoForeignDraft(target, holder, "a file upload")
        val before = storedEntry(holder, refId)
        val kept = if (description == null) before?.get(CCT.description).toOptStr() else description.ifBlank { null }
        val written = patchEntry(svc, bound, holder, refId, FileRefDef(refId, FREF.sourceStore, hash, kept).toEntry())
        return goLive(cxt, target, written, refId, undo = { patchEntry(svc, bound, written, refId, before) })
    }

    /**
     * Removes [client]'s ref [refId] and makes that take effect. A 400 when no stored configuration holds it. The
     * file it named is left where it is: an older revision of the configuration still names it.
     */
    fun remove(cxt: KdrCxt, client: String, refId: String): Result {
        val target = ClientStoredEdit.target(cxt, client, editName)
        val bound = target.bound
        val svc = GedraConfigService.get(bound)
        val holder = holderOf(bound, target.client, refId)
            ?: throw KdrException.mkInput("No stored configuration of client '${target.client}' holds a file ref '$refId'.")
        ClientStoredEdit.requireNoForeignDraft(target, holder, "removing a file ref")
        val before = storedEntry(holder, refId)
        val written = patchEntry(svc, bound, holder, refId, null)
        return goLive(cxt, target, written, refId, undo = { patchEntry(svc, bound, written, refId, before) })
    }

    /** The stored config of [client]'s that holds the ref [refId], or null when none does. */
    private fun holderOf(bound: KdrCxt, client: String, refId: String): GedraConfigRow? =
        GedraConfigService.get(bound).listConfigs(bound).firstOrNull { it.client == client && storedEntry(it, refId) != null }

    /** [row]'s stored entry for the ref [refId], or null when it holds none. */
    private fun storedEntry(row: GedraConfigRow, refId: String): Map<String, Any?>? =
        row.entriesBySlot()[CCT.fileRefDef]?.firstOrNull { it[CCT.refId].toOptStr() == refId }

    /**
     * Patches [row] so its entry for [refId] is [entry] -- or, with null, so it holds none. Run inside `patchConfig`,
     * over the revision as it stands under the lock, so a ref another administrator added a moment ago is kept.
     */
    private fun patchEntry(
        svc: GedraConfigService,
        bound: KdrCxt,
        row: GedraConfigRow,
        refId: String,
        entry: Map<String, Any?>?,
    ): GedraConfigRow = svc.patchConfig(bound, row.configId, trial = true) { current ->
        val out = LinkedHashMap<String, List<Map<String, Any?>>>(current)
        val entries = current[CCT.fileRefDef].orEmpty().toMutableList()
        val at = entries.indexOfFirst { it[CCT.refId].toOptStr() == refId }
        ClientStoredEdit.storeEntry(out, CCT.fileRefDef, entries, at, entry.orEmpty(), emptied = entry == null)
        out
    }

    /** Makes [written] take effect ([ClientStoredEdit.takeEffect]: live, or a draft) and says where it landed. */
    private fun goLive(cxt: KdrCxt, target: ClientStoredEdit.EditTarget, written: GedraConfigRow, refId: String, undo: () -> Unit): Result {
        ClientStoredEdit.takeEffect(cxt, target, written, undo)
        return Result(target.client, refId, written.configId.baseId, target.mode, target.readsAs)
    }

    /** What the edit's sub context is called, in the log. */
    private const val editName = "fileRefEdit"
}
