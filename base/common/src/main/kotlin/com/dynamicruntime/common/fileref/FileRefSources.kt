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

    /**
     * As [meta], but **the answer [read] would give now**, without the content (issue #1131): nothing a source
     * remembers to spare a load or a listing stands in for asking. What a meta-only read beside a download answers,
     * so that the two say the same of whether there is a file. A source whose [meta] already is that answer need
     * not say it twice.
     */
    fun currentMeta(cxt: KdrCxt, ref: FileRef): FileMeta? = meta(cxt, ref)

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

    /** What the store answered for one file, and when -- and which store it was (issue #1282). */
    private class Asked(val atNanos: Long, val meta: FileMeta?, val store: String)

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

    /** The store's record of [ref]'s file, from what it last said ([metaOf]), when it is one [read] would serve. */
    override fun meta(cxt: KdrCxt, ref: FileRef): FileMeta? =
        metaOf(cxt, ref.owner, ref.def.hash.orEmpty())?.takeIf { servesAs(it, ref) }

    /** The store's record of [ref]'s file as the store says it now, when it is one [read] would serve (issue #1131). */
    override fun currentMeta(cxt: KdrCxt, ref: FileRef): FileMeta? =
        metaOf(cxt, ref.owner, ref.def.hash.orEmpty(), fresh = true)?.takeIf { servesAs(it, ref) }

    /**
     * Whether the file [meta] describes is one [ref] may be served as: **the one the ref names** -- its recorded
     * hash is the ref's, which a key by content makes true unless something wrote the key directly -- and **a kind
     * of file a ref may be**, by the type the checked upload recorded. Asked of the record alone, so a read and a
     * description of the file cannot disagree about it.
     */
    private fun servesAs(meta: FileMeta, ref: FileRef): Boolean =
        meta.hash == ref.def.hash.orEmpty() && WebAssetCheck.declaredKind(meta.mimeType) != null

    /**
     * The file store's record of the file [hash] under [owner], or null when it holds none: from what the store
     * last said within [FileRefService.askedMemoMs] unless [fresh] asks the store now, which also renews what is
     * remembered. A record says the store *describes* such a file; whether its bytes are whole is [read]'s to find,
     * on the way out.
     */
    fun metaOf(cxt: KdrCxt, owner: String, hash: String, fresh: Boolean = false): FileMeta? {
        val memoKey = "$owner/$hash"
        val files = FileStoreService.get(cxt).forOwner(cxt, owner)
        if (!fresh) {
            // What another store said is no answer: a client moved to another store (issue #1283) is asked there.
            asked[memoKey]?.takeIf { it.store == files.storeName && nanoTime() - it.atNanos < FileRefService.askedMemoMs * nanosPerMs }
                ?.let { return it.meta }
        }
        return files.meta(cxt, FileRefService.bucket, FileRefService.keyOf(owner, hash))
            .also { asked[memoKey] = Asked(nanoTime(), it, files.storeName) }
    }

    /** Drops what was last said of the file [hash] under [owner]: an upload has just changed the answer. */
    fun forget(owner: String, hash: String) {
        asked.remove("$owner/$hash")
    }

    /**
     * Two things are held to here, on every read, that an entry cannot be trusted for ([servesAs]): the file is the
     * one the ref names, and a kind of file a ref may be. Anything else is no file, said in the log.
     */
    override fun read(cxt: KdrCxt, ref: FileRef): StoredFile? {
        val hash = ref.def.hash.orEmpty()
        val file = FileStoreService.get(cxt).forOwner(cxt, ref.owner).get(cxt, FileRefService.bucket, FileRefService.keyOf(ref.owner, hash))
            ?: return null
        if (!servesAs(file.meta, ref)) {
            LogFileRef.warn(cxt) {
                "The file kept for ref '${ref.refId}' of client '${ref.owner}' is not what the ref names (hash " +
                    "${file.meta.hash}, type ${file.meta.mimeType}); answering as though there were none."
            }
            return null
        }
        return file
    }

    @Suppress("ConstPropertyName")
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
 *   told one thing, whatever the reason: a ref's problem, and a 404. It is said once for a ref and a reason, and
 *   again only after [repeatReasonAfterMs]: a missing picture on a busy page is asked for by every view of it.
 * - **A load and a listing do not read a file they have already looked at** ([fileProblem], [meta]). A write's
 *   trial judges every ref of its client, more than once, under the lock every client's reload takes, and a listing
 *   answers every ref's hash -- and a client's own administrator can name one file under many ids. What a file was
 *   found to be is remembered by where it is, its size and when it last changed. A stale answer there costs a
 *   problem said one load late, or a listed address that is answered `no-store`. Serving remembers nothing: there
 *   the answer is the bytes.
 *
 * **Two kinds of ref are read here, and which is said by the ref** ([source]): an ordinary local ref names
 * `<root>/<path>`, and a client's own (`clientLocal`, issue #1164) names `<root>/<client>/<path>` in a root open to
 * clients. One never becomes the other by a node's configuration -- see `FREF.sourceClientLocal`.
 */
