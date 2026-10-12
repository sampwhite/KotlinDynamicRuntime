package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FSENV
import com.dynamicruntime.common.filestore.FST
import com.dynamicruntime.common.filestore.FileBucketDef
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/** A component that keeps files: it declares its bucket, which is all a component does to have one. */
class FileBucketFixtureComponent : ComponentDefinition {
    override val providerName: String = "fileBucketFixture"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addFileBucket(FileBucketDef(bucket, "Files of the file-store boot test."))
    }

    companion object {
        const val bucket = "boot-fixture-files"
    }
}

/**
 * The file store as a booted instance carries it (issue #1129): the service is there, the bucket a component
 * declared is made ready at startup, and one that cannot be refuses the boot.
 */
class FileStoreBootTest : StringSpec({
    val tempDirs = mutableListOf<File>()

    fun tempDir(): File = Files.createTempDirectory("kdrFileStoreBoot").toFile().also { tempDirs.add(it) }

    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    "a test instance keeps its files in memory, and the check that its buckets are ready is on the boot report" {
        val cxt = TestInstances.default("fileStoreDefault")

        val service = FileStoreService.get(cxt)

        service.providerType shouldBe FST.memory
        BootCheckRegistry.get(cxt).results().single { it.name == BCHK.fileStore }.findings.shouldBeEmpty()
    }

    "a bucket a component declares is made ready by the boot, and files go in and come out of it" {
        val dir = tempDir()
        // Its own instance: the store and its directory are instance-wide, and the fixture declares a bucket.
        val cxt = Startup.mkTestBootCxt(
            "fileStoreBoot", "fileStoreBootLocal", mapOf(FSENV.type.name to FST.local, FSENV.dir.name to dir.absolutePath),
            additionalComponents = listOf(FileBucketFixtureComponent()),
        )
        val bucket = FileBucketFixtureComponent.bucket
        File(dir, bucket).isDirectory shouldBe true
        val service = FileStoreService.get(cxt)
        service.declaredBuckets().map { it.name } shouldContainExactly listOf(bucket)

        val meta = service.put(cxt, bucket, "reports/q3", ContentData("three,four\n", "text/csv", saveAsFilename = "q3.csv"))

        File(dir, "$bucket/reports/q3").readText() shouldBe "three,four\n"
        File(dir, "$bucket/reports/q3.meta.json").isFile shouldBe true
        val file = service.get(cxt, bucket, "reports/q3").shouldNotBeNull()
        file.meta.fileName shouldBe "q3.csv"
        file.meta.hash shouldBe meta.hash
        service.meta(cxt, bucket, "reports/q3").shouldNotBeNull().mimeType shouldBe "text/csv"
    }

    "a declared bucket the store cannot make ready refuses the boot" {
        // A file where the store's directory should be, so no bucket can be made under it.
        val taken = File(tempDir(), "taken").apply { writeText("x") }

        val refused = shouldThrow<KdrException> {
            // Its own instance: the boot itself is under test, and is expected to be refused.
            Startup.mkTestBootCxt(
                "fileStoreRefused", "fileStoreBootRefused", mapOf(FSENV.type.name to FST.local, FSENV.dir.name to taken.absolutePath),
                additionalComponents = listOf(FileBucketFixtureComponent()),
            )
        }

        generateSequence<Throwable>(refused) { it.cause }.mapNotNull { it.message }.joinToString(" | ") shouldContain
            "the local file store cannot serve every declared bucket"
    }
})
