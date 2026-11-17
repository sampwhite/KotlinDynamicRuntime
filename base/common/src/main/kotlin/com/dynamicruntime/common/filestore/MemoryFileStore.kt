package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.sha256Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.time.Instant

/**
 * A file store held in the JVM and gone with it (issue #1129): what the unit tests run on, and a node running
 * in memory only, so neither writes a file anywhere. A file's content and metadata are one entry, so there is
 * no part-way state to guard; the rule about a key that is also a folder is kept, so that a key scheme a test
 * passes with is one a filesystem will take ([FileStore]).
 *
 * **Nothing bounds it.** Every file stored stays on the heap until it is deleted or the JVM stops, which is
 * right for a test and for a developer's throwaway node and wrong for anything that keeps running while files
 * keep arriving: that node names another store.
 */
class MemoryFileStore : FileStore {
    override val providerType: String = FST.memory
    override val location: String = FST.memory

    private val buckets = ConcurrentHashMap<String, ConcurrentSkipListMap<String, StoredFile>>()

    /** An upload under way: its key, when it began and last took a part, and its parts by number. */
    private class Staged(val key: String, val startedAt: Instant) {
        val parts = ConcurrentSkipListMap<Int, ByteArray>()

        @Volatile
        var lastActiveAt: Instant = startedAt
    }

    /** The uploads under way in each bucket, by token. */
    private val staged = ConcurrentHashMap<String, ConcurrentHashMap<String, Staged>>()

    private fun stagedIn(bucket: String): ConcurrentHashMap<String, Staged> = staged.computeIfAbsent(bucket) { ConcurrentHashMap() }

    private fun files(bucket: String): ConcurrentSkipListMap<String, StoredFile> =
        buckets.computeIfAbsent(bucket) { ConcurrentSkipListMap() }

    override fun ensureBucket(cxt: KdrCxt, bucket: String): List<String> {
        files(bucket)
        return emptyList()
    }

    override fun put(cxt: KdrCxt, bucket: String, key: String, bytes: ByteArray, meta: FileMeta) {
        val files = files(bucket)
        // Checked and stored under one lock, or two puts could each pass the check against the other's absence.
        synchronized(files) {
            folderClash(files, key)?.let { throw KdrException(it, null, EXC.conflict) }
            files[key] = StoredFile(bytes.copyOf(), meta)
        }
    }

