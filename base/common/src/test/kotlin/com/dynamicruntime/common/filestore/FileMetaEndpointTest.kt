package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith

/** The meta-only read's builder (issue #1131): the endpoint it makes, and the type it brings with it. */
class FileMetaEndpointTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("fileMetaEndpoint")
    val meta = FileMeta("image/png", 3, "abc", "2026-10-14T09:00:00Z".parseDate(), fileName = "logo.png")

    "it is a GET answering FileMeta under item, and brings the type into a module that had none" {
        val module = schemaModule(cxt, "metaone") {
            fileMetaEndpoint("/one/download/meta", "What the file is.", inputFields = { field("id", "Which file.", required = true) }) { _, _ -> meta }
        }

        module.defs.keys shouldContainExactly setOf("metaone.${FMETA.infoTypeName}")
        val ep = module.endpoints.single()
        ep.method shouldBe HttpMethod.GET
        ep.kind shouldBe EndpointKind.item
        ep.outputSchema[SCH.properties].toJsonMapOrEmpty()[EP.item].toJsonMapOrEmpty()[SCH.dRef].toString() shouldEndWith
            "metaone.${FMETA.infoTypeName}"
        // The output resolves: the type it names is the one it brought.
        parseSchemaTypes(module.defs)["metaone.${FMETA.infoTypeName}"].shouldNotBeNull()
    }

    "a module that declares the type itself, for a listing, keeps the one it declared" {
        val module = schemaModule(cxt, "metatwo") {
            FileMeta.defineInfoType(this)
            fileMetaEndpoint("/two/a/meta", "What file a is.") { _, _ -> meta }
            fileMetaEndpoint("/two/b/meta", "What file b is.") { _, _ -> meta }
        }

        module.defs.keys shouldContainExactly setOf("metatwo.${FMETA.infoTypeName}")
        module.endpoints.map { it.path } shouldContainExactly listOf("/two/a/meta", "/two/b/meta")
    }

    "what an endpoint is told reaches the endpoint: a named input type, and every flag" {
        val module = schemaModule(cxt, "metafour") {
            type("FileQuery") { type = SCT.kObject; property("id", "Which file.", required = true) }
            fileMetaEndpoint(
                "/four/meta", "What the file is.", inputRef = "FileQuery", forTestingOnly = true, publicApi = true,
                tags = setOf("files"), needsClientConfig = true,
            ) { _, _ -> meta }
        }
        val ep = module.endpoints.single()

        ep.inputTypeRef shouldBe "metafour.FileQuery"
        ep.forTestingOnly shouldBe true
        ep.publicApi shouldBe true
        ep.tags shouldBe setOf("files")
        ep.needsClientConfig shouldBe true
        // And none of them is on by default.
        val plain = schemaModule(cxt, "metafive") { fileMetaEndpoint("/five/meta", "What the file is.") { _, _ -> meta } }.endpoints.single()
        listOf(plain.forTestingOnly, plain.publicApi, plain.needsClientConfig) shouldBe listOf(false, false, false)
        plain.tags shouldBe emptySet()
    }

    "the answer is the metadata as its file holds it, and no metadata is a 404" {
        val module = schemaModule(cxt, "metathree") {
            fileMetaEndpoint("/three/meta", "What the file is.") { _, request -> if (request["id"] == "logo") meta else null }
        }
        val handler = module.endpoints.single().handler

        handler(cxt, mapOf("id" to "logo")) shouldBe meta.toJsonMap()
        val missing = shouldThrow<KdrException> { handler(cxt, mapOf("id" to "other")) }
        missing.code shouldBe EXC.notFound
        missing.message shouldBe "There is no such file."
    }
})
