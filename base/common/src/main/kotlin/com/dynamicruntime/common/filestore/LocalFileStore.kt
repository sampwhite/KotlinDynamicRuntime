package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A file store in a directory on this machine (issue #1129): each bucket a directory under [root], each key a
 * path under its bucket, each file's metadata beside it -- `web/logo` is described by `web/logo.meta.json`.
 * What a developer's own node and a single-machine deployment run on.
 *
 * Every file is written whole or not at all: its content and its metadata are each written out under a
 * temporary name first, and only then moved into place -- the old metadata removed, the content moved, the
 * metadata moved. So the slow part of a write can fail and leave a file already there exactly as it was, and
 * what is left to go wrong is three renames. The temporary name holds a `~`, which no key can, so it is never
 * mistaken for a file.
 *
 * Two keys that differ only by case are one file on a filesystem that ignores case, which is most developers'.
 * A key scheme that tells files apart by case alone is not portable to this provider.
 */
class LocalFileStore(val root: File) : FileStore {
    override val providerType: String = FST.local
    override val location: String get() = root.path

    /** One lock per stripe of keys, so two writes of one file in this JVM cannot interleave their two halves. */
    private val locks = Array(lockStripes) { Any() }

    private fun lockFor(bucket: String, key: String): Any = locks[Math.floorMod("$bucket/$key".hashCode(), lockStripes)]

    private fun bucketDir(bucket: String): File = File(root, FNAME.checkBucket(bucket))

    // The names are checked again here, though the service has checked them: this is where a name becomes a
    // path, and a provider called directly must be no easier to walk out of than one called through the service.
    private fun contentFile(bucket: String, key: String): File = File(bucketDir(bucket), FNAME.checkKey(key))

    private fun metaFile(bucket: String, key: String): File = File(bucketDir(bucket), FileMeta.metaKey(FNAME.checkKey(key)))

    override fun ensureBucket(cxt: KdrCxt, bucket: String): List<String> {
        val dir = bucketDir(bucket)
        return try {
            Files.createDirectories(dir.toPath())
            if (Files.isWritable(dir.toPath())) {
                emptyList()
            } else {
                listOf("The directory '${dir.path}', for the bucket '$bucket', cannot be written to.")
            }
        } catch (e: IOException) {
            listOf("The directory '${dir.path}', for the bucket '$bucket', could not be created: ${e.message}")
        }
    }

    override fun put(cxt: KdrCxt, bucket: String, key: String, bytes: ByteArray, meta: FileMeta) {
        val file = contentFile(bucket, key)
        val metaFile = metaFile(bucket, key)
        synchronized(lockFor(bucket, key)) {
            folderClash(bucketDir(bucket), file, key)?.let { throw KdrException(it, null, EXC.conflict) }
            io("write the file '$key' in the bucket '$bucket'") {
                Files.createDirectories(file.parentFile.toPath())
                val tmp = tmpOf(file)
                val metaTmp = tmpOf(metaFile)
                try {
                    Files.write(tmp.toPath(), bytes)
                    Files.write(metaTmp.toPath(), meta.toFileText().toByteArray(Charsets.UTF_8))
                    Files.deleteIfExists(metaFile.toPath())
                    moveInto(tmp, file)
                    moveInto(metaTmp, metaFile)
                } finally {
                    // Still here only when the write failed part-way.
                    tmp.delete()
                    metaTmp.delete()
                }
            }
        }
    }

    /**
     * What stops [key] being stored at [file]: a file where one of its folders would be, or a folder holding
     * files where it would be. A folder left empty by deletes is not in the way, and is removed.
     */
    private fun folderClash(bucketDir: File, file: File, key: String): String? {
        var parent = file.parentFile
        while (parent != null && parent != bucketDir) {
            if (parent.isFile) {
                val parentKey = parent.relativeTo(bucketDir).invariantSeparatorsPath
                return "Cannot store '$key': '$parentKey' is a file, and a key cannot be both a file and a folder."
            }
            parent = parent.parentFile
        }
        if (file.isDirectory && !file.delete()) {
            return "Cannot store '$key': it is a folder of other files, and a key cannot be both a file and a folder."
        }
        return null
    }

    private fun tmpOf(target: File): File = File(target.parentFile, target.name + tmpSuffix)

    private fun moveInto(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun get(cxt: KdrCxt, bucket: String, key: String): StoredFile? {
        val meta = meta(cxt, bucket, key) ?: return null
        val file = contentFile(bucket, key)
        if (!file.isFile) return null
        return StoredFile(io("read the file '$key' in the bucket '$bucket'") { Files.readAllBytes(file.toPath()) }, meta)
    }

    override fun meta(cxt: KdrCxt, bucket: String, key: String): FileMeta? = readMeta(metaFile(bucket, key))

    /** The metadata in [metaFile], or null when it is not there or is not whole. */
    private fun readMeta(metaFile: File): FileMeta? {
        if (!metaFile.isFile) return null
        val text = try {
            Files.readString(metaFile.toPath())
        } catch (_: IOException) {
            // Removed since the check above, or not text: either way, not metadata that can be read.
            return null
        }
        return FileMeta.fromFileText(text)
    }

    override fun delete(cxt: KdrCxt, bucket: String, key: String): Boolean {
        val file = contentFile(bucket, key)
        val metaFile = metaFile(bucket, key)
        synchronized(lockFor(bucket, key)) {
            return io("delete the file '$key' in the bucket '$bucket'") {
                val existed = Files.deleteIfExists(metaFile.toPath())
                // Content with no metadata is the leftover of an unfinished write, and goes too. A folder of
                // the same name is other files' and stays.
                if (file.isFile) Files.deleteIfExists(file.toPath())
                existed
            }
        }
    }

    override fun list(cxt: KdrCxt, bucket: String, keyPrefix: String): List<FileEntry> {
        val dir = bucketDir(bucket)
        // Everything a prefix can match lies under the folder its last slash closes, so the walk starts there.
        val start = File(dir, FNAME.checkKeyPrefix(keyPrefix).substringBeforeLast('/', ""))
        if (!start.isDirectory) return emptyList()
        return start.walkTopDown().maxDepth(FNAME.maxKeyLength)
            .filter { it.isFile && it.name.endsWith(FileMeta.metaSuffix) }
            .mapNotNull { metaFile ->
                val key = metaFile.relativeTo(dir).invariantSeparatorsPath.removeSuffix(FileMeta.metaSuffix)
                if (!key.startsWith(keyPrefix) || FNAME.keyProblem(key) != null || !File(dir, key).isFile) {
                    null
                } else {
                    readMeta(metaFile)?.let { FileEntry(key, it) }
                }
            }
            .sortedBy { it.key }
            .toList()
    }

    private fun <T> io(what: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw KdrException.mkFileIo("Could not $what: ${e.message}", e, EXC.internalError)
    }

    @Suppress("ConstPropertyName")
    companion object {
        /** What a file being written is called until it is whole; `~` appears in no key. */
        const val tmpSuffix = "~tmp"

        const val lockStripes = 64
    }
}
