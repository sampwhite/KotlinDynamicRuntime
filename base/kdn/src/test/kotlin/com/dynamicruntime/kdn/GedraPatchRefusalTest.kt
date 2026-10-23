package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A refusal partway through a patch of several targets (issue #1307): each target applies in a transaction of its own,
 * in order, so the refusal says which landed, which it is for, and which were never tried. The refusal used is a stale
 * `entryId` on the middle target, one of the checks only the apply phase makes.
 */
class GedraPatchRefusalTest : StringSpec({
    val cxt = TestInstances.default("gedraPatchRefusal")
    val user = TestUser.create(cxt, "patch-refusal@gedra.test", userClient = CL.hub)

    fun newForm(name: String): String = user.postItem(
        GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to name)))),
    )[GDF.gedraId].toOptStr()!!

    fun nameOf(gedraId: String): Any? = user.getItem(GEP.formDoc, mapOf(GDF.gedraId to gedraId))[GDF.entries]
        .toJsonListOfMaps().single { it[GE.traitId] == GT.name }[GE.data].toJsonMapOrEmpty()[GT.nameField]

    /** A rename of [gedraId]; with [staleEntryId], one that names an entry the form does not hold. */
    fun rename(gedraId: String, name: String, staleEntryId: String? = null): Map<String, Any?> = mapOf(
        GDF.gedraId to gedraId,
        GPF.edits to listOf(
            listOfNotNull(
                GED.action to GedraEditAction.addOrReplace.name, GE.traitId to GT.name,
                GE.data to mapOf(GT.nameField to name), staleEntryId?.let { GE.entryId to it },
            ).toMap(),
        ),
    )

    fun patch(vararg targets: Map<String, Any?>) = mapOf(GPF.targets to mapOf(GedraDataType.formDoc.name to targets.toList()))

    "a refusal partway through says which targets landed, which it is for, and which were never tried" {
        val (a, b, c) = listOf(newForm("A"), newForm("B"), newForm("C"))
        val refusal = user.expectError(
            EXC.badInput, GEP.patch, patch(rename(a, "A2"), rename(b, "B2", staleEntryId = "stale-entry"), rename(c, "C2")),
        )
        val extra = refusal[EP.extraData].toJsonMapOrEmpty()
        extra[GPF.committed].toJsonListOfMaps().map { it[GDF.gedraId] } shouldBe listOf(a)
        extra[GPF.committed].toJsonListOfMaps().single()[GPF.outcomes].toJsonListOrEmpty().size shouldBe 1
        extra[GPF.refused] shouldBe b
        extra[GPF.notAttempted].toJsonListOrEmpty() shouldBe listOf(c)
        // As it says: the first landed, the second and third did not.
        nameOf(a) shouldBe "A2"
        nameOf(b) shouldBe "B"
        nameOf(c) shouldBe "C"
    }

    "a patch of one target is refused as before, with nothing added" {
        val one = newForm("Solo")
        val extra = user.expectError(EXC.badInput, GEP.patch, patch(rename(one, "Solo2", staleEntryId = "stale-entry")))[EP.extraData]
            .toJsonMapOrEmpty()
        extra.keys.intersect(setOf(GPF.committed, GPF.refused, GPF.notAttempted)) shouldBe emptySet()
    }
})
