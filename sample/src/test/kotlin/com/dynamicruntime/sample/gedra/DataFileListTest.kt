package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DFSTAT
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.sample.SampleTestInstances
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The listing of data files (issue #1863), on the sample client globex, whose `sample:siteMedia` trait holds files: a
 * user's own, an administrator's whole client, none of another client's, each with how many live forms use it.
 */
class DataFileListTest : StringSpec({
    val cxt = SampleTestInstances.default("fileList")
    val pat = TestUser.create(cxt, "pat@list1863.globex.test", userClient = SC.globex)
    val sam = TestUser.create(cxt, "sam@list1863.globex.test", userClient = SC.globex)
    val chief = TestUser.create(cxt, "chief@list1863.globex.test", level = ROLE.admin, userClient = SC.globex)
    val outsider = TestUser.create(cxt, "pat@list1863.acme.test", userClient = SC.acme)

    fun png(seed: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(200) { (it * seed).toByte() }

    fun upload(user: TestUser, name: String, bytes: ByteArray): String {
        val args = mapOf(DFILE.name to name, DFILE.mimeType to "image/png")
        val sent = user.client.sendRawBodyRequest(DFILE.uploadPath, args, bytes).rptResponseData?.jsonMap().shouldNotBeNull()
        sent[EP.status] shouldBe null
        return sent[EP.results].toJsonMapOrEmpty()[DFILE.fileId].toOptStr().shouldNotBeNull()
    }

    /** The whole envelope of a listing, as [user] asks it. */
    fun list(user: TestUser, vararg args: Pair<String, Any?>): Map<String, Any?> =
        user.client.sendGetRequest(DFILE.listPath, mapOf(*args)).rptResponseData?.jsonMap().shouldNotBeNull()

    fun rows(user: TestUser, vararg args: Pair<String, Any?>): List<Map<String, Any?>> = user.getItems(DFILE.listPath, mapOf(*args))

    val used = upload(pat, "used.png", png(3))
    val spare = upload(pat, "spare.png", png(5))
    val sams = upload(sam, "sams.png", png(7))
    val created = pat.client.sendEditRequest(
        clientPath(GEP.formDocCreate, SC.globex), null,
        mapOf(GDF.entries to listOf(mapOf(GE.traitId to ST.siteMedia, GE.data to mapOf(ST.photo to used)))), HttpMethod.POST,
    ).rptResponseData?.jsonMap().shouldNotBeNull()
    created[EP.status] shouldBe null

    "a user lists their own files, each with how many live forms use it and who uploaded it" {
        val mine = rows(pat).associateBy { it[DFILE.fileId] }
        mine.keys shouldContainExactlyInAnyOrder listOf(used, spare)
        mine.getValue(used)[DFILE.forms] shouldBe 1L
        mine.getValue(spare)[DFILE.forms] shouldBe 0L
        mine.getValue(used)[DFILE.name] shouldBe "used.png"
        mine.getValue(used)[DFILE.status] shouldBe DFSTAT.ready
        mine.getValue(used)[DFILE.ownerId] shouldBe pat.userId
    }

    "a listing filters by whether a form uses a file, by kind, and by status" {
        rows(pat, DFILE.linked to false).map { it[DFILE.fileId] } shouldBe listOf(spare)
        rows(pat, DFILE.linked to true).map { it[DFILE.fileId] } shouldBe listOf(used)
        rows(pat, DFILE.kind to FKIND.pdf) shouldBe emptyList()
        rows(pat, DFILE.status to DFSTAT.uploading) shouldBe emptyList()
    }

    "an administrator lists the client's files, everyone's; another client's user lists none of them" {
        rows(chief).map { it[DFILE.fileId] } shouldContainExactlyInAnyOrder listOf(used, spare, sams)
        val ids = rows(outsider).map { it[DFILE.fileId] }
        for (id in listOf(used, spare, sams)) ids shouldNotContain id
    }

    "a listing grows a batch at a time, newest first, and says how many there are" {
        val page = list(chief, EP.limit to 2)
        (page[EP.items] as List<*>).size shouldBe 2
        page[EP.numAvailable] shouldBe 3L
        page[EP.hasMore] shouldBe true
        // Most recently written first: Sam's was the last uploaded.
        (page[EP.items] as List<*>).first().toJsonMapOrEmpty()[DFILE.fileId] shouldBe sams
    }
})