class LocalFileSource(val roots: LocalFileRoots, override val source: String = FREF.sourceLocal) : FileRefSource {
    /** Whether this reads the client's own directory of a root: the `clientLocal` kind. */
    private val ownDirectory = source == FREF.sourceClientLocal

    /**
     * Where [ref]'s file is, located once: the client directory of the root it is read from, where that is a
     * client's own (else null, an ordinary local ref naming the root whole), and what is there. It is the ref's
     * client's -- or, for a ref the client **inherits** from a template and has no such file of its own, the
     * template's (issue #1180): the template's file is the default, which a client replaces by having its own put
     * there, as a template's copy is the default its words replace.
     *
     * Only a file that is **absent** falls back. One the client has and that cannot be served -- no image, too large,
     * reached through a link -- is the client's mistake, said as one, and not hidden behind the template's picture.
     */
    private fun located(ref: FileRef): Located {
        fun at(dir: String?) = Located(dir, roots.locate(ref.def.root.orEmpty(), ref.def.path.orEmpty(), dir))
        if (!ownDirectory) return at(null)
        val own = at(ref.owner)
        val template = ref.inheritedFrom ?: return own
        return if (own.at.absent) at(template) else own
    }

    /**
     * Where a ref's file was looked for -- the client directory of the root it is read from ([dir], null for an
     * ordinary local ref) -- and what was found there ([at]): the one answer a lookup, a read and a log line go by.
     */
    private class Located(val dir: String?, val at: LocalAt)

    /** Where [ref]'s file is looked for in [dir], from the root down: what an operator needs to find it by. */
    private fun sought(ref: FileRef, dir: String?): String = listOfNotNull(ref.def.root, dir, ref.def.path).joinToString("/")

    /** What was last said of a ref whose file could not be served, and when. */
    private class Said(val reason: String, val atNanos: Long)

    /** The reason last logged for each ref whose file cannot be served; gone once the file is served again. */
    private val said = ConcurrentHashMap<String, Said>()

    /** What a file was found to be when it was last read: the file as it then was, and its record -- null for one that is no image. */
    private class Looked(val stamp: String, val meta: FileMeta?)

    /** What each file was last found to be, by where it is looked for: see the class note. */
    private val looked = ConcurrentHashMap<String, Looked>()

    /**
     * Whether to log that [ref]'s file cannot be served for [reason], at [nowNanos]: the first time, when the reason
     * has changed, and once every [repeatReasonAfterMs] while it stays the same. By ref and client as well as file,
     * so two clients naming one missing picture are each told of their own.
     */
    @KdrPrivate
    fun shouldSay(ref: FileRef, reason: String, nowNanos: Long): Boolean {
        val key = "${ref.owner}/${ref.refId}/${ref.def.pointer}"
        val last = said[key]
        if (last != null && last.reason == reason && nowNanos - last.atNanos < repeatReasonAfterMs * nanosPerMs) return false
        // A ref taken out of its configuration is never read again to be forgotten; this is what bounds the map.
        if (said.size >= maxRemembered) said.clear()
        said[key] = Said(reason, nowNanos)
        return true
    }

