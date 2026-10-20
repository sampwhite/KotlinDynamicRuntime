package com.dynamicruntime.script

import com.dynamicruntime.common.filestore.FSENV
import com.dynamicruntime.common.filestore.FST
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.startup.BootStage
import com.dynamicruntime.common.webasset.ClientWebAssetService
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * The script that empties a deployment's file store (issue #1244), on the boot it runs with on a node: the `collected`
 * stage, with the file store's service alone (issue #1289). It reports until told to remove, and keeps the web assets
 * of clients defined in source.
 */
class ClearFileStoreTest : StringSpec({

    "it reports, then removes, keeping a source client's web assets" {
        val dir = Files.createTempDirectory("kdrClearScript").toFile()
        try {
            val cxt = Startup.mkTestBootCxt(
                "clearFileStore", "clearFileStoreTest",
                mapOf(FSENV.type.name to FST.local, FSENV.dir.name to dir.absolutePath),
                stage = BootStage.collected, services = setOf(FileStoreService.serviceName),
            )
            // Read from the components' contributions, with no service run: the clients a reset recreates.
            val sources = ClearFileStore.sourceClients(cxt).shouldNotBeEmpty()
            val source = sources.first()
            val files = FileStoreService.get(cxt)
            files.forOwner(cxt, source).put(cxt, ClientWebAssetService.bucket, ClientWebAssetService.keyOf(source, "favicon"), ContentData("s".toByteArray(), "image/png"))
            files.forOwner(cxt, "visitor").put(cxt, ClientWebAssetService.bucket, ClientWebAssetService.keyOf("visitor", "favicon"), ContentData("v".toByteArray(), "image/png"))

            val reported = mutableListOf<String>()
            ClearFileStore.runOn(cxt, remove = false) { reported.add(it) } shouldBe 0
            reported shouldContain "${ClientWebAssetService.bucket}: would remove 1 file(s), keep 1."
            files.forOwner(cxt, "visitor").meta(cxt, ClientWebAssetService.bucket, ClientWebAssetService.keyOf("visitor", "favicon")).shouldNotBeNull()

            val removed = mutableListOf<String>()
            ClearFileStore.runOn(cxt, remove = true) { removed.add(it) } shouldBe 0
            removed shouldContain "${ClientWebAssetService.bucket}: removed 1 file(s), kept 1."
            files.forOwner(cxt, "visitor").meta(cxt, ClientWebAssetService.bucket, ClientWebAssetService.keyOf("visitor", "favicon")).shouldBeNull()
            files.forOwner(cxt, source).meta(cxt, ClientWebAssetService.bucket, ClientWebAssetService.keyOf(source, "favicon")).shouldNotBeNull()
        } finally {
            dir.deleteRecursively()
        }
    }
})
