package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.AppPaths
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.truncateToMs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

/**
 * The file store service (issue #1129): which store a node runs on, the boot check on its declared buckets, and
 * what the service adds over a provider -- the names it checks, the metadata it writes, the content it verifies.
 * Built by hand rather than booted, so each case states its whole configuration.
 */
class FileStoreServiceTest : StringSpec({
    val tempDirs = mutableListOf<File>()
    var instances = 0
    val bucket = "service-files"

    fun tempDir(): File = Files.createTempDirectory("kdrFileStoreService").toFile().also { tempDirs.add(it) }

    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    /** A context over a fresh instance config holding [settings] and a collector declaring [buckets]. */
    fun cxtWith(env: String, vararg settings: Pair<String, Any?>, buckets: List<String> = listOf(bucket)): KdrCxt {
        val config = KdrInstanceConfig("fileStoreService${instances++}", env, ENV.liveSource)
        settings.forEach { (key, value) -> config.put(key, value) }
        val collector = SchemaCollector()
        buckets.forEach { collector.addFileBucket(FileBucketDef(it, "Files of a test.")) }
        config.put(SchemaCollector.key, collector)
        return KdrCxt("fileStoreService", config)
    }

    /**
     * A context whose store is named as the one in memory. A case that goes on to store a file names its store
     * rather than taking the default: were the default ever wrong, it would be a directory in the real workspace,
     * and the case that says what the default is only creates the service and never writes through it.
     */
    fun memoryCxt(): KdrCxt = cxtWith(ENV.unit, FSENV.type.name to FST.memory)

    fun created(cxt: KdrCxt): FileStoreService = FileStoreService().apply { onCreate(cxt) }

    fun started(cxt: KdrCxt): FileStoreService = created(cxt).apply { checkInit(cxt) }

    fun fileStoreCheck(cxt: KdrCxt) = BootCheckRegistry.get(cxt).results().singleOrNull { it.name == BCHK.fileStore }

    "a node running in memory keeps its files in memory, and so does every unit test" {
        created(cxtWith(ENV.local, ACFG.inMemoryOnly to true)).providerType shouldBe FST.memory
        // Even one that turns in-memory mode off, as a test sharing a database between instances does.
        created(cxtWith(ENV.unit, ACFG.inMemoryOnly to false)).providerType shouldBe FST.memory
        created(cxtWith(ENV.unit)).providerType shouldBe FST.memory
    }

    "any other node keeps them in a directory: 'fileStore' in the workspace unless one is named" {
        // Created only: starting it would make the bucket's directory, and the default is in the real workspace --
        // which creating the service leaves exactly as it found it.
        val workspaceDir = AppPaths.resolve("fileStore")
        val wasThere = workspaceDir.exists()
        val byDefault = created(cxtWith(ENV.local))
        byDefault.providerType shouldBe FST.local
        byDefault.store.location shouldBe workspaceDir.path
        workspaceDir.exists() shouldBe wasThere

        // A unit test is never given that default: one that runs on a directory says which.
        shouldThrow<KdrException> { created(cxtWith(ENV.unit, FSENV.type.name to FST.local)) }.let {
            it.message shouldContain "has no directory by default in the unit test environment"
            it.source shouldBe SRC.config
        }

        created(cxtWith(ENV.local, FSENV.dir.name to "data/files")).store.location shouldBe AppPaths.resolve("data/files").path
        val absolute = tempDir()
        created(cxtWith(ENV.local, FSENV.dir.name to absolute.absolutePath)).store.location shouldBe absolute.absolutePath
    }

    "the store can be named outright, and a name that is none of them refuses the start" {
        val dir = tempDir()
        created(cxtWith(ENV.unit, FSENV.type.name to FST.local, FSENV.dir.name to dir.absolutePath)).providerType shouldBe FST.local
        created(cxtWith(ENV.local, FSENV.type.name to FST.memory)).providerType shouldBe FST.memory

        val refused = shouldThrow<KdrException> { created(cxtWith(ENV.local, FSENV.type.name to "gcs")) }
        refused.message shouldContain "KDR_FILE_STORE_TYPE is 'gcs'; it must be one of memory, local, s3."
        refused.source shouldBe SRC.config
    }

    "the S3 store runs over a stand-in a test hands in, and is refused in a unit test that hands in none" {
        val api = FakeS3Api("kdr-$bucket")
        val service = created(cxtWith(ENV.unit, FSENV.type.name to FST.s3, FSENV.s3BucketPrefix.name to "kdr-", FileStoreService.s3ApiConfigKey to api))
        service.store.shouldBeInstanceOf<S3FileStore>().physicalBucket(bucket) shouldBe "kdr-$bucket"

        val refused = shouldThrow<KdrException> { created(cxtWith(ENV.unit, FSENV.type.name to FST.s3, FSENV.s3Region.name to "us-east-1")) }
        refused.message shouldContain "not available in the unit test environment"
    }

    "the S3 store needs a region" {
        val refused = shouldThrow<KdrException> { created(cxtWith(ENV.local, FSENV.type.name to FST.s3)) }
        refused.message shouldContain "KDR_FILE_STORE_TYPE is 's3', which needs KDR_S3_REGION."
    }

    "a bucket prefix that could begin no bucket's name refuses the start, as the other settings do" {
        val refused = shouldThrow<KdrException> {
            created(cxtWith(ENV.unit, FSENV.type.name to FST.s3, FSENV.s3BucketPrefix.name to "Acme_", FileStoreService.s3ApiConfigKey to FakeS3Api()))
        }
        refused.message shouldContain "Refusing to start: KDR_S3_BUCKET_PREFIX is 'Acme_'"
        refused.source shouldBe SRC.config
    }

    "starting makes every declared bucket ready and records that the check ran clean" {
        val dir = tempDir()
        val cxt = cxtWith(ENV.unit, FSENV.type.name to FST.local, FSENV.dir.name to dir.absolutePath, buckets = listOf("one-bucket", "two-bucket"))

        val service = started(cxt)

        File(dir, "one-bucket").isDirectory shouldBe true
        File(dir, "two-bucket").isDirectory shouldBe true
        service.declaredBuckets().map { it.name } shouldContainExactly listOf("one-bucket", "two-bucket")
        val check = fileStoreCheck(cxt).shouldNotBeNull()
        check.mode shouldBe BootCheckMode.strict
        check.envVar shouldBe "KDR_FILE_STORE_CHECK"
        check.findings.shouldBeEmpty()
    }

    "a declared bucket the store cannot serve refuses the start, naming every one" {
        val api = FakeS3Api("kdr-there")
        val cxt = cxtWith(
            ENV.local, FSENV.type.name to FST.s3, FSENV.s3BucketPrefix.name to "kdr-", FileStoreService.s3ApiConfigKey to api,
            buckets = listOf("there", "missing-one", "missing-two"),
        )

        val refused = shouldThrow<KdrException> { started(cxt) }

        refused.message shouldContain "Refusing to start: the s3 file store cannot serve every declared bucket."
        refused.message shouldContain "'kdr-missing-one'"
        refused.message shouldContain "'kdr-missing-two'"
        fileStoreCheck(cxt).shouldNotBeNull().findings shouldHaveSize 2
        api.buckets shouldContainExactly setOf("kdr-there")
    }

    "in production it is a warning, and the node starts; the check's mode can be set either way" {
        fun cxtFor(env: String, vararg more: Pair<String, Any?>) =
            cxtWith(env, FSENV.type.name to FST.s3, FileStoreService.s3ApiConfigKey to FakeS3Api(), *more)

        val prod = cxtFor(ENV.prod)
        started(prod)
        fileStoreCheck(prod).shouldNotBeNull().let {
            it.mode shouldBe BootCheckMode.warn
            it.findings shouldHaveSize 1
        }

        val warned = cxtFor(ENV.local, FSENV.check.name to "warn")
        started(warned)
        fileStoreCheck(warned).shouldNotBeNull().findings shouldHaveSize 1

        shouldThrow<KdrException> { started(cxtFor(ENV.prod, FSENV.check.name to "strict")) }

        // Off: nothing is asked of the store, and the check is recorded as the one that did not run -- in the
        // report, rather than missing from it as though there were none.
        val off = cxtFor(ENV.local, FSENV.check.name to "off")
        started(off)
        fileStoreCheck(off).shouldNotBeNull().let {
            it.mode shouldBe BootCheckMode.off
            it.findings.shouldBeEmpty()
        }
        (off.instanceConfig.get(FileStoreService.s3ApiConfigKey) as FakeS3Api).calls.shouldBeEmpty()
    }

    "a bucket declared twice refuses the boot" {
        val collector = SchemaCollector()
        collector.addFileBucket(FileBucketDef("twice-files", "One."))
        shouldThrow<KdrException> { collector.addFileBucket(FileBucketDef("twice-files", "Two.")) }
            .message shouldContain "'twice-files' is declared more than once"
    }

    "storing writes the metadata from the content and from who is storing it" {
        val cxt = memoryCxt()
        val service = started(cxt)
        cxt.userProfile = UserProfile(authId = "pat", userId = 77, client = "acme")
        cxt.client = "acme"
        val bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, -1)

        val before = cxt.now().truncateToMs()
        val meta = service.put(cxt, bucket, "reports/acme/logo", ContentData(bytes, "image/png", saveAsFilename = "../../My Logo.png"), mapOf("slot" to "brandMark"))

        meta.mimeType shouldBe "image/png"
        meta.size shouldBe 6
        meta.hash shouldBe bytes.sha256Hex()
        (meta.uploadedAt >= before && meta.uploadedAt <= cxt.now()) shouldBe true
        // The name is kept as a name: whatever path it arrived with is not.
        meta.fileName shouldBe "My Logo.png"
        meta.uploadedBy shouldBe 77
        meta.client shouldBe "acme"
        meta.extra shouldBe mapOf("slot" to "brandMark")

        val file = service.get(cxt, bucket, "reports/acme/logo").shouldNotBeNull()
        file.bytes.toList() shouldBe bytes.toList()
        file.meta.toJsonMap() shouldBe meta.toJsonMap()
        // The moment answered is the moment recorded, to the precision the metadata file holds.
        file.meta.uploadedAt shouldBe meta.uploadedAt
        service.meta(cxt, bucket, "reports/acme/logo").shouldNotBeNull().toJsonMap() shouldBe meta.toJsonMap()
        service.list(cxt, bucket, "reports/").map { it.key } shouldContainExactly listOf("reports/acme/logo")

        // As content to send: the stored type, the name offered back, the hash.
        val content = file.toContentData(inLine = false)
        content.mimeType shouldBe "image/png"
        content.saveAsFilename shouldBe "My Logo.png"
        content.inLine shouldBe false
        content.hash shouldBe meta.hash

        service.delete(cxt, bucket, "reports/acme/logo") shouldBe true
        service.get(cxt, bucket, "reports/acme/logo").shouldBeNull()
    }

    "a file stored by no signed-in user records none, and text is stored as its bytes" {
        val cxt = memoryCxt()
        val service = started(cxt)

        val meta = service.put(cxt, bucket, "notes/hello", ContentData("héllo", "text/plain; charset=utf-8"))

        meta.uploadedBy.shouldBeNull()
        meta.fileName.shouldBeNull()
        meta.size shouldBe 6
        service.get(cxt, bucket, "notes/hello").shouldNotBeNull().bytes.decodeToString() shouldBe "héllo"
    }

    "a bucket nobody declared is served to nobody, and a name that is not a key is refused before the store sees it" {
        val api = FakeS3Api(bucket, "undeclared-files")
        val cxt = cxtWith(ENV.unit, FSENV.type.name to FST.s3, FileStoreService.s3ApiConfigKey to api)
        val service = started(cxt)
        api.calls.clear()
        val content = ContentData("x", "text/plain")

        for (call in listOf<() -> Any?>(
            { service.put(cxt, "undeclared-files", "a", content) }, { service.get(cxt, "undeclared-files", "a") },
            { service.meta(cxt, "undeclared-files", "a") }, { service.delete(cxt, "undeclared-files", "a") },
            { service.list(cxt, "undeclared-files") },
        )) {
            shouldThrow<KdrException> { call() }.message shouldContain "The file-store bucket 'undeclared-files' is not declared"
        }
        for (call in listOf<() -> Any?>(
            { service.put(cxt, bucket, "../a", content) }, { service.get(cxt, bucket, "a//b") },
            { service.meta(cxt, bucket, "a.meta.json") }, { service.delete(cxt, bucket, "/a") },
            { service.list(cxt, bucket, "../") },
        )) {
            shouldThrow<KdrException> { call() }.code shouldBe EXC.badInput
        }
        api.calls.shouldBeEmpty()
    }

    "content that no longer matches its metadata is refused, not handed on" {
        val dir = tempDir()
        val cxt = cxtWith(ENV.unit, FSENV.type.name to FST.local, FSENV.dir.name to dir.absolutePath)
        val service = started(cxt)
        service.put(cxt, bucket, "doc", ContentData("as stored", "text/plain"))
        File(dir, "$bucket/doc").writeText("changed on disk")

        val refused = shouldThrow<KdrException> { service.get(cxt, bucket, "doc") }

        refused.message shouldContain "The file 'doc' in the bucket '$bucket' does not match its metadata"
        refused.source shouldBe SRC.file
        // It was read again, over a moment, before being called damaged: a replace in flight looks the same.
        FileStoreService.rereadPausesMs.sum() shouldBe 310L
        // What the file is can still be asked.
        service.meta(cxt, bucket, "doc").shouldNotBeNull().size shouldBe 9
    }

    "a file replaced while it is being read comes back whole, as the new one" {
        val api = FakeS3Api(bucket)
        val cxt = cxtWith(ENV.unit, FSENV.type.name to FST.s3, FileStoreService.s3ApiConfigKey to api)
        val service = started(cxt)
        service.put(cxt, bucket, "logo", ContentData("old content", "text/plain"))
        // Another caller's write lands between this read's two fetches: the metadata read is the old one, the
        // content read the new.
        var replaced = false
        api.afterGet = { _, key ->
            if (!replaced && key == "logo.meta.json") {
                replaced = true
                service.put(cxt, bucket, "logo", ContentData("new content, longer", "text/markdown"))
            }
        }

        val file = service.get(cxt, bucket, "logo").shouldNotBeNull()

        replaced shouldBe true
        file.bytes.decodeToString() shouldBe "new content, longer"
        file.meta.mimeType shouldBe "text/markdown"
        file.meta.hash shouldBe "new content, longer".toByteArray().sha256Hex()
    }

    // The moment a replace has written its content and not yet its metadata: the old metadata, the new content.
    // A read that lands in it waits it out rather than answering with no file or with a failure.
    "a file read in the middle of a replace is the new file, once its metadata lands" {
        val api = FakeS3Api(bucket)
        val cxt = cxtWith(ENV.unit, FSENV.type.name to FST.s3, FileStoreService.s3ApiConfigKey to api)
        val service = started(cxt)
        service.put(cxt, bucket, "logo", ContentData("old content", "text/plain"))
        val newBytes = "new content, longer".toByteArray()
        // The replace's first half: its content is in, under the old file's metadata.
        api.objects["$bucket/logo"] = newBytes
        // Its second half lands while the read is waiting to look again.
        var contentReads = 0
        api.afterGet = { _, key ->
            if (key == "logo" && ++contentReads == 2) {
                val meta = FileMeta("text/markdown", newBytes.size.toLong(), newBytes.sha256Hex(), "2026-10-12T08:30:00Z".parseDate())
                api.objects["$bucket/logo.meta.json"] = meta.toFileText().toByteArray()
            }
        }

        val file = service.get(cxt, bucket, "logo").shouldNotBeNull()

        file.bytes.decodeToString() shouldBe "new content, longer"
        file.meta.mimeType shouldBe "text/markdown"
        contentReads shouldBe 3
    }

    "a listing is refused when it would be longer than its limit, rather than cut short" {
        val cxt = memoryCxt()
        val service = started(cxt)
        for (n in 1..5) service.put(cxt, bucket, "f/$n", ContentData("$n", "text/plain"))

        service.list(cxt, bucket, "f/", limit = 5).map { it.key } shouldContainExactly (1..5).map { "f/$it" }
        val refused = shouldThrow<KdrException> { service.list(cxt, bucket, "f/", limit = 4) }
        refused.code shouldBe EXC.badInput
        refused.message shouldContain "holds more than 4 files under 'f/'"
        shouldThrow<KdrException> { service.list(cxt, bucket, limit = 0) }.code shouldBe EXC.badInput
        // The limit a caller does not name is the store's own.
        service.list(cxt, bucket).size shouldBe 5
        FNAME.defaultListLimit shouldBe 1000
    }

    // What is answered is what is kept, and what is kept is what its file holds -- on the store in memory as on
    // any other, so a test that passes there is not passing on something a disk would have changed.
    "extra is kept as its JSON, a copy of what was handed in" {
        val cxt = memoryCxt()
        val service = started(cxt)
        val nested = mutableMapOf<String, Any?>("tags" to listOf("a", "b"), "more" to mapOf("n" to 2, "deep" to mapOf("ok" to true)))
        val handedIn = mutableMapOf<String, Any?>("purpose" to "brand", "at" to "2026-10-12T08:30:00Z".parseDate(), "nested" to nested)

        val answered = service.put(cxt, bucket, "logo", ContentData("PNG", "image/png"), handedIn)
        handedIn["purpose"] = "changed after the fact"
        nested["tags"] = listOf("changed")

        val kept = service.meta(cxt, bucket, "logo").shouldNotBeNull()
        kept.extra shouldBe answered.extra
        kept.extra["purpose"] shouldBe "brand"
        kept.extra["nested"].toJsonMapOrEmpty().let {
            it["tags"] shouldBe listOf("a", "b")
            it["more"].toJsonMapOrEmpty()["deep"] shouldBe mapOf("ok" to true)
        }
        // A value that is not JSON's is the text it is written as, in memory as it would be on a disk.
        kept.extra["at"].shouldBeInstanceOf<String>() shouldContain "2026-10-12T08:30:00"
    }

    // The client beside a file is the one its context was bound to, as a row's owner is: not who was acting.
    "the client recorded is the one the storing context is bound to" {
        val cxt = memoryCxt()
        val service = started(cxt)
        val forAcme = cxt.mkSubContext("job", "acme")

        service.put(forAcme, bucket, "report", ContentData("r", "text/plain")).let {
            it.client shouldBe "acme"
            it.uploadedBy.shouldBeNull()
        }
        // A context bound to no client records the deployment's own.
        service.put(cxt, bucket, "unbound", ContentData("u", "text/plain")).client shouldBe cxt.client
    }

    // --- Issue #1282: a client's files reached through forOwner, in its own folder ---------------------------------

    "a client's files are reached through forOwner, and only in its own folder; the service's calls take no client's" {
        val cxt = memoryCxt()
        val service = started(cxt)
        val acme = service.forOwner(cxt, "acme")
        acme.put(cxt, bucket, "clients/acme/logo", ContentData("a", "text/plain"))
        acme.get(cxt, bucket, "clients/acme/logo").shouldNotBeNull()
        service.forOwner(cxt, "globex").put(cxt, bucket, "clients/globex/logo", ContentData("g", "text/plain"))
        service.put(cxt, bucket, "notes/plain", ContentData("p", "text/plain"))

        // The service's own calls refuse a key or a prefix in a client's folder, and their listing leaves those out.
        shouldThrow<KdrException> { service.get(cxt, bucket, "clients/acme/logo") }.message shouldContain
            "The file-store key 'clients/acme/logo' is a client's"
        shouldThrow<KdrException> { service.put(cxt, bucket, "clients/acme/other", ContentData("x", "text/plain")) }
        shouldThrow<KdrException> { service.list(cxt, bucket, "clients/") }.message shouldContain "is a client's folder"
        service.list(cxt, bucket, "notes/").map { it.key } shouldContainExactly listOf("notes/plain")
        // The whole bucket holds clients' files, which the default listing does not take: refused, never answered short.
        shouldThrow<KdrException> { service.list(cxt, bucket) }.message shouldContain "holds clients' files"

        // A client's view takes its own folder and no other.
        shouldThrow<KdrException> { acme.get(cxt, bucket, "clients/globex/logo") }.message shouldContain
            "is not one of client 'acme''s, which are under 'clients/acme/'"
        shouldThrow<KdrException> { acme.put(cxt, bucket, "notes/plain", ContentData("x", "text/plain")) }
        acme.list(cxt, bucket).map { it.key } shouldContainExactly listOf("clients/acme/logo")
        acme.list(cxt, bucket, "clients/acme/").map { it.key } shouldContainExactly listOf("clients/acme/logo")
        shouldThrow<KdrException> { acme.list(cxt, bucket, "clients/") }.message shouldContain "not within client 'acme''s folder"
        // An owner that could be no folder's name is no owner.
        shouldThrow<KdrException> { service.forOwner(cxt, "../acme") }
        // Nor is a path, which would be a folder inside another client's.
        shouldThrow<KdrException> { service.forOwner(cxt, "acme/web") }.message shouldContain "it holds a slash"
        shouldThrow<KdrException> { service.forOwner(cxt, "") }
        // The prefix every client key is built from.
        FNAME.clientKeyPrefix("acme") shouldBe "clients/acme/"
    }
})
