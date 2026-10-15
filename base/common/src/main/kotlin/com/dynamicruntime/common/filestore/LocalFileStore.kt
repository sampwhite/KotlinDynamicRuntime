package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.util.sha256Hex
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/**
 * A file store in a directory on this machine (issue #1129): each bucket a directory under [root], each key a
 * path under its bucket, each file's metadata beside it -- `web/logo` is described by `web/logo.meta.json`.
 * What a developer's own node and a single-machine deployment run on.
 *
 * Every file is written whole or not at all: its content and its metadata are each written out under a
 * temporary name first, and only then moved into place -- the content, then the metadata. So the slow part of
 * a write can fail and leave a file already there exactly as it was, and what is left to go wrong is two
 * renames. The temporary name holds a `~`, which no key can, so it is never mistaken for a file.
 *
 * **One writer of a file at a time, across every node that shares the directory.** A write or a delete holds a
 * lock for its key that the operating system keeps between processes ([exclusively]), since a file is two
 * files here and two writers taking turns at them would leave one's content under the other's metadata. A
 * directory shared between nodes has to be on a filesystem that honors such locks. Reading takes none: what a
 * read can catch part-way is told from a whole file by its hash ([FileStoreService.get]).
 *
 * Two keys that differ only by case are one file on a filesystem that ignores case, which is most developers'.
 * A key scheme that tells files apart by case alone is not portable to this provider.
 */
class LocalFileStore(val root: File) : FileStore {
    override val providerType: String = FST.local
    override val location: String get() = root.path

    private fun bucketDir(bucket: String): File = File(root, FNAME.checkBucket(bucket))

    // The names are checked again here, though the service has checked them: this is where a name becomes a
    // path, and a provider called directly must be no easier to walk out of than one called through the service.
    private fun contentFile(bucket: String, key: String): File = File(bucketDir(bucket), FNAME.checkKey(key))

    private fun metaFile(bucket: String, key: String): File = File(bucketDir(bucket), FileMeta.metaKey(FNAME.checkKey(key)))

