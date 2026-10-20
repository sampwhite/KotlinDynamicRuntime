package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.filestore.FSENV
import com.dynamicruntime.common.filestore.FST
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.operator.FSB
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.webasset.ClientWebAssetService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/**
 * The operator's file-store browser (issue #1297), over HTTP: the buckets, a bucket drilled folder by folder down to
 * a file's row, the file downloaded byte for byte, its record read alone -- and who may, which is the operator
 * section's answer. Response-schema validation is on in tests, so each answer is also held to its declared type.
 */
class FileStoreOperatorEndpointsTest : StringSpec({
    val cxt = TestInstances.default("fileStoreBrowser")
    val files = FileStoreService.get(cxt)
    val bucket = ClientWebAssetService.bucket
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val operator = TestUser.createOperator(cxt, "fsb-operator@example.com")

    // What an application stores: two clients' images, a client's file ref, and a file that is no client's.
    files.forOwner(cxt, "acme").put(cxt.mkSubContext("seed", "acme"), bucket, ClientWebAssetService.keyOf("acme", "favicon"), ContentData(png, "image/png", saveAsFilename = "favicon-32.png"))
    files.forOwner(cxt, "acme").put(cxt.mkSubContext("seed", "acme"), bucket, FileRefService.keyOf("acme", png.sha256Hex()), ContentData(png, "image/png"))
    files.forOwner(cxt, "globex").put(cxt.mkSubContext("seed", "globex"), bucket, ClientWebAssetService.keyOf("globex", "brandMark"), ContentData(png, "image/png"))
    files.put(cxt, bucket, "notes/readme.txt", ContentData("hello".toByteArray(), "text/plain", saveAsFilename = "readme.txt"))

    fun listing(prefix: String? = null, limit: Int? = null): Map<String, Any?> =
        operator.getData(OPS.fileStoreListPath, buildMap { put(FSB.bucket, bucket); prefix?.let { put(FSB.prefix, it) }; limit?.let { put(FSB.limit, it) } })

    fun folders(prefix: String? = null) = listing(prefix)[FSB.folders].toJsonListOrEmpty()
    fun fileRows(prefix: String? = null) = listing(prefix)[FSB.files].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }

    "the buckets are listed with what the store is" {
        val answer = operator.client.sendJsonGetRequest(OPS.fileStoreBucketsPath)
        val buckets = answer[EP.items].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
        buckets.map { it[FSB.name] } shouldContain bucket
        buckets.first { it[FSB.name] == bucket }[FSB.description].toString() shouldContain "tab icon"
        answer[EP.summary].toJsonMapOrEmpty().let {
            it[FSB.provider] shouldBe FST.memory
            it[FSB.location] shouldBe FST.memory
        }
    }

    "a bucket is drilled folder by folder, the folders read off the keys, down to a file's row with its record" {
        // The instance is shared with other tests, which keep their own clients' files here: so what this one seeded
        // is looked for among them, never as the whole.
        listing().let {
            it[FSB.bucket] shouldBe bucket
            // The top of the bucket: no prefix at all.
            it[FSB.prefix] shouldBe null
            it[FSB.folders].toJsonListOrEmpty() shouldContainAll listOf("clients/", "notes/")
            it[FSB.files].toJsonListOrEmpty().shouldBeEmpty()
            it[FSB.more] shouldBe false
        }
        folders("clients/") shouldContainAll listOf("acme/", "globex/")
        folders("clients/acme/") shouldContainExactly listOf("refs/", "web/")
        // A folder named without its slash is the same folder.
        folders("clients/acme") shouldContainExactly listOf("refs/", "web/")
        listing("clients/acme")[FSB.prefix] shouldBe "clients/acme/"
        val favicon = fileRows("clients/acme/web/").first { it[FSB.name] == "favicon" }
        favicon[FSB.key] shouldBe "clients/acme/web/favicon"
        favicon[FSB.name] shouldBe "favicon"
        favicon[FSB.state] shouldBe FSB.stateOk
        favicon[FSB.modifiedAt].shouldNotBeNull()
        favicon[FSB.meta].toJsonMapOrEmpty().let {
            it[FMETA.mimeType] shouldBe "image/png"
            (it[FMETA.size] as Number).toLong() shouldBe png.size.toLong()
            it[FMETA.hash] shouldBe png.sha256Hex()
            it[FMETA.fileName] shouldBe "favicon-32.png"
            it[FMETA.client] shouldBe "acme"
        }
        fileRows("clients/acme/refs/").map { it[FSB.name] } shouldContain png.sha256Hex()
        fileRows("notes/").single()[FSB.name] shouldBe "readme.txt"
        // An empty folder is an empty answer, and a long one is cut, folders first, and says so.
        listing("nowhere/")[FSB.folders].toJsonListOrEmpty().shouldBeEmpty()
        listing(limit = 1).let {
            it[FSB.folders].toJsonListOrEmpty() shouldContainExactly listOf("clients/")
            it[FSB.more] shouldBe true
        }
    }

    "a file is downloaded byte for byte under its key's last segment, and its record read alone" {
        val key = ClientWebAssetService.keyOf("acme", "favicon")
        val download = operator.client.sendGetRequest(OPS.fileStoreDownloadPath, mapOf(FSB.bucket to bucket, FSB.key to key))
        download.rptStatusCode shouldBe EXC.ok
        download.rptResponseMimeType shouldBe "image/png"
        download.rptResponseBytes.shouldNotBeNull().toList() shouldBe png.toList()
        download.rptResponseHeaders["content-disposition"]?.first().shouldNotBeNull().let {
            it shouldContain "attachment"
            it shouldContain "filename=\"favicon\""
        }
        val meta = operator.getItem(OPS.fileStoreDownloadMetaPath, mapOf(FSB.bucket to bucket, FSB.key to key))
        meta[FMETA.hash] shouldBe png.sha256Hex()
        meta[FMETA.fileName] shouldBe "favicon-32.png"
        // No such file: a 404 from both; no such bucket: a 404 naming the buckets; a key that is no key: a 400.
        operator.expectError(EXC.notFound, OPS.fileStoreDownloadPath, args = mapOf(FSB.bucket to bucket, FSB.key to "clients/acme/web/nothing"))
        operator.expectError(EXC.notFound, OPS.fileStoreDownloadMetaPath, args = mapOf(FSB.bucket to bucket, FSB.key to "clients/acme/web/nothing"))
        operator.expectError(EXC.notFound, OPS.fileStoreListPath, args = mapOf(FSB.bucket to "no-such-bucket"))
        operator.expectError(EXC.badInput, OPS.fileStoreListPath, args = mapOf(FSB.bucket to bucket, FSB.prefix to "../x"))
        operator.expectError(EXC.badInput, OPS.fileStoreDownloadPath, args = mapOf(FSB.bucket to bucket, FSB.key to "a//b"))
    }

    "it is the operator's: a plain user, and an operator confined to a client, are refused" {
        val plain = TestUser.create(cxt, "fsb-plain@example.com")
        val confined = TestUser.create(cxt, "fsb-confined@example.com", level = ROLE.operator)
        for (user in listOf(plain, confined)) {
            user.expectError(EXC.notAuthorized, OPS.fileStoreBucketsPath)
            user.expectError(EXC.notAuthorized, OPS.fileStoreListPath, args = mapOf(FSB.bucket to bucket))
            user.expectError(EXC.notAuthorized, OPS.fileStoreDownloadPath, args = mapOf(FSB.bucket to bucket, FSB.key to "notes/readme.txt"))
        }
    }

    // On a directory store, what a write that stopped part-way leaves: shown flagged where it is, as the check
    // script would report it, never hidden and never passed off as a file.
    "a file missing its record is listed flagged, and cannot be downloaded" {
        val dir = Files.createTempDirectory("kdrFileStoreBrowser").toFile()
        try {
            val local = Startup.mkTestBootCxt("fileStoreBrowserLocal", "fileStoreBrowserLocal", mapOf(FSENV.type.name to FST.local, FSENV.dir.name to dir.absolutePath))
            val opal = TestUser.createOperator(local, "fsb-local-operator@example.com")
            FileStoreService.get(local).put(local, bucket, "notes/whole.txt", ContentData("a".toByteArray(), "text/plain"))
            File(dir, "$bucket/notes/half.txt").writeText("b")
            val rows = opal.getData(OPS.fileStoreListPath, mapOf(FSB.bucket to bucket, FSB.prefix to "notes/"))[FSB.files].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
            rows.map { it[FSB.name] to it[FSB.state] } shouldContainExactly listOf("half.txt" to FSB.stateNoMeta, "whole.txt" to FSB.stateOk)
            rows.first { it[FSB.name] == "half.txt" }[FSB.meta] shouldBe null
            opal.expectError(EXC.notFound, OPS.fileStoreDownloadPath, args = mapOf(FSB.bucket to bucket, FSB.key to "notes/half.txt"))
            opal.client.sendGetRequest(OPS.fileStoreDownloadPath, mapOf(FSB.bucket to bucket, FSB.key to "notes/whole.txt")).rptStatusCode shouldBe EXC.ok
            // Content that no longer matches its record is refused, as every read refuses it, not handed out as good.
            File(dir, "$bucket/notes/whole.txt").writeText("changed")
            opal.client.sendGetRequest(OPS.fileStoreDownloadPath, mapOf(FSB.bucket to bucket, FSB.key to "notes/whole.txt")).rptStatusCode shouldBe EXC.internalError
        } finally {
            dir.deleteRecursively()
        }
    }
})
