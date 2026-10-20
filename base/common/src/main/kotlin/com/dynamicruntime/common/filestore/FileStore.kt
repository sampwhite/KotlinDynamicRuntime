package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContentData
import kotlin.time.Instant

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

    /** The most folders a key can be under: every segment takes a character and a slash. */
    const val maxKeyDepth = maxKeyLength / 2

    /**
     * The most files a listing answers unless it is asked for more ([FileStoreService.list]). A listing is for
     * the folder of one owner's files; one that would be longer is refused rather than cut short.
     */
    const val defaultListLimit = 1000

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

    /**
     * What is wrong with [prefix] as the start of a deployment's S3 bucket names, or null: empty, or what a
     * bucket name is made of, beginning with a letter or digit. It may end in a hyphen, as `acme-prod-` does,
     * since a bucket's own name follows; the two together must fit in [maxPhysicalBucketLength].
     */
    fun bucketPrefixProblem(prefix: String): String? = when {
        prefix.isEmpty() -> null
        !prefix.all { isBucketChar(it) } -> "it may hold only lowercase letters, digits and hyphens"
        prefix.first() == '-' -> "it must begin with a letter or digit"
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
     * The folder every client's files are under, in whichever bucket they are kept (issue #1282): a client's web
     * assets and its file refs are `clients/<owner>/…`. Such a file is reached through [FileStoreService.forOwner],
     * which takes that client's folder and no other, and never through the service's own calls, which refuse a key
     * in this folder.
     */
    const val clientsFolder = "clients/"

    /**
     * The folder of [owner]'s files: every key of a client's file begins with it. [owner] is one segment of a key --
     * never a path, which would name a folder inside another client's.
     */
    fun clientKeyPrefix(owner: String): String {
        val problem = if ('/' in owner) "it holds a slash" else keyProblem(owner)
        problem?.let { throw KdrException("Not a file-store owner: '$owner' ($it).", null, EXC.badInput) }
        return "$clientsFolder$owner/"
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

/**
 * One object a store holds in a bucket, whatever it is -- a file's content, its metadata, or what a write that
 * stopped part-way left behind (issue #1147) -- by its [name] in the bucket, and when it last changed.
 */
class StoredObject(val name: String, val modifiedAt: Instant)

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
 * [FileMeta.metaKey]) -- the same two on every provider, rather than a provider's own notion of metadata, so a
 * file reads the same wherever it is kept and its metadata can be read by any tool. A file exists when its
 * metadata does, and the order of writes keeps that true through a failure part-way:
 * - a put writes the content, then the metadata;
 * - a delete removes the metadata, then the content.
 *
 * So content without metadata is a write that did not finish, or a delete that did not, and reads as no file
 * at all -- never as a file with nothing known about it.
 *
 * **Replacing a file never takes it away.** The file that was there is whole until the new content is, and a
 * replace that fails before then leaves it exactly as it was. Between the new content landing and its metadata
 * there is a moment when the content is the new file's and the metadata the old one's: [FileStoreService.get]
 * checks what it read against the recorded hash and reads again, so that moment is never served. The one thing
 * it costs is a stop at exactly that moment -- a crash, not a failure, since a put whose metadata cannot be
 * written removes the old -- which leaves content its metadata does not match, refused by a read as damaged
 * until the file is stored again. (Removing the old metadata first would trade that for a file that reads as
 * absent throughout every replace, and is lost by one that fails.)
 *
 * **What a stop part-way leaves is found by [FileStoreCheck]** (issue #1147), run by an operator
 * (`com.dynamicruntime.script.CheckFileStore`): content with no metadata, metadata with no content or that cannot
 * be read, content its metadata does not match, and a directory store's temporary files. It reports, and repairs
 * only when asked, never touching what changed too recently to tell from a write still under way.
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

    /**
     * The files whose key begins with [keyPrefix] (every file, when it is empty), in key order -- and no more
     * than [max] of them, so that what a listing costs is bounded by what was asked for.
     */
    fun list(cxt: KdrCxt, bucket: String, keyPrefix: String, max: Int = FNAME.defaultListLimit): List<FileEntry>

    /**
     * Every object in [bucket] whose name begins with [prefix] (every object, when it is empty), in name order: each
     * file's content and metadata, and whatever is neither. Names and dates only, never a read of any of them. For
     * [FileStoreCheck], which walks a whole bucket, and the operator's browser, which walks one folder (issue
     * #1297); not for serving, where [list] is what answers for files.
     */
    fun objects(cxt: KdrCxt, bucket: String, prefix: String = ""): List<StoredObject>

    /**
     * When [name], one of [objects], is a temporary object this store writes on its way to a file, the key whose
     * write left it; null for anything else. So a check knows a store's own leftovers without knowing how it names
     * them -- a store that writes none answers null for every name, and an object merely named like one is left
     * alone.
     */
    fun tmpKeyOf(name: String): String? = null

    /**
     * Removes what [leftover] found, when it is still that and none of it changed after [cutoff], and answers
     * whether it did. Asked again as it is removed -- under the key's lock where this store has one -- since a write
     * may have come to the key since it was found, and what it left then is a file.
     */
    fun clearLeftover(cxt: KdrCxt, bucket: String, leftover: FileLeftover, cutoff: Instant): Boolean

    /**
     * Puts [meta] beside the content at [key] in place of the metadata there, when that content still hashes to
     * [meta]'s hash, did not change after [cutoff], and is not what the metadata there already describes; whether
     * it did. The repair of content its metadata does not match ([FLEFT.mismatch]).
     */
    fun rewriteMeta(cxt: KdrCxt, bucket: String, key: String, meta: FileMeta, cutoff: Instant): Boolean
}
