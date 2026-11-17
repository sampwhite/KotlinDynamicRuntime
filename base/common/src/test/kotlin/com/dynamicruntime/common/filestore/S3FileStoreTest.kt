package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.parseDate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The S3 store's own business (issue #1129), over a stand-in for S3: which objects it writes, in what order, and what it asks for. */
class S3FileStoreTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("s3FileStore")
    val bucket = "web-assets"

    fun meta(bytes: ByteArray) = FileMeta("image/png", bytes.size.toLong(), "h${bytes.size}", "2026-10-12T08:30:00Z".parseDate())

    "a declared bucket is the S3 bucket of that name behind the deployment's prefix, and a file is two objects in it" {
        val api = FakeS3Api("acme-prod-web-assets")
        val store = S3FileStore(api, "acme-prod-")
        store.physicalBucket(bucket) shouldBe "acme-prod-web-assets"

        store.put(cxt, bucket, "clients/acme/logo", "PNG".toByteArray(), meta("PNG".toByteArray()))

        api.objects.keys.toList() shouldContainExactly
            listOf("acme-prod-web-assets/clients/acme/logo", "acme-prod-web-assets/clients/acme/logo.meta.json")
        // The content goes up under its own type, so S3 serves or inspects it as what it is.
        api.contentTypes["acme-prod-web-assets/clients/acme/logo"] shouldBe "image/png"
        api.contentTypes["acme-prod-web-assets/clients/acme/logo.meta.json"] shouldBe "application/json"
    }

    "a put writes the content, then the metadata; a delete removes the metadata first" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)

        store.put(cxt, bucket, "logo", "PNG".toByteArray(), meta("PNG".toByteArray()))
        api.calls shouldContainExactly listOf("put $bucket/logo", "put $bucket/logo.meta.json")

        api.calls.clear()
        store.delete(cxt, bucket, "logo") shouldBe true
        api.calls shouldContainExactly listOf("head $bucket/logo.meta.json", "delete $bucket/logo.meta.json", "delete $bucket/logo")
        api.objects.keys.shouldBeEmpty()
    }

    // Replacing a file never takes it away: there is no moment when the file that was there is not, and a
    // replace that fails before the new content is in leaves it exactly as it was.
    "a replace that fails leaves the file that was there, or none -- never one whose metadata is another's" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        store.put(cxt, bucket, "logo", "old".toByteArray(), meta("old".toByteArray()))

        // The content cannot be written: nothing has changed.
        api.failing = { it == "put $bucket/logo" }
        shouldThrow<KdrException> { store.put(cxt, bucket, "logo", "newer".toByteArray(), meta("newer".toByteArray())) }
        api.failing = null
        store.get(cxt, bucket, "logo").shouldNotBeNull().let {
            it.bytes.decodeToString() shouldBe "old"
            it.meta.size shouldBe 3
        }

        // The content is written and its metadata cannot be: the old metadata would describe content it does not
        // match, so it goes, and the file reads as a write that did not finish -- no file.
        api.failing = { it == "put $bucket/logo.meta.json" }
        shouldThrow<KdrException> { store.put(cxt, bucket, "logo", "newer".toByteArray(), meta("newer".toByteArray())) }
            .message shouldContain "put $bucket/logo.meta.json"
        api.failing = null
        store.get(cxt, bucket, "logo").shouldBeNull()
        store.meta(cxt, bucket, "logo").shouldBeNull()
        // And storing it again is all it takes.
        store.put(cxt, bucket, "logo", "newer".toByteArray(), meta("newer".toByteArray()))
        store.get(cxt, bucket, "logo").shouldNotBeNull().bytes.decodeToString() shouldBe "newer"
    }

    "a listing asks for no more than it was asked for, and again only when strays took the room" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        for (n in 1..9) store.put(cxt, bucket, "f/$n", "$n".toByteArray(), meta("$n".toByteArray()))
        api.calls.clear()

        // Three files are six objects, and a few to spare: one listing, and a read of each file's metadata.
        store.list(cxt, bucket, "f/", 3).map { it.key } shouldContainExactly listOf("f/1", "f/2", "f/3")
        api.listSizes shouldContainExactly listOf(3 * 2 + S3FileStore.spareKeys)
        api.calls.count { it.startsWith("get ") } shouldBe 3

        // Objects that are no file's take the room of ones that are, so the listing is asked again for more.
        for (n in 1..40) api.objects["$bucket/a/stray$n"] = "x".toByteArray()
        store.put(cxt, bucket, "z/last", "z".toByteArray(), meta("z".toByteArray()))
        api.listSizes.clear()
        store.list(cxt, bucket, "", 10).map { it.key } shouldContainExactly (1..9).map { "f/$it" } + "z/last"
        api.listSizes.size shouldBe 2
        // A file whose metadata is not readable JSON is passed over, as on a disk.
        api.objects["$bucket/a/Bad Name.meta.json"] = "{}".toByteArray()
        api.objects["$bucket/a/Bad Name"] = "x".toByteArray()
        store.list(cxt, bucket, "a/").shouldBeEmpty()
    }

    // 'a-01' and its neighbours sort between 'a' and 'a.meta.json', so a listing that stops among them has seen
    // the content of 'a' and not its metadata.
    "a listing that stops between a file's content and its metadata does not pass the file over" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        val names = (1..30).map { "a-%02d".format(it) }
        for (key in names + "a") store.put(cxt, bucket, key, key.toByteArray(), meta(key.toByteArray()))

        store.list(cxt, bucket, "", 1).map { it.key } shouldContainExactly listOf("a")
        store.list(cxt, bucket, "", 3).map { it.key } shouldContainExactly listOf("a", "a-01", "a-02")
        store.list(cxt, bucket, "", 100).map { it.key } shouldContainExactly listOf("a") + names
    }

    "reading the metadata fetches the metadata object and never the content" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        store.put(cxt, bucket, "a/one", "1".toByteArray(), meta("1".toByteArray()))
        store.put(cxt, bucket, "a/two", "22".toByteArray(), meta("22".toByteArray()))
        api.calls.clear()

        store.meta(cxt, bucket, "a/one").shouldNotBeNull().size shouldBe 1
        api.calls shouldContainExactly listOf("get $bucket/a/one.meta.json")

        api.calls.clear()
        store.list(cxt, bucket, "a/").map { it.meta.size } shouldContainExactly listOf(1L, 2L)
        api.calls shouldContainExactly listOf("list $bucket/a/", "get $bucket/a/one.meta.json", "get $bucket/a/two.meta.json")
    }

    "content with no metadata object beside it is not a file" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        store.put(cxt, bucket, "kept", "a".toByteArray(), meta("a".toByteArray()))
        store.put(cxt, bucket, "half", "b".toByteArray(), meta("b".toByteArray()))
        api.objects.remove("$bucket/half.meta.json")

        store.get(cxt, bucket, "half").shouldBeNull()
        store.meta(cxt, bucket, "half").shouldBeNull()
        store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf("kept")
        store.delete(cxt, bucket, "half") shouldBe false
        api.objects.keys.toList() shouldContainExactly listOf("$bucket/kept", "$bucket/kept.meta.json")
    }

    "a metadata object with no content beside it is not a file either" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        store.put(cxt, bucket, "kept", "a".toByteArray(), meta("a".toByteArray()))
        store.put(cxt, bucket, "gone", "b".toByteArray(), meta("b".toByteArray()))
        api.objects.remove("$bucket/gone")

        store.get(cxt, bucket, "gone").shouldBeNull()
        store.list(cxt, bucket, "").map { it.key } shouldContainExactly listOf("kept")
    }

    "a bucket that is not there is reported and never created" {
        val api = FakeS3Api()
        val store = S3FileStore(api, "kdr-")

        val findings = store.ensureBucket(cxt, bucket)

        findings shouldHaveSize 1
        findings.single() shouldContain "The S3 bucket 'kdr-web-assets', for the bucket 'web-assets', does not exist"
        api.calls shouldContainExactly listOf("headBucket kdr-web-assets")
        api.buckets.shouldBeEmpty()

        api.buckets.add("kdr-web-assets")
        store.ensureBucket(cxt, bucket).shouldBeEmpty()
    }

    "S3 being out of reach is a finding at startup, and a failure when a file is asked for" {
        val api = FakeS3Api(bucket).apply { unreachable = true }
        val store = S3FileStore(api)

        val findings = store.ensureBucket(cxt, bucket)
        findings.single() shouldContain "could not be checked: S3 could not be reached."

        shouldThrow<KdrException> { store.get(cxt, bucket, "logo") }.message shouldContain "could not be reached"
    }

    "a prefix that makes a bucket's name longer than S3 allows is a finding, before S3 is asked" {
        val api = FakeS3Api()
        val store = S3FileStore(api, "a".repeat(60))

        store.ensureBucket(cxt, bucket).single() shouldContain "longer than the 63 characters S3 allows"
        api.calls.shouldBeEmpty()
    }

    "a prefix that could not begin a bucket's name is refused" {
        FNAME.bucketPrefixProblem("") shouldBe null
        FNAME.bucketPrefixProblem("acme-prod-") shouldBe null
        FNAME.bucketPrefixProblem("Acme-").shouldNotBeNull()
        FNAME.bucketPrefixProblem("-acme").shouldNotBeNull()
        FNAME.bucketPrefixProblem("acme_prod").shouldNotBeNull()
        shouldThrow<KdrException> { S3FileStore(FakeS3Api(), "Acme-") }.message shouldContain "Not a bucket-name prefix"
    }

    // Issue #1835: S3 refuses a part smaller than its smallest unless it is the last -- when the upload completes, after
    // every part was taken. The service holds every plan to that rule before a store is asked (FPART).
    "S3 refuses a small part but the last only at the completion, which is why the plan is checked before it" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        val token = store.beginStaged(cxt, bucket, "staging/s1", "video/mp4")
        val small = store.putPart(cxt, bucket, "staging/s1", token, 1, "small".toByteArray())
        val last = store.putPart(cxt, bucket, "staging/s1", token, 2, "last".toByteArray())

        shouldThrow<KdrException> { store.completeStaged(cxt, bucket, "staging/s1", token, listOf(small, last), meta(ByteArray(9))) }
            .message shouldContain "EntityTooSmall"
        FileStoreService.partPlanProblem(listOf(small, last)).shouldNotBeNull() shouldContain "at least ${FPART.minPartSize}"
        // Its metadata was never written, so there is no file -- and the upload is still there to abort.
        store.meta(cxt, bucket, "staging/s1").shouldBeNull()
        store.abortStaged(cxt, bucket, "staging/s1", token) shouldBe true
    }

    "a range is fetched as that range alone" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        store.put(cxt, bucket, "files/v", ByteArray(1000), meta(ByteArray(1000)))
        api.calls.clear()

        store.openRange(cxt, bucket, "files/v", 10, 20).shouldNotBeNull().use { it.readAllBytes() }.size shouldBe 20
        // One request: the service has read the metadata already.
        api.calls shouldContainExactly listOf("getRange $bucket/files/v 10+20")
    }

    "a move copies the content, then the metadata, and only then removes the key moved from" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        store.put(cxt, bucket, "staging/s1", "abc".toByteArray(), meta("abc".toByteArray()))
        api.calls.clear()

        store.move(cxt, bucket, "staging/s1", "files/h") shouldBe true
        api.calls shouldContainExactly listOf(
            "head $bucket/staging/s1.meta.json",
            "copy $bucket/staging/s1 files/h",
            "copy $bucket/staging/s1.meta.json files/h.meta.json",
            "delete $bucket/staging/s1.meta.json",
            "delete $bucket/staging/s1",
        )
    }

    "a part is held to the size S3 holds of it, which S3 itself does not check when it completes" {
        val api = FakeS3Api(bucket, minPartSize = 1)
        val store = S3FileStore(api)
        val token = store.beginStaged(cxt, bucket, "staging/s1", "video/mp4")
        val part = store.putPart(cxt, bucket, "staging/s1", token, 1, "abc".toByteArray())

        val misstated = StagedPart(part.number, 300, part.tag)
        shouldThrow<KdrException> { store.completeStaged(cxt, bucket, "staging/s1", token, listOf(misstated), meta(ByteArray(300))) }
            .code shouldBe EXC.conflict
        store.meta(cxt, bucket, "staging/s1").shouldBeNull()
        store.completeStaged(cxt, bucket, "staging/s1", token, listOf(part), meta("abc".toByteArray()))
        store.meta(cxt, bucket, "staging/s1").shouldNotBeNull().size shouldBe 3
    }

    "metadata that cannot be written after an upload completes leaves no file, and says why" {
        val api = FakeS3Api(bucket, minPartSize = 1)
        val store = S3FileStore(api)
        val token = store.beginStaged(cxt, bucket, "staging/s1", "video/mp4")
        val part = store.putPart(cxt, bucket, "staging/s1", token, 1, "abc".toByteArray())
        api.failing = { it == "put $bucket/staging/s1.meta.json" }

        shouldThrow<KdrException> { store.completeStaged(cxt, bucket, "staging/s1", token, listOf(part), meta("abc".toByteArray())) }
        store.meta(cxt, bucket, "staging/s1").shouldBeNull()
        store.get(cxt, bucket, "staging/s1").shouldBeNull()
    }

    "a move whose metadata copy fails leaves the key moved from whole" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)
        store.put(cxt, bucket, "staging/s1", "abc".toByteArray(), meta("abc".toByteArray()))
        api.failing = { it == "copy $bucket/staging/s1.meta.json files/h.meta.json" }

        shouldThrow<KdrException> { store.move(cxt, bucket, "staging/s1", "files/h") }
        store.get(cxt, bucket, "staging/s1").shouldNotBeNull().bytes.decodeToString() shouldBe "abc"
        store.meta(cxt, bucket, "files/h").shouldBeNull()
    }

    "an upload is aged by its newest part, so one still taking parts is not aborted however long it has run" {
        val api = FakeS3Api(bucket, minPartSize = 1)
        val store = S3FileStore(api)
        val began = "2026-11-17T08:00:00Z".parseDate()
        api.clock = { began }
        val token = store.beginStaged(cxt, bucket, "staging/slow", "video/mp4")
        api.clock = { began + 2.hours }
        store.putPart(cxt, bucket, "staging/slow", token, 1, "x".toByteArray())

        val found = FileStoreCheck.find(cxt, store, listOf(bucket)).single()
        found.modifiedAt shouldBe began + 2.hours
        // Three hours after it began, but only an hour after its last part: left alone.
        FileStoreCheck.repair(cxt, store, listOf(found), began + 3.hours, 90.minutes).single().done shouldBe false
        FileStoreCheck.repair(cxt, store, listOf(found), began + 4.hours, 90.minutes).single().done shouldBe true
        store.stagedUploads(cxt, bucket).shouldBeEmpty()
    }
})
