package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.parseDate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/** The directory-backed store's own business (issue #1129): what it leaves on disk, and what it does when a write fails. */
class LocalFileStoreTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("localFileStore")
    val tempDirs = mutableListOf<File>()
    val bucket = "local-files"

    fun tempDir(): File = Files.createTempDirectory("kdrLocalStore").toFile().also { tempDirs.add(it) }

    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    fun meta(bytes: ByteArray) = FileMeta("text/plain", bytes.size.toLong(), "h${bytes.size}", "2026-10-12T08:30:00Z".parseDate())

    fun filesUnder(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()

    "a bucket is a directory under the root, a key a path in it, and the metadata a file beside the content" {
        val root = tempDir()
        val store = LocalFileStore(root)
        store.ensureBucket(cxt, bucket).shouldBeEmpty()
        File(root, bucket).isDirectory shouldBe true

        store.put(cxt, bucket, "clients/acme/logo", "PNG".toByteArray(), meta("PNG".toByteArray()))

        filesUnder(root) shouldContainExactlyInAnyOrder
            listOf("$bucket/clients/acme/logo", "$bucket/clients/acme/logo.meta.json")
        File(root, "$bucket/clients/acme/logo").readText() shouldBe "PNG"
        // The metadata file is the metadata as JSON, readable by anything.
        FileMeta.fromFileText(File(root, "$bucket/clients/acme/logo.meta.json").readText()).shouldNotBeNull().size shouldBe 3
    }

    "content with no metadata beside it is not a file" {
        val root = tempDir()
        val store = LocalFileStore(root)
        store.ensureBucket(cxt, bucket)
        store.put(cxt, bucket, "kept", "a".toByteArray(), meta("a".toByteArray()))
        store.put(cxt, bucket, "half", "b".toByteArray(), meta("b".toByteArray()))
        File(root, "$bucket/half.meta.json").delete() shouldBe true

        store.get(cxt, bucket, "half").shouldBeNull()
        store.meta(cxt, bucket, "half").shouldBeNull()
        store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf("kept")
        // Deleting it says there was no file, and still clears the leftover away.
        store.delete(cxt, bucket, "half") shouldBe false
        File(root, "$bucket/half").exists() shouldBe false
    }

    "metadata that is not whole, or with no content, is not a file either" {
        val root = tempDir()
        val store = LocalFileStore(root)
        store.ensureBucket(cxt, bucket)
        store.put(cxt, bucket, "broken", "a".toByteArray(), meta("a".toByteArray()))
        store.put(cxt, bucket, "orphan", "b".toByteArray(), meta("b".toByteArray()))
        File(root, "$bucket/broken.meta.json").writeText("{\"mimeType\": \"text/plain\"")
        File(root, "$bucket/orphan").delete() shouldBe true

        store.get(cxt, bucket, "broken").shouldBeNull()
        store.meta(cxt, bucket, "broken").shouldBeNull()
        store.get(cxt, bucket, "orphan").shouldBeNull()
        store.list(cxt, bucket, "").shouldBeEmpty()
    }

    "a write that fails part-way leaves the file that was there exactly as it was" {
        val root = tempDir()
        val store = LocalFileStore(root)
        store.ensureBucket(cxt, bucket)
        store.put(cxt, bucket, "doc", "original".toByteArray(), meta("original".toByteArray()))
        // The metadata's temporary name taken by a folder, so writing it out fails after the content was.
        val blocker = File(root, "$bucket/doc.meta.json${LocalFileStore.tmpSuffix}").also { File(it, "x").apply { parentFile.mkdirs() }.writeText("x") }

        val failed = shouldThrow<KdrException> { store.put(cxt, bucket, "doc", "replacement".toByteArray(), meta("replacement".toByteArray())) }
        failed.message shouldContain "Could not write the file 'doc'"

        val file = store.get(cxt, bucket, "doc").shouldNotBeNull()
        file.bytes.decodeToString() shouldBe "original"
        file.meta.size shouldBe 8
        // The half-written content is cleared away rather than left for a listing to trip over.
        File(root, "$bucket/doc${LocalFileStore.tmpSuffix}").exists() shouldBe false

        blocker.deleteRecursively()
        store.put(cxt, bucket, "doc", "replacement".toByteArray(), meta("replacement".toByteArray()))
        store.get(cxt, bucket, "doc").shouldNotBeNull().bytes.decodeToString() shouldBe "replacement"
        filesUnder(root) shouldHaveSize 2
    }

    "a listing passes over anything in the directory that is not a stored file" {
        val root = tempDir()
        val store = LocalFileStore(root)
        store.ensureBucket(cxt, bucket)
        store.put(cxt, bucket, "a/one", "1".toByteArray(), meta("1".toByteArray()))
        File(root, "$bucket/a/one${LocalFileStore.tmpSuffix}").writeText("half written")
        File(root, "$bucket/a/stray.txt").writeText("put here by hand")
        File(root, "$bucket/a/Bad Name.meta.json").writeText(meta("x".toByteArray()).toFileText())

        store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf("a/one")
    }

    "a bucket whose directory cannot be made is a finding, not a failure" {
        val root = tempDir()
        // The root is a file, so no directory can be made under it.
        val notADir = File(root, "taken").apply { writeText("x") }

        val findings = LocalFileStore(notADir).ensureBucket(cxt, bucket)

        findings shouldHaveSize 1
        findings.single() shouldContain "for the bucket '$bucket'"
        findings.single() shouldContain "could not be created"
    }

    "a name that is not a key never becomes a path, even handed straight to the provider" {
        val root = tempDir()
        val store = LocalFileStore(root)
        store.ensureBucket(cxt, bucket)

        for (key in listOf("../escape", "a/../../escape", "/etc/passwd", "a\\b", "doc.meta.json")) {
            shouldThrow<KdrException> { store.put(cxt, bucket, key, "x".toByteArray(), meta("x".toByteArray())) }
            shouldThrow<KdrException> { store.get(cxt, bucket, key) }
        }
        shouldThrow<KdrException> { store.get(cxt, "../$bucket", "doc") }
        filesUnder(root.parentFile).filter { it.endsWith("escape") }.shouldBeEmpty()
    }
})
