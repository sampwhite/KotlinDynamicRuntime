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
import com.dynamicruntime.common.util.sha256Hex
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
        // Created only: starting it would make the bucket's directory, and the default is in the real workspace.
        val byDefault = created(cxtWith(ENV.local))
        byDefault.providerType shouldBe FST.local
        byDefault.store.location shouldBe AppPaths.resolve("fileStore").path

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

        // Off: nothing is asked of the store, and nothing is recorded as having been checked.
        val off = cxtFor(ENV.local, FSENV.check.name to "off")
        started(off)
        fileStoreCheck(off).shouldBeNull()
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
        val meta = service.put(cxt, bucket, "clients/acme/logo", ContentData(bytes, "image/png", saveAsFilename = "../../My Logo.png"), mapOf("slot" to "brandMark"))

        meta.mimeType shouldBe "image/png"
        meta.size shouldBe 6
        meta.hash shouldBe bytes.sha256Hex()
        (meta.uploadedAt >= before && meta.uploadedAt <= cxt.now()) shouldBe true
        // The name is kept as a name: whatever path it arrived with is not.
        meta.fileName shouldBe "My Logo.png"
        meta.uploadedBy shouldBe 77
        meta.client shouldBe "acme"
        meta.extra shouldBe mapOf("slot" to "brandMark")

        val file = service.get(cxt, bucket, "clients/acme/logo").shouldNotBeNull()
        file.bytes.toList() shouldBe bytes.toList()
        file.meta.toJsonMap() shouldBe meta.toJsonMap()
        // The moment answered is the moment recorded, to the precision the metadata file holds.
        file.meta.uploadedAt shouldBe meta.uploadedAt
        service.meta(cxt, bucket, "clients/acme/logo").shouldNotBeNull().toJsonMap() shouldBe meta.toJsonMap()
        service.list(cxt, bucket, "clients/").map { it.key } shouldContainExactly listOf("clients/acme/logo")

        // As content to send: the stored type, the name offered back, the hash.
        val content = file.toContentData(inLine = false)
        content.mimeType shouldBe "image/png"
        content.saveAsFilename shouldBe "My Logo.png"
        content.inLine shouldBe false
        content.hash shouldBe meta.hash

        service.delete(cxt, bucket, "clients/acme/logo") shouldBe true
        service.get(cxt, bucket, "clients/acme/logo").shouldBeNull()
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
})