    /**
     * Runs [block] as the only writer of [key] in [bucket]: the only thread of this JVM, and the only JVM of
     * those sharing the directory. The lock is one of [lockStripes] files per bucket, under [lockDirName] --
     * which holds a `~`, so it is no bucket's directory and no key's.
     */
    private fun <T> exclusively(bucket: String, key: String, what: String, block: () -> T): T {
        val stripe = Math.floorMod(key.hashCode(), lockStripes)
        val lockFile = File(File(root, lockDirName), "${FNAME.checkBucket(bucket)}.$stripe")
        // A lock on a file belongs to the whole JVM: a second thread asking for one this JVM already holds is
        // refused rather than made to wait. So the threads take turns here first, whichever store they came by.
        return synchronized(jvmLocks.computeIfAbsent(lockFile.absolutePath) { Any() }) {
            io(what) {
                Files.createDirectories(lockFile.parentFile.toPath())
                FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use { block() }
                }
            }
        }
    }

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
        exclusively(bucket, key, "write the file '$key' in the bucket '$bucket'") {
            folderClash(bucketDir(bucket), file, key)?.let { throw KdrException(it, null, EXC.conflict) }
            Files.createDirectories(file.parentFile.toPath())
            val tmp = tmpOf(file)
            val metaTmp = tmpOf(metaFile)
            try {
                Files.write(tmp.toPath(), bytes)
                Files.write(metaTmp.toPath(), meta.toFileText().toByteArray(Charsets.UTF_8))
                // Until here the file that was there is untouched.
                moveInto(tmp, file)
                try {
                    moveInto(metaTmp, metaFile)
                } catch (e: IOException) {
                    // The content is the new file's and its metadata could not be put beside it. Left alone,
                    // the old metadata would describe content it does not match; removed, this is a write that
                    // did not finish.
                    Files.deleteIfExists(metaFile.toPath())
                    throw e
                }
            } finally {
                // Still here only when the write failed part-way.
                tmp.delete()
                metaTmp.delete()
            }
        }
    }

    /**
     * What stops [key] being stored at [file]: a file where one of its folders would be, or a folder holding
     * files where it would be. A folder that holds none -- left by deletes, however deep -- is not in the way,
     * and is removed.
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
        if (file.isDirectory) {
            // A leftover of a write that stopped is nobody's file, and goes with the folders.
            val holdsFiles = file.walkTopDown().maxDepth(FNAME.maxKeyDepth).any { it.isFile && !it.name.contains(tmpMark) }
            if (!holdsFiles) file.walkBottomUp().maxDepth(FNAME.maxKeyDepth).forEach { it.delete() }
            if (file.exists()) {
                return "Cannot store '$key': it is a folder of other files, and a key cannot be both a file and a folder."
            }
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
        val bytes = try {
            Files.readAllBytes(file.toPath())
        } catch (_: java.nio.file.NoSuchFileException) {
            // Deleted since the check above: no file, as it would have been a moment later.
            return null
        } catch (e: IOException) {
            throw failure("read the file '$key' in the bucket '$bucket'", e)
        }
        return StoredFile(bytes, meta)
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
        return exclusively(bucket, key, "delete the file '$key' in the bucket '$bucket'") {
            val existed = Files.deleteIfExists(metaFile.toPath())
            // Content with no metadata is the leftover of an unfinished write, and goes too. A folder of the
            // same name is other files' and stays.
            if (file.isFile) Files.deleteIfExists(file.toPath())
            existed
        }
    }

    override fun list(cxt: KdrCxt, bucket: String, keyPrefix: String, max: Int): List<FileEntry> {
        val dir = bucketDir(bucket)
        // Everything a prefix can match lies under the folder its last slash closes, so the walk starts there.
        val start = File(dir, FNAME.checkKeyPrefix(keyPrefix).substringBeforeLast('/', ""))
        if (!start.isDirectory) return emptyList()
        // The keys first, which costs no read; then the metadata of as many as were asked for, in key order.
        return start.walkTopDown().maxDepth(FNAME.maxKeyDepth)
            .filter { it.isFile && it.name.endsWith(FileMeta.metaSuffix) }
            .map { it.relativeTo(dir).invariantSeparatorsPath.removeSuffix(FileMeta.metaSuffix) }
            .filter { it.startsWith(keyPrefix) && FNAME.keyProblem(it) == null }
            .sorted()
            .mapNotNull { key ->
                if (!File(dir, key).isFile) null else readMeta(File(dir, FileMeta.metaKey(key)))?.let { FileEntry(key, it) }
            }
            .take(max)
            .toList()
    }

    override fun objects(cxt: KdrCxt, bucket: String): List<StoredObject> {
        val dir = bucketDir(bucket)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().maxDepth(FNAME.maxKeyDepth).filter { it.isFile }
            .map { StoredObject(it.relativeTo(dir).invariantSeparatorsPath, modifiedAt(it)) }
            .sortedBy { it.name }
            .toList()
    }

    override fun tmpKeyOf(name: String): String? =
        name.takeIf { it.endsWith(tmpSuffix) }?.removeSuffix(tmpSuffix)?.removeSuffix(FileMeta.metaSuffix)?.takeIf { FNAME.keyProblem(it) == null }

    /**
     * Under the key's lock, which every write of it holds throughout: so what is found there is no write's under
     * way, and a temporary file in particular is nobody's. [cutoff] is held to all the same.
     */
    override fun clearLeftover(cxt: KdrCxt, bucket: String, leftover: FileLeftover, cutoff: Instant): Boolean {
        val key = FNAME.checkKey(leftover.key)
        val file = contentFile(bucket, key)
        val metaFile = metaFile(bucket, key)
        return exclusively(bucket, key, "clear what a write left of '$key' in the bucket '$bucket'") {
            if (leftover.kind == FLEFT.tmp) {
                // Only a name a write of this key leaves, so a leftover handed in cannot reach anything else.
                val tmp = when (leftover.name) {
                    key + tmpSuffix -> tmpOf(file)
                    FileMeta.metaKey(key) + tmpSuffix -> tmpOf(metaFile)
                    else -> return@exclusively false
                }
                return@exclusively tmp.isFile && modifiedAt(tmp) <= cutoff && tmp.delete()
            }
            if (FileStoreCheck.kindNow(file.isFile, metaTextOf(metaFile)) != leftover.kind) return@exclusively false
            if (listOf(file, metaFile).any { it.isFile && modifiedAt(it) > cutoff }) return@exclusively false
            Files.deleteIfExists(metaFile.toPath())
            if (file.isFile) Files.deleteIfExists(file.toPath())
            true
        }
    }

    override fun rewriteMeta(cxt: KdrCxt, bucket: String, key: String, meta: FileMeta, cutoff: Instant): Boolean {
        val file = contentFile(bucket, key)
        val metaFile = metaFile(bucket, key)
        return exclusively(bucket, key, "rewrite the metadata of '$key' in the bucket '$bucket'") {
            if (!file.isFile || modifiedAt(file) > cutoff) return@exclusively false
            val current = readMeta(metaFile) ?: return@exclusively false
            val hash = Files.readAllBytes(file.toPath()).sha256Hex()
            if (hash != meta.hash || hash == current.hash) return@exclusively false
            val tmp = tmpOf(metaFile)
            try {
                Files.write(tmp.toPath(), meta.toFileText().toByteArray(Charsets.UTF_8))
                moveInto(tmp, metaFile)
            } finally {
                tmp.delete()
            }
            true
        }
    }

    private fun modifiedAt(file: File): Instant = Instant.fromEpochMilliseconds(file.lastModified())

    /**
     * The text of [metaFile], or null when there is none -- and, when its bytes are not text, an empty string: metadata
     * that cannot be read, as [readMeta] takes it, rather than a failure of whatever asked.
     */
    private fun metaTextOf(metaFile: File): String? {
        if (!metaFile.isFile) return null
        return try {
            Files.readString(metaFile.toPath())
        } catch (_: java.nio.charset.CharacterCodingException) {
            ""
        }
    }

    private fun <T> io(what: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw failure(what, e)
    }

    /**
     * **Sensitive**: what the operating system says of a failure names a path on this machine, which is for
     * whoever runs the deployment and not for whoever asked for a file.
     */
    private fun failure(what: String, e: IOException): KdrException =
        KdrException("Could not $what: ${e.message}", e, EXC.internalError, SRC.file, ACT.io, sensitive = true)

    @Suppress("ConstPropertyName")
    companion object {
        /** What marks a name as not a key's: no key can hold it. */
        const val tmpMark = "~"

        /** What a file being written is called until it is whole. */
        const val tmpSuffix = "${tmpMark}tmp"

        /** The directory under a store's root that holds its lock files. */
        const val lockDirName = "${tmpMark}locks"

        /** How many lock files a bucket's keys share: two writes wait for each other only when their keys meet. */
        const val lockStripes = 64

        /** This JVM's turn-taking for each lock file, by its path: shared by every store over one directory. */
        private val jvmLocks = ConcurrentHashMap<String, Any>()
    }
}
