package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GIF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `g-visibleWhen` enforced on trait-data writes (issue #830). Acme's `expenseReport.reviewerNote` is gated on
 * `hasAdminLevel`: an administrator may write it; for anyone else it keeps its stored value -- left out, as a form
 * that hid it sends, or sent back unchanged, as a raw editor does -- and a change to it is refused with a 403. On
 * every write path: create, patch (which the survey's and a workflow's saves go through), and import.
 */
class VisibleWhenWriteTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "visibleWhen830", "visibleWhen830Test", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val create = clientPath(GEP.formDocCreate, SC.acme)
    val patch = clientPath(GEP.patch, SC.acme)
    val get = clientPath(GEP.formDoc, SC.acme)
    val import = clientPath(GEP.formDocImport, SC.acme)

    val owner = TestUser.create(cxt, "gate-owner@acme.test", userClient = SC.acme)
    val admin = TestUser.create(cxt, "gate-admin@acme.test", userClient = SC.acme, level = ROLE.admin)

    fun expense(vararg extra: Pair<String, Any?>) =
        mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf<String, Any?>(ST.year to 2026L) + extra)

    fun expenseData(gid: String): Map<String, Any?> =
        owner.getItem(get, mapOf(GDF.gedraId to gid))[GDF.entries].toJsonListOfMaps()
            .first { it[GE.traitId] == ST.expenseReport }[GE.data].toJsonMapOrEmpty()

    fun patchBody(gid: String, data: Map<String, Any?>?, action: GedraEditAction = GedraEditAction.addOrReplace) =
        mapOf(
            GPF.targets to mapOf(
                GedraDataType.formDoc.name to listOf(
                    mapOf(
                        GDF.gedraId to gid,
                        GPF.edits to listOf(
                            buildMap {
                                put(GED.action, action.name); put(GE.traitId, ST.expenseReport)
                                data?.let { put(GE.data, it) }
                            },
                        ),
                    ),
                ),
            ),
        )

    /** An owner's form holding a note an administrator wrote. */
    fun formWithNote(note: String): String {
        val gid = owner.postItem(create, mapOf(GDF.entries to listOf(expense())))[GDF.gedraId].toOptStr()!!
        admin.postItems(patch, patchBody(gid, mapOf(ST.year to 2026L, ST.reviewerNote to note)))
        expenseData(gid)[ST.reviewerNote] shouldBe note
        return gid
    }

    fun refused(user: TestUser, path: String, body: Map<String, Any?>): String =
        user.expectError(EXC.notAuthorized, path, body)["errorMessage"].toOptStr().orEmpty()

    "an ordinary caller cannot create a form carrying the gated field; an administrator can" {
        refused(owner, create, mapOf(GDF.entries to listOf(expense(ST.reviewerNote to "self-approved"))))
            .shouldContain("'${ST.reviewerNote}' of '${ST.expenseReport}'")
        admin.postItem(create, mapOf(GDF.entries to listOf(expense(ST.reviewerNote to "fine"))))[GDF.gedraId]
            .toOptStr().orEmpty().isNotBlank() shouldBe true
    }

    "a replace that leaves the gated field out keeps the stored value -- the survey form hides it" {
        val gid = formWithNote("Checked by admin")
        owner.postItems(patch, patchBody(gid, mapOf(ST.year to 2026L, ST.itemCount to 3L)))
        expenseData(gid).let {
            it[ST.itemCount] shouldBe 3L
            it[ST.reviewerNote] shouldBe "Checked by admin"
        }
    }

    "a merge, and a raw editor sending the stored value back unchanged, both keep it" {
        val gid = formWithNote("Keep me")
        owner.postItems(patch, patchBody(gid, mapOf(ST.perItemAmount to 12.5), GedraEditAction.addOrMerge))
        owner.postItems(patch, patchBody(gid, mapOf(ST.year to 2026L, ST.itemCount to 2L, ST.reviewerNote to "Keep me")))
        expenseData(gid).let {
            it[ST.itemCount] shouldBe 2L
            it[ST.reviewerNote] shouldBe "Keep me"
        }
    }

    "changing the gated field is refused, nothing of that patch is stored, and a null cannot clear it" {
        val gid = formWithNote("Original")
        refused(owner, patch, patchBody(gid, mapOf(ST.year to 2026L, ST.itemCount to 9L, ST.reviewerNote to "Rewritten")))
            .shouldContain("'${ST.reviewerNote}'")
        // Null is absent, as to the validator: sending it is leaving the field out, so the stored note stays.
        owner.postItems(patch, patchBody(gid, mapOf(ST.reviewerNote to null), GedraEditAction.addOrMerge))
        expenseData(gid).let {
            it[ST.reviewerNote] shouldBe "Original"
            it[ST.itemCount] shouldBe null
        }
        // An administrator passes the gate, so their change lands.
        admin.postItems(patch, patchBody(gid, mapOf(ST.year to 2026L, ST.reviewerNote to "Revised")))
        expenseData(gid)[ST.reviewerNote] shouldBe "Revised"
    }

    "an import carrying the gated field is refused whole, forgiving or not" {
        val doc = mapOf(GDF.entries to listOf(expense(ST.reviewerNote to "imported")))
        refused(owner, import, mapOf(GIF.data to doc))
        refused(owner, import, mapOf(GIF.data to doc, GIF.forgiveInvalidEntries to true))
        admin.postData(import, mapOf(GIF.data to doc))[GIF.imported].toJsonListOfMaps().size shouldBe 1
    }
})
