package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Emptying a store's buckets (issue #1244): the file-store half of clearing a deployment's data, as removing the
 * database is the other. What a reference deployment does from time to time, through `kdr-node reset-data`.
 *
 * **A bucket is emptied, never removed.** The application never creates one, and a store's strict boot check refuses
 * the start without it -- so a reset that took the buckets away would leave a node that cannot start.
 *
 * **Every object goes, file by file**: each file's content and metadata ([FileStore.delete]), and then whatever a
 * write that stopped part-way left of a removed file ([FileStoreCheck]'s leftovers -- a local store's temporary files,
 * content with no metadata), since a reset is the moment nothing is writing. What [keep] answers true for is left
 * exactly as it is, leftovers included.
 */
object FileStoreClear {
    /** What emptying [bucket] removes and keeps: each by the key of the file it is part of, in key order. */
    class BucketPlan(val bucket: String, val remove: List<String>, val keep: List<String>)

    /**
     * What emptying [bucket] of [store] would do, keeping the files whose key [keep] answers true for. Every object
     * counts by the file it belongs to -- content, metadata, or a temporary object of its write -- so a file is
     * removed or kept whole.
     */
    fun plan(cxt: KdrCxt, store: FileStore, bucket: String, keep: (String) -> Boolean): BucketPlan {
        val keys = store.objects(cxt, bucket).map { keyOf(store, it.name) }.distinct().sorted()
        val (kept, removed) = keys.partition(keep)
        return BucketPlan(bucket, removed, kept)
    }

    /**
     * Removes what [plan] says to, and then the leftovers of those files; how many files went. Leftovers are cleared
     * however recent ([Duration.ZERO]): emptying a store is for when nothing is writing to it, and a leftover of a
     * file being removed is nothing anyone will finish.
     */
    fun clear(cxt: KdrCxt, store: FileStore, plan: BucketPlan, now: Instant): Int {
        for (key in plan.remove) store.delete(cxt, plan.bucket, key)
        val removed = plan.remove.toSet()
        val leftovers = FileStoreCheck.find(cxt, store, listOf(plan.bucket)).filter { it.key in removed }
        FileStoreCheck.repair(cxt, store, leftovers, now, Duration.ZERO)
        return plan.remove.size
    }

    /** The key of the file [name] belongs to: a temporary object's, a metadata object's, or the content's own. */
    private fun keyOf(store: FileStore, name: String): String = store.tmpKeyOf(name) ?: name.removeSuffix(FileMeta.metaSuffix)
}
