package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.webasset.ClientWebAssetService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock

/**
 * Emptying a store's bucket (issue #1244), on each provider: what is kept stays exactly as it is, everything else goes
 * file by file, and the bucket itself is still there.
 */
class FileStoreClearTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("fileStoreClear")
    val tempDirs = mutableListOf<File>()
    val bucket = "web-assets"
    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    fun meta(bytes: ByteArray) = FileMeta(
        mimeType = "image/png", size = bytes.size.toLong(), hash = "hash-of-${bytes.size}", uploadedAt = "2026-10-20T08:30:00Z".parseDate(),
    )

    // Kept: a source client's web assets. Removed: a runtime client's, and the file refs beside them.
    val acmeFavicon = ClientWebAssetService.keyOf("acme", "favicon")
    val acmeMark = ClientWebAssetService.keyOf("acme", "brandMark")
    val visitorFavicon = ClientWebAssetService.keyOf("visitor", "favicon")
    val fileRef = "clients/acme/files/report"
    val keep: (String) -> Boolean = { it.startsWith(ClientWebAssetService.folderOf("acme")) }

    val providers: List<Pair<String, () -> FileStore>> = listOf(
        FST.memory to { MemoryFileStore() },
        FST.local to { LocalFileStore(Files.createTempDirectory("kdrClear").toFile().also { tempDirs.add(it) }) },
        FST.s3 to { S3FileStore(FakeS3Api("kdr-$bucket"), "kdr-") },
    )

    for ((name, create) in providers) {
        "$name: a source client's web assets are kept, and everything else goes, the bucket staying" {
            val store = create().also { it.ensureBucket(cxt, bucket).shouldBeEmpty() }
            for (key in listOf(acmeFavicon, acmeMark, visitorFavicon, fileRef)) {
                val bytes = key.toByteArray()
                store.put(cxt, bucket, key, bytes, meta(bytes))
            }

            val plan = FileStoreClear.plan(cxt, store, bucket, keep)
            plan.keep shouldBe listOf(acmeMark, acmeFavicon)
            plan.remove shouldBe listOf(fileRef, visitorFavicon)

            FileStoreClear.clear(cxt, store, plan, Clock.System.now()) shouldBe 2
            store.get(cxt, bucket, acmeFavicon).shouldNotBeNull()
            store.get(cxt, bucket, acmeMark).shouldNotBeNull()
            store.get(cxt, bucket, visitorFavicon).shouldBeNull()
            store.get(cxt, bucket, fileRef).shouldBeNull()
            // Nothing of a removed file is left -- no content, no metadata -- and the bucket can still be used.
            store.objects(cxt, bucket).map { it.name }.none { it.startsWith("clients/visitor") || it.startsWith(fileRef) } shouldBe true
            store.ensureBucket(cxt, bucket).shouldBeEmpty()
        }
    }

    "a local store's leftovers of a removed file go too" {
        val root = Files.createTempDirectory("kdrClearLeftover").toFile().also { tempDirs.add(it) }
        val store = LocalFileStore(root).also { it.ensureBucket(cxt, bucket).shouldBeEmpty() }
        val bytes = "ref".toByteArray()
        store.put(cxt, bucket, fileRef, bytes, meta(bytes))
        // A write that stopped part-way: its temporary file, which is no file of the store's.
        File(root, "$bucket/$fileRef.next${LocalFileStore.tmpSuffix}").also { it.parentFile.mkdirs() }.writeText("half")
        File(root, "$bucket/clients/acme/files/half${LocalFileStore.tmpSuffix}").writeText("half")

        val plan = FileStoreClear.plan(cxt, store, bucket) { false }
        FileStoreClear.clear(cxt, store, plan, Clock.System.now())
        store.objects(cxt, bucket).shouldBeEmpty()
    }
})
