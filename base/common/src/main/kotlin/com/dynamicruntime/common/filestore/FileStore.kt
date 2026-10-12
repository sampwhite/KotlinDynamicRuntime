package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContentData

/** The kinds of file store a deployment can run on; a `KDR_FILE_STORE_TYPE` value. */
@Suppress("ConstPropertyName")
object FST {
    /** Held in the JVM and gone with it: unit tests, and a node running in memory only. */
    const val memory = "memory"

    /** A directory on this machine. */
    const val local = "local"

    /** AWS S3, or a service that speaks its API. */
    const val s3 = "s3"

    val all = listOf(memory, local, s3)
}

/**
 * A bucket a component keeps files in (issue #1129), declared with `SchemaCollector.addFileBucket`.
 *
 * **A bucket is the file store's own unit of addressing, on every provider** -- not something S3 has and the
 * others imitate. Every call names one. What it *is* differs by provider: a directory under the store's root
 * on a filesystem, a map in memory, a bucket in S3. Declaring it is what makes the store check, at startup,
 * that it can be served: nothing is kept in a bucket no component declared.
 */
class FileBucketDef(
    /** The bucket's name, held to [FNAME.checkBucket]. */
    val name: String,
    /** What is kept in it, for whoever reads the boot report or provisions the bucket. */
    val description: String,
) {
    init {
        FNAME.checkBucket(name)
    }
}

/**
 * The rules a bucket name and a key are held to. The service applies them to everything a caller names, so a
 * provider is only ever handed names that passed.
 */
@Suppress("ConstPropertyName")
object FNAME {
    const val minBucketLength = 3
    const val maxBucketLength = 40

    /** The longest name S3 gives a bucket: what a configured prefix and a bucket name must fit in together. */
    const val maxPhysicalBucketLength = 63
    const val maxKeyLength = 512
    const val maxSegmentLength = 128

    private fun isBucketChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '-'

    private fun isKeyChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-'

    /**
     * What is wrong with [name] as a bucket name, or null when it is one: lowercase letters, digits and hyphens,
     * beginning and ending with a letter or digit, [minBucketLength] to [maxBucketLength] characters. That is a
     * subset of what S3 accepts, so a name that passes here is one every provider can carry -- a bucket is
     * never renamed to fit the provider a deployment happens to run on.
     */
    fun bucketProblem(name: String): String? = when {
        name.length !in minBucketLength..maxBucketLength ->
            "it must be $minBucketLength to $maxBucketLength characters long"
        !name.all { isBucketChar(it) } -> "it may hold only lowercase letters, digits and hyphens"
        name.first() == '-' || name.last() == '-' -> "it must begin and end with a letter or digit"
        else -> null
    }

    /** [name], or a refusal saying why it is not a bucket name ([bucketProblem]). */
    fun checkBucket(name: String): String {
        bucketProblem(name)?.let { throw KdrException("Not a file-store bucket name: '$name' ($it).", null, EXC.badInput) }
        return name
    }

    /**
     * What is wrong with [key] as a file's key, or null when it is one: segments separated by single slashes,
     * each of letters, digits, `.`, `_` and `-`, none of them `.` or `..`, none ending in the metadata suffix
     * ([FileMeta.metaSuffix], which names the file beside a file), at most [maxKeyLength] characters in all.
     *
     * A key is **composed by the code that stores the file**, out of ids it already holds. It is never a name
     * somebody uploaded a file under: that name is attacker-supplied, and letting it choose where a file goes
     * is how an upload overwrites another user's file. The name is kept, as metadata ([FileMeta.fileName]).
     */
    fun keyProblem(key: String): String? {
        if (key.isEmpty()) return "it is empty"
        if (key.length > maxKeyLength) return "it is longer than $maxKeyLength characters"
        for (segment in key.split('/')) {
            when {
                segment.isEmpty() -> return "it has an empty segment (a leading, trailing or doubled slash)"
                segment == "." || segment == ".." -> return "it has a '$segment' segment"
                segment.length > maxSegmentLength -> return "a segment is longer than $maxSegmentLength characters"
                !segment.all { isKeyChar(it) } -> return "a segment may hold only letters, digits, '.', '_' and '-'"
                segment.endsWith(FileMeta.metaSuffix) -> return "a segment ends in '${FileMeta.metaSuffix}', which names a file's metadata"
            }
        }
        return null
    }

