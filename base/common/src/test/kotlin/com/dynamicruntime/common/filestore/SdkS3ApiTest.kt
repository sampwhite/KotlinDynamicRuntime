package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.TreeMap

/**
 * Just enough of S3's HTTP interface to answer the calls [SdkS3Api] makes: buckets and objects in a map, behind
 * a server on this machine's loopback address. It does not check signatures; what it is for is that the SDK's
 * own client, built the way the application builds it, sends requests S3 would understand and reads its answers.
 */
private class S3Stub(vararg existingBuckets: String) : AutoCloseable {
    val buckets = existingBuckets.toSet()
    val objects = TreeMap<String, ByteArray>()
    val contentTypes = HashMap<String, String>()
    var listRequests = 0

    /** Each put, by `bucket/key`: whether it carried the SDK's own checksum, a trailer after its body or a header. */
    val checksummedPuts = HashMap<String, Boolean>()

    /**
     * Objects whose reads answer with a CRC32 checksum header that does not match their content, as a service that
     * computes its checksums some other way would.
     */
    val otherwiseChecksummed = HashSet<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val parts = exchange.requestURI.rawPath.removePrefix("/").split('/', limit = 2)
        val bucket = parts[0]
        val key = parts.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) }.orEmpty()
        val body = readBody(exchange)
        when {
            bucket !in buckets -> error(exchange, 404, "NoSuchBucket")
            key.isEmpty() && exchange.requestMethod == "HEAD" -> respond(exchange, 200)
            key.isEmpty() && exchange.requestMethod == "GET" -> list(exchange, bucket)
            exchange.requestMethod == "PUT" -> {
                objects["$bucket/$key"] = body
                contentTypes["$bucket/$key"] = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                checksummedPuts["$bucket/$key"] = carriesChecksum(exchange)
                exchange.responseHeaders.add("ETag", "\"stub\"")
                respond(exchange, 200)
            }
            exchange.requestMethod == "DELETE" -> {
                objects.remove("$bucket/$key")
                respond(exchange, 204)
            }
            "$bucket/$key" !in objects -> error(exchange, 404, "NoSuchKey")
            exchange.requestMethod == "HEAD" -> respond(exchange, 200)
            else -> {
                if ("$bucket/$key" in otherwiseChecksummed) {
                    exchange.responseHeaders.add("x-amz-checksum-crc32", "AAAAAA==")
                }
                respond(exchange, 200, objects.getValue("$bucket/$key"))
            }
        }
    }

    /** A listing two keys to a page, so a caller that reads only the first page is found out. */
    private fun list(exchange: HttpExchange, bucket: String) {
        listRequests++
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
        val keys = objects.keys.filter { it.startsWith("$bucket/${query["prefix"].orEmpty()}") }.map { it.removePrefix("$bucket/") }
        val from = query["continuation-token"]?.toInt() ?: 0
        val page = keys.drop(from).take(2)
        val more = from + page.size < keys.size
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult><Name>$bucket</Name>")
            append("<KeyCount>${page.size}</KeyCount><IsTruncated>$more</IsTruncated>")
            if (more) append("<NextContinuationToken>${from + page.size}</NextContinuationToken>")
            page.forEach { append("<Contents><Key>$it</Key><Size>${objects.getValue("$bucket/$it").size}</Size></Contents>") }
            append("</ListBucketResult>")
        }
        exchange.responseHeaders.add("Content-Type", "application/xml")
        respond(exchange, 200, xml.toByteArray())
    }

    private fun error(exchange: HttpExchange, status: Int, code: String) {
        exchange.responseHeaders.add("Content-Type", "application/xml")
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>$code</Code><Message>$code</Message></Error>"
        respond(exchange, status, if (exchange.requestMethod == "HEAD") ByteArray(0) else xml.toByteArray())
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0)) {
        val noBody = exchange.requestMethod == "HEAD" || status == 204 || body.isEmpty()
        exchange.sendResponseHeaders(status, if (noBody) -1 else body.size.toLong())
        if (!noBody) exchange.responseBody.write(body)
    }

    /**
     * Whether the request carries a checksum the SDK added of its own accord: announced as a trailer after a chunked
     * body (what SDK 2.30 and later send by default), or computed into a header.
     */
    private fun carriesChecksum(exchange: HttpExchange): Boolean {
        val headers = exchange.requestHeaders
        return headers.getFirst("x-amz-trailer") != null || headers.getFirst("x-amz-sdk-checksum-algorithm") != null ||
            headers.getFirst("x-amz-content-sha256").orEmpty().endsWith("-TRAILER") ||
            headers.keys.any { it.lowercase().startsWith("x-amz-checksum-") }
    }

    /** The request's content, out of S3's chunked upload framing when the SDK used it. */
    private fun readBody(exchange: HttpExchange): ByteArray {
        val raw = exchange.requestBody.readAllBytes()
        val chunked = exchange.requestHeaders.getFirst("Content-Encoding").orEmpty().contains("aws-chunked") ||
            exchange.requestHeaders.getFirst("x-amz-content-sha256").orEmpty().startsWith("STREAMING-")
        if (!chunked) return raw
        val input = raw.inputStream()
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toInt(16)
            if (size == 0) break
            out.write(input.readNBytes(size))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.toString()
    }
}

/**
 * [SdkS3Api] itself (issue #1129): the one file that speaks to the AWS SDK, run for real against a stand-in for
 * S3 on this machine. Every other test of the S3 store runs over `FakeS3Api`, which proves the store and none of
 * this -- that the SDK's client can be built with the HTTP client the build leaves it, that each call is the
 * request S3 expects, and that "not there" comes back as an answer rather than an exception.
 */
