package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.sha256Hex
import kotlin.time.Duration
import kotlin.time.Instant

/** What a write or a delete that stopped part-way can leave in a bucket, as [FileStoreCheck] finds it (issue #1147). */
@Suppress("ConstPropertyName")
object FLEFT {
    /** A directory store's temporary file of a write ([LocalFileStore.tmpSuffix]): nothing reads it. */
    const val tmp = "tmp"

    /** Content with no metadata beside it: a new file whose write stopped between the two. It is no file. */
    const val contentOnly = "contentOnly"

    /** Metadata with no content: its `meta` answers for a file that `get` and `list` say is not there. */
    const val metaOnly = "metaOnly"

    /** Content beside metadata that cannot be read: no file, as metadata that is not whole makes it. */
    const val unreadableMeta = "unreadableMeta"

    /**
     * Content its metadata does not match: a replace that stopped between its two writes, leaving the new content
     * under the old metadata -- or content damaged where it is kept. `meta` and `list` answer for it and `get`
     * refuses it, on every read, until it is repaired or stored again.
     */
    const val mismatch = "mismatch"

    /** In [FileMeta.extra] of metadata a repair rewrote: when it did. */
    const val metaRewrittenAt = "metaRewrittenAt"

    /** In [FileMeta.extra] of metadata a repair rewrote: the hash the metadata it replaced recorded. */
    const val metaRewrittenFromHash = "metaRewrittenFromHash"
}

/**
 * One thing [FileStoreCheck] found in [bucket] that is not a whole file: of the file at [key], the object [name]
 * (the content's own, the metadata's, or a temporary file's), its [kind] ([FLEFT]), and the latest moment any of
 * it changed. For [FLEFT.mismatch], [meta] is the metadata there and [contentHash] and [contentSize] what the
 * content is.
 */
class FileLeftover(
    val bucket: String,
    val key: String,
    val name: String,
    val kind: String,
    val modifiedAt: Instant,
    val meta: FileMeta? = null,
    val contentHash: String? = null,
    val contentSize: Long? = null,
) {
    /** What it is, in a sentence for a report. */
    fun describe(): String = when (kind) {
        FLEFT.tmp -> "'$name' is a temporary file of a write of '$key' that did not finish; nothing reads it."
        FLEFT.contentOnly -> "'$key' has content and no metadata: a write that stopped between the two. It is no file."
        FLEFT.metaOnly -> "'$key' has metadata and no content: its metadata answers for a file that cannot be read."
        FLEFT.unreadableMeta -> "'$key' has content beside metadata that cannot be read. It is no file."
        FLEFT.mismatch ->
            "'$key' does not match its metadata: its content hashes to $contentHash, its metadata records " +
                "${meta?.hash}. Every read of it is refused."
        else -> "'$name': $kind."
    }

    /** What repairing it does, in a sentence for a report. */
    fun repairText(): String = when (kind) {
        FLEFT.mismatch ->
            "rewrite its metadata from the content there -- the size and hash recomputed, the type, name and who " +
                "stored it carried over -- so it can be read again"
        FLEFT.unreadableMeta -> "remove it, content and metadata"
        else -> "remove '$name'"
    }
}

/**
 * Finds what writes and deletes that stopped part-way left in a file store, and repairs it when asked (issue
 * #1147). Nothing here runs on its own: it is an operator's, through `com.dynamicruntime.script.CheckFileStore`.
 *
 * **Finding reads everything.** Every object of every bucket is listed, and every file's content is read to be
 * checked against its metadata's hash -- which is the only way to find the one leftover that stays broken
 * ([FLEFT.mismatch]). So it is not something a node does at every boot.
 *
 * **Repairing is a separate step, and nothing recent is touched.** A write under way looks exactly like a write
 * that stopped, so only what changed no later than the cutoff given is repaired, and each is asked again as it is
 * repaired ([FileStore.clearLeftover], [FileStore.rewriteMeta]) -- on a directory store under the key's lock, which a
 * write holds throughout, so there what is found there belongs to nobody. A file whose content its metadata does not
 * match keeps its content: the old content is gone, and the caller did ask for this one to be stored.
 */
object FileStoreCheck {
    /**
     * What [store] holds in each of [buckets] that is not a whole file, in bucket and name order. Content is read
     * only for the objects that have both halves, which is how a mismatch is found.
     */
    fun find(cxt: KdrCxt, store: FileStore, buckets: List<String>): List<FileLeftover> =
        buckets.flatMap { bucket -> findIn(cxt, store, bucket) }

