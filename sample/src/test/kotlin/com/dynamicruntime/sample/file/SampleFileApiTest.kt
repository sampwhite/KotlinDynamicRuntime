package com.dynamicruntime.sample.file

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.filestore.FST
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * End-to-end coverage of the sample file endpoints (issue #96): upload, list, download — the round trip the
 * file interface exists for, driven through the in-process [TestHttpClient].
 *
 * The uploads are kept in the runtime's file store (issue #1144), which a test instance runs in memory: nothing is
 * written to the workspace.
 */
class SampleFileApiTest : StringSpec({
    // Force SampleComponent.isLoaded on: it otherwise gates to developer envs, and mkTestBootCxt uses unit.
    val cxt = Startup.mkTestBootCxt("sampleFiles", "sampleFileTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()))
    val c = TestHttpClient(cxt.instanceConfig)
    val files = FileStoreService.get(cxt)

    // A PNG header: the bytes are deliberately not valid UTF-8, so a round trip that survives them proves the
    // content never went through the text path.
    val pngBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0x42)

    fun upload(content: ContentData): Map<String, Any?> {
        val uploaded = c.sendUploadRequest("/demo/file/upload", mapOf(SF.file to content))
        uploaded.rptStatusCode shouldBe 200
        return uploaded.rptResponseData.shouldNotBeNull().jsonMap()?.get(EP.results).toJsonMapOrEmpty()
    }

    "a file survives an upload and download round trip byte-for-byte" {
        val results = upload(ContentData(pngBytes, "image/png", saveAsFilename = "chart.png"))
        val id = results[SF.id] as? String
        id.shouldNotBeNull()
        results[SF.fileName] shouldBe "chart.png"
        results[SF.mimeType] shouldBe "image/png"
        (results[SF.size] as Number).toLong() shouldBe pngBytes.size.toLong()

        val downloaded = c.sendGetRequest("/demo/file/download", mapOf(SF.id to id))
        downloaded.rptStatusCode shouldBe 200
        downloaded.rptResponseMimeType shouldBe "image/png"
        // The bytes come back exactly, through the binary response path.
        downloaded.rptResponseBytes.shouldNotBeNull().toList() shouldBe pngBytes.toList()
        // ... and never as a (lossy) String.
        downloaded.rptResponseData shouldBe null
        // The name it was uploaded under is offered back as the download name.
        downloaded.rptResponseHeaders["content-disposition"]?.first().shouldNotBeNull()
            .shouldContain("filename=\"chart.png\"")
    }

    "an upload is a file in the store: in the sample's own bucket, under its id, with its metadata beside it" {
        files.providerType shouldBe FST.memory
        val id = upload(ContentData(pngBytes, "image/png", saveAsFilename = "kept.png"))[SF.id] as String
        val meta = files.meta(cxt, SFK.bucket, "${SFK.uploadsPrefix}$id").shouldNotBeNull()
        meta.hash shouldBe pngBytes.sha256Hex()
        meta.fileName shouldBe "kept.png"
        meta.mimeType shouldBe "image/png"
    }

    "the list holds what was uploaded, newest first" {
        // Several, a moment apart: the store answers in key order, and keys are random ids, so two files would be in
        // the right order by chance half the time -- six, one time in 720.
        val uploaded = (1..6).map { n ->
            if (n > 1) Thread.sleep(5)
            upload(ContentData("file $n".toByteArray(), "text/plain", saveAsFilename = "file$n.txt"))[SF.id] as String
        }
        val items = (c.sendJsonGetRequest("/demo/file/list")[EP.items] as? List<*>).shouldNotBeNull().map { it.toJsonMapOrEmpty() }
        items.map { it[SF.id] }.filter { it in uploaded } shouldBe uploaded.reversed()
        items.first { it[SF.id] == uploaded[0] }[SF.fileName] shouldBe "file1.txt"
        // Each listed file downloads by the id the list gave.
        c.sendGetRequest("/demo/file/download", mapOf(SF.id to uploaded[1])).rptResponseBytes.shouldNotBeNull().decodeToString() shouldBe "file 2"
    }

    "a part with no filename is listed under one made from its id" {
        val results = upload(ContentData("x".toByteArray(), "text/plain"))
        results[SF.fileName] shouldBe "upload-${results[SF.id]}"
    }

    "downloading an unknown id is a 404, not a server error" {
        val resp = c.sendGetRequest("/demo/file/download", mapOf(SF.id to "0123456789abcdef0123456789abcdef"))
        resp.rptStatusCode shouldBe 404
    }

    // The id becomes a key, so it is checked rather than sanitized: anything that is not one of our hex ids is
    // refused as that, before the store is asked.
    "a file id that is not ours is refused before it becomes a key" {
        for (bad in listOf("../../etc/passwd", "..", "a/b", "abc .bin", "abc\u0000.bin", "ABCDEF", "zz", "0123456789abcdef", "0123456789ABCDEF0123456789abcdef")) {
            c.sendGetRequest("/demo/file/download", mapOf(SF.id to bad)).rptStatusCode shouldBe 400
        }
    }

    // The client's filename is metadata, never a key: the file is kept under its id.
    "an upload's filename never becomes its key" {
        val results = upload(ContentData("x".toByteArray(), "text/plain", saveAsFilename = "../../evil.txt"))
        val id = results[SF.id] as String
        // The name is kept as metadata -- its last segment, which is all of it the store records...
        results[SF.fileName] shouldBe "evil.txt"
        // ... while the file is kept under its id, and nothing in the bucket is named after what the client asked for.
        val keys = files.list(cxt, SFK.bucket).map { it.key }
        keys shouldContain "${SFK.uploadsPrefix}$id"
        keys.none { it.contains("evil") } shouldBe true
    }
})
