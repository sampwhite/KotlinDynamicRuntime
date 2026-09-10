package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for the edit form's inline completeness check (issue #662). An `addOrReplace` replaces an
 * entry whole, so its data must be complete; [editDataCompletenessFailures] validates it against the trait's
 * data *type* (not the optionalContents edit property, so `required` bites) and re-paths the failures to the
 * edit's place in the target, so the form marks the field instead of the server rejecting it form-level. A
 * merge (partial by design) and a delete (key only) are left to the server. Also pins that a branch switch in a
 * data-entry form seeds the new trait's `data` so its fields show.
 */
class EditCompletenessTest {
    // A one-target patch shape: { gedraId, edits: [ <edit union> ] }, with a keyed `yearly` and a `questionnaire`
    // whose data requires `topic`. Mirrors formDocPatchTargetType's result.
    private fun defs(): Map<String, Any?> = mapOf(
        "t.QData" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "topic" to mapOf(SCH.type to SCT.string),
                "notes" to mapOf(SCH.type to SCT.string),
            ),
            SCH.required to listOf("topic"),
        ),
        "t.QEdit" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                GE.traitId to mapOf(SCH.type to SCT.string, SCH.const to "questionnaire"),
                GED.action to mapOf(SCH.type to SCT.string),
                GE.data to mapOf(SCH.dRef to "t.QData"),
            ),
        ),
        "t.EditUnion" to mapOf(
            SCH.oneOf to listOf(mapOf(SCH.dRef to "t.QEdit")),
            SCH.discriminator to mapOf(SCH.propertyName to GE.traitId),
        ),
        "t.Target" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "gedraId" to mapOf(SCH.type to SCT.string),
                GPF.edits to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "t.EditUnion")),
            ),
        ),
    )

    private val target: SchType get() = parseSchemaTypes(defs()).getValue("t.Target")

    private fun edit(action: String, data: Map<String, Any?>?) = buildMap {
        put(GE.traitId, "questionnaire")
        put(GED.action, action)
        if (data != null) put(GE.data, data)
    }

    private fun targetValues(vararg edits: Map<String, Any?>) = mapOf(GPF.edits to edits.toList())

    @Test
    fun addOrReplaceWithMissingRequiredIsFlaggedAtTheField() {
        val failures = editDataCompletenessFailures(
            target, targetValues(edit(GedraEditAction.addOrReplace.name, emptyMap())),
        )
        // Data is present (an empty object, as a switch seeds it) so its fields are on screen: the missing
        // `topic` is pathed to that field, the same path space the form walks.
        assertEquals(listOf("${GPF.edits}[0].${GE.data}.topic"), failures.map { it.path })
    }

    @Test
    fun addOrReplaceWithAbsentDataFlagsTheDataRow() {
        // Absent data (the user removed the section's data) renders collapsed, so the failure lands on the data
        // field itself -- not on hidden child fields, which would mark nothing (issue #662 review).
        val failures = editDataCompletenessFailures(
            target, targetValues(edit(GedraEditAction.addOrReplace.name, null)),
        )
        assertEquals(listOf("${GPF.edits}[0].${GE.data}"), failures.map { it.path })
    }

    @Test
    fun addOrReplaceWithCompleteDataIsClean() {
        val failures = editDataCompletenessFailures(
            target, targetValues(edit(GedraEditAction.addOrReplace.name, mapOf("topic" to "Onboarding"))),
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun onlyMissingRequiredIsAdded_notADuplicateOfCheckInput() {
        // A wrong type is already reported by checkInput; the completeness pass must not report it again. Here
        // `topic` is present (so not missing) but the wrong type -- completeness contributes nothing.
        val failures = editDataCompletenessFailures(
            target, targetValues(edit(GedraEditAction.addOrReplace.name, mapOf("topic" to 5))),
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aMergeIsLeftToTheServer() {
        // addOrMerge is partial by design (folds over the stored entry), so the client does not demand completeness.
        val failures = editDataCompletenessFailures(
            target, targetValues(edit(GedraEditAction.addOrMerge.name, emptyMap())),
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aDeleteIsLeftAlone() {
        val failures = editDataCompletenessFailures(
            target, targetValues(edit(GedraEditAction.deleteOrNoOp.name, null)),
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aSwitchInADataEntryFormSeedsTheNewBranchsData() {
        val union = parseSchemaTypes(defs()).getValue("t.EditUnion")
        val to = union.variants!!.select("questionnaire")
        // Switching into questionnaire with seeding on: `data` is present (empty), so the form expands its fields.
        val seeded = valuesAfterBranchSwitch(
            mapOf(GE.traitId to "yearly"), GE.traitId, from = null, to = to, picked = "questionnaire",
            seedObjects = true,
        )
        assertEquals(emptyMap<String, Any?>(), seeded[GE.data])
        // Off (the default, e.g. the wire-documenting catalog): `data` stays absent.
        val bare = valuesAfterBranchSwitch(
            mapOf(GE.traitId to "yearly"), GE.traitId, from = null, to = to, picked = "questionnaire",
        )
        assertTrue(bare[GE.data] == null)
    }
}
