package com.dynamicruntime.sample.gedra

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
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import com.dynamicruntime.common.endpoint.EP

/**
 * A trait with a **primary key** (issue #487): several entries of one trait on one form document, told apart by
 * a key drawn from their data. The sample `yearly` trait keys on `year`, which is *numeric* -- the case the
 * issue names, since a primary key is not always a string.
 *
 * The point that the whole feature rests on is the second test: two entries that would share a key are refused,
 * because nothing could address them afterward. The rest show the ordinary lifecycle -- coexist, add, edit one
 * without touching its neighbour, and delete one by naming its key.
 */
class YearlyTraitTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("yearlyTrait", "yearlyTraitTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()))
    val alice = TestUser.create(cxt, "alice@yearly.test")

    fun yearly(year: Int, note: String? = null): Map<String, Any?> =
        mapOf(GE.traitId to ST.yearly, GE.data to buildMap { put(ST.year, year); if (note != null) put(ST.note, note) })

    fun nameEntry(name: String) = mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name))

    fun edit(action: GedraEditAction, traitId: String, data: Map<String, Any?>? = null): Map<String, Any?> =
        buildMap {
            put(GED.action, action.name)
            put(GE.traitId, traitId)
            if (data != null) put(GE.data, data)
        }

    fun patch(id: String, edits: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf(GPF.targets to mapOf(GedraDataType.formDoc.name to listOf(mapOf(GDF.gedraId to id, GPF.edits to edits))))

    fun create(vararg entries: Map<String, Any?>): String =
        alice.postItem(GEP.formDocCreate, mapOf(GDF.entries to entries.toList()))[GDF.gedraId].toOptStr().shouldNotBeNull()

    /** The `yearly` entries on [id], keyed by their (numeric) year, so a test can assert one without the others. */
    fun byYear(id: String): Map<Int, Map<String, Any?>> =
        alice.getItem(GEP.formDoc, mapOf(GDF.gedraId to id))[GDF.entries].toJsonListOfMaps()
            .filter { it[GE.traitId].toOptStr() == ST.yearly }
            .associateBy { (it[GE.data].toJsonMapOrEmpty()[ST.year] as Number).toInt() }

    fun noteOf(entry: Map<String, Any?>): String? = entry[GE.data].toJsonMapOrEmpty()[ST.note].toOptStr()

    "several entries of one trait coexist, told apart by their key" {
        val id = create(yearly(2023, "first"), yearly(2024, "second"))
        val entries = byYear(id)
        entries.keys shouldContainExactlyInAnyOrder listOf(2023, 2024)
        noteOf(entries.getValue(2023)) shouldBe "first"
        noteOf(entries.getValue(2024)) shouldBe "second"
    }

    "two entries that would share a key are refused" {
        val env = alice.expectError(
            400, GEP.formDocCreate,
            mapOf(GDF.entries to listOf(yearly(2024, "a"), yearly(2024, "b"))),
        )
        env[EP.errorMessage].toOptStr().orEmpty() shouldContain "primary key"
    }

    "an add, an edit of one, and a delete by key each name the right entry" {
        val id = create(yearly(2023, "a"), yearly(2024, "b"))

        // Add a third year.
        alice.postItems(GEP.patch, patch(id, listOf(edit(GedraEditAction.addOrReplace, ST.yearly, mapOf(ST.year to 2025, ST.note to "c")))))
        byYear(id).keys shouldContainExactlyInAnyOrder listOf(2023, 2024, 2025)

        // Replace 2024 only; 2023 must be untouched.
        alice.postItems(GEP.patch, patch(id, listOf(edit(GedraEditAction.addOrReplace, ST.yearly, mapOf(ST.year to 2024, ST.note to "b2")))))
        val edited = byYear(id)
        noteOf(edited.getValue(2024)) shouldBe "b2"
        noteOf(edited.getValue(2023)) shouldBe "a"

        // Delete 2024 by naming its key in the edit's data; the others remain.
        alice.postItems(GEP.patch, patch(id, listOf(edit(GedraEditAction.deleteOrNoOp, ST.yearly, mapOf(ST.year to 2024)))))
        byYear(id).keys shouldContainExactlyInAnyOrder listOf(2023, 2025)
    }

    "one patch may edit several entries of one keyed trait at once" {
        val id = create(yearly(2023, "a"))
        // Two edits naming the same trait but different keys, in one patch -- what the add-row UI submits when
        // a user changes several years before saving. The old one-edit-per-trait guard would have refused this.
        alice.postItems(
            GEP.patch,
            patch(
                id,
                listOf(
                    edit(GedraEditAction.addOrReplace, ST.yearly, mapOf(ST.year to 2024, ST.note to "b")),
                    edit(GedraEditAction.addOrReplace, ST.yearly, mapOf(ST.year to 2025, ST.note to "c")),
                ),
            ),
        )
        byYear(id).keys shouldContainExactlyInAnyOrder listOf(2023, 2024, 2025)
    }

    "one patch may not name the same keyed entry twice" {
        val id = create(yearly(2023, "a"))
        // Same trait *and* same key: still contradictory, so still refused.
        val env = alice.expectError(
            400, GEP.patch,
            patch(
                id,
                listOf(
                    edit(GedraEditAction.addOrReplace, ST.yearly, mapOf(ST.year to 2024, ST.note to "b")),
                    edit(GedraEditAction.addOrReplace, ST.yearly, mapOf(ST.year to 2024, ST.note to "c")),
                ),
            ),
        )
        env[EP.errorMessage].toOptStr().orEmpty() shouldContain "same 'yearly' entry"
    }

    "a trait with no primary key still holds only one entry" {
        // The single-instance rule is unchanged for an unkeyed trait: two `name` entries have no key to tell
        // them apart, so the pair is refused exactly as before.
        alice.expectError(400, GEP.formDocCreate, mapOf(GDF.entries to listOf(nameEntry("A"), nameEntry("B"))))
    }
})
