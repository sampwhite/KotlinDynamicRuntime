package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.sha256Hex
import kotlin.time.Instant

/**
 * A file store in S3 (issue #1129), or in anything that speaks its API. Each declared bucket is an S3 bucket
 * of the same name behind [bucketPrefix] -- bucket names are shared by everyone who uses S3, so a deployment
 * says what its own begin with -- and each file is two objects, its content at its key and its metadata
 * beside it.
 *
 * **The application never creates an S3 bucket.** A bucket carries decisions that are not this code's to make
 * -- its region, who may read it, how long it keeps what is deleted -- so a declared bucket that is not there
 * is reported at startup ([ensureBucket]) for whoever runs the deployment to provision.
 *
 * **A file costs two requests to read and two to write**, where S3's own object metadata would cost one. That
 * is the price of the metadata being an object like the content ([FileStore]): the same file on every provider,
 * readable without this code, and not held to the two kilobytes S3 allows an object's own metadata. A listing
 * reads each listed file's metadata with a request of its own, so it costs a request per file, up to the most
 * it was asked for: fine for the folder of one owner's files, not a way to walk a large bucket.
 */
class S3FileStore(val api: S3Api, val bucketPrefix: String = "") : FileStore {
    override val providerType: String = FST.s3
    override val location: String = api.location + if (bucketPrefix.isEmpty()) "" else ", buckets named '$bucketPrefix...'"

    init {
        FNAME.bucketPrefixProblem(bucketPrefix)?.let { throw KdrException("Not a bucket-name prefix: '$bucketPrefix' ($it).") }
    }

    /** The S3 bucket behind the declared [bucket]. */
    fun physicalBucket(bucket: String): String = bucketPrefix + bucket

    override fun ensureBucket(cxt: KdrCxt, bucket: String): List<String> {
        val physical = physicalBucket(bucket)
        if (physical.length > FNAME.maxPhysicalBucketLength) {
            return listOf(
                "The S3 bucket '$physical', for the bucket '$bucket', has a name longer than the " +
                    "${FNAME.maxPhysicalBucketLength} characters S3 allows; shorten the prefix.",
            )
        }
        return try {
            if (api.headBucket(physical)) {
                emptyList()
            } else {
                listOf("The S3 bucket '$physical', for the bucket '$bucket', does not exist. The application does not create buckets.")
            }
        } catch (e: KdrException) {
            listOf("The S3 bucket '$physical', for the bucket '$bucket', could not be checked: ${e.message}")
        }
    }

    override fun put(cxt: KdrCxt, bucket: String, key: String, bytes: ByteArray, meta: FileMeta) {
        val physical = physicalBucket(bucket)
        // S3 swaps an object whole, so until this returns the file that was there is untouched -- and still is
        // if it fails.
        api.put(physical, key, bytes, meta.mimeType)
        try {
            api.put(physical, FileMeta.metaKey(key), meta.toFileText().toByteArray(Charsets.UTF_8), FileMeta.metaMimeType)
        } catch (e: KdrException) {
            // The content is the new file's and its metadata could not be written. Left alone, the old metadata
            // would describe content it does not match; removed, this is a write that did not finish.
            try {
                api.delete(physical, FileMeta.metaKey(key))
            } catch (_: KdrException) {
                // S3 is failing; the write's own failure is the one to report.
            }
            throw e
        }
    }

    override fun get(cxt: KdrCxt, bucket: String, key: String): StoredFile? {
        val meta = meta(cxt, bucket, key) ?: return null
        val bytes = api.get(physicalBucket(bucket), key) ?: return null
        return StoredFile(bytes, meta)
    }

    override fun meta(cxt: KdrCxt, bucket: String, key: String): FileMeta? =
        api.get(physicalBucket(bucket), FileMeta.metaKey(key))?.let { FileMeta.fromFileText(it.toString(Charsets.UTF_8)) }

    override fun delete(cxt: KdrCxt, bucket: String, key: String): Boolean {
        val physical = physicalBucket(bucket)
        // S3 answers a delete the same whether or not there was anything there, so that is asked first.
        val existed = api.head(physical, FileMeta.metaKey(key))
        api.delete(physical, FileMeta.metaKey(key))
        api.delete(physical, key)
        return existed
    }

