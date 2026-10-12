package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

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

    override fun list(cxt: KdrCxt, bucket: String, keyPrefix: String, max: Int): List<FileEntry> =
        files(bucket).tailMap(keyPrefix, true).entries.asSequence()
            .takeWhile { it.key.startsWith(keyPrefix) }
            .take(max)
            .map { FileEntry(it.key, it.value.meta) }
            .toList()
}
