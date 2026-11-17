package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileBucketDef
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.http.request.BODY
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.StreamedContent
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.http.server.TestHttpServer
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A file sent a range at a time, and a part of one received as a raw body (issue #1836): in process, where every
 * header is read back, and over a real socket, where Jetty writes the bytes in pieces and the lengths must agree.
 */
class StreamedRangeEndpointTest : StringSpec({
    // Its own instance (issue #1075): RangeFixtureComponent is a fixture component no shared entry has.
    val cxt = Startup.mkTestBootCxt("streamedRanges", "streamedRangeEndpointTest", emptyMap(), listOf(RangeFixtureComponent()))
    val http = TestHttpClient(cxt.instanceConfig)

    // Every byte value, and more than the 64 KB a response writes at a time, so a whole answer is several writes.
    val video = ByteArray(300_000) { ((it * 31 + 7) and 0xFF).toByte() }
    val etag = "\"${video.sha256Hex()}\""
    val acme = FileStoreService.get(cxt).forOwner(cxt, "acme")
    acme.put(cxt, RangeFixtureComponent.bucket, "clients/acme/clip", ContentData(video, "video/mp4"))
    acme.put(cxt, RangeFixtureComponent.bucket, "clients/acme/report", ContentData("a report".toByteArray(), "application/pdf"))

    fun get(path: String, key: String, vararg headers: Pair<String, String>): RequestHandler {
        headers.forEach { (name, value) -> http.setHeader(name, value) }
        try {
            return http.sendGetRequest(path, mapOf("key" to key))
        } finally {
            headers.forEach { (name, _) -> http.curHeaders.remove(name.lowercase()) }
        }
    }

    fun RequestHandler.header(name: String): String? = rptResponseHeaders[name.lowercase()]?.singleOrNull()

    "the whole file is a 200 that says it takes ranges, with its tag and length" {
        val r = get(RangeFixtureComponent.stream, "clients/acme/clip")
        r.rptStatusCode shouldBe 200
        r.rptResponseMimeType shouldBe "video/mp4"
        r.rptResponseBytes!!.toList() shouldBe video.toList()
        r.header("Accept-Ranges") shouldBe "bytes"
        r.header("ETag") shouldBe etag
        r.header("Content-Length") shouldBe "300000"
        r.header("Cache-Control") shouldBe "private, no-cache"
        r.header("X-Content-Type-Options") shouldBe "nosniff"
        // A video is played, never run: no sandbox, which would only get in a player's way.
        r.header("Content-Security-Policy").shouldBeNull()
    }

    "a range is a 206 of those bytes alone, saying where they lie in the whole" {
        val r = get(RangeFixtureComponent.stream, "clients/acme/clip", "Range" to "bytes=1000-1999")
        r.rptStatusCode shouldBe 206
        r.rptResponseBytes!!.toList() shouldBe video.copyOfRange(1000, 2000).toList()
        r.header("Content-Range") shouldBe "bytes 1000-1999/300000"
        r.header("Content-Length") shouldBe "1000"

        val tail = get(RangeFixtureComponent.stream, "clients/acme/clip", "Range" to "bytes=-10")
        tail.rptResponseBytes!!.toList() shouldBe video.copyOfRange(299_990, 300_000).toList()
        tail.header("Content-Range") shouldBe "bytes 299990-299999/300000"
        // An open end, and an end past the file, both run to its last byte.
        get(RangeFixtureComponent.stream, "clients/acme/clip", "Range" to "bytes=299998-").rptResponseBytes!!.size shouldBe 2
        get(RangeFixtureComponent.stream, "clients/acme/clip", "Range" to "bytes=299998-999999").header("Content-Range") shouldBe
            "bytes 299998-299999/300000"
    }

    "a range past the end is a 416 saying how large the file is; one this does not read is the whole file" {
        val past = get(RangeFixtureComponent.stream, "clients/acme/clip", "Range" to "bytes=300000-")
        past.rptStatusCode shouldBe 416
        past.header("Content-Range") shouldBe "bytes */300000"
        past.rptResponseBytes!!.size shouldBe 0

        for (ignored in listOf("bytes=0-9,20-29", "lines=1-2", "bytes=9-1", "bytes=x-")) {
            val r = get(RangeFixtureComponent.stream, "clients/acme/clip", "Range" to ignored)
            r.rptStatusCode shouldBe 200
            r.rptResponseBytes!!.size shouldBe video.size
        }
    }

    "a copy that is current is a 304 with no body, and a range of other bytes is the whole file" {
        val current = get(RangeFixtureComponent.stream, "clients/acme/clip", "If-None-Match" to etag)
        current.rptStatusCode shouldBe 304
        current.rptResponseBytes!!.size shouldBe 0
        current.header("ETag") shouldBe etag

        val stale = get(RangeFixtureComponent.stream, "clients/acme/clip", "Range" to "bytes=0-9", "If-Range" to "\"older\"")
        stale.rptStatusCode shouldBe 200
        stale.rptResponseBytes!!.size shouldBe video.size
        get(RangeFixtureComponent.stream, "clients/acme/clip", "Range" to "bytes=0-9", "If-Range" to etag).rptStatusCode shouldBe 206
    }

    "a document is sandboxed and offered as a download under a safe name" {
        val r = get(RangeFixtureComponent.stream, "clients/acme/report")
        r.rptStatusCode shouldBe 200
        r.header("Content-Security-Policy") shouldBe "sandbox"
        r.header("Content-Disposition") shouldBe "attachment; filename=\"report.pdf\""
    }

    "content sent whole carries its hash as its tag, and a current copy is a 304" {
        val r = get(RangeFixtureComponent.whole, "clients/acme/report")
        r.rptStatusCode shouldBe 200
        val tag = r.header("ETag").shouldNotBeNull()
        tag shouldBe "\"${"a report".toByteArray().sha256Hex()}\""
        val again = get(RangeFixtureComponent.whole, "clients/acme/report", "If-None-Match" to "W/$tag, \"other\"")
        again.rptStatusCode shouldBe 304
        again.rptResponseBytes!!.size shouldBe 0
    }

    "a part arrives as the raw body, in its own type, and no larger than the endpoint takes" {
        val part = ByteArray(800) { (it % 251).toByte() }
        val stored = http.sendRawBodyRequest(RangeFixtureComponent.chunk, mapOf("key" to "clients/acme/part1"), part)
            .rptResponseData?.jsonMap().shouldNotBeNull()
        stored[EP.status].shouldBeNull()
        stored[EP.results].toJsonMapOrEmpty()["hash"] shouldBe part.sha256Hex()
        acme.get(cxt, RangeFixtureComponent.bucket, "clients/acme/part1").shouldNotBeNull().bytes.toList() shouldBe part.toList()

        val tooLarge = http.sendRawBodyRequest(RangeFixtureComponent.chunk, mapOf("key" to "clients/acme/part2"), ByteArray(1001))
            .rptResponseData?.jsonMap().shouldNotBeNull()
        tooLarge[EP.status] shouldBe EXC.payloadTooLarge
        // A form a page elsewhere could post is no part: only the octet-stream type is read.
        val asText = http.sendRawBodyRequest(RangeFixtureComponent.chunk, mapOf("key" to "clients/acme/part3"), part, contentType = "text/plain")
            .rptResponseData?.jsonMap().shouldNotBeNull()
        asText[EP.status] shouldBe EXC.badInput
        asText[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "application/octet-stream"
        acme.meta(cxt, RangeFixtureComponent.bucket, "clients/acme/part3").shouldBeNull()
    }

    "an endpoint taking a raw body is listed for a firewall, with its own limit" {
        val operator = TestUser.createOperator(cxt, "ops@ranges1836.test")
        val root = RequestService.get(cxt).apiContextRoot
        operator.getItem(OPS.largeBodiesPath)["endpoints"].toJsonListOfMaps()
            .single { it["path"] == "/$root${RangeFixtureComponent.chunk}" }["maxBytes"] shouldBe 1000L
    }

    "a call in this process is never sent a large range whole: it is refused, and a range of it is not" {
        val whole = http.sendGetRequest(RangeFixtureComponent.huge)
        whole.rptStatusCode shouldBe EXC.notSupported
        whole.rptResponseBytes.shouldBeNull()
        http.setHeader("Range", "bytes=0-99")
        val part = try {
            http.sendGetRequest(RangeFixtureComponent.huge)
        } finally {
            http.curHeaders.remove("range")
        }
        part.rptStatusCode shouldBe 206
        part.rptResponseBytes!!.size shouldBe 100
    }

    "a node refuses to start over a count of raw bodies that is not one" {
        shouldThrow<Exception> {
            Startup.mkTestBootCxt("rawBodyPermits1836", "rawBodyPermits1836", mapOf(BODY.rawBodyPermits.name to "0"))
        }.message.orEmpty() shouldContain BODY.rawBodyPermits.name
    }

    "over the wire, a request runs on a virtual thread, and a failure part-way ends that response alone" {
        TestHttpServer(cxt.instanceConfig).use { server ->
            val client = HttpClient.newHttpClient()
            fun request(path: String) = HttpRequest.newBuilder(URI.create(server.url("/kda$path"))).build()

            client.send(request(RangeFixtureComponent.thread), HttpResponse.BodyHandlers.ofString()).body() shouldBe "virtual"

            // The content fails after its status is out: the connection ends short of the length it promised.
            shouldThrow<IOException> { client.send(request(RangeFixtureComponent.broken), HttpResponse.BodyHandlers.ofByteArray()) }

            // A reader that goes away part-way, as a player does when it seeks.
            val reading = client.send(request(RangeFixtureComponent.huge), HttpResponse.BodyHandlers.ofInputStream())
            reading.statusCode() shouldBe 200
            reading.body().use { it.readNBytes(1024 * 1024).size shouldBe 1024 * 1024 }

            // Neither touched the next request.
            val after = client.send(request("${RangeFixtureComponent.stream}?key=clients/acme/clip"), HttpResponse.BodyHandlers.ofByteArray())
            after.statusCode() shouldBe 200
            after.body().toList() shouldBe video.toList()
        }
    }

    // Over a real socket: Jetty writes the body in pieces, and a length that disagrees with them fails the exchange.
    "over the wire, the whole file and a range arrive byte for byte, and a part goes up as raw bytes" {
        TestHttpServer(cxt.instanceConfig).use { server ->
            val client = HttpClient.newHttpClient()
            fun request(path: String, key: String) = HttpRequest.newBuilder(URI.create(server.url("/kda$path?key=$key")))

            val whole = client.send(request(RangeFixtureComponent.stream, "clients/acme/clip").build(), HttpResponse.BodyHandlers.ofByteArray())
            whole.statusCode() shouldBe 200
            whole.body().toList() shouldBe video.toList()
            whole.headers().firstValue("Content-Length").orElse(null) shouldBe "300000"

            val ranged = client.send(
                request(RangeFixtureComponent.stream, "clients/acme/clip").header("Range", "bytes=70000-200000").build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            ranged.statusCode() shouldBe 206
            ranged.body().toList() shouldBe video.copyOfRange(70_000, 200_001).toList()
            ranged.headers().firstValue("Content-Range").orElse(null) shouldBe "bytes 70000-200000/300000"

            val unsatisfiable = client.send(
                request(RangeFixtureComponent.stream, "clients/acme/clip").header("Range", "bytes=400000-").build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            unsatisfiable.statusCode() shouldBe 416

            val part = ByteArray(900) { (it * 3).toByte() }
            val up = client.send(
                request(RangeFixtureComponent.chunk, "clients/acme/wire").header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(part)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            up.statusCode() shouldBe 200
            acme.get(cxt, RangeFixtureComponent.bucket, "clients/acme/wire").shouldNotBeNull().bytes.toList() shouldBe part.toList()

            val tooLarge = client.send(
                request(RangeFixtureComponent.chunk, "clients/acme/wire2").header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(ByteArray(5000))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            tooLarge.statusCode() shouldBe EXC.payloadTooLarge
        }
    }
})

/** A component keeping files in one bucket, served a range at a time, whole, and taken a part at a time. */
class RangeFixtureComponent : ComponentDefinition {
    override val providerName: String = "rangeFixture"
    override val ownerRoot: String = namespace

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addFileBucket(FileBucketDef(bucket, "Files of the streamed range test."))
        collector.addModule(
            schemaModule(cxt, namespace) {
                type("Stored") { type = SCT.kObject; property("hash", "The stored part's hash.", required = true) }
                fileDownloadEndpoint(stream, "The file at a key, a range at a time.", inputFields = { field("key", "Which file.", required = true) }) { c, request ->
                    val key = request["key"].toOptStr().orEmpty()
                    val video = key.endsWith("clip")
                    FileStoreService.get(c).forOwner(c, "acme")
                        .streamed(c, bucket, key, inLine = video, saveAsFilename = "${key.substringAfterLast('/')}.pdf")
                        ?: throw KdrException("No such file.", code = EXC.notFound)
                }
                // A gigabyte of nothing, made as it is read: what a call in this process may not be sent whole.
                fileDownloadEndpoint(huge, "A very large file.") { _, _ ->
                    StreamedContent("video/mp4", hugeSize, "0".repeat(64)) { _, length -> ZeroStream(length) }
                }
                // Content that fails to read part-way, once its status is sent.
                fileDownloadEndpoint(broken, "A file that breaks while it is sent.") { _, _ ->
                    StreamedContent("video/mp4", brokenSize) { _, _ -> BreakingStream(brokenAfter) }
                }
                fileDownloadEndpoint(thread, "Which kind of thread runs the request.") { _, _ ->
                    ContentData(if (Thread.currentThread().isVirtual) "virtual" else "platform", "text/plain")
                }
                fileDownloadEndpoint(whole, "The file at a key, whole.", inputFields = { field("key", "Which file.", required = true) }) { c, request ->
                    FileStoreService.get(c).forOwner(c, "acme").get(c, bucket, request["key"].toOptStr().orEmpty())?.toContentData()
                        ?: throw KdrException("No such file.", code = EXC.notFound)
                }
                fileChunkEndpoint(chunk, "Stores a raw body at a key.", outputRef = "Stored", maxBodyBytes = 1000,
                    inputFields = { field("key", "Where to keep it.", required = true) }) { c, request ->
                    val part = request[EP.rawBody] as? ContentData ?: throw KdrException.mkInput("No body.")
                    val stored = FileStoreService.get(c).forOwner(c, "acme").put(c, bucket, request["key"].toOptStr().orEmpty(), part)
                    mapOf("hash" to stored.hash)
                }
            },
        )
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val namespace = "rangefixture"
        const val bucket = "range-fixture"
        const val stream = "/fixture/ranges/stream"
        const val whole = "/fixture/ranges/whole"
        const val chunk = "/fixture/ranges/chunk"
        const val huge = "/fixture/ranges/huge"
        const val broken = "/fixture/ranges/broken"
        const val thread = "/fixture/ranges/thread"
        const val hugeSize = 1024L * 1024 * 1024
        const val brokenSize = 600_000L
        const val brokenAfter = 200_000L
    }
}

/** [length] zero bytes, made as they are read. */
class ZeroStream(private var left: Long) : InputStream() {
    override fun read(): Int = if (left-- > 0) 0 else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = minOf(len.toLong(), left).toInt()
        b.fill(0, off, off + n)
        left -= n
        return n
    }
}

/** Bytes that fail to read after [after] of them, as content going missing under a response would. */
class BreakingStream(private val after: Long) : InputStream() {
    private var read = 0L

    override fun read(): Int = if (read++ < after) 1 else throw IOException("The content went away.")

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (read >= after) throw IOException("The content went away.")
        val n = minOf(len.toLong(), after - read).toInt()
        b.fill(1, off, off + n)
        read += n
        return n
    }
}
