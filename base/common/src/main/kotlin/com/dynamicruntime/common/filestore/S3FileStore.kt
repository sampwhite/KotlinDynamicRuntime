package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException

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
 * A listing reads each listed file's metadata with a request of its own, so it costs a request per file: fine
 * for the folder of one owner's files, not a way to walk a large bucket.
 */
class S3FileStore(val api: S3Api, val bucketPrefix: String = "") : FileStore {
    override val providerType: String = FST.s3
    override val location: String = api.location + if (bucketPrefix.isEmpty()) "" else ", buckets named '$bucketPrefix...'"

    init {
        prefixProblem(bucketPrefix)?.let { throw KdrException("Not a bucket-name prefix: '$bucketPrefix' ($it).") }
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
        api.delete(physical, FileMeta.metaKey(key))
        api.put(physical, key, bytes, meta.mimeType)
        api.put(physical, FileMeta.metaKey(key), meta.toFileText().toByteArray(Charsets.UTF_8), FileMeta.metaMimeType)
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

    override fun list(cxt: KdrCxt, bucket: String, keyPrefix: String): List<FileEntry> {
        val listed = api.list(physicalBucket(bucket), keyPrefix).toSet()
        return listed.asSequence()
            .filter { it.endsWith(FileMeta.metaSuffix) }
            .map { it.removeSuffix(FileMeta.metaSuffix) }
            .filter { it in listed && FNAME.keyProblem(it) == null }
            .sorted()
            .mapNotNull { key -> meta(cxt, bucket, key)?.let { FileEntry(key, it) } }
            .toList()
    }

    companion object {
        /**
         * What is wrong with [prefix] as the start of this deployment's bucket names, or null: empty, or
         * lowercase letters, digits and hyphens beginning with a letter or digit. It may end in a hyphen, as
         * `acme-prod-` does, since a bucket's own name follows.
         */
        fun prefixProblem(prefix: String): String? = when {
            prefix.isEmpty() -> null
            !prefix.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } -> "it may hold only lowercase letters, digits and hyphens"
            prefix.first() == '-' -> "it must begin with a letter or digit"
            else -> null
        }
    }
}