class SdkS3ApiTest : StringSpec({
    val credentialProperties = listOf("aws.accessKeyId" to "stub-key", "aws.secretAccessKey" to "stub-secret")
    val previous = HashMap<String, String?>()
    val stub = S3Stub("stub-files")
    lateinit var api: SdkS3Api

    beforeSpec {
        // The SDK finds its credentials itself; these are the first place it looks. Nothing checks them.
        credentialProperties.forEach { (name, value) ->
            previous[name] = System.getProperty(name)
            System.setProperty(name, value)
        }
        api = SdkS3Api("us-east-1", stub.endpoint)
    }

    afterSpec {
        api.close()
        stub.close()
        previous.forEach { (name, value) -> if (value == null) System.clearProperty(name) else System.setProperty(name, value) }
    }

    "a bucket that exists is found, and one that does not is an answer rather than a failure" {
        api.headBucket("stub-files") shouldBe true
        api.headBucket("no-such-bucket") shouldBe false
    }

    "an object goes up with its type and comes back byte for byte" {
        val bytes = ByteArray(70_000) { (it * 31).toByte() }

        api.put("stub-files", "clients/acme/logo", bytes, "image/png")

        stub.contentTypes["stub-files/clients/acme/logo"] shouldBe "image/png"
        stub.objects.getValue("stub-files/clients/acme/logo").toList() shouldBe bytes.toList()
        api.get("stub-files", "clients/acme/logo").shouldNotBeNull().toList() shouldBe bytes.toList()
        api.head("stub-files", "clients/acme/logo") shouldBe true
    }

    // Issue #1271: an S3-compatible service need not take the SDK's default trailing checksum -- an older Linode
    // endpoint was reported refusing it, others keep the framing as the content -- so through an endpoint an upload
    // carries none. (Over this stub's plain HTTP the SDK still signs the body in chunks, SigV4's own streaming form;
    // over HTTPS it sends it whole.)
    "through an endpoint an upload carries no checksum of the SDK's own, and arrives intact" {
        val bytes = ByteArray(70_000) { (it * 7).toByte() }
        api.put("stub-files", "plain/one", bytes, "application/octet-stream")
        stub.checksummedPuts["stub-files/plain/one"] shouldBe false
        stub.objects.getValue("stub-files/plain/one").toList() shouldBe bytes.toList()
        api.get("stub-files", "plain/one").shouldNotBeNull().toList() shouldBe bytes.toList()
        // Nor is a read failed by a checksum the service computes some other way: what the store reads is checked
        // against the SHA-256 in its own metadata (`FileStoreService.get`), on every provider alike. The SDK
        // validates a read only when the call asks for its checksum, which none here does; were a later SDK to start
        // by default, this is what would say so, and `responseChecksumValidation` is the setting to reach for.
        stub.otherwiseChecksummed.add("stub-files/plain/one")
        api.get("stub-files", "plain/one").shouldNotBeNull().toList() shouldBe bytes.toList()
    }

    // Issue #1282: a store may sign in with a profile of its own, which is how two stores are two accounts. The
    // profile is read when a call first needs credentials, so the client is built, says what it is, and closes,
    // with nothing on this machine to read.
    "a client with a credentials profile names it, and is built and closed without signing in" {
        val profiled = SdkS3Api("us-east-1", stub.endpoint, profile = "kdr-test-profile")
        try {
            profiled.location shouldBe "S3 at ${stub.endpoint} (us-east-1), profile 'kdr-test-profile'"
            SdkS3Api("us-east-1").use { it.location shouldBe "S3 in us-east-1" }
        } finally {
            profiled.close()
        }
    }

    "an object that is not there is null, false, and no error to delete" {
        api.get("stub-files", "nothing/here").shouldBeNull()
        api.head("stub-files", "nothing/here") shouldBe false
        api.delete("stub-files", "nothing/here")

        api.put("stub-files", "going", "x".toByteArray(), "text/plain")
        api.delete("stub-files", "going")
        api.get("stub-files", "going").shouldBeNull()
    }

    "a listing is every key under the prefix, however many pages S3 gives it in" {
        val keys = (1..5).map { "list/item$it" }
        keys.forEach { api.put("stub-files", it, it.toByteArray(), "text/plain") }
        api.put("stub-files", "other/item", "x".toByteArray(), "text/plain")
        stub.listRequests = 0

        api.list("stub-files", "list/") shouldContainExactly keys

        stub.listRequests shouldBeGreaterThan 1
    }

    "a missing bucket is not a missing file, and a failure is ours to report, not the SDK's" {
        // S3 says "not found" for both. Read as an absent file, a bucket that was never made would have every
        // file in it quietly not exist.
        val noBucket = shouldThrow<KdrException> { api.get("no-such-bucket", "a") }
        noBucket.message shouldContain "S3 could not read 'a' in the bucket 'no-such-bucket'"
        shouldThrow<KdrException> { api.put("no-such-bucket", "a", "x".toByteArray(), "text/plain") }
        shouldThrow<KdrException> { api.list("no-such-bucket", "") }

        // Nothing listening at all.
        val nowhere = SdkS3Api("us-east-1", "http://127.0.0.1:1")
        try {
            val failed = shouldThrow<KdrException> { nowhere.headBucket("stub-files") }
            failed.message shouldContain "S3 could not check the bucket 'stub-files'"
            failed.code shouldBe EXC.notAvailable
            failed.source shouldBe SRC.network
        } finally {
            nowhere.close()
        }
    }
})
