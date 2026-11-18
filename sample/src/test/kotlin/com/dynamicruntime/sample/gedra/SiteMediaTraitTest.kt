package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.sample.SampleTestInstances
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The sample's file trait (issue #1838), `sample:siteMedia`: a client taking every global trait carries it, so its
 * forms can name uploaded files -- what a developer exercises the data files against by hand.
 */
class SiteMediaTraitTest : StringSpec({
    val cxt = SampleTestInstances.default("siteMedia")
    val user = TestUser.create(cxt, "media@globex.test", userClient = SC.globex)

    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
        ByteArray(300) { it.toByte() }

    fun envelope(handler: com.dynamicruntime.common.http.request.RequestHandler): Map<String, Any?> =
        handler.rptResponseData?.jsonMap().shouldNotBeNull()

    fun uploadPng(): String {
        val sent = envelope(user.client.sendRawBodyRequest(DFILE.uploadPath, mapOf(DFILE.name to "site.png", DFILE.mimeType to "image/png"), png))
        sent[EP.status] shouldBe null
        return sent[EP.results].toJsonMapOrEmpty()[DFILE.fileId].toOptStr().shouldNotBeNull()
    }

    fun create(data: Map<String, Any?>): Map<String, Any?> = envelope(
        user.client.sendEditRequest(
            clientPath(GEP.formDocCreate, SC.globex), null,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to ST.siteMedia, GE.data to data))), HttpMethod.POST,
        ),
    )

    "a globex form's photo names an uploaded file, and its read says what the file is" {
        val photo = uploadPng()
        val created = create(mapOf(ST.caption to "The north wall", ST.photo to photo))
        val formId = created[EP.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr().shouldNotBeNull()
        val files = user.getItem(clientPath(GEP.formDoc, SC.globex), mapOf(GDF.gedraId to formId))[GDF.files].toJsonMapOrEmpty()
        files[photo].toJsonMapOrEmpty()[DFILE.kind] shouldBe FKIND.png
    }

    "the video and the report take only their own kinds" {
        val photo = uploadPng()
        val refused = create(mapOf(ST.video to photo))
        refused[EP.status] shouldBe EXC.badInput.toLong()
        refused[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "takes only MP4 video or WebM video;"
        create(mapOf(ST.report to photo))[EP.status] shouldBe EXC.badInput.toLong()
    }
})