    private fun findIn(cxt: KdrCxt, store: FileStore, bucket: String): List<FileLeftover> {
        val objects = store.objects(cxt, bucket).associateBy { it.name }
        val out = mutableListOf<FileLeftover>()
        val keys = sortedSetOf<String>()
        for (obj in objects.values) {
            val tmpOf = store.tmpKeyOf(obj.name)
            when {
                tmpOf != null -> out.add(FileLeftover(bucket, tmpOf, obj.name, FLEFT.tmp, obj.modifiedAt))
                obj.name.endsWith(FileMeta.metaSuffix) -> obj.name.removeSuffix(FileMeta.metaSuffix).takeIf { FNAME.keyProblem(it) == null }?.let { keys.add(it) }
                FNAME.keyProblem(obj.name) == null -> keys.add(obj.name)
            }
        }
        for (key in keys) {
            val content = objects[key]
            val metaObject = objects[FileMeta.metaKey(key)]
            val changed = listOfNotNull(content, metaObject).maxOf { it.modifiedAt }
            when {
                content == null -> out.add(FileLeftover(bucket, key, FileMeta.metaKey(key), FLEFT.metaOnly, changed))
                metaObject == null -> out.add(FileLeftover(bucket, key, key, FLEFT.contentOnly, changed))
                else -> {
                    // Both halves are there; read whole, the file is either whole or one of the two below. Its
                    // metadata not read is metadata that cannot be -- or a delete since the listing, which a repair
                    // asks again about before it touches anything.
                    val read = store.get(cxt, bucket, key)
                    when {
                        read == null && store.meta(cxt, bucket, key) == null ->
                            out.add(FileLeftover(bucket, key, key, FLEFT.unreadableMeta, changed))
                        read == null -> {}
                        read.bytes.sha256Hex() != read.meta.hash -> out.add(
                            FileLeftover(
                                bucket, key, key, FLEFT.mismatch, changed, read.meta,
                                contentHash = read.bytes.sha256Hex(), contentSize = read.bytes.size.toLong(),
                            ),
                        )
                    }
                }
            }
        }
        return out.sortedWith(compareBy({ it.bucket }, { it.name }))
    }

    /** What happened to one [leftover] asked to be repaired: whether it was, and if not, why not. */
    class Repair(val leftover: FileLeftover, val done: Boolean, val note: String)

    /**
     * Repairs each of [leftovers] in [store] that last changed no later than [now] less [minAge] -- removing it, or
     * for a [FLEFT.mismatch] rewriting its metadata ([rebuiltMeta]) -- and says of each what happened.
     */
    fun repair(cxt: KdrCxt, store: FileStore, leftovers: List<FileLeftover>, now: Instant, minAge: Duration): List<Repair> {
        val cutoff = now - minAge
        return leftovers.map { leftover ->
            if (leftover.modifiedAt > cutoff) {
                return@map Repair(leftover, false, "left alone: it changed at ${leftover.modifiedAt.formatDate()}, too recently to tell from a write under way")
            }
            val done = if (leftover.kind == FLEFT.mismatch) {
                store.rewriteMeta(cxt, leftover.bucket, leftover.key, rebuiltMeta(leftover, now), cutoff)
            } else {
                store.clearLeftover(cxt, leftover.bucket, leftover, cutoff)
            }
            Repair(leftover, done, if (done) "repaired: ${leftover.repairText()}" else "left alone: it has changed since it was found")
        }
    }

    /**
     * The metadata a [FLEFT.mismatch] is repaired with: what the content is -- its size and hash -- and what the
     * metadata there said of everything else, which is the best account there is of a file whose own was not
     * written: its type, name, client and who stored it. That the repair wrote it, when, and over what hash, is kept
     * in [FileMeta.extra], since the type in particular is carried over and not known.
     */
    fun rebuiltMeta(leftover: FileLeftover, now: Instant): FileMeta {
        val old = leftover.meta
        val size = leftover.contentSize
        val hash = leftover.contentHash
        if (leftover.kind != FLEFT.mismatch || old == null || size == null || hash == null) {
            throw KdrException("'${leftover.key}' is not a mismatch found with its metadata and its content's size and hash; there is no metadata to rebuild.")
        }
        return FileMeta(
            mimeType = old.mimeType,
            size = size,
            hash = hash,
            uploadedAt = old.uploadedAt,
            fileName = old.fileName,
            uploadedBy = old.uploadedBy,
            client = old.client,
            extra = old.extra + mapOf(FLEFT.metaRewrittenAt to now.formatDate(), FLEFT.metaRewrittenFromHash to old.hash),
        )
    }

    /**
     * What a key is now, from whether its content is there ([contentThere]) and its metadata's [metaText] -- for a
     * store asking again as it repairs ([FileStore.clearLeftover]): the [FLEFT] kind, or null for a whole file or
     * nothing.
     */
    fun kindNow(contentThere: Boolean, metaText: String?): String? = when {
        !contentThere && metaText == null -> null
        !contentThere -> FLEFT.metaOnly
        metaText == null -> FLEFT.contentOnly
        FileMeta.fromFileText(metaText) == null -> FLEFT.unreadableMeta
        else -> null
    }
}
