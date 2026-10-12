package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FSENV
import com.dynamicruntime.common.filestore.FST
import com.dynamicruntime.common.filestore.FakeS3Api
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.sha256Hex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * What a node keeps of a client's web assets between reads of the store (issue #1130), over a store that can be
 * made to fail and to change mid-read -- a stand-in for S3, which is what the memo is there to spare. The
 * endpoints, the shell config and the static address are `ClientWebAssetsTest`'s, on a booted instance.
 */
class ClientWebAssetServiceTest : StringSpec({
    val bucket = ClientWebAssetService.bucket
    val client = "acme"
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val name = WAS.versionedName(WAS.favicon, png.sha256Hex())
    var instances = 0

    /** The service over its own store, with the clock the memo is aged by in the test's hands. */
    class Rig(val cxt: KdrCxt, val api: FakeS3Api, val files: FileStoreService, val assets: ClientWebAssetService) {
        var now = 0L

        init {
            assets.nanoTime = { now }
        }

        fun age(ms: Long) {
            now += ms * ClientWebAssetService.nanosPerMs
        }
    }

    fun rig(): Rig {
        val api = FakeS3Api(bucket)
        val config = KdrInstanceConfig("webAssetService${instances++}", ENV.unit, ENV.liveSource)
        config.put(FSENV.type.name, FST.s3)
        config.put(FileStoreService.s3ApiConfigKey, api)
        config.put(SchemaCollector.key, SchemaCollector().apply { addFileBucket(ClientWebAssetService.bucketDef()) })
        val cxt = KdrCxt("webAssetService", config)
        val files = FileStoreService().apply { onCreate(cxt); checkInit(cxt) }
        config.put(FileStoreService.serviceName, files)
        return Rig(cxt, api, files, ClientWebAssetService())
    }

    /** Stores the favicon as another node would: straight into the store, past this node's service. */
    fun Rig.storeFavicon() {
        files.put(cxt.mkSubContext("otherNode", client), bucket, ClientWebAssetService.keyOf(client, WAS.favicon), ContentData(png, "image/png"))
    }

    "what a client's slots hold is read once and gone on for the memo's length" {
        val rig = rig()
        rig.storeFavicon()

        rig.assets.versionedNames(rig.cxt, client) shouldBe mapOf(WAS.favicon to name)
        rig.api.calls.clear()
        rig.age(ClientWebAssetService.memoMs - 1)
        rig.assets.versionedNames(rig.cxt, client) shouldBe mapOf(WAS.favicon to name)
        rig.api.calls.shouldBeEmpty()
        // Asked fresh, the store is read whatever is held; and aged, it is read again.
        rig.assets.slots(rig.cxt, client, fresh = true).keys shouldBe setOf(WAS.favicon)
        rig.api.calls.isNotEmpty() shouldBe true
        rig.api.calls.clear()
        rig.age(ClientWebAssetService.memoMs + 1)
        rig.assets.versionedNames(rig.cxt, client)
        rig.api.calls.isNotEmpty() shouldBe true
    }

    "a store that cannot be read costs a client its images and not its shell, and is not asked again by every page" {
        val rig = rig()
        rig.storeFavicon()
        rig.api.unreachable = true

        // The shell config is answered -- with nothing, so the deployment's images -- rather than failed.
        rig.assets.versionedNames(rig.cxt, client).shouldBeEmpty()
        // The page that edits the slots is told: an empty listing there would say the client has set nothing.
        shouldThrow<KdrException> { rig.assets.slots(rig.cxt, client, fresh = true) }

        // The store is back, and is left alone until the memo of the failure has aged.
        rig.api.unreachable = false
        rig.assets.versionedNames(rig.cxt, client).shouldBeEmpty()
        rig.api.calls.clear()
        rig.assets.versionedNames(rig.cxt, client).shouldBeEmpty()
        rig.api.calls.shouldBeEmpty()
        rig.age(ClientWebAssetService.memoMs + 1)
        rig.assets.versionedNames(rig.cxt, client) shouldBe mapOf(WAS.favicon to name)
    }

    "a failed read that an upload on this node overtook does not hide the upload" {
        val rig = rig()
        rig.storeFavicon()
        // The read fails, and while it was failing an upload finished here: it has dropped what was held.
        rig.api.failing = { call ->
            (call.startsWith("get ")).also { failed -> if (failed) rig.assets.forget(client) }
        }
        rig.assets.versionedNames(rig.cxt, client).shouldBeEmpty()

        // "Nothing set" was not written over that: the next page load reads the store and finds the image.
        rig.api.failing = null
        rig.assets.versionedNames(rig.cxt, client) shouldBe mapOf(WAS.favicon to name)
    }

    "a read that an upload on this node overtook is not kept as what the slots hold" {
        val rig = rig()
        // The slots are read as empty, and before the read returns an upload lands and drops what was held.
        rig.api.afterGet = { _, _ ->
            rig.api.afterGet = null
            rig.storeFavicon()
            rig.assets.forget(client)
        }
        rig.assets.versionedNames(rig.cxt, client).shouldBeEmpty()

        // What that read saw predates the upload, so it was not kept: the next one is the store's.
        rig.assets.versionedNames(rig.cxt, client) shouldBe mapOf(WAS.favicon to name)
    }

    "each client's slots are held apart, and a sandbox's are its parent's" {
        val rig = rig()
        rig.storeFavicon()

        rig.assets.versionedNames(rig.cxt, "globex").shouldBeEmpty()
        rig.assets.versionedNames(rig.cxt, client) shouldBe mapOf(WAS.favicon to name)
        rig.assets.versionedNames(rig.cxt, "$client:sandbox") shouldBe mapOf(WAS.favicon to name)
        ClientWebAssetService.ownerOf("$client:sandbox") shouldBe client
        ClientWebAssetService.keyOf(client, WAS.brandMark) shouldBe "clients/acme/web/brandMark"
    }
})
