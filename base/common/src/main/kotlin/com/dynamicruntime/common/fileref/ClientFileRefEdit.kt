package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
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
 * Changing a client's file refs from the API (issues #1153, #1164): uploading a file as a named ref, naming a local
 * file as one, and removing one. Each is one call that leaves the client's stored configuration and the file store
 * agreeing, by the path the copy and menu editors take ([ClientStoredEdit]).
 *
 * **The bytes first, then the entry.** An upload stores the file under the hash of its bytes -- a key nothing else
 * can be at, so storing it changes nothing anyone is served -- and only then patches the entry that names it into
 * the client's configuration, under the client's lock and through a trial. A patch that is refused leaves an
 * object no entry names, which is debris and not a fault; the other order would leave an entry naming bytes that
 * are not there. Nothing here removes an object, and nothing yet bounds how many a client leaves behind: a
 * replaced or removed ref's file stays for the revisions that still name it, until a sweep of what no revision
 * names exists (issue #1147).
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
     * effect. [description] absent keeps the ref's description; blank clears it.
     *
     * **Everything that can be refused is refused before any bytes are stored**, since a stored object nothing
     * names is left where it is: an id that is no name, a description too long, an empty file, one over
     * [FREF.maxSize], one that is not the image it was sent as ([WebAssetCheck]); a client that takes no stored
     * configuration here; a configuration holding somebody's unpublished changes; and a client already served as
     * many refs as it may have. The last is said early as a courtesy -- it is the write's trial that holds a client
     * to its limit, across every configuration and call.
     */
    fun upload(cxt: KdrCxt, client: String, refId: String, content: ContentData, description: String?): Result {
        FREF.refIdProblem(refId)?.let { throw KdrException.mkInput(it) }
        checkDescription(description)
        val bytes = content.contentBytes()
        if (bytes.isEmpty()) throw KdrException.mkInput("The file sent for the ref '$refId' is empty.")
        if (bytes.size > FREF.maxSize) {
            throw KdrException.mkInput(
                "A file ref's file may be at most ${byteSizeText(FREF.maxSize.toLong())}; this one is ${byteSizeText(bytes.size.toLong())}.",
            )
        }
        val kind = WebAssetCheck.check("file ref", content.mimeType, bytes)

        val at = landing(cxt, client, refId, "a file upload")
        val hash = bytes.sha256Hex()
        storeBytes(cxt, at.bound, at.refs, at.owner, refId, hash, ContentData(bytes, kind.mimeType, saveAsFilename = content.saveAsFilename))
        return land(cxt, at, refId, description) { kept, extra -> FileRefDef(refId, FREF.sourceStore, hash, kept, extra = extra) }
    }

    /**
     * Names the local file at [path] under the root [root] as [client]'s ref [refId], replacing what the ref named,
     * and makes that take effect (issue #1164). [description] absent keeps the ref's description; blank clears it.
     *
     * No bytes come with it: the file is where the deployment put it, and in a root open to clients that is the
     * client's own directory there (`LocalFileRoots`). **Who may name a file where is the configuration write's to
     * decide** (`GedraConfigService.mayNameLocalFile`) -- it is asked here only so that a call that will be refused
     * leaves nothing behind -- and whether the file is there to be served is the write's trial's, which refuses a
     * ref to one that is not, saying one thing whatever the reason.
     */
    fun nameLocal(cxt: KdrCxt, client: String, refId: String, root: String, path: String, description: String?): Result {
        FREF.refIdProblem(refId)?.let { throw KdrException.mkInput(it) }
        checkDescription(description)
        FREF.rootProblem(root)?.let { throw KdrException.mkInput(it) }
        LocalFileRoots.pathProblem(path)?.let { throw KdrException.mkInput("'$path' is not a path a local file may have: $it.") }
        if (!GedraConfigService.get(cxt).mayNameLocalFile(cxt, root)) {
            // The same words whether or not there is such a root: only that it is not one opened to clients.
            throw KdrException(
                "The root '$root' is not one a client's own administrator may name a file in. The roots open to " +
                    "clients are listed; any other file is uploaded.",
                code = EXC.notAuthorized,
            )
        }
        val at = landing(cxt, client, refId, "naming a local file")
        return land(cxt, at, refId, description) { kept, extra ->
            FileRefDef(refId, FREF.sourceLocal, hash = null, kept, extra = extra, root = root, path = path)
        }
    }

    private fun checkDescription(description: String?) {
        if (description != null && description.length > FREF.maxDescriptionLength) {
            throw KdrException.mkInput("A file ref's description may be at most ${FREF.maxDescriptionLength} characters; this one is ${description.length}.")
        }
    }

    /** Where a ref's entry is about to land, and what was found on the way: see [landing]. */
    private class Landing(
        val target: ClientStoredEdit.EditTarget,
        val svc: GedraConfigService,
        val refs: FileRefService,
        /** The stored configuration already holding the ref, or null when none does. */
        val holder: GedraConfigRow?,
        /** The refs the client's administrator is shown now. */
        val shown: Map<String, FileRef>,
    ) {
        val bound: KdrCxt get() = target.bound
        val owner: String get() = target.client
    }

    /**
     * Where [client]'s ref [refId] is to be written, having refused what can be refused before anything is: a
     * client that takes no stored configuration here; a configuration holding somebody's unpublished changes
     * ([what] names the change, for that refusal); and a client already served as many refs as it may have.
     */
    private fun landing(cxt: KdrCxt, client: String, refId: String, what: String): Landing {
        val target = ClientStoredEdit.target(cxt, client, editName)
        val bound = target.bound
        val owner = target.client
        val svc = GedraConfigService.get(bound)
        val refs = FileRefService.get(cxt)
        if (svc.isStaticHere(bound, owner)) {
            throw KdrException.mkInput(
                "Client '$owner' is statically configured: in production its configuration comes from source code, " +
                    "so it takes no file ref here.",
            )
        }
        val holder = holderOf(bound, owner, refId)
        holder?.let { ClientStoredEdit.requireNoForeignDraft(target, it, what) }
        val shown = refs.forClient(target.readsAs).refs
        if (refId !in shown && shown.size >= FREF.maxRefs) {
            throw KdrException.mkInput(
                "Client '$owner' has ${FREF.maxRefs} file refs, which is as many as a client is served. Remove one first.",
            )
        }
        return Landing(target, svc, refs, holder, shown)
    }

    /**
     * Writes the entry [def] builds for [refId] and makes it take effect. The entry is always patched in, under the
     * lock and over the revision as it then stands -- into the configuration already holding the ref, else the
     * editors' own, made empty first when there is none. Never written as a new configuration with the entry in it,
     * which a second first-write would write over. [def] is handed the description to keep and the fields of the
     * prior entry this release does not read, which are carried.
     */
    private fun land(
        cxt: KdrCxt,
        at: Landing,
        refId: String,
        description: String?,
        def: (kept: String?, extra: Map<String, Any?>) -> FileRefDef,
    ): Result {
        val row = at.holder ?: ClientStoredEdit.ensureEditConfig(at.bound, at.owner)
        val before = storedEntry(row, refId)
        val prior = before?.let { FileRefDef.fromEntry(it, null, stored = true) }
        // Absent keeps what the ref says now -- of the stored entry, or of the shipped ref this is the first to override.
        val kept = if (description == null) (prior ?: at.shown[refId]?.def)?.description else description.ifBlank { null }
        val written = patchEntry(at.svc, at.bound, row, refId, def(kept, prior?.extra.orEmpty()).toEntry())
        return goLive(cxt, at.target, written, refId, undo = { patchEntry(at.svc, at.bound, written, refId, before, restoring = true) })
    }

    /**
     * Puts [content] at the key its [hash] makes under [owner], unless a whole file of that content is already
     * there -- in which case nothing is written and the first upload's record of it stands (its name, who sent it
     * and when).
     *
     * **"Already there" is judged by reading the file**, not by its record alone: a file whose bytes are gone or
     * damaged while its metadata remains is not there, and is written again, which is how uploading a file once
     * more repairs it. What the store was last said to hold of it is dropped either way.
     */
    private fun storeBytes(cxt: KdrCxt, bound: KdrCxt, refs: FileRefService, owner: String, refId: String, hash: String, content: ContentData) {
        val files = FileStoreService.get(bound)
        val key = FileRefService.keyOf(owner, hash)
        val there = try {
            files.get(bound, FileRefService.bucket, key)
        } catch (_: KdrException) {
            // A file that does not match its own record: not one a ref can be served from.
            null
        }
        if (there == null || there.meta.hash != hash || WebAssetCheck.declaredKind(there.meta.mimeType) == null) {
            val extra = buildMap<String, Any?> {
                put(CCT.refId, refId)
                cxt.userProfile.takeIf { it.isLoggedIn }?.displayName?.let { put(FREF.uploadedByName, it) }
            }
            // In a context bound to the owner, so the file is recorded as the client's whoever uploaded it.
            files.put(bound, FileRefService.bucket, key, content, extra)
        }
        refs.forget(owner, hash)
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
        return goLive(cxt, target, written, refId, undo = { patchEntry(svc, bound, written, refId, before, restoring = true) })
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
     *
     * [restoring] says [entry] is what the configuration held before this call changed it, put back as the call's
     * undo: the one write of a local ref a client's own administrator makes, since it names nothing new.
     */
    private fun patchEntry(
        svc: GedraConfigService,
        bound: KdrCxt,
        row: GedraConfigRow,
        refId: String,
        entry: Map<String, Any?>?,
        restoring: Boolean = false,
    ): GedraConfigRow = svc.patchConfig(bound, row.configId, trial = true, restoring = if (restoring) listOfNotNull(entry) else emptyList()) { current ->
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
