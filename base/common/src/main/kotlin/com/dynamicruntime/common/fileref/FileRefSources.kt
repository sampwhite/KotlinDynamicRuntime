package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileMeta
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.webasset.WebAssetCheck
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a file ref's bytes are (issue #1154): one of these for each `source` an entry may name. [FileRefService]
 * holds the rules every ref keeps -- its id, its client, the limit -- and asks the ref's source for the rest: whether
 * the entry is one it can read, whether its file is there, and the file.
 *
 * **A source answers a file by its bytes, never by what an entry says of them.** The hash and the type it hands
 * back are the bytes' own -- the store's by a checked upload, a local file's by reading it -- so nothing typed
 * into configuration chooses the `Content-Type` a file is served with, or the address it may be cached under.
 */
interface FileRefSource {
    /** The `source` an entry names this by. */
    val source: String

    /**
     * What stops [def] being a ref of this source, or null: said of [ref], which names the ref, its client and its
     * configuration. Asked before anything is read.
     */
    fun defProblem(def: FileRefDef, ref: String): String?

    /**
     * What a load reports of [ref]'s file -- the rest of a sentence that begins by naming the ref -- or null when
     * the file can be served, or when that could not be asked. A ref with such a problem is kept: the entry is not
     * wrong, the file may yet arrive, and serving asks again.
     */
    fun fileProblem(cxt: KdrCxt, ref: FileRef): String?

    /** What is known of [ref]'s file without its content, or null when there is none to serve. */
    fun meta(cxt: KdrCxt, ref: FileRef): FileMeta?

    /** [ref]'s file, content and all, or null when there is none to serve. */
    fun read(cxt: KdrCxt, ref: FileRef): StoredFile?
}

/**
 * The file store as a source of file refs (issue #1153): a ref is a pointer, by the hash of its bytes, to an object
 * a checked upload wrote.
 *
 * **Bytes are kept by their hash**, in [FileRefService.bucket] at `clients/<owner>/refs/<hash>`, where the owner is
 * the client whose stored configuration holds the entry. An object is written once and never rewritten, so no
 * upload can change the bytes a published revision names; two refs with the same bytes share one object; and
 * removing a ref leaves its object, which older revisions still name.
 */
class StoreFileSource : FileRefSource {
    override val source: String = FREF.sourceStore

    /** What the store answered for one file, and when. */
    private class Asked(val atNanos: Long, val meta: FileMeta?)

    /**
     * What the file store last said of each file, by owner and hash, for [FileRefService.askedMemoMs]. An object is
     * written once and never rewritten, so an answer of "there" stays true unless somebody deletes the object --
     * which is why it is kept at all, and what makes a reload, a trial and a listing cost only the refs that are
     * new. It is not kept for good: a deleted object would otherwise be reported present by this node until it
     * restarted, while another node said it was missing. "Not there" is kept as long, so a ref whose file is gone
     * is not asked for again by every listing and every write.
     */
    private val asked = ConcurrentHashMap<String, Asked>()

    /** The clock the memo is aged by. A test's to replace; never the instance clock, which a test may travel. */
    var nanoTime: () -> Long = System::nanoTime

    override fun defProblem(def: FileRefDef, ref: String): String? = when {
        !def.stored ->
            "$ref is declared in source code and names bytes in the file store. Those are in one deployment's " +
                "store, and source configuration runs on every machine, so only stored configuration may hold such " +
                "a ref; one in source code names a local file."
        !FREF.isHash(def.hash.orEmpty()) ->
            "$ref has the hash '${def.hash.orEmpty()}', which is not one: a SHA-256 is ${FREF.hashLength} lowercase hex characters."
        def.root != null || def.path != null ->
            "$ref names both bytes in the file store and a local file. It is one or the other."
        else -> null
    }

    /**
     * That the store holds no file for the ref's hash. A store that cannot be asked is not a missing file and not a
     * fault of the configuration: it is logged, and the ref is taken as it stands.
     */
    override fun fileProblem(cxt: KdrCxt, ref: FileRef): String? {
        val hash = ref.def.hash.orEmpty()
        val missing = try {
            metaOf(cxt, ref.owner, hash) == null
        } catch (e: KdrException) {
            LogFileRef.warn(cxt) { "Could not ask the file store for the file '$hash' of client '${ref.owner}'; taking its ref as it stands. ${e.message}" }
            false
        }
        return if (missing) "names a file that is not in the file store ($hash)." else null
    }

    override fun meta(cxt: KdrCxt, ref: FileRef): FileMeta? = metaOf(cxt, ref.owner, ref.def.hash.orEmpty())

    /**
     * The file store's record of the file [hash] under [owner], or null when it holds none: from what the store
     * last said within [FileRefService.askedMemoMs], else asked again. A record says the store *describes* such a
     * file; whether its bytes are whole is [read]'s to find, on the way out.
     */
    fun metaOf(cxt: KdrCxt, owner: String, hash: String): FileMeta? {
        val memoKey = "$owner/$hash"
        asked[memoKey]?.takeIf { nanoTime() - it.atNanos < FileRefService.askedMemoMs * nanosPerMs }?.let { return it.meta }
        return FileStoreService.get(cxt).meta(cxt, FileRefService.bucket, FileRefService.keyOf(owner, hash))
            .also { asked[memoKey] = Asked(nanoTime(), it) }
    }

