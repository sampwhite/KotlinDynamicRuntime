package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
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

    "a put removes the old metadata, then writes the content, then the metadata; a delete removes the metadata first" {
        val api = FakeS3Api(bucket)
        val store = S3FileStore(api)

        store.put(cxt, bucket, "logo", "PNG".toByteArray(), meta("PNG".toByteArray()))
        api.calls shouldContainExactly listOf("delete $bucket/logo.meta.json", "put $bucket/logo", "put $bucket/logo.meta.json")

        api.calls.clear()
        store.delete(cxt, bucket, "logo") shouldBe true
        api.calls shouldContainExactly listOf("head $bucket/logo.meta.json", "delete $bucket/logo.meta.json", "delete $bucket/logo")
        api.objects.keys.shouldBeEmpty()
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
        S3FileStore.prefixProblem("") shouldBe null
        S3FileStore.prefixProblem("acme-prod-") shouldBe null
        S3FileStore.prefixProblem("Acme-").shouldNotBeNull()
        S3FileStore.prefixProblem("-acme").shouldNotBeNull()
        S3FileStore.prefixProblem("acme_prod").shouldNotBeNull()
        shouldThrow<KdrException> { S3FileStore(FakeS3Api(), "Acme-") }.message shouldContain "Not a bucket-name prefix"
    }
})
