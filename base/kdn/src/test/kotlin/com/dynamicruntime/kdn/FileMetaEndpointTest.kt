package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.filestore.FileBucketDef
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.filestore.fileMetaEndpoint
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A meta-only read over real HTTP (issue #1131), beside the upload and the download of one fixture bucket: what it
 * answers is the metadata the upload wrote, under `item`, and a missing file is a 404 from it as from the download.
 * The fixture's module never declares the `FileMeta` type itself, and response validation is on in tests, so a
 * builder that did not bring the type would fail every answer here.
 */
class FileMetaEndpointTest : StringSpec({
    // Its own instance (issue #1075): FileMetaFixtureComponent, a fixture component no shared entry has.
    val cxt = Startup.mkTestBootCxt("fileMetaEndpoint", "fileMetaEndpointTest", emptyMap(), listOf(FileMetaFixtureComponent()))
    val http = TestHttpClient(cxt.instanceConfig)

    fun upload(key: String, content: ContentData): Map<String, Any?> =
        http.sendUploadRequest(FileMetaFixtureComponent.upload, mapOf("key" to key, "file" to content)).rptResponseData?.jsonMap().shouldNotBeNull()

    "the meta read answers the metadata the upload wrote, and never the file" {
        val bytes = ByteArray(300) { (it * 7).toByte() }
        upload("reports/q3", ContentData(bytes, "application/pdf", saveAsFilename = "Q3 report.pdf"))[EP.status].shouldBeNull()

        val resp = http.sendJsonGetRequest(FileMetaFixtureComponent.meta, mapOf("key" to "reports/q3"))

        resp[EP.status].shouldBeNull()
        val item = resp[EP.item].toJsonMapOrEmpty()
        item[FMETA.mimeType] shouldBe "application/pdf"
        item[FMETA.size] shouldBe 300L
        item[FMETA.hash] shouldBe bytes.sha256Hex()
        item[FMETA.fileName] shouldBe "Q3 report.pdf"
        item[FMETA.uploadedAt].toOptStr().shouldNotBeNull()
        // The answer is the store's record, field for field.
        item shouldBe FileStoreService.get(cxt).meta(cxt, FileMetaFixtureComponent.bucket, "reports/q3").shouldNotBeNull().toJsonMap()
        // And the download beside it is the file the record describes.
        val download = http.sendGetRequest(FileMetaFixtureComponent.download, mapOf("key" to "reports/q3"))
        download.rptResponseBytes!!.sha256Hex() shouldBe item[FMETA.hash]
    }

    "a file that is not there is a 404 from the meta read as it is from the download" {
        val meta = http.sendJsonGetRequest(FileMetaFixtureComponent.meta, mapOf("key" to "reports/none"))
        meta[EP.status] shouldBe EXC.notFound
        meta[EP.errorMessage] shouldBe "There is no such file."
        http.sendJsonGetRequest(FileMetaFixtureComponent.download, mapOf("key" to "reports/none"))[EP.status] shouldBe EXC.notFound

        // A handler may say why there is no file, in its own words.
        val explained = http.sendJsonGetRequest(FileMetaFixtureComponent.explained, mapOf("key" to "reports/none"))
        explained[EP.status] shouldBe EXC.notFound
        explained[EP.errorMessage] shouldBe "No report 'reports/none' has been filed."
    }

    "a file replaced is described as it now is" {
        upload("logo", ContentData("first", "text/plain"))
        upload("logo", ContentData("second, longer", "text/markdown"))

        val item = http.sendJsonGetRequest(FileMetaFixtureComponent.meta, mapOf("key" to "logo"))[EP.item].toJsonMapOrEmpty()

        item[FMETA.mimeType] shouldBe "text/markdown"
        item[FMETA.hash] shouldBe "second, longer".toByteArray().sha256Hex()
    }
})

/** A component keeping files in one bucket, with an upload, a download, and the meta-only read beside the download. */
class FileMetaFixtureComponent : ComponentDefinition {
    override val providerName: String = "fileMetaFixture"
    override val ownerRoot: String = namespace

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addFileBucket(FileBucketDef(bucket, "Files of the meta-only read test."))
        collector.addModule(
            schemaModule(cxt, namespace) {
                type("Stored") { type = SCT.kObject; property("hash", "The stored file's hash.", required = true) }
                fileUploadEndpoint(upload, "Stores a file at a key.", outputRef = "Stored", inputFields = {
                    field("key", "Where to keep it.", required = true)
                    field("file", "The file.", required = true) { binaryContent() }
                }) { c, request ->
                    val content = request["file"] as? ContentData ?: throw KdrException.mkInput("No file.")
                    mapOf("hash" to FileStoreService.get(c).put(c, bucket, request["key"].toOptStr().orEmpty(), content).hash)
                }
                fileDownloadEndpoint(download, "The file at a key.", inputFields = { field("key", "Which file.", required = true) }) { c, request ->
                    FileStoreService.get(c).get(c, bucket, request["key"].toOptStr().orEmpty())?.toContentData()
                        ?: throw KdrException("No such file.", code = EXC.notFound)
                }
                fileMetaEndpoint(meta, "What the file at a key is.", inputFields = { field("key", "Which file.", required = true) }) { c, request ->
                    FileStoreService.get(c).meta(c, bucket, request["key"].toOptStr().orEmpty())
                }
                fileMetaEndpoint(explained, "What the report at a key is.", inputFields = { field("key", "Which report.", required = true) }) { c, request ->
                    val key = request["key"].toOptStr().orEmpty()
                    FileStoreService.get(c).meta(c, bucket, key)
                        ?: throw KdrException("No report '$key' has been filed.", code = EXC.notFound)
                }
            },
        )
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val namespace = "filemetafixture"
        const val bucket = "file-meta-fixture"
        const val upload = "/fixture/filemeta/upload"
        const val download = "/fixture/filemeta/download"
        const val meta = "$download/meta"
        const val explained = "/fixture/filemeta/report/meta"
    }
}
