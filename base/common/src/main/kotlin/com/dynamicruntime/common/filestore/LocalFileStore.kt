package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.util.sha256Hex
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
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
        val lockFile = File(File(root, lockDirName), "${FNAME.checkBucket(bucket)}.${stripeOf(key)}")
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
        exclusively(bucket, key, "write the file '$key' in the bucket '$bucket'") {
            install(bucket, key, meta) { tmp -> Files.write(tmp.toPath(), bytes) }
        }
    }

    /**
     * Writes the file at [key]: its content into a temporary file by [writeContent], its metadata [meta] beside that,
     * and then each moved into place, the content first. Run under the key's lock.
     */
    private fun install(bucket: String, key: String, meta: FileMeta, writeContent: (File) -> Unit) {
        val file = contentFile(bucket, key)
        val metaFile = metaFile(bucket, key)
        folderClash(bucketDir(bucket), file, key)?.let { throw KdrException(it, null, EXC.conflict) }
        Files.createDirectories(file.parentFile.toPath())
        val tmp = tmpOf(file)
        val metaTmp = tmpOf(metaFile)
        try {
            writeContent(tmp)
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

    override fun objects(cxt: KdrCxt, bucket: String, prefix: String): List<StoredObject> {
        val dir = bucketDir(bucket)
        // The walk starts at the folder the prefix is in, so a folder of a large bucket costs its own files alone.
        val from = prefix.substringBeforeLast('/', "").let { if (it.isEmpty()) dir else File(dir, it) }
        if (!from.isDirectory) return emptyList()
        return from.walkTopDown().maxDepth(FNAME.maxKeyDepth).filter { it.isFile }
            .map { StoredObject(it.relativeTo(dir).invariantSeparatorsPath, modifiedAt(it)) }
            .filter { it.name.startsWith(prefix) }
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
        if (leftover.kind == FLEFT.staged) return clearStaged(bucket, leftover, cutoff)
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

    override fun openRange(cxt: KdrCxt, bucket: String, key: String, offset: Long, length: Long): InputStream? {
        if (!metaFile(bucket, key).isFile) return null
        val channel = try {
            FileChannel.open(contentFile(bucket, key).toPath(), StandardOpenOption.READ)
        } catch (_: java.nio.file.NoSuchFileException) {
            return null
        } catch (e: IOException) {
            throw failure("read the file '$key' in the bucket '$bucket'", e)
        }
        return try {
            channel.position(offset)
            BoundedInputStream(Channels.newInputStream(channel), length)
        } catch (e: IOException) {
            channel.close()
            throw failure("read the file '$key' in the bucket '$bucket'", e)
        }
    }

    /** The directory of [bucket]'s uploads under way: under [stagingDirName], which no bucket's directory can be. */
    private fun stagingDir(bucket: String): File = File(File(root, stagingDirName), FNAME.checkBucket(bucket))

    /**
     * The directory of the upload [token] in [bucket]. A token is only ever one this store made, which is checked
     * here, where it becomes a path: a token handed in from elsewhere names no other directory.
     */
    private fun uploadDir(bucket: String, token: String): File {
        if (!tokenPattern.matches(token)) throw KdrException("Not an upload token of this store: '$token'.", null, EXC.badInput)
        return File(stagingDir(bucket), token)
    }

    /** The upload [token] of [key], when it is under way; null when there is none, or it is another key's. */
    private fun uploadOf(bucket: String, key: String, token: String): File? {
        val dir = uploadDir(bucket, token)
        val keyFile = File(dir, uploadKeyName)
        return dir.takeIf { keyFile.isFile && keyFile.readText() == key }
    }

    private fun noUpload(bucket: String, key: String, token: String): KdrException =
        KdrException("There is no upload '$token' of '$key' in the bucket '$bucket' under way.", null, EXC.notFound)

    override fun beginStaged(cxt: KdrCxt, bucket: String, key: String, mimeType: String): String {
        FNAME.checkKey(key)
        val token = UUID.randomUUID().toString().replace("-", "")
        val dir = uploadDir(bucket, token)
        io("begin an upload of '$key' in the bucket '$bucket'") {
            Files.createDirectories(dir.toPath())
            // Written last, as what makes the directory an upload: one that stopped before it is nothing's.
            Files.writeString(File(dir, uploadKeyName).toPath(), key)
        }
        return token
    }

    override fun putPart(cxt: KdrCxt, bucket: String, key: String, token: String, number: Int, bytes: ByteArray): StagedPart {
        val dir = uploadOf(bucket, key, token) ?: throw noUpload(bucket, key, token)
        val part = StagedPart(number, bytes.size.toLong(), bytes.sha256Hex())
        io("store part $number of the upload of '$key' in the bucket '$bucket'") {
            val partFile = File(dir, "$number")
            val tagFile = File(dir, "$number$tagSuffix")
            // The part and then its tag, each whole: a part written again is a new tag, and a part whose tag did not
            // follow it is refused when the upload completes, never put into a file.
            writeWhole(partFile, bytes)
            writeWhole(tagFile, part.tag.toByteArray(Charsets.UTF_8))
        }
        return part
    }

    private fun writeWhole(target: File, bytes: ByteArray) {
        val tmp = tmpOf(target)
        try {
            Files.write(tmp.toPath(), bytes)
            moveInto(tmp, target)
        } finally {
            tmp.delete()
        }
    }

    override fun completeStaged(cxt: KdrCxt, bucket: String, key: String, token: String, parts: List<StagedPart>, meta: FileMeta) {
        val dir = uploadOf(bucket, key, token) ?: throw noUpload(bucket, key, token)
        val partFiles = parts.map { part ->
            val partFile = File(dir, "${part.number}")
            val tagFile = File(dir, "${part.number}$tagSuffix")
            val tag = if (tagFile.isFile) Files.readString(tagFile.toPath()) else null
            if (!partFile.isFile || partFile.length() != part.size || tag != part.tag) {
                throw KdrException("Part ${part.number} of the upload of '$key' is not the part that was stored.", null, EXC.conflict)
            }
            partFile
        }
        exclusively(bucket, key, "complete the upload of '$key' in the bucket '$bucket'") {
            install(bucket, key, meta) { tmp ->
                Files.newOutputStream(tmp.toPath()).use { out -> partFiles.forEach { Files.copy(it.toPath(), out) } }
            }
        }
        dir.deleteRecursively()
    }

    override fun abortStaged(cxt: KdrCxt, bucket: String, key: String, token: String): Boolean {
        val dir = uploadOf(bucket, key, token) ?: return false
        return dir.deleteRecursively()
    }

    override fun stagedUploads(cxt: KdrCxt, bucket: String): List<StagedUpload> {
        val dirs = stagingDir(bucket).listFiles { f -> f.isDirectory && tokenPattern.matches(f.name) } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val keyFile = File(dir, uploadKeyName)
            if (!keyFile.isFile) return@mapNotNull null
            StagedUpload(keyFile.readText(), dir.name, modifiedAt(keyFile))
        }.sortedBy { it.key }
    }

    /** An upload that is still there, still [leftover]'s key's, and begun no later than [cutoff]: its parts go. */
    private fun clearStaged(bucket: String, leftover: FileLeftover, cutoff: Instant): Boolean {
        if (!tokenPattern.matches(leftover.name)) return false
        val dir = uploadOf(bucket, leftover.key, leftover.name) ?: return false
        if (modifiedAt(File(dir, uploadKeyName)) > cutoff) return false
        return dir.deleteRecursively()
    }

    override fun move(cxt: KdrCxt, bucket: String, from: String, to: String): Boolean {
        if (from == to) return meta(cxt, bucket, from) != null
        val (first, second) = listOf(from, to).sortedBy { stripeOf(it) }
        // Both keys' locks, in the order of their stripes, so two moves between the same stripes cannot each hold one.
        val both = { block: () -> Boolean ->
            exclusively(bucket, first, "move '$from' to '$to' in the bucket '$bucket'") {
                if (stripeOf(first) == stripeOf(second)) block() else exclusively(bucket, second, "move '$from' to '$to' in the bucket '$bucket'", block)
            }
        }
        return both moved@{
            val fromFile = contentFile(bucket, from)
            val fromMeta = metaFile(bucket, from)
            if (!fromFile.isFile || !fromMeta.isFile) return@moved false
            val toFile = contentFile(bucket, to)
            val toMeta = metaFile(bucket, to)
            folderClash(bucketDir(bucket), toFile, to)?.let { throw KdrException(it, null, EXC.conflict) }
            Files.createDirectories(toFile.parentFile.toPath())
            val metaTmp = tmpOf(toMeta)
            try {
                Files.copy(fromMeta.toPath(), metaTmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                // From here [from] is content with no metadata, a write that did not finish; [to] is untouched until
                // its content lands, and then is as a put leaves it between its content and its metadata.
                Files.delete(fromMeta.toPath())
                moveInto(fromFile, toFile)
                moveInto(metaTmp, toMeta)
            } finally {
                metaTmp.delete()
            }
            true
        }
    }

    private fun stripeOf(key: String): Int = Math.floorMod(key.hashCode(), lockStripes)

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

        /**
         * The directory under a store's root that holds the uploads under way (issue #1835): a directory a bucket,
         * each upload a directory of its token in it, holding the key it is for ([uploadKeyName]) and its parts by
         * number, each with its tag beside it ([tagSuffix]).
         */
        const val stagingDirName = "${tmpMark}staging"

        /** In an upload's directory, the file holding the key it is for: written last, so it marks an upload begun. */
        const val uploadKeyName = "key"

        /** Beside a part, what names the file holding its tag. */
        const val tagSuffix = ".tag"

        /** What an upload token of this store is: what [beginStaged] makes, and nothing that could name another path. */
        private val tokenPattern = Regex("[0-9a-f]{32}")

        /** How many lock files a bucket's keys share: two writes wait for each other only when their keys meet. */
        const val lockStripes = 64

        /** This JVM's turn-taking for each lock file, by its path: shared by every store over one directory. */
        private val jvmLocks = ConcurrentHashMap<String, Any>()
    }
}