    override fun list(cxt: KdrCxt, bucket: String, keyPrefix: String, max: Int): List<FileEntry> {
        val physical = physicalBucket(bucket)
        // A file is two objects, so twice the keys and a few to spare -- more only when objects that are no
        // file's (content with no metadata, or the reverse) took the room.
        var asked = max.toLong() * 2 + spareKeys
        while (true) {
            val maxKeys = minOf(asked, Int.MAX_VALUE.toLong()).toInt()
            val listed = api.list(physical, keyPrefix, maxKeys)
            val whole = listed.size < maxKeys || maxKeys == Int.MAX_VALUE
            val keys = listed.toSet()
            // A listing that stopped short may have stopped between a file's content and its metadata, which
            // other keys sort between ('a', 'a-b', 'a.meta.json'). Such a file cannot be told from a stray, so
            // nothing from the first of them on is answered from this listing.
            val unsettled = if (whole) null else listed.lastOrNull()?.let { last ->
                listed.filter { !it.endsWith(FileMeta.metaSuffix) && FileMeta.metaKey(it) > last }.minOrNull()
            }
            val entries = listed.asSequence()
                .filter { it.endsWith(FileMeta.metaSuffix) }
                .map { it.removeSuffix(FileMeta.metaSuffix) }
                .filter { it in keys && FNAME.keyProblem(it) == null && (unsettled == null || it < unsettled) }
                .sorted()
                .mapNotNull { key -> meta(cxt, bucket, key)?.let { FileEntry(key, it) } }
                .take(max)
                .toList()
            if (entries.size >= max || whole) return entries
            asked *= 2
        }
    }

    override fun objects(cxt: KdrCxt, bucket: String): List<StoredObject> = api.listObjects(physicalBucket(bucket), "")

    /**
     * S3 has no lock to hold, so what is asked again is asked a moment before the removal: which of the key's two
     * objects are there and when each was last written, in one listing, and the metadata's text when it is there --
     * a write that came to the key since it was found has rewritten it. What is left is a write that lands between
     * that and the removal, which [cutoff] makes a write of a key nothing has touched for that long.
     */
    override fun clearLeftover(cxt: KdrCxt, bucket: String, leftover: FileLeftover, cutoff: Instant): Boolean {
        // An S3 store writes no temporary objects ([tmpKeyOf]), so nothing is ever found as one.
        if (leftover.kind == FLEFT.tmp) return false
        val key = FNAME.checkKey(leftover.key)
        val physical = physicalBucket(bucket)
        val there = objectsOf(physical, key)
        val metaText = if (FileMeta.metaKey(key) in there) api.get(physical, FileMeta.metaKey(key))?.toString(Charsets.UTF_8) else null
        if (FileStoreCheck.kindNow(key in there, metaText) != leftover.kind) return false
        if (there.values.any { it > cutoff }) return false
        api.delete(physical, FileMeta.metaKey(key))
        api.delete(physical, key)
        return true
    }

    override fun rewriteMeta(cxt: KdrCxt, bucket: String, key: String, meta: FileMeta, cutoff: Instant): Boolean {
        val physical = physicalBucket(bucket)
        val current = meta(cxt, bucket, FNAME.checkKey(key)) ?: return false
        val hash = api.get(physical, key)?.sha256Hex() ?: return false
        if (hash != meta.hash || hash == current.hash || objectsOf(physical, key).values.any { it > cutoff }) return false
        api.put(physical, FileMeta.metaKey(key), meta.toFileText().toByteArray(Charsets.UTF_8), FileMeta.metaMimeType)
        return true
    }

    /** [key]'s own objects in [physical] -- its content and its metadata, those there -- with when each was written. */
    private fun objectsOf(physical: String, key: String): Map<String, Instant> =
        api.listObjects(physical, key).filter { it.name == key || it.name == FileMeta.metaKey(key) }.associate { it.name to it.modifiedAt }

    @Suppress("ConstPropertyName")
    companion object {
        /** Keys asked for beyond two per file, so a stray object or two does not cost a second listing. */
        const val spareKeys = 16
    }
}
