package com.dynamicruntime.sample.file

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * End-to-end coverage of the sample file endpoints (issue #96): upload, list, download — the round trip the
 * file interface exists for, driven through the in-process [TestHttpClient].
 *
 * The store writes to `<workspace>/sampleFiles`, so these use a temporary workspace via the `kdr.workspaceDir`
 * system property ([com.dynamicruntime.common.sql.AppPaths]) rather than scribbling in the real one.
 */
class SampleFileApiTest : StringSpec({

    InstanceRegistry.register(listOf(SampleComponent()))

    val tempWorkspace = createTempDir()

    beforeSpec { System.setProperty("kdr.workspaceDir", tempWorkspace.absolutePath) }
    afterSpec {
        System.clearProperty("kdr.workspaceDir")
        tempWorkspace.deleteRecursively()
    }

    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "sampleFileTest").instanceConfig)

    // A PNG header: the bytes are deliberately not valid UTF-8, so a round trip that survives them proves the
    // content never went through the text path.
    val pngBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0x42)

    "a file survives an upload and download round trip byte-for-byte" {
        val c = client("roundTrip")

        val uploaded = c.sendUploadRequest(
            "/file/upload",
            mapOf(SF.file to ContentData(pngBytes, "image/png", saveAsFilename = "chart.png")),
        )
        uploaded.rptStatusCode shouldBe 200
        val results = uploaded.rptResponseData.shouldNotBeNull().jsonMap()?.get(EP.results).toJsonMapOrEmpty()
        val id = results[SF.id] as? String
        id.shouldNotBeNull()
        results[SF.fileName] shouldBe "chart.png"
        results[SF.mimeType] shouldBe "image/png"
        (results[SF.size] as Number).toLong() shouldBe pngBytes.size.toLong()

        val downloaded = c.sendGetRequest("/file/download", mapOf(SF.id to id))
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

    "an uploaded file appears in the list" {
        val c = client("listing")
        c.sendUploadRequest("/file/upload", mapOf(SF.file to ContentData("hello".toByteArray(), "text/plain", saveAsFilename = "notes.txt")))
        val items = c.sendJsonGetRequest("/file/list")[EP.items] as? List<*>
        items.shouldNotBeNull()
        items.map { it.toJsonMapOrEmpty()[SF.fileName] } shouldContain "notes.txt"
    }

    "downloading an unknown id is a 404, not a server error" {
        val resp = client("missing").sendGetRequest("/file/download", mapOf(SF.id to "0123456789abcdef0123456789abcdef"))
        resp.rptStatusCode shouldBe 404
    }

    // The store never derives a path from a client-supplied name, but the id does reach the filesystem, so it
    // is checked rather than sanitized: anything that is not one of our hex ids is refused outright.
    "a file id that is not ours cannot walk out of the store" {
        for (bad in listOf("../../etc/passwd", "..", "a/b", "abc .bin", "abc\u0000.bin", "ABCDEF", "zz")) {
            val resp = client("traversal").sendGetRequest("/file/download", mapOf(SF.id to bad))
            // 400 (not a valid id) -- never a 200, and never a 500 from the filesystem.
            resp.rptStatusCode shouldNotBe 200
            resp.rptStatusCode shouldNotBe 500
        }
    }

    // The client's filename is metadata, never a path: the file on disk is named for its id.
    "an upload's filename never becomes the path on disk" {
        val c = client("naming")
        val uploaded = c.sendUploadRequest(
            "/file/upload",
            mapOf(SF.file to ContentData("x".toByteArray(), "text/plain", saveAsFilename = "../../evil.txt")),
        )
        val results = uploaded.rptResponseData.shouldNotBeNull().jsonMap()?.get(EP.results).toJsonMapOrEmpty()
        val id = results[SF.id] as String
        // The name is preserved as metadata...
        results[SF.fileName] shouldBe "../../evil.txt"

        // ... while on disk the file is named for its id, and nothing anywhere is named after what the client
        // asked for. (The store is shared across this spec's tests, so this checks what is present and absent
        // rather than the exact directory contents.)
        val store = java.io.File(tempWorkspace, SampleFileStore.storeDirName)
        val names = store.listFiles()?.map { it.name }.orEmpty()
        names shouldContain "$id.bin"
        names shouldContain "$id.json"
        names.none { it.contains("evil") } shouldBe true
        // Above all, the `../../` in the name walked nowhere: no file escaped the store.
        java.io.File(tempWorkspace, "evil.txt").exists() shouldBe false
        java.io.File(tempWorkspace.parentFile, "evil.txt").exists() shouldBe false
    }
})

private fun createTempDir(): java.io.File =
    java.nio.file.Files.createTempDirectory("kdrSampleFileTest").toFile()
