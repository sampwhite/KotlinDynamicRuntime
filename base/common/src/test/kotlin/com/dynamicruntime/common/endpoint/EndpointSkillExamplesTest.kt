package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Holds `.claude/skills/kdr-endpoint-builder` to the code, by building its worked examples and checking the
 * claims it makes about them.
 *
 * A skill is read *instead of* working the API out, so a wrong one is followed rather than noticed. This one
 * had earned that: it claimed execution "is not built yet" long after it was, showed `/user/{id}` as though
 * path parameters existed, said a list endpoint's input was nested under `request` when it has been flat since
 * #40, listed an `EP.request` constant that does not exist, gave a `KdrEndpoint` constructor with a parameter
 * that does not exist, and omitted the mandatory `description` from **every** example — so not one of them
 * compiled. A signature change now breaks this instead of quietly staling the documentation.
 *
 * **What this does not do:** it cannot read the Markdown, so it cannot prove the prose is right. It pins the
 * *examples* — if you change the DSL and land here, the skill needs the same edit. Keep the code below a
 * faithful transcription of what the skill shows; do not "improve" it past that.
 *
 * The builders themselves are covered by [EndpointBuilderTest] / [EndpointCatalogTest]. This is about the
 * documentation.
 */
class EndpointSkillExamplesTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    // Transcribed from the skill's "The DSL" section.
    fun example(): SchModule = schemaModule(cxt, "users") {
        type("UserQuery") { type = SCT.kObject; property("namePrefix", "Filter by name prefix") }
        type("User") {
            type = SCT.kObject
            property("id", "User id") { type = SCT.integer }
            property("name", "Full name")
        }

        generalEndpoint(
            "/user/rename", "Rename a user.", HttpMethod.POST,
            outputRef = "User", inputRef = "UserQuery",
        ) { _, _ -> emptyMap<String, Any?>() }

        itemEndpoint(
            "/user/get", "Fetch one user by id.", HttpMethod.GET, outputRef = "User",
            inputFields = { field("id", "The user's id", required = true) { type = SCT.integer } },
        ) { _, _ -> null }

        listEndpoint(
            "/users", "List users.", outputRef = "User", inputRef = "UserQuery",
            hasMore = true,
        ) { _, _ -> emptyList<Any?>() }
    }

    // Transcribed from the skill's "Files" section.
    fun fileExample(): SchModule = schemaModule(cxt, "file") {
        type("FileInfo") { type = SCT.kObject; property("id", "Id of the stored file.") }

        fileUploadEndpoint(
            "/file/upload", "Upload a file.", outputRef = "FileInfo",
            inputFields = { field("file", "The file to upload.", required = true) { binaryContent() } },
        ) { _, request ->
            val content = request["file"] as? ContentData ?: throw KdrException.mkInput("No file supplied.")
            mapOf("id" to content.saveAsFilename)
        }

        fileDownloadEndpoint(
            "/file/download", "Download a file by id.",
            inputFields = { field("id", "Id of the file.", required = true) },
        ) { _, _ -> ContentData(byteArrayOf(1), "application/octet-stream") }
    }

    fun endpoint(module: SchModule, path: String): KdrEndpoint = module.endpoints.first { it.path == path }

    "the skill's example builds the endpoints it says it does" {
        example().endpoints.map { it.path } shouldContainExactlyInAnyOrder
            listOf("/user/rename", "/user/get", "/users")
    }

    // "The kinds (differ by where the payload sits)".
    "each kind puts its payload where the skill says" {
        val m = example()
        val rename = endpoint(m, "/user/rename")
        rename.kind shouldBe EndpointKind.general
        rename.outputSchema[SCH.properties].toJsonMapOrEmpty().keys shouldContain EP.results

        val get = endpoint(m, "/user/get")
        get.kind shouldBe EndpointKind.item
        get.outputSchema[SCH.properties].toJsonMapOrEmpty().keys shouldContain EP.item

        val list = endpoint(m, "/users")
        list.kind shouldBe EndpointKind.list
        val listProps = list.outputSchema[SCH.properties].toJsonMapOrEmpty()
        listProps.keys shouldContain EP.items
        listProps.keys shouldContain EP.numItems
        listProps.keys shouldContain EP.hasMore // the example passes hasMore = true
    }

    // "Every JSON output also carries requestUri (String) and duration (number, ms)."
    "every output carries the protocol metadata" {
        for (ep in example().endpoints) {
            val props = ep.outputSchema[SCH.properties].toJsonMapOrEmpty()
            props.keys shouldContain EP.requestUri
            props.keys shouldContain EP.duration
        }
    }

    // "Method defaults to GET" for a list; the example does not pass one.
    "a list endpoint's method defaults to GET" {
        endpoint(example(), "/users").method shouldBe HttpMethod.GET
    }

    // "Input is FLAT" -- the claim that was wrong for longest.
    "a list endpoint's limit is a flat sibling field, not a nested request object" {
        val m = example()
        val types = parseSchemaTypes(m.defs)
        val input = resolveEndpointInputType(endpoint(m, "/users"), types)!!

        // The named input type's own property, lifted to the top level...
        input.properties.keys shouldContain "namePrefix"
        // ... beside limit, as a sibling.
        input.properties.keys shouldContain EP.limit
        // And emphatically NOT wrapped in anything.
        input.properties.keys shouldNotContain "request"
    }

    "inputFields declares fields inline, flat like the rest" {
        val m = example()
        val input = resolveEndpointInputType(endpoint(m, "/user/get"), parseSchemaTypes(m.defs))!!
        input.properties.keys shouldContainExactlyInAnyOrder listOf("id")
        input.required shouldContain "id"
    }

    // "Paths are matched exactly (path:method) ... there is no path-parameter extraction".
    "an endpoint is keyed by its exact path and method" {
        endpoint(example(), "/user/rename").collationKey shouldBe "/user/rename:POST"
    }

    // --- the Files section --------------------------------------------------------------------------------

    "both file endpoints are of kind file" {
        fileExample().endpoints.forEach { it.kind shouldBe EndpointKind.file }
    }

    // "Its output schema is OpenAPI's {"type": "string", "format": "binary"}".
    "a download's output schema declares binary content, with no envelope" {
        val download = endpoint(fileExample(), "/file/download")
        // The literal values the skill quotes -- OpenAPI's, so not ours to change.
        download.outputSchema[SCH.type] shouldBe "string"
        download.outputSchema[SCH.format] shouldBe "binary"
        // No results/item/items wrapper: the response IS the file.
        download.outputSchema[SCH.properties] shouldBe null
    }

    // "Its *response* is ordinary JSON under `results` -- an upload's answer is metadata, not a file."
    "an upload answers under results, like a general endpoint" {
        val upload = endpoint(fileExample(), "/file/upload")
        upload.outputSchema[SCH.properties].toJsonMapOrEmpty().keys shouldContain EP.results
    }

    "an upload's file field is declared binary" {
        val m = fileExample()
        val input = resolveEndpointInputType(endpoint(m, "/file/upload"), parseSchemaTypes(m.defs))!!
        val file = input.properties["file"]!!.valueType
        file.jsonType shouldBe SCT.string
        file.format shouldBe SFMT.binary
    }

    // The skill's default: "a body-carrying request; POST unless the caller says otherwise", and a download
    // is a GET.
    "the file endpoints' methods default as documented" {
        endpoint(fileExample(), "/file/upload").method shouldBe HttpMethod.POST
        endpoint(fileExample(), "/file/download").method shouldBe HttpMethod.GET
    }
})
