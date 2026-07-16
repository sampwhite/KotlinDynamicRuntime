package com.dynamicruntime.sample.file

import com.dynamicruntime.common.http.server.TestHttpServer
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * The file endpoints over **real HTTP**, against a real Jetty — which is the only way to cover the multipart
 * parsing, and the reason this exists alongside [SampleFileApiTest].
 *
 * [com.dynamicruntime.common.http.request.TestHttpClient] hands an upload's parts to the handler already
 * decoded, because in-process there is no wire to parse. That is the right trade for testing endpoints, but it
 * means Jetty's parser — and the `MultiPartConfig` it is given — had **no coverage at all**, and a real upload
 * failed on a configuration those tests could not see: Jetty spills any part over `maxMemoryPartSize` to a
 * file, its default for which is **1 KB**, and with no `location` configured the upload dies with "No files
 * directory configured".
 *
 * So the sizes here matter. [largeUpload] is deliberately well over that 1 KB threshold: a small file takes a
 * different path through Jetty and proves nothing about a real one. (The bug survived a hand-check with an
 * 895-byte PNG — it passed by 129 bytes.)
 */
class SampleFileHttpTest : StringSpec({

    InstanceRegistry.register(listOf(SampleComponent()))

    val tempWorkspace = Files.createTempDirectory("kdrSampleFileHttpTest").toFile()
    lateinit var server: TestHttpServer

    beforeSpec {
        System.setProperty("kdr.workspaceDir", tempWorkspace.absolutePath)
        // Boot the instance so its endpoints are registered, then serve them on a real socket.
        val instanceName = "sampleFileHttpTest"
        Startup.mkTestBootCxt("httpBoot", instanceName)
        server = TestHttpServer(instanceName)
    }

    afterSpec {
        server.close()
        System.clearProperty("kdr.workspaceDir")
        tempWorkspace.deleteRecursively()
    }

    val client: HttpClient = HttpClient.newHttpClient()

    /** A real multipart/form-data body: one file part, built by hand so the bytes on the wire are known. */
    fun multipartBody(boundary: String, field: String, fileName: String, mimeType: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"$field\"; filename=\"$fileName\"\r\n".toByteArray())
        out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())
        out.write(content)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    fun upload(fileName: String, mimeType: String, content: ByteArray): HttpResponse<String> {
        val boundary = "----kdrTestBoundary${System.nanoTime()}"
        val request = HttpRequest.newBuilder(URI.create(server.url("/kda/file/upload")))
            .header("Content-Type", "$MULTIPART; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, SF.file, fileName, mimeType, content)))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    // 64 KB: far past Jetty's 1 KB in-memory part limit, so this is the case that actually exercises the
    // config. Pseudo-random content, and not valid UTF-8, so any text round trip would mangle it.
    val largeUpload = ByteArray(64 * 1024) { i -> ((i * 31 + 7) and 0xFF).toByte() }

    "a file larger than Jetty's in-memory part limit uploads and downloads byte-for-byte" {
        val uploaded = upload("large.bin", "application/octet-stream", largeUpload)
        uploaded.statusCode() shouldBe 200
        val results = uploaded.body().jsonMap()?.get("results").toJsonMapOrEmpty()
        val id = results[SF.id] as? String
        id.shouldNotBeNull()
        (results[SF.size] as Number).toLong() shouldBe largeUpload.size.toLong()

        val downloaded = client.send(
            HttpRequest.newBuilder(URI.create(server.url("/kda/file/download?id=$id"))).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        downloaded.statusCode() shouldBe 200
        downloaded.headers().firstValue("Content-Type").get() shouldBe "application/octet-stream"
        downloaded.headers().firstValue("Content-Disposition").get() shouldContain "filename=\"large.bin\""
        // The whole point: every byte survives the wire, the parser, the disk and the response.
        downloaded.body().toList() shouldBe largeUpload.toList()
    }

    // The small case still has to work -- it takes Jetty's in-memory path, a different branch entirely.
    "a file smaller than the in-memory part limit also round trips" {
        val small = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A)
        val uploaded = upload("small.png", "image/png", small)
        uploaded.statusCode() shouldBe 200
        val results = uploaded.body().jsonMap()?.get("results").toJsonMapOrEmpty()
        results[SF.mimeType] shouldBe "image/png"

        val downloaded = client.send(
            HttpRequest.newBuilder(URI.create(server.url("/kda/file/download?id=${results[SF.id]}"))).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        downloaded.body().toList() shouldBe small.toList()
    }
})

private const val MULTIPART = "multipart/form-data"
