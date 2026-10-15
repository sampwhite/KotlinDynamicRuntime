package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.sha256Hex
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * What a write or a delete that stopped part-way leaves, found and repaired (issue #1147): on a directory, on S3,
 * and on memory, where nothing is ever left. Each state is made by hand -- the way a stop would leave it -- since a
 * stop is a process dying, which a test does not do.
 */
class FileStoreCheckTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("fileStoreCheck")
    val bucket = "check-files"
    val tempDirs = mutableListOf<File>()
    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    // Long enough ago that a repair may touch it, and "now" an hour and more after that.
    val old = "2026-10-01T08:00:00Z".parseDate()
    val now = "2026-10-15T08:00:00Z".parseDate()
    val minAge = 60.minutes

    fun meta(bytes: ByteArray, mimeType: String = "image/png") =
        FileMeta(mimeType, bytes.size.toLong(), bytes.sha256Hex(), old, fileName = "pic.png", uploadedBy = 7L, client = "acme", extra = mapOf("refId" to "logo"))

    val first = "first".toByteArray()
    val second = "second".toByteArray()

    fun kinds(found: List<FileLeftover>): List<Pair<String, String>> = found.map { it.name to it.kind }

    "a directory: each thing a stopped write leaves is found, a whole file is not, and an old one is repaired" {
        val root = Files.createTempDirectory("kdrStoreCheck").toFile().also { tempDirs.add(it) }
        val store = LocalFileStore(root)
        store.ensureBucket(cxt, bucket)
        val dir = File(root, bucket)
        for (key in listOf("ok", "content", "meta", "unread", "mixed", "fresh")) store.put(cxt, bucket, key, first, meta(first))
        File(dir, "content.meta.json").delete() shouldBe true
        File(dir, "meta").delete() shouldBe true
        File(dir, "unread.meta.json").writeText("{\"mimeType\": ")
        // A replace that stopped between its two writes: the new content under the old metadata.
        File(dir, "mixed").writeBytes(second)
        File(dir, "half~tmp").writeText("partial")
        File(dir, "fresh.meta.json").delete() shouldBe true
        // Everything long unchanged but the one written a moment ago.
        dir.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(old.toEpochMilliseconds()) }
        File(dir, "fresh").setLastModified(now.toEpochMilliseconds() - 1000)

        val found = FileStoreCheck.find(cxt, store, listOf(bucket))
        kinds(found) shouldContainExactly listOf(
            "content" to FLEFT.contentOnly,
            "fresh" to FLEFT.contentOnly,
            "half~tmp" to FLEFT.tmp,
            "meta.meta.json" to FLEFT.metaOnly,
            "mixed" to FLEFT.mismatch,
            "unread" to FLEFT.unreadableMeta,
        )
        val mixed = found.single { it.kind == FLEFT.mismatch }
        mixed.contentHash shouldBe second.sha256Hex()
        mixed.meta.shouldNotBeNull().hash shouldBe first.sha256Hex()
        mixed.describe() shouldContain "Every read of it is refused"

        val repairs = FileStoreCheck.repair(cxt, store, found, now, minAge)
        repairs.filter { it.done }.map { it.leftover.name } shouldContainExactly listOf("content", "half~tmp", "meta.meta.json", "mixed", "unread")
        repairs.single { !it.done }.note shouldContain "too recently to tell from a write under way"

        // What is left is the recent one; the rest is gone, or -- the mismatch -- a file again.
        kinds(FileStoreCheck.find(cxt, store, listOf(bucket))) shouldContainExactly listOf("fresh" to FLEFT.contentOnly)
        listOf("content", "half~tmp", "meta.meta.json", "unread", "unread.meta.json").forEach { File(dir, it).exists() shouldBe false }
        val repaired = store.get(cxt, bucket, "mixed").shouldNotBeNull()
        repaired.bytes.toList() shouldBe second.toList()
        repaired.meta.hash shouldBe second.sha256Hex()
        repaired.meta.size shouldBe second.size.toLong()
        // What else the metadata said is carried, and the repair says it made it.
        repaired.meta.mimeType shouldBe "image/png"
        repaired.meta.fileName shouldBe "pic.png"
        repaired.meta.uploadedBy shouldBe 7L
        repaired.meta.client shouldBe "acme"
        repaired.meta.extra["refId"] shouldBe "logo"
        repaired.meta.extra[FLEFT.metaRewrittenFromHash] shouldBe first.sha256Hex()
        repaired.meta.extra[FLEFT.metaRewrittenAt].shouldNotBeNull()
        store.get(cxt, bucket, "ok").shouldNotBeNull().bytes.toList() shouldBe first.toList()
    }

    // A leftover is asked about again as it is repaired: a write that came to the key since it was found has made
    // it a file, which a repair must not take.
    "a directory: what a write has made whole since it was found is not touched" {
        val root = Files.createTempDirectory("kdrStoreCheck").toFile().also { tempDirs.add(it) }
        val store = LocalFileStore(root)
        store.ensureBucket(cxt, bucket)
        val dir = File(root, bucket)
        store.put(cxt, bucket, "late", first, meta(first))
        store.put(cxt, bucket, "replaced", first, meta(first))
        store.put(cxt, bucket, "touched", first, meta(first))
        store.put(cxt, bucket, "retouched", first, meta(first))
        File(dir, "late.meta.json").delete()
        File(dir, "replaced").writeBytes(second)
        File(dir, "touched.meta.json").delete()
        File(dir, "retouched").writeBytes(second)
        dir.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(old.toEpochMilliseconds()) }
        val found = FileStoreCheck.find(cxt, store, listOf(bucket))
        kinds(found) shouldContainExactly listOf(
            "late" to FLEFT.contentOnly, "replaced" to FLEFT.mismatch, "retouched" to FLEFT.mismatch, "touched" to FLEFT.contentOnly,
        )

        // Two written whole again before the repair; two still as they were found, but changed since -- within the
        // cutoff, which the store asks again about too.
        store.put(cxt, bucket, "late", second, meta(second))
        store.put(cxt, bucket, "replaced", second, meta(second))
        dir.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(old.toEpochMilliseconds()) }
        File(dir, "touched").setLastModified(now.toEpochMilliseconds() - 1000)
        File(dir, "retouched").setLastModified(now.toEpochMilliseconds() - 1000)

        FileStoreCheck.repair(cxt, store, found, now, minAge).map { it.done } shouldBe listOf(false, false, false, false)
        store.get(cxt, bucket, "late").shouldNotBeNull().bytes.toList() shouldBe second.toList()
        store.get(cxt, bucket, "replaced").shouldNotBeNull().meta.extra[FLEFT.metaRewrittenFromHash].shouldBeNull()
        File(dir, "touched").exists() shouldBe true
        store.meta(cxt, bucket, "retouched").shouldNotBeNull().hash shouldBe first.sha256Hex()
        // Content that changed again since it was found -- and still does not match -- is not given metadata
        // describing the content the check read.
        store.put(cxt, bucket, "again", first, meta(first))
        File(dir, "again").writeBytes(second)
        listOf("again", "again.meta.json").forEach { File(dir, it).setLastModified(old.toEpochMilliseconds()) }
        val again = FileStoreCheck.find(cxt, store, listOf(bucket)).single { it.key == "again" }
        File(dir, "again").writeBytes("third".toByteArray())
        File(dir, "again").setLastModified(old.toEpochMilliseconds())
        FileStoreCheck.repair(cxt, store, listOf(again), now, minAge).single().done shouldBe false
        store.meta(cxt, bucket, "again").shouldNotBeNull().hash shouldBe first.sha256Hex()
        // A leftover naming something no write of its key leaves reaches nothing: here a whole file, as a "temporary" one.
        store.clearLeftover(cxt, bucket, FileLeftover(bucket, "late", "late", FLEFT.tmp, old), now) shouldBe false
        File(dir, "late").exists() shouldBe true
    }

    "S3: the same states are found and repaired, and nothing recent is touched" {
        val api = FakeS3Api("kdr-$bucket").also { it.clock = { old } }
        val store = S3FileStore(api, "kdr-")
        val physical = "kdr-$bucket"
        for (key in listOf("ok", "content", "meta", "unread", "mixed", "fresh")) store.put(cxt, bucket, key, first, meta(first))
        api.objects.remove("$physical/content.meta.json")
        api.objects.remove("$physical/meta")
        api.objects["$physical/unread.meta.json"] = "{".toByteArray()
        api.objects["$physical/mixed"] = second
        api.objects.remove("$physical/fresh.meta.json")
        api.modified["$physical/fresh"] = now - 5.minutes
        // S3 writes no temporary objects: one named like a directory store's is somebody else's, and not reported.
        api.objects["$physical/stray~tmp"] = "x".toByteArray()

        val found = FileStoreCheck.find(cxt, store, listOf(bucket))
        kinds(found) shouldContainExactly listOf(
            "content" to FLEFT.contentOnly,
            "fresh" to FLEFT.contentOnly,
            "meta.meta.json" to FLEFT.metaOnly,
            "mixed" to FLEFT.mismatch,
            "unread" to FLEFT.unreadableMeta,
        )
        val repairs = FileStoreCheck.repair(cxt, store, found, now, minAge)
        repairs.filter { !it.done }.map { it.leftover.name } shouldContainExactly listOf("fresh")
        kinds(FileStoreCheck.find(cxt, store, listOf(bucket))) shouldContainExactly listOf("fresh" to FLEFT.contentOnly)
        listOf("content", "meta.meta.json", "unread", "unread.meta.json").forEach { (("$physical/$it") in api.objects) shouldBe false }
        store.get(cxt, bucket, "mixed").shouldNotBeNull().meta.hash shouldBe second.sha256Hex()
        ("$physical/stray~tmp" in api.objects) shouldBe true
    }

    "S3: a key written since it was found, or written within the cutoff since, is left alone" {
        val api = FakeS3Api("kdr-$bucket").also { it.clock = { old } }
        val store = S3FileStore(api, "kdr-")
        val physical = "kdr-$bucket"
        store.put(cxt, bucket, "late", first, meta(first))
        store.put(cxt, bucket, "touched", first, meta(first))
        api.objects.remove("$physical/late.meta.json")
        api.objects.remove("$physical/touched.meta.json")
        val found = FileStoreCheck.find(cxt, store, listOf(bucket))
        // One made whole again; the other's content rewritten a moment ago, still without its metadata.
        store.put(cxt, bucket, "late", second, meta(second))
        api.modified["$physical/touched"] = now - 1.minutes
        FileStoreCheck.repair(cxt, store, found, now, minAge).map { it.done } shouldBe listOf(false, false)
        store.get(cxt, bucket, "late").shouldNotBeNull()
        ("$physical/touched" in api.objects) shouldBe true

        // Content that changed again since it was found, still not matching, keeps the metadata it had.
        store.put(cxt, bucket, "again", first, meta(first))
        api.objects["$physical/again"] = second
        val again = FileStoreCheck.find(cxt, store, listOf(bucket)).single { it.key == "again" }
        api.objects["$physical/again"] = "third".toByteArray()
        FileStoreCheck.repair(cxt, store, listOf(again), now, minAge).single().done shouldBe false
        store.meta(cxt, bucket, "again").shouldNotBeNull().hash shouldBe first.sha256Hex()
    }

    "memory holds a file whole, so there is never anything to find" {
        val store = MemoryFileStore()
        store.put(cxt, bucket, "a/b", first, meta(first))
        store.put(cxt, bucket, "c", second, meta(second))
        store.objects(cxt, bucket).map { it.name } shouldContainExactly listOf("a/b", "a/b.meta.json", "c", "c.meta.json")
        FileStoreCheck.find(cxt, store, listOf(bucket)).shouldBeEmpty()
    }

    "what a key is now, as a repair asks again" {
        FileStoreCheck.kindNow(contentThere = false, metaText = null).shouldBeNull()
        FileStoreCheck.kindNow(contentThere = true, metaText = null) shouldBe FLEFT.contentOnly
        FileStoreCheck.kindNow(contentThere = false, metaText = meta(first).toFileText()) shouldBe FLEFT.metaOnly
        FileStoreCheck.kindNow(contentThere = true, metaText = "{") shouldBe FLEFT.unreadableMeta
        FileStoreCheck.kindNow(contentThere = true, metaText = meta(first).toFileText()).shouldBeNull()
    }
})