    private fun sayWhy(cxt: KdrCxt, ref: FileRef, dir: String?, reason: String?) {
        if (!shouldSay(ref, reason.orEmpty(), System.nanoTime())) return
        // By where it was looked for, which for a client's own ref is not the root and path its entry holds -- and
        // for an inherited one with no file of the client's, the template's directory, looked in last.
        LogFileRef.warn(cxt) { "The local file '${sought(ref, dir)}' of ref '${ref.refId}' (client '${ref.owner}') cannot be served: $reason." }
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

    /**
     * What [ref]'s file was found to be, read only when it has not been looked at as it now is: its record, or null
     * when there is none to serve -- no such root, no such file, a file that is no image, or for a client's own
     * ref a root that is not open.
     */
    private fun lookedAt(cxt: KdrCxt, ref: FileRef): FileMeta? {
        val found = located(ref)
        val at = found.at
        if (at.real == null) {
            sayWhy(cxt, ref, found.dir, at.reason)
            return null
        }
        // The real path is in the stamp, so one key cannot answer for two files; the key is only what is replaced.
        val stamp = "${at.real}|${at.size}|${at.modifiedMs}"
        val key = sought(ref, found.dir)
        looked[key]?.takeIf { it.stamp == stamp }?.let { return it.meta }
        return readFrom(cxt, ref, found)?.meta.also {
            if (looked.size >= maxRemembered) looked.clear()
            looked[key] = Looked(stamp, it)
        }
    }

    /**
     * That [ref]'s file is not there to be served, in one sentence whatever the reason. It says where the entry
     * points and, for a client's own ref, that it is the client's own directory there -- which is all its
     * administrator needs, and nothing of what else the root holds.
     */
    override fun fileProblem(cxt: KdrCxt, ref: FileRef): String? {
        if (lookedAt(cxt, ref) != null) return null
        val where = when {
            !ownDirectory -> ""
            ref.inheritedFrom != null -> " in the client's own directory of that root, or the template '${ref.inheritedFrom}''s"
            else -> " in the client's own directory of that root"
        }
        return "names the local file '${ref.def.root}/${ref.def.path}'$where, which is not there to be served as an image."
    }

    /** The file's record as it was last found to be while it has not changed ([lookedAt]): what a listing answers. */
    override fun meta(cxt: KdrCxt, ref: FileRef): FileMeta? = lookedAt(cxt, ref)

    /**
     * The file as it is now, described as the store describes one: the type the bytes were found to be, their
     * hash, the file's own name, and -- as when it was "uploaded" -- when the file last changed. Its `client` is the
     * client whose directory it was read from: a template's, for an inherited ref served its default (issue #1180).
     */
    override fun read(cxt: KdrCxt, ref: FileRef): StoredFile? = readFrom(cxt, ref, located(ref))

    /** The file [found] says is there, read from where it was found -- not looked for again. */
    private fun readFrom(cxt: KdrCxt, ref: FileRef, found: Located): StoredFile? {
        val dir = found.dir
        val read = roots.readAt(found.at)
        val file = read.file
        if (file == null) {
            sayWhy(cxt, ref, dir, read.reason)
            return null
        }
        // Mended: were it to break again, that is news.
        if (said.isNotEmpty()) said.remove("${ref.owner}/${ref.refId}/${ref.def.pointer}")
        val meta = FileMeta(
            mimeType = file.kind.mimeType, size = file.bytes.size.toLong(), hash = file.bytes.sha256Hex(),
            uploadedAt = file.modifiedAt, fileName = file.name, client = dir ?: ref.owner,
        )
        return StoredFile(file.bytes, meta)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** How long the same reason for the same ref goes unrepeated in the log. */
        const val repeatReasonAfterMs = 10 * 60_000L

        /** How many refs and files are remembered at once before what is remembered is dropped and learned again. */
        const val maxRemembered = 5_000

        private const val nanosPerMs = 1_000_000L
    }
}
