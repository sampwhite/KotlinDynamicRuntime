package com.dynamicruntime.script

import com.dynamicruntime.common.filestore.FSENV
import com.dynamicruntime.common.filestore.FST
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.startup.BootStage
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.webasset.ClientWebAssetService
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * `kdr-run com.dynamicruntime.script.CheckFileStoreKt` (issue #1147): what its arguments mean, and a run against a
 * booted node's own store -- a directory, as a deployment's would be -- reporting by default, repairing when asked.
 */
class CheckFileStoreTest : StringSpec({
    val bucket = ClientWebAssetService.bucket

    "the arguments: buckets, --repair, and how old something must be before it is touched" {
        CheckFileStore.parseArgs(emptyList()) shouldBe CheckFileStore.Options(emptyList(), repair = false, minAgeMinutes = CheckFileStore.defaultMinAgeMinutes)
        CheckFileStore.parseArgs(listOf(bucket, "--repair", "--min-age-minutes", "5")) shouldBe CheckFileStore.Options(listOf(bucket), repair = true, minAgeMinutes = 5)
        CheckFileStore.parseArgs(listOf("--min-age-minutes")).shouldBeNull()
        CheckFileStore.parseArgs(listOf("--min-age-minutes", "-1")).shouldBeNull()
        CheckFileStore.parseArgs(listOf("--force")).shouldBeNull()
    }

    "a run reports what is left behind, changes nothing until asked, and then repairs it" {
        val dir = Files.createTempDirectory("kdrCheckFileStore").toFile()
        try {
            val cxt = Startup.mkTestBootCxt(
                "checkFileStore", "checkFileStoreTest",
                mapOf(FSENV.type.name to FST.local, FSENV.dir.name to dir.absolutePath),
                stage = BootStage.collected, services = setOf(FileStoreService.serviceName),
            )
            val files = FileStoreService.get(cxt)
            files.put(cxt, bucket, "kept", ContentData("a".toByteArray(), "image/png"))
            files.put(cxt, bucket, "half", ContentData("b".toByteArray(), "image/png"))
            File(dir, "$bucket/half.meta.json").delete() shouldBe true
            val lines = mutableListOf<String>()

            CheckFileStore.runOn(cxt, CheckFileStore.Options(emptyList(), repair = false, minAgeMinutes = 0)) { lines.add(it) } shouldBe CheckFileStore.foundExit
            lines.first() shouldContain "Checking the local file store"
            lines.any { it.contains("'half' has content and no metadata") && it.contains("a repair would remove 'half'") } shouldBe true
            lines.last() shouldContain "1 found; nothing was changed."
            File(dir, "$bucket/half").exists() shouldBe true

            // Asked to repair, but what changed within the hour is left alone.
            lines.clear()
            CheckFileStore.runOn(cxt, CheckFileStore.Options(emptyList(), repair = true, minAgeMinutes = 60)) { lines.add(it) } shouldBe CheckFileStore.foundExit
            lines.last() shouldBe "0 repaired, 1 left alone."
            File(dir, "$bucket/half").exists() shouldBe true

            lines.clear()
            CheckFileStore.runOn(cxt, CheckFileStore.Options(listOf(bucket), repair = true, minAgeMinutes = 0)) { lines.add(it) } shouldBe 0
            lines.last() shouldBe "1 repaired, 0 left alone."
            File(dir, "$bucket/half").exists() shouldBe false
            files.get(cxt, bucket, "kept").shouldNotBeNull()

            lines.clear()
            CheckFileStore.runOn(cxt, CheckFileStore.Options(emptyList(), repair = false, minAgeMinutes = 0)) { lines.add(it) } shouldBe 0
            lines shouldContain "Nothing left behind."
            // A bucket no component declared is a mistake, said before anything is read.
            CheckFileStore.runOn(cxt, CheckFileStore.Options(listOf("no-such"), repair = false, minAgeMinutes = 0)) { lines.add(it) } shouldBe CheckFileStore.failureExit
        } finally {
            dir.deleteRecursively()
        }
    }

    "the memory store holds nothing beyond its run, so there is nothing to check" {
        val cxt = Startup.mkTestBootCxt("checkFileStoreMemory", "checkFileStoreMemoryTest")
        CheckFileStore.runOn(cxt, CheckFileStore.Options(emptyList(), repair = false, minAgeMinutes = 0)) {} shouldBe CheckFileStore.failureExit
    }
})