    /** What stops [key] being stored beside the keys already in [files] -- one of them a folder of the other. */
    private fun folderClash(files: ConcurrentSkipListMap<String, StoredFile>, key: String): String? {
        val under = files.ceilingKey("$key/")
        if (under != null && under.startsWith("$key/")) {
            return "Cannot store '$key': it is a folder of '$under', and a key cannot be both a file and a folder."
        }
        var parent = key.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            if (files.containsKey(parent)) {
                return "Cannot store '$key': '$parent' is a file, and a key cannot be both a file and a folder."
            }
            parent = parent.substringBeforeLast('/', "")
        }
        return null
    }

    // A copy each way: content handed back must not be a handle on what is stored.
    override fun get(cxt: KdrCxt, bucket: String, key: String): StoredFile? =
        files(bucket)[key]?.let { StoredFile(it.bytes.copyOf(), it.meta) }

    override fun meta(cxt: KdrCxt, bucket: String, key: String): FileMeta? = files(bucket)[key]?.meta

    override fun delete(cxt: KdrCxt, bucket: String, key: String): Boolean = files(bucket).remove(key) != null

    /** Each file as its two objects, though here they are one entry: so a check walks this store as any other. */
    override fun objects(cxt: KdrCxt, bucket: String, prefix: String): List<StoredObject> =
        files(bucket).tailMap(prefix, true).entries.asSequence()
            .takeWhile { it.key.startsWith(prefix) }
            .flatMap { (key, file) ->
                listOf(StoredObject(key, file.meta.uploadedAt), StoredObject(FileMeta.metaKey(key), file.meta.uploadedAt))
            }.sortedBy { it.name }.toList()

    /** A file's two halves are one entry here, so a write leaves nothing part-way to clear but an upload never finished. */
    override fun clearLeftover(cxt: KdrCxt, bucket: String, leftover: FileLeftover, cutoff: Instant): Boolean {
        if (leftover.kind != FLEFT.staged) return false
        val upload = stagedIn(bucket)[leftover.name]?.takeIf { it.key == leftover.key && it.lastActiveAt <= cutoff } ?: return false
        return stagedIn(bucket).remove(leftover.name, upload)
    }

    /** Nothing here can come to not match its metadata, so there is never metadata to rewrite. */
    override fun rewriteMeta(cxt: KdrCxt, bucket: String, key: String, meta: FileMeta, cutoff: Instant): Boolean = false

    override fun openRange(cxt: KdrCxt, bucket: String, key: String, offset: Long, length: Long): InputStream? {
        val file = files(bucket)[key] ?: return null
        // A copy of the range alone, so the stream is no handle on what is stored.
        return ByteArrayInputStream(file.bytes.copyOfRange(offset.toInt(), (offset + length).toInt()))
    }

    override fun beginStaged(cxt: KdrCxt, bucket: String, key: String, mimeType: String): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        stagedIn(bucket)[token] = Staged(key, cxt.now())
        return token
    }

    override fun putPart(cxt: KdrCxt, bucket: String, key: String, token: String, number: Int, bytes: ByteArray): StagedPart {
        val upload = stagedIn(bucket)[token]?.takeIf { it.key == key } ?: throw noUpload(bucket, key, token)
        upload.parts[number] = bytes.copyOf()
        upload.lastActiveAt = cxt.now()
        return StagedPart(number, bytes.size.toLong(), bytes.sha256Hex())
    }

    override fun completeStaged(cxt: KdrCxt, bucket: String, key: String, token: String, parts: List<StagedPart>, meta: FileMeta) {
        val uploads = stagedIn(bucket)
        val upload = uploads[token]?.takeIf { it.key == key } ?: throw noUpload(bucket, key, token)
        val out = ByteArrayOutputStream()
        for (part in parts) {
            val bytes = upload.parts[part.number]
            if (bytes == null || bytes.size.toLong() != part.size || bytes.sha256Hex() != part.tag) {
                throw KdrException("Part ${part.number} of the upload of '$key' is not the part that was stored.", null, EXC.conflict)
            }
            out.write(bytes)
        }
        put(cxt, bucket, key, out.toByteArray(), meta)
        uploads.remove(token)
    }

    override fun abortStaged(cxt: KdrCxt, bucket: String, key: String, token: String): Boolean {
        val uploads = stagedIn(bucket)
        return uploads[token]?.takeIf { it.key == key }?.let { uploads.remove(token) } != null
    }

    override fun stagedUploads(cxt: KdrCxt, bucket: String): List<StagedUpload> =
        stagedIn(bucket).map { (token, upload) -> StagedUpload(upload.key, token, upload.startedAt, upload.lastActiveAt) }.sortedBy { it.key }

    override fun move(cxt: KdrCxt, bucket: String, from: String, to: String): Boolean {
        val files = files(bucket)
        synchronized(files) {
            val file = files[from] ?: return false
            if (from == to) return true
            folderClash(files, to)?.let { throw KdrException(it, null, EXC.conflict) }
            files[to] = file
            files.remove(from)
        }
        return true
    }

    private fun noUpload(bucket: String, key: String, token: String): KdrException =
        KdrException("There is no upload '$token' of '$key' in the bucket '$bucket' under way.", null, EXC.notFound)

    override fun list(cxt: KdrCxt, bucket: String, keyPrefix: String, max: Int): List<FileEntry> =
        files(bucket).tailMap(keyPrefix, true).entries.asSequence()
            .takeWhile { it.key.startsWith(keyPrefix) }
            .take(max)
            .map { FileEntry(it.key, it.value.meta) }
            .toList()
}
