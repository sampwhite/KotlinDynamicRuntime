package com.dynamicruntime.common.filestore

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.toJsonStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** A stored file's metadata (issue #1129): what its file holds, what reads it back, and the type that describes it. */
class FileMetaTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("fileMeta")
    val whole = FileMeta(
        mimeType = "image/svg+xml", size = 1234, hash = "ab".repeat(32), uploadedAt = "2026-10-12T08:30:15Z".parseDate(),
        fileName = "mark.svg", uploadedBy = 7, client = "acme", extra = mapOf("slot" to "brandMark", "n" to 3L),
    )

    "the metadata file holds every field, and reading it back gives the same metadata" {
        val read = FileMeta.fromFileText(whole.toFileText()).shouldNotBeNull()

        read.mimeType shouldBe "image/svg+xml"
        read.size shouldBe 1234
        read.hash shouldBe "ab".repeat(32)
        read.uploadedAt shouldBe "2026-10-12T08:30:15Z".parseDate()
        read.fileName shouldBe "mark.svg"
        read.uploadedBy shouldBe 7
        read.client shouldBe "acme"
        read.extra shouldBe mapOf("slot" to "brandMark", "n" to 3L)
        read.toJsonMap() shouldBe whole.toJsonMap()
    }

    "what was not known is left out rather than written as nothing" {
        val bare = FileMeta("text/plain", 0, "e3b0", "2026-10-12T08:30:15Z".parseDate())
        val map = bare.toFileText().jsonMap().shouldNotBeNull()

        map.keys.toList() shouldContainExactly listOf(FMETA.mimeType, FMETA.size, FMETA.hash, FMETA.uploadedAt)
        val read = FileMeta.fromFileText(bare.toFileText()).shouldNotBeNull()
        read.fileName.shouldBeNull()
        read.uploadedBy.shouldBeNull()
        read.extra shouldBe emptyMap()
    }

    "metadata missing what makes it metadata is none at all" {
        for (dropped in listOf(FMETA.mimeType, FMETA.size, FMETA.hash, FMETA.uploadedAt)) {
            FileMeta.fromJsonMap(whole.toJsonMap() - dropped).shouldBeNull()
        }
        FileMeta.fromJsonMap(whole.toJsonMap() + (FMETA.uploadedAt to "yesterday")).shouldBeNull()
        FileMeta.fromFileText("not json at all").shouldBeNull()
        FileMeta.fromFileText("[1, 2]").shouldBeNull()
        FileMeta.fromFileText("").shouldBeNull()
    }

    "the metadata of the file at a key is kept at the key with the suffix" {
        FileMeta.metaKey("clients/acme/logo") shouldBe "clients/acme/logo.meta.json"
    }

    "the declared type describes what the metadata serializes to" {
        val types = parseSchemaTypes(schemaDefs(cxt, "kdr.test") { FileMeta.defineInfoType(this) }, emptyMap())
        val type = types["kdr.test.${FMETA.infoTypeName}"].shouldNotBeNull()

        validate(type, whole.toJsonMap().toJsonStr().jsonMap()).shouldBeEmpty()
        validate(type, FileMeta("text/plain", 0, "e3b0", "2026-10-12T08:30:15Z".parseDate()).toJsonMap()).shouldBeEmpty()
        // And it is a description with teeth: metadata without its hash does not pass.
        validate(type, whole.toJsonMap() - FMETA.hash).shouldNotBeEmpty()
    }
})
