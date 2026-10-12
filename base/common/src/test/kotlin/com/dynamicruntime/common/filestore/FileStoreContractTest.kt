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
        FST.s3 to { S3FileStore(FakeS3Api("kdr-$bucket"), "kdr-") },
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