    /** Drops what was last said of the file [hash] under [owner]: an upload has just changed the answer. */
    fun forget(owner: String, hash: String) {
        asked.remove("$owner/$hash")
    }

    /**
     * Two things are held to here, on every read, that an entry cannot be trusted for: the file is **the one the
     * ref names** -- its recorded hash is the ref's, which a key by content makes true unless something wrote the
     * key directly -- and it is **a kind of file a ref may be**, by the type the checked upload recorded. Anything
     * else is no file, said in the log.
     */
    override fun read(cxt: KdrCxt, ref: FileRef): StoredFile? {
        val hash = ref.def.hash.orEmpty()
        val file = FileStoreService.get(cxt).get(cxt, FileRefService.bucket, FileRefService.keyOf(ref.owner, hash)) ?: return null
        if (file.meta.hash != hash || WebAssetCheck.declaredKind(file.meta.mimeType) == null) {
            LogFileRef.warn(cxt) {
                "The file kept for ref '${ref.refId}' of client '${ref.owner}' is not what the ref names (hash " +
                    "${file.meta.hash}, type ${file.meta.mimeType}); answering as though there were none."
            }
            return null
        }
        return file
    }

    private companion object {
        const val nanosPerMs = 1_000_000L
    }
}

/**
 * Local files as a source of file refs (issue #1154): a ref names a root the deployment declared and a path under
 * it, and its bytes are that file as it is on this machine now -- no import, and no copy to keep in step.
 *
 * Everything about *which* files is [LocalFileRoots]'s. What is here is how such a file is a ref's:
 *
 * - **Its hash is of the bytes as read, every time.** Nothing remembers a hash by a file's size and date: a file
 *   rewritten in place within one tick of the clock would then be sent, under the permanent cache header, at an
 *   address that names other bytes. One hash for each request no cache answered is what a read of the file store
 *   costs too.
 * - **So a file edited on disk is a new address**, and the old address is answered with the current bytes and
 *   `no-store`. Nodes that hold different files give different addresses; the one that does not hold the bytes an
 *   address names says so the same way, so a shared cache only ever keeps the exact bytes an address names.
 * - **Why a file cannot be served is the operator's to read, in the log** ([LocalRead.reason]). Everybody else is
 *   told one thing, whatever the reason: a ref's problem, and a 404. It is said once for a file and a reason, and
 *   again only after [repeatReasonAfterMs]: a missing picture on a busy page is asked for by every view of it.
 */
class LocalFileSource(val roots: LocalFileRoots) : FileRefSource {
    override val source: String = FREF.sourceLocal

    /** What was last said of a file that could not be served, and when. */
    private class Said(val reason: String, val atNanos: Long)

    /** The reason last logged for each file that cannot be served, by its pointer; gone once the file is served again. */
    private val said = ConcurrentHashMap<String, Said>()

    /**
     * Whether to log that the file at [pointer] cannot be served for [reason], at [nowNanos]: the first time, when
     * the reason has changed, and once every [repeatReasonAfterMs] while it stays the same.
     */
    @KdrPrivate
    fun shouldSay(pointer: String, reason: String, nowNanos: Long): Boolean {
        val last = said[pointer]
        if (last != null && last.reason == reason && nowNanos - last.atNanos < repeatReasonAfterMs * nanosPerMs) return false
        said[pointer] = Said(reason, nowNanos)
        return true
    }

    override fun defProblem(def: FileRefDef, ref: String): String? {
        val root = def.root.orEmpty()
        val path = def.path.orEmpty()
        FREF.rootProblem(root)?.let { return "$ref names no root of local files: $it" }
        LocalFileRoots.pathProblem(path)?.let { return "$ref has the path '$path', which is not one a local file may have: $it." }
        if (def.hash != null) {
            return "$ref names a local file and carries a hash. A local file's hash is that of the file as it is read, so none is written."
        }
        return null
    }

    override fun fileProblem(cxt: KdrCxt, ref: FileRef): String? =
        if (read(cxt, ref) != null) null else "names the local file '${ref.def.root}/${ref.def.path}', which is not there to be served as an image."

    override fun meta(cxt: KdrCxt, ref: FileRef): FileMeta? = read(cxt, ref)?.meta

    /**
     * The file as it is now, described as the store describes one: the type the bytes were found to be, their
     * hash, the file's own name, and -- as when it was "uploaded" -- when the file last changed.
     */
    override fun read(cxt: KdrCxt, ref: FileRef): StoredFile? {
        val read = roots.read(ref.def.root.orEmpty(), ref.def.path.orEmpty())
        val file = read.file
        if (file == null) {
            if (shouldSay(ref.def.pointer, read.reason.orEmpty(), System.nanoTime())) {
                LogFileRef.warn(cxt) {
                    "The local file '${ref.def.root}/${ref.def.path}' of ref '${ref.refId}' (client '${ref.owner}') cannot be served: ${read.reason}."
                }
            }
            return null
        }
        // Mended: were it to break again, that is news.
        if (said.isNotEmpty()) said.remove(ref.def.pointer)
        val meta = FileMeta(
            mimeType = file.kind.mimeType, size = file.bytes.size.toLong(), hash = file.bytes.sha256Hex(),
            uploadedAt = file.modifiedAt, fileName = file.name, client = ref.owner,
        )
        return StoredFile(file.bytes, meta)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** How long the same reason for the same file goes unrepeated in the log. */
        const val repeatReasonAfterMs = 10 * 60_000L

        private const val nanosPerMs = 1_000_000L
    }
}
