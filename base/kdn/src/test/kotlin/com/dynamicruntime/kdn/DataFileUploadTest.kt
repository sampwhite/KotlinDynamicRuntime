package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DFSTAT
import com.dynamicruntime.common.datafile.DataFileService
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.CustomerDef
import com.dynamicruntime.common.gedra.CustomerStore
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.PLAN
import com.dynamicruntime.common.gedra.PlanLimits
import com.dynamicruntime.common.gedra.QUOTA
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.test.ClockOp
import com.dynamicruntime.common.test.TCLK
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A file uploaded for a form's data (issue #1837): begun, sent in chunks that resume where they stopped, finished by
 * the last of them (#1840) into a record whose bytes are checked and named by their hash, and read a range at a time --
 * by its owner, and by an administrator of its client, and by nobody else. A small file is uploaded in one request.
 */
class DataFileUploadTest : StringSpec({
    val cxt = TestInstances.default("files1837")
    val operator = TestUser.createFullAdmin(cxt, "operator@files1837.test")

    /** Creates [clientId] on the shared instance, a client of this test's own. */
    fun createClient(clientId: String, vararg more: Pair<String, Any?>) = operator.postData(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to clientId, CLD.name to "Client $clientId", CLD.usageType to ClientUsageType.dev.name,
            CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            CLD.starterWorkflows to false,
        ) + more,
    )
    val client = "files1837"
    createClient(client)
    val owner = TestUser.create(cxt, "pat@files1837.test", userClient = client)
    val other = TestUser.create(cxt, "sam@files1837.test", userClient = client)
    val admin = TestUser.create(cxt, "chief@files1837.test", level = ROLE.admin, userClient = client)

    fun ascii(s: String): ByteArray = s.encodeToByteArray()
    fun u32(n: Int): ByteArray = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, b -> acc + b }
        return u32(8 + body.size) + ascii(type) + body
    }

    /** An MP4 of [size] bytes whose one video track is [codec]: its index first, then its data. */
    fun mp4(codec: String, size: Int): ByteArray {
        val ftyp = box("ftyp", ascii("isom"), ByteArray(4), ascii("isom"), ascii("avc1"))
        val hdlr = box("hdlr", ByteArray(4), ByteArray(4), ascii("vide"), ByteArray(12), ascii("Video\u0000"))
        val stsd = box("stsd", ByteArray(4), u32(1), box(codec, ByteArray(78)))
        val moov = box("moov", box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd)))))
        val head = ftyp + moov
        return head + u32(size - head.size) + ascii("mdat") + ByteArray(size - head.size - 8) { ((it * 31 + 7) and 0xFF).toByte() }
    }

    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(200) { it.toByte() }
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200)

    fun envelope(handler: com.dynamicruntime.common.http.request.RequestHandler): Map<String, Any?> =
        handler.rptResponseData?.jsonMap().shouldNotBeNull()

    fun begin(user: TestUser, name: String, mime: String, size: Int): Map<String, Any?> =
        user.postItem(DFILE.beginPath, mapOf(DFILE.name to name, DFILE.mimeType to mime, DFILE.declaredSize to size))

    fun sendChunk(user: TestUser, fileId: String, offset: Long, bytes: ByteArray, hash: String = bytes.sha256Hex()) = envelope(
        user.client.sendRawBodyRequest(DFILE.chunkPath, mapOf(DFILE.fileId to fileId, DFILE.offset to offset, DFILE.chunkHash to hash), bytes),
    )

    /** The file's record in the [envelope] of the chunk that finished its upload. */
    fun recordOf(envelope: Map<String, Any?>): Map<String, Any?> =
        envelope[EP.results].toJsonMapOrEmpty()[DFILE.file].toJsonMapOrEmpty()

    /** Uploads [bytes] chunk by chunk, and answers the envelope of the last, which finishes the upload. */
    fun upload(user: TestUser, name: String, mime: String, bytes: ByteArray): Map<String, Any?> {
        val begun = begin(user, name, mime, bytes.size)
        val fileId = begun[DFILE.fileId].toOptStr().shouldNotBeNull()
        val chunk = begun[DFILE.chunkSize].toOptLong()!!.toInt()
        var at = 0
        while (true) {
            val end = minOf(at + chunk, bytes.size)
            val answer = sendChunk(user, fileId, at.toLong(), bytes.copyOfRange(at, end))
            if (end == bytes.size) return answer
            answer[EP.status] shouldBe null
            at = end
        }
    }

    /** Uploads [bytes] in one request, answering its envelope. */
    fun uploadWhole(user: TestUser, name: String, mime: String, bytes: ByteArray): Map<String, Any?> = envelope(
        user.client.sendRawBodyRequest(DFILE.uploadPath, mapOf(DFILE.name to name, DFILE.mimeType to mime), bytes),
    )

    "a video in two chunks, resumed between them, is ready under its hash and read back a range at a time" {
        val video = mp4("avc1", DFILE.chunkBytes + 300_000)
        val begun = begin(owner, "visit.mp4", "video/mp4", video.size)
        val fileId = begun[DFILE.fileId].toOptStr().shouldNotBeNull()
        begun[DFILE.status] shouldBe DFSTAT.uploading
        begun[DFILE.chunkSize] shouldBe DFILE.chunkBytes.toLong()
        fileId.startsWith("gd.df.$client.") shouldBe true

        sendChunk(owner, fileId, 0, video.copyOfRange(0, DFILE.chunkBytes))[EP.results].toJsonMapOrEmpty()[DFILE.nextOffset] shouldBe
            DFILE.chunkBytes.toLong()
        // The connection is lost here: the upload says where to resume.
        owner.getItem(DFILE.statusPath, mapOf(DFILE.fileId to fileId))[DFILE.nextOffset] shouldBe DFILE.chunkBytes.toLong()
        val last = video.copyOfRange(DFILE.chunkBytes, video.size)
        // The last chunk finishes the upload, and its answer is the file's record.
        val finished = sendChunk(owner, fileId, DFILE.chunkBytes.toLong(), last)[EP.results].toJsonMapOrEmpty()
        finished[DFILE.status] shouldBe DFSTAT.ready
        finished[DFILE.nextOffset] shouldBe video.size.toLong()
        val ready = finished[DFILE.file].toJsonMapOrEmpty()
        ready[DFILE.status] shouldBe DFSTAT.ready
        ready[DFILE.hash] shouldBe video.sha256Hex()
        ready[DFILE.size] shouldBe video.size.toLong()
        ready[DFILE.kind] shouldBe "mp4"
        ready[DFILE.name] shouldBe "visit.mp4"
        owner.getItem(DFILE.statusPath, mapOf(DFILE.fileId to fileId))[DFILE.status] shouldBe DFSTAT.ready
        // Its answer was lost and the last chunk sent again: answered with the record, as before.
        recordOf(sendChunk(owner, fileId, DFILE.chunkBytes.toLong(), last))[DFILE.hash] shouldBe video.sha256Hex()

        owner.client.setHeader("Range", "bytes=100-199")
        val range = try {
            owner.client.sendGetRequest(DFILE.contentPath, mapOf(DFILE.fileId to fileId))
        } finally {
            owner.client.curHeaders.remove("range")
        }
        range.rptStatusCode shouldBe 206
        range.rptResponseBytes!!.toList() shouldBe video.copyOfRange(100, 200).toList()
        range.rptResponseHeaders["etag"]?.single() shouldBe "\"${video.sha256Hex()}\""
    }

    "a chunk out of order is a 409 saying where the upload is; a bad hash is refused; one sent again is answered again" {
        val video = mp4("avc1", DFILE.chunkBytes + 1000)
        val fileId = begin(owner, "a.mp4", "video/mp4", video.size)[DFILE.fileId].toOptStr()!!
        val first = video.copyOfRange(0, DFILE.chunkBytes)

        val ahead = sendChunk(owner, fileId, DFILE.chunkBytes.toLong(), video.copyOfRange(DFILE.chunkBytes, video.size))
        ahead[EP.status] shouldBe EXC.conflict.toLong()
        ahead[EP.extraData].toJsonMapOrEmpty()[DFILE.nextOffset] shouldBe 0L

        sendChunk(owner, fileId, 0, first, hash = "0".repeat(64))[EP.status] shouldBe EXC.badInput.toLong()
        sendChunk(owner, fileId, 0, first.copyOfRange(0, 100))[EP.status] shouldBe EXC.badInput.toLong()

        sendChunk(owner, fileId, 0, first)[EP.status] shouldBe null
        // The answer was lost and the chunk sent again: taken once, answered twice.
        sendChunk(owner, fileId, 0, first)[EP.results].toJsonMapOrEmpty()[DFILE.nextOffset] shouldBe DFILE.chunkBytes.toLong()
        // Not all of it taken, it is not finished, and says where to resume.
        val status = owner.getItem(DFILE.statusPath, mapOf(DFILE.fileId to fileId))
        status[DFILE.status] shouldBe DFSTAT.uploading
        status[DFILE.nextOffset] shouldBe DFILE.chunkBytes.toLong()
    }

    "a last chunk sent while another request finishes the upload waits, then finishes it; a finished file takes no other chunk" {
        val video = mp4("avc1", DFILE.chunkBytes + 20_000)
        val fileId = begin(owner, "busy.mp4", "video/mp4", video.size)[DFILE.fileId].toOptStr()!!
        val first = video.copyOfRange(0, DFILE.chunkBytes)
        val last = video.copyOfRange(DFILE.chunkBytes, video.size)
        sendChunk(owner, fileId, 0, first)[EP.status] shouldBe null

        // Another request holds the finish, as the first does while it puts a large file together.
        DataFileService.claimFinish(cxt, fileId)
        val busy = sendChunk(owner, fileId, DFILE.chunkBytes.toLong(), last)
        busy[EP.status] shouldBe EXC.tooManyRequests.toLong()
        busy[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "is being finished"
        // Every byte is taken, and the upload is not finished: resuming sends the last chunk again.
        val status = owner.getItem(DFILE.statusPath, mapOf(DFILE.fileId to fileId))
        status[DFILE.status] shouldBe DFSTAT.uploading
        status[DFILE.nextOffset] shouldBe video.size.toLong()

        DataFileService.releaseFinish(cxt, fileId)
        recordOf(sendChunk(owner, fileId, DFILE.chunkBytes.toLong(), last))[DFILE.status] shouldBe DFSTAT.ready
        // Only the chunk whose answer was lost is answered with the record; any other is not one the file took.
        sendChunk(owner, fileId, 0, first)[EP.status] shouldBe EXC.conflict.toLong()
    }

    "a file whose bytes are not the kind it was sent as is refused by the chunk that finishes it, and is gone" {
        val sent = begin(owner, "photo.png", "image/png", jpeg.size)[DFILE.fileId].toOptStr()!!
        val refused = sendChunk(owner, sent, 0, jpeg)
        refused[EP.status] shouldBe EXC.badInput.toLong()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "its content is a JPEG file"
        envelope(owner.client.sendGetRequest(DFILE.infoPath, mapOf(DFILE.fileId to sent)))[EP.status] shouldBe EXC.notFound.toLong()
    }

    "an HEVC video is refused with a message asking for H.264, and a type no file may be is refused at the begin" {
        val refused = upload(owner, "phone.mp4", "video/mp4", mp4("hvc1", 50_000))
        refused[EP.status] shouldBe EXC.badInput.toLong()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "H.264"

        val html = envelope(
            owner.client.sendEditRequest(
                DFILE.beginPath, null, mapOf(DFILE.mimeType to "text/html", DFILE.declaredSize to 10), HttpMethod.POST,
            ),
        )
        html[EP.status] shouldBe EXC.badInput.toLong()
        val huge = envelope(
            owner.client.sendEditRequest(
                DFILE.beginPath, null, mapOf(DFILE.mimeType to "image/png", DFILE.declaredSize to 30L * 1024 * 1024), HttpMethod.POST,
            ),
        )
        huge[EP.status] shouldBe EXC.payloadTooLarge.toLong()
    }

    "two uploads of one file are two records sharing one copy of its bytes" {
        val a = recordOf(upload(owner, "a.png", "image/png", png))
        val b = recordOf(upload(other, "b.png", "image/png", png))
        (a[DFILE.fileId] == b[DFILE.fileId]) shouldBe false
        a[DFILE.hash] shouldBe b[DFILE.hash]
        val stored = FileStoreService.get(cxt).forOwner(cxt, client).list(cxt, DFILE.bucket, "clients/$client/files/")
        stored.count { it.key.endsWith(png.sha256Hex()) } shouldBe 1
    }

    "a file is read by its owner and an administrator of its client, sent only by its owner, and removed" {
        val video = mp4("avc1", 40_000)
        val fileId = recordOf(upload(owner, "mine.mp4", "video/mp4", video))[DFILE.fileId].toOptStr()!!

        envelope(other.client.sendGetRequest(DFILE.infoPath, mapOf(DFILE.fileId to fileId)))[EP.status] shouldBe EXC.notFound.toLong()
        envelope(other.client.sendGetRequest(DFILE.contentPath, mapOf(DFILE.fileId to fileId)))[EP.status] shouldBe EXC.notFound.toLong()
        admin.getItem(DFILE.infoPath, mapOf(DFILE.fileId to fileId))[DFILE.ownerId] shouldBe owner.userId

        val pending = begin(owner, "more.mp4", "video/mp4", video.size)[DFILE.fileId].toOptStr()!!
        sendChunk(admin, pending, 0, video)[EP.status] shouldBe EXC.notAuthorized.toLong()

        // Removing one still uploading drops what the store held of it.
        owner.deleteData(DFILE.deletePath, mapOf(DFILE.fileId to pending))[DFILE.removed] shouldBe true
        FileStoreService.get(cxt).store.stagedUploads(cxt, DFILE.bucket).filter { it.key.endsWith(pending.substringAfterLast('.')) }.shouldBeEmpty()
        admin.deleteData(DFILE.deletePath, mapOf(DFILE.fileId to fileId))[DFILE.removed] shouldBe true
        envelope(owner.client.sendGetRequest(DFILE.infoPath, mapOf(DFILE.fileId to fileId)))[EP.status] shouldBe EXC.notFound.toLong()
    }

    "an aborted upload is gone, record and staged chunks; a file declared empty is refused at the begin" {
        // Two chunks, the first sent: the last would have finished it.
        val video = mp4("avc1", DFILE.chunkBytes + 30_000)
        val fileId = begin(owner, "dropped.mp4", "video/mp4", video.size)[DFILE.fileId].toOptStr()!!
        sendChunk(owner, fileId, 0, video.copyOfRange(0, DFILE.chunkBytes))[EP.status] shouldBe null
        owner.postData(DFILE.abortPath, mapOf(DFILE.fileId to fileId))[DFILE.removed] shouldBe true
        envelope(owner.client.sendGetRequest(DFILE.infoPath, mapOf(DFILE.fileId to fileId)))[EP.status] shouldBe EXC.notFound.toLong()
        FileStoreService.get(cxt).store.stagedUploads(cxt, DFILE.bucket).filter { it.key.endsWith(fileId.substringAfterLast('.')) }.shouldBeEmpty()

        val empty = envelope(
            owner.client.sendEditRequest(DFILE.beginPath, null, mapOf(DFILE.mimeType to "image/png", DFILE.declaredSize to 0), HttpMethod.POST),
        )
        empty[EP.status] shouldBe EXC.badInput.toLong()
    }

    "an MP4 whose boxes are no video we can read is refused like any other, and leaves nothing behind" {
        // An `ftyp` box and data, with no index: what a recording cut off before it was written looks like.
        val truncated = box("ftyp", ascii("isom"), ByteArray(4), ascii("isom")) + box("mdat", ByteArray(20_000))
        val begun = begin(owner, "cut.mp4", "video/mp4", truncated.size)
        val fileId = begun[DFILE.fileId].toOptStr()!!
        val refused = sendChunk(owner, fileId, 0, truncated)
        refused[EP.status] shouldBe EXC.badInput.toLong()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "no index"
        envelope(owner.client.sendGetRequest(DFILE.infoPath, mapOf(DFILE.fileId to fileId)))[EP.status] shouldBe EXC.notFound.toLong()
        FileStoreService.get(cxt).store.objects(cxt, DFILE.bucket, "clients/$client/staging/").none {
            it.name.contains(fileId.substringAfterLast('.'))
        } shouldBe true
    }

    "a WebM and a PDF are told by their bytes, the PDF offered as a download" {
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x82.toByte(), 0x84.toByte()) +
            ascii("webm") + ByteArray(3000)
        recordOf(upload(owner, "clip.webm", "video/webm", webm))[DFILE.kind] shouldBe "webm"
        val pdf = ascii("%PDF-1.7\n") + ByteArray(3000)
        val fileId = recordOf(upload(owner, "report.pdf", "application/pdf", pdf))[DFILE.fileId].toOptStr()!!
        val content = owner.client.sendGetRequest(DFILE.contentPath, mapOf(DFILE.fileId to fileId))
        content.rptStatusCode shouldBe 200
        content.rptResponseHeaders["content-disposition"]?.single() shouldBe "attachment; filename=\"report.pdf\""
    }

    "a file uploaded in a sandbox keeps its bytes in the parent's folder, as its named images do" {
        val parent = "files1837s"
        createClient(parent, CLD.sandbox to true)
        val chief = TestUser.create(cxt, "chief@files1837s.test", level = ROLE.admin, userClient = parent)
        chief.postData(AEP.openSandbox, emptyMap())
        val made = recordOf(upload(chief, "in-sandbox.png", "image/png", png))
        made[DFILE.fileId].toOptStr().shouldNotBeNull() shouldContain ":sandbox"
        FileStoreService.get(cxt).forOwner(cxt, parent).meta(cxt, DFILE.bucket, "clients/$parent/files/${png.sha256Hex()}").shouldNotBeNull()
        chief.getItem(DFILE.infoPath, mapOf(DFILE.fileId to made[DFILE.fileId]))[DFILE.status] shouldBe DFSTAT.ready
    }

    "a file of one chunk or less is uploaded in one request; one refused there leaves nothing behind" {
        val whole = uploadWhole(owner, "one.png", "image/png", png)
        whole[EP.status] shouldBe null
        val record = whole[EP.results].toJsonMapOrEmpty()
        record[DFILE.status] shouldBe DFSTAT.ready
        record[DFILE.hash] shouldBe png.sha256Hex()
        record[DFILE.name] shouldBe "one.png"
        owner.getItem(DFILE.infoPath, mapOf(DFILE.fileId to record[DFILE.fileId]))[DFILE.kind] shouldBe "png"

        val before = GedraDataService.get(cxt).liveGedraIds(cxt, GedraDataType.dataFile, client).size
        val wrong = uploadWhole(owner, "photo.png", "image/png", jpeg)
        wrong[EP.status] shouldBe EXC.badInput.toLong()
        wrong[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "its content is a JPEG file"
        uploadWhole(owner, "page.html", "text/html", ascii("<html></html>"))[EP.status] shouldBe EXC.badInput.toLong()
        uploadWhole(owner, "big.mp4", "video/mp4", mp4("avc1", DFILE.chunkBytes + 1))[EP.status] shouldBe EXC.payloadTooLarge.toLong()
        GedraDataService.get(cxt).liveGedraIds(cxt, GedraDataType.dataFile, client).size shouldBe before
    }

    "a data file holds no entries, so nothing that writes entries makes one" {
        val gedras = GedraDataService.get(cxt)
        val bound = cxt.mkSubContext("files", client).also { it.userId = owner.userId }
        shouldThrow<KdrException> { gedras.createGedra(bound, GedraDataType.dataFile, emptyList()) }.message.orEmpty() shouldContain
            "holds no entries"
    }

    "a client on the free plan has room for the megabytes its plan allows, and no more" {
        val free = "freefiles"
        createClient(free, CLD.newCustomer to mapOf(CUST.name to "Free files"))
        CustomerStore.put(cxt, CustomerDef(free, "Free files", rootClientId = free, plan = PLAN.free))
        val member = TestUser.create(cxt, "pat@freefiles.test", userClient = free)
        val limit = PLAN.freeQuotas.fileMegabytes!!

        val over = envelope(
            member.client.sendEditRequest(
                DFILE.beginPath, null,
                mapOf(DFILE.mimeType to "video/mp4", DFILE.declaredSize to (limit + 1) * 1024L * 1024L), HttpMethod.POST,
            ),
        )
        over[EP.status] shouldBe EXC.badInput.toLong()
        over[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "plan"

        upload(member, "small.png", "image/png", png)[EP.status] shouldBe null
        PlanLimits.usageOf(cxt, free).shouldNotBeNull().measures.single { it.measure == QUOTA.fileMegabytes }.used shouldBe 1
    }
})

