package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.parseDate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * What every file store does, whichever it is (issue #1129): the same cases run over the store in memory, the
 * one in a directory, and the S3 one over a stand-in for S3. A provider is handed names the service has
 * checked, so these use valid ones; the rules for names are `FileNamesTest`'s.
 */
class FileStoreContractTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("fileStoreContract")
    val tempDirs = mutableListOf<File>()
    val bucket = "contract-files"

    fun tempDir(): File = Files.createTempDirectory("kdrFileStore").toFile().also { tempDirs.add(it) }

    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    fun meta(bytes: ByteArray, mimeType: String = "application/octet-stream") = FileMeta(
        mimeType = mimeType, size = bytes.size.toLong(), hash = "hash-of-${bytes.size}",
        uploadedAt = "2026-10-12T08:30:00Z".parseDate(),
    )

    val providers: List<Pair<String, () -> FileStore>> = listOf(
        FST.memory to { MemoryFileStore() },
        FST.local to { LocalFileStore(tempDir()) },
        // Parts as small as a test likes: the service holds every plan to S3's smallest part, whichever the store.
        FST.s3 to { S3FileStore(FakeS3Api("kdr-$bucket", minPartSize = 1), "kdr-") },
    )

    for ((name, create) in providers) {
        fun store(): FileStore = create().also { it.ensureBucket(cxt, bucket).shouldBeEmpty() }

        "$name: a file comes back byte for byte, with the metadata stored beside it" {
            val store = store()
            // Every byte value: nothing on the way may treat the content as text.
            val bytes = ByteArray(512) { it.toByte() }
            val stored = FileMeta(
                mimeType = "image/png", size = 512, hash = "abc123", uploadedAt = "2026-10-12T08:30:00Z".parseDate(),
                fileName = "Logo (final).png", uploadedBy = 42, client = "acme",
                extra = mapOf("slot" to "favicon", "width" to 32L, "tags" to listOf("a", "b")),
            )
            store.put(cxt, bucket, "clients/acme/web/favicon", bytes, stored)

            val file = store.get(cxt, bucket, "clients/acme/web/favicon").shouldNotBeNull()
            file.bytes.toList() shouldBe bytes.toList()
            file.meta.toJsonMap() shouldBe stored.toJsonMap()
            file.meta.fileName shouldBe "Logo (final).png"
            file.meta.uploadedBy shouldBe 42
            file.meta.extra shouldBe mapOf("slot" to "favicon", "width" to 32L, "tags" to listOf("a", "b"))
        }

        "$name: the metadata is read without the file, and a missing file has neither" {
            val store = store()
            store.put(cxt, bucket, "notes/one", "hello".toByteArray(), meta("hello".toByteArray(), "text/plain"))

            store.meta(cxt, bucket, "notes/one").shouldNotBeNull().mimeType shouldBe "text/plain"
            store.meta(cxt, bucket, "notes/two").shouldBeNull()
            store.get(cxt, bucket, "notes/two").shouldBeNull()
            store.delete(cxt, bucket, "notes/two") shouldBe false
        }

        "$name: storing at a key again replaces the content and the metadata together" {
            val store = store()
            store.put(cxt, bucket, "doc", "first".toByteArray(), meta("first".toByteArray(), "text/plain"))
            store.put(cxt, bucket, "doc", "second, longer".toByteArray(), meta("second, longer".toByteArray(), "text/markdown"))

            val file = store.get(cxt, bucket, "doc").shouldNotBeNull()
            file.bytes.decodeToString() shouldBe "second, longer"
            file.meta.mimeType shouldBe "text/markdown"
            file.meta.size shouldBe 14
            store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf("doc")
        }

        "$name: a deleted file is gone, and deleting it again says there was nothing" {
            val store = store()
            store.put(cxt, bucket, "a/b", "x".toByteArray(), meta("x".toByteArray()))

            store.delete(cxt, bucket, "a/b") shouldBe true
            store.get(cxt, bucket, "a/b").shouldBeNull()
            store.meta(cxt, bucket, "a/b").shouldBeNull()
            store.list(cxt, bucket, "").shouldBeEmpty()
            store.delete(cxt, bucket, "a/b") shouldBe false
        }

        "$name: a listing is the files under a prefix in key order, each with its metadata and no metadata file among them" {
            val store = store()
            for (key in listOf("clients/globex/web/favicon", "clients/acme/web/favicon", "clients/acme/web/brandMark", "clients/acme2/x", "top")) {
                store.put(cxt, bucket, key, key.toByteArray(), meta(key.toByteArray()))
            }

            store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf(
                "clients/acme/web/brandMark", "clients/acme/web/favicon", "clients/acme2/x", "clients/globex/web/favicon", "top",
            )
            store.list(cxt, bucket, "clients/acme/").map { it.key } shouldContainExactly
                listOf("clients/acme/web/brandMark", "clients/acme/web/favicon")
            // A prefix is matched as text, as S3 matches one: without the slash it takes `acme2` too.
            store.list(cxt, bucket, "clients/acme").map { it.key } shouldContainExactly
                listOf("clients/acme/web/brandMark", "clients/acme/web/favicon", "clients/acme2/x")
            store.list(cxt, bucket, "clients/acme/web/favicon").single().meta.size shouldBe "clients/acme/web/favicon".length
            store.list(cxt, bucket, "nothing/").shouldBeEmpty()
            // No more than it is asked for, and those the first in key order.
            store.list(cxt, bucket, "", 2).map { it.key } shouldContainExactly listOf("clients/acme/web/brandMark", "clients/acme/web/favicon")
            store.list(cxt, bucket, "clients/acme", 1).map { it.key } shouldContainExactly listOf("clients/acme/web/brandMark")
        }

        // Issue #1835: a range is read without the rest of the file.
        "$name: a range of a file is those bytes alone, at either end or the whole of it" {
            val store = store()
            val bytes = ByteArray(1000) { (it % 251).toByte() }
            store.put(cxt, bucket, "video/clip", bytes, meta(bytes, "video/mp4"))

            fun range(offset: Long, length: Long): List<Byte> =
                store.openRange(cxt, bucket, "video/clip", offset, length).shouldNotBeNull().use { it.readAllBytes() }.toList()

            range(0, 1) shouldBe listOf(bytes[0])
            range(999, 1) shouldBe listOf(bytes[999])
            range(100, 250) shouldBe bytes.copyOfRange(100, 350).toList()
            range(0, 1000) shouldBe bytes.toList()
            store.openRange(cxt, bucket, "video/none", 0, 1).shouldBeNull()
        }

        "$name: a file put together from parts sent in any order is them in the order named, with its metadata beside it" {
            val store = store()
            val token = store.beginStaged(cxt, bucket, "staged/video", "video/mp4")
            store.stagedUploads(cxt, bucket).map { it.key to it.token } shouldContainExactly listOf("staged/video" to token)
            // Nothing is at the key until the upload completes.
            store.meta(cxt, bucket, "staged/video").shouldBeNull()

            val third = store.putPart(cxt, bucket, "staged/video", token, 3, "!".toByteArray())
            val first = store.putPart(cxt, bucket, "staged/video", token, 1, "hello ".toByteArray())
            val second = store.putPart(cxt, bucket, "staged/video", token, 2, "world".toByteArray())
            second.size shouldBe 5
            val whole = "hello world!".toByteArray()
            store.completeStaged(cxt, bucket, "staged/video", token, listOf(first, second, third), meta(whole, "video/mp4"))

            val file = store.get(cxt, bucket, "staged/video").shouldNotBeNull()
            file.bytes.decodeToString() shouldBe "hello world!"
            file.meta.mimeType shouldBe "video/mp4"
            store.stagedUploads(cxt, bucket).shouldBeEmpty()
            store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf("staged/video")
        }

        "$name: a part sent again replaces the one before, and completing with the part it replaced is refused" {
            val store = store()
            val token = store.beginStaged(cxt, bucket, "staged/again", "video/mp4")
            val stale = store.putPart(cxt, bucket, "staged/again", token, 1, "first try".toByteArray())
            val fresh = store.putPart(cxt, bucket, "staged/again", token, 1, "second try".toByteArray())

            shouldThrow<KdrException> {
                store.completeStaged(cxt, bucket, "staged/again", token, listOf(stale), meta("first try".toByteArray()))
            }
            store.meta(cxt, bucket, "staged/again").shouldBeNull()
            store.completeStaged(cxt, bucket, "staged/again", token, listOf(fresh), meta("second try".toByteArray()))
            store.get(cxt, bucket, "staged/again").shouldNotBeNull().bytes.decodeToString() shouldBe "second try"
        }

        "$name: a file already at the key is untouched until an upload completes, and then replaced" {
            val store = store()
            store.put(cxt, bucket, "replaced", "old".toByteArray(), meta("old".toByteArray(), "text/plain"))
            val token = store.beginStaged(cxt, bucket, "replaced", "text/markdown")
            val part = store.putPart(cxt, bucket, "replaced", token, 1, "new content".toByteArray())

            store.get(cxt, bucket, "replaced").shouldNotBeNull().bytes.decodeToString() shouldBe "old"
            store.completeStaged(cxt, bucket, "replaced", token, listOf(part), meta("new content".toByteArray(), "text/markdown"))
            val file = store.get(cxt, bucket, "replaced").shouldNotBeNull()
            file.bytes.decodeToString() shouldBe "new content"
            file.meta.mimeType shouldBe "text/markdown"
        }

        "$name: an aborted upload leaves nothing, and is no upload to abort, add to or complete again" {
            val store = store()
            val token = store.beginStaged(cxt, bucket, "staged/dropped", "video/mp4")
            val part = store.putPart(cxt, bucket, "staged/dropped", token, 1, "x".toByteArray())

            store.abortStaged(cxt, bucket, "staged/dropped", token) shouldBe true
            store.stagedUploads(cxt, bucket).shouldBeEmpty()
            store.meta(cxt, bucket, "staged/dropped").shouldBeNull()
            store.abortStaged(cxt, bucket, "staged/dropped", token) shouldBe false
            shouldThrow<KdrException> { store.putPart(cxt, bucket, "staged/dropped", token, 2, "y".toByteArray()) }
            shouldThrow<KdrException> {
                store.completeStaged(cxt, bucket, "staged/dropped", token, listOf(part), meta("x".toByteArray()))
            }
            // An upload is its key's: another key does not reach it.
            val other = store.beginStaged(cxt, bucket, "staged/mine", "video/mp4")
            store.abortStaged(cxt, bucket, "staged/theirs", other) shouldBe false
            store.stagedUploads(cxt, bucket).map { it.key } shouldContainExactly listOf("staged/mine")
        }

        "$name: a file moves to another key whole, replacing one there, and a move of nothing says so" {
            val store = store()
            store.put(cxt, bucket, "staging/abc", "content".toByteArray(), meta("content".toByteArray(), "video/webm"))
            store.put(cxt, bucket, "files/h1", "older".toByteArray(), meta("older".toByteArray()))

            store.move(cxt, bucket, "staging/abc", "files/h1") shouldBe true
            store.get(cxt, bucket, "staging/abc").shouldBeNull()
            store.meta(cxt, bucket, "staging/abc").shouldBeNull()
            val moved = store.get(cxt, bucket, "files/h1").shouldNotBeNull()
            moved.bytes.decodeToString() shouldBe "content"
            moved.meta.mimeType shouldBe "video/webm"
            store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf("files/h1")
            store.openRange(cxt, bucket, "files/h1", 3, 4).shouldNotBeNull().use { it.readAllBytes() }.decodeToString() shouldBe "tent"

            store.move(cxt, bucket, "staging/none", "files/h2") shouldBe false
            store.meta(cxt, bucket, "files/h2").shouldBeNull()
        }

        "$name: an upload never finished is found by the check, and its repair aborts it once it is old enough" {
            val store = store()
            val token = store.beginStaged(cxt, bucket, "staged/abandoned", "video/mp4")
            store.putPart(cxt, bucket, "staged/abandoned", token, 1, "x".toByteArray())

            val found = FileStoreCheck.find(cxt, store, listOf(bucket)).single()
            found.kind shouldBe FLEFT.staged
            found.key shouldBe "staged/abandoned"
            found.name shouldBe token
            // Too recent to tell from an upload under way: left alone.
            FileStoreCheck.repair(cxt, store, listOf(found), found.modifiedAt, kotlin.time.Duration.parse("1h")).single().done shouldBe false
            store.stagedUploads(cxt, bucket).size shouldBe 1

            FileStoreCheck.repair(cxt, store, listOf(found), found.modifiedAt + kotlin.time.Duration.parse("2h"), kotlin.time.Duration.parse("1h"))
                .single().done shouldBe true
            store.stagedUploads(cxt, bucket).shouldBeEmpty()
            FileStoreCheck.find(cxt, store, listOf(bucket)).shouldBeEmpty()
        }

        "$name: one bucket's files are not another's" {
            val store = create()
            val other = "contract-other"
            if (store is S3FileStore) (store.api as FakeS3Api).buckets.add("kdr-$other")
            store.ensureBucket(cxt, bucket).shouldBeEmpty()
            store.ensureBucket(cxt, other).shouldBeEmpty()
            store.put(cxt, bucket, "same/key", "one".toByteArray(), meta("one".toByteArray()))

            store.get(cxt, other, "same/key").shouldBeNull()
            store.list(cxt, other, "").shouldBeEmpty()
            store.put(cxt, other, "same/key", "two".toByteArray(), meta("two".toByteArray()))
            store.get(cxt, bucket, "same/key").shouldNotBeNull().bytes.decodeToString() shouldBe "one"
        }
    }

    // A filesystem cannot hold a file and a folder of one name, so the two stores a developer meets first refuse
    // a key that is both: a key scheme that passes on them is one every provider takes.
    for ((name, create) in providers.filter { it.first != FST.s3 }) {
        "$name: a key cannot be both a file and a folder of other files" {
            val store = create().also { it.ensureBucket(cxt, bucket) }
            store.put(cxt, bucket, "a/b", "x".toByteArray(), meta("x".toByteArray()))

            val asFile = shouldThrow<KdrException> { store.put(cxt, bucket, "a", "y".toByteArray(), meta("y".toByteArray())) }
            asFile.code shouldBe EXC.conflict
            asFile.message shouldContain "folder"
            val under = shouldThrow<KdrException> { store.put(cxt, bucket, "a/b/c", "z".toByteArray(), meta("z".toByteArray())) }
            under.code shouldBe EXC.conflict
            under.message shouldContain "'a/b' is a file"

            // Nothing was disturbed, and a folder emptied by a delete is not in the way.
            store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf("a/b")
            store.delete(cxt, bucket, "a/b") shouldBe true
            store.put(cxt, bucket, "a", "y".toByteArray(), meta("y".toByteArray()))
            store.get(cxt, bucket, "a").shouldNotBeNull().bytes.decodeToString() shouldBe "y"

            // However deep the folders the deletes left: what a filesystem keeps of them holds no file.
            store.put(cxt, bucket, "deep/er/still/file", "x".toByteArray(), meta("x".toByteArray()))
            store.delete(cxt, bucket, "deep/er/still/file") shouldBe true
            store.put(cxt, bucket, "deep", "z".toByteArray(), meta("z".toByteArray()))
            store.get(cxt, bucket, "deep").shouldNotBeNull().bytes.decodeToString() shouldBe "z"
            // A folder that does still hold one, further down, is in the way.
            store.put(cxt, bucket, "held/er/still/file", "x".toByteArray(), meta("x".toByteArray()))
            shouldThrow<KdrException> { store.put(cxt, bucket, "held", "z".toByteArray(), meta("z".toByteArray())) }.code shouldBe EXC.conflict
        }
    }

    "memory: content handed back is a copy, so changing it does not change what is stored" {
        val store = MemoryFileStore()
        val bytes = "abc".toByteArray()
        store.put(cxt, bucket, "k", bytes, meta(bytes))
        bytes[0] = 'z'.code.toByte()
        store.get(cxt, bucket, "k").shouldNotBeNull().bytes[1] = 'z'.code.toByte()

        store.get(cxt, bucket, "k").shouldNotBeNull().bytes.decodeToString() shouldBe "abc"
    }
})