    /** [key], or a refusal saying why it is not a key ([keyProblem]). */
    fun checkKey(key: String): String {
        keyProblem(key)?.let { throw KdrException("Not a file-store key: '$key' ($it).", null, EXC.badInput) }
        return key
    }

    /**
     * [prefix], or a refusal: what a listing may be narrowed by -- nothing, a key, or a key followed by a slash
     * (the files under it). A prefix is matched against the start of a key as text, as S3 matches one.
     */
    fun checkKeyPrefix(prefix: String): String {
        if (prefix.isEmpty()) return prefix
        keyProblem(prefix.removeSuffix("/"))?.let {
            throw KdrException("Not a file-store key prefix: '$prefix' ($it).", null, EXC.badInput)
        }
        return prefix
    }
}

/** One file of a listing: where it is, and what is known about it. A listing never reads a file's content. */
class FileEntry(val key: String, val meta: FileMeta)

/** A file as it was stored: its content, and the metadata kept beside it. */
class StoredFile(val bytes: ByteArray, val meta: FileMeta) {
    /**
     * This file as content ready to send: its bytes under the type it was stored with, the name it was uploaded
     * under offered as the name to save it as, and its hash. [inLine] false makes it a download.
     */
    fun toContentData(inLine: Boolean = true): ContentData =
        ContentData(bytes, meta.mimeType, saveAsFilename = meta.fileName, inLine = inLine, hash = meta.hash)
}

/**
 * A place files are kept: one of the providers behind [FileStoreService] (issue #1129), which is what the rest
 * of the code calls. A provider is handed names the service has already checked ([FNAME]) and buckets a
 * component declared, and does nothing but keep and return what it is given.
 *
 * **Two things are stored for every file: its content, and its metadata beside it** ([FileMeta], at
 * [FileMeta.metaKey]). A file exists when its metadata does, and the order of writes keeps that true through
 * a failure part-way:
 * - a put removes any metadata already there, writes the content, then writes the metadata;
 * - a delete removes the metadata, then the content.
 *
 * So content without metadata is a write that did not finish, or a delete that did not, and reads as no file
 * at all -- never as a file with nothing known about it, and never as new content under old metadata.
 *
 * **A key that names a file is not also a folder of others**: with `a/b` stored, `a` cannot be, nor the other
 * way round. A filesystem cannot hold both, so the two providers a developer meets first ([FST.memory],
 * [FST.local]) refuse it, and a key scheme that works on one provider works on all three.
 */
interface FileStore {
    /** Which kind of store this is, an [FST] value. */
    val providerType: String

    /** Where this store keeps its files, in words: for the startup line and the boot report. */
    val location: String

    /**
     * Makes [bucket] ready to hold files where that is this provider's to do, and answers what stops it being
     * so -- nothing when it is ready. Never throws for a bucket that cannot be served: that is a finding.
     */
    fun ensureBucket(cxt: KdrCxt, bucket: String): List<String>

    /** Stores [bytes] at [key] with [meta] beside it, replacing a file already there. */
    fun put(cxt: KdrCxt, bucket: String, key: String, bytes: ByteArray, meta: FileMeta)

    /** The file at [key], or null when there is none. */
    fun get(cxt: KdrCxt, bucket: String, key: String): StoredFile?

    /** The metadata of the file at [key] without its content, or null when there is no such file. */
    fun meta(cxt: KdrCxt, bucket: String, key: String): FileMeta?

    /** Removes the file at [key]; whether there was one. */
    fun delete(cxt: KdrCxt, bucket: String, key: String): Boolean

    /** The files whose key begins with [keyPrefix] (every file, when it is empty), in key order. */
    fun list(cxt: KdrCxt, bucket: String, keyPrefix: String): List<FileEntry>
}