/**
 * An upload past its time (issue #1837): no longer taken, and holding no room in its client's plan. Its own instance,
 * since it travels the instance clock, which is instance-wide.
 */
class DataFileExpiryTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("filesExpiry", "dataFileExpiryTest")
    val operator = TestUser.createFullAdmin(cxt, "operator@expiry1837.test")
    val client = "expiry1837"
    operator.postData(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to client, CLD.name to "Expiry", CLD.usageType to ClientUsageType.dev.name,
            CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            CLD.starterWorkflows to false,
        ),
    )
    val owner = TestUser.create(cxt, "pat@expiry1837.test", userClient = client)
    val clock = TestHttpClient(cxt.instanceConfig).alwaysWithTestAccess()

    "an upload past its hours takes no more chunks, and no longer counts against its client's plan" {
        val bytes = ByteArray(5000) { 1 }
        val begun = owner.postItem(DFILE.beginPath, mapOf(DFILE.mimeType to "image/png", DFILE.declaredSize to bytes.size))
        val fileId = begun[DFILE.fileId].toOptStr()!!
        DataFileService.usedBytes(cxt, client) shouldBe 5000L
        clock.sendJsonPostRequest(TCLK.path, mapOf(TCLK.op to ClockOp.advance.name, TCLK.deltaMs to (DFILE.uploadHours + 1) * 3_600_000L))
        try {
            val late = owner.client.sendRawBodyRequest(
                DFILE.chunkPath, mapOf(DFILE.fileId to fileId, DFILE.offset to 0, DFILE.chunkHash to bytes.sha256Hex()), bytes,
            ).rptResponseData?.jsonMap().shouldNotBeNull()
            late[EP.status] shouldBe EXC.conflict.toLong()
            DataFileService.usedBytes(cxt, client) shouldBe 0L
        } finally {
            clock.sendJsonPostRequest(TCLK.path, mapOf(TCLK.op to ClockOp.reset.name))
        }
    }
})
