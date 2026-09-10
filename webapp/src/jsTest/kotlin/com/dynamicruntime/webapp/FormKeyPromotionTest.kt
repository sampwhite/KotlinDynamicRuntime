package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for primary-key promotion (issue #642): [promotableKeys] names the object properties
 * whose key fields the edit form lifts out beside the trait choice -- a single keyed object (a trait's `data`),
 * never a keyed array or a plain object. The render that hoists the fields and hides them below is browser-
 * driven; this pins which fields lift, which is the decision the render rests on.
 */
class FormKeyPromotionTest {
    // A keyed trait data type (year is its primary key), an unkeyed one, and parents that hold each.
    private fun defs(): Map<String, Any?> = mapOf(
        "t.Keyed" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "year" to mapOf(SCH.type to SCT.integer),
                "note" to mapOf(SCH.type to SCT.string),
            ),
            SCH.primaryKey to listOf("year"),
        ),
        "t.Unkeyed" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("note" to mapOf(SCH.type to SCT.string)),
        ),
        // An edit branch shape: `data` is the keyed object, `action` a scalar beside it.
        "t.Branch" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "action" to mapOf(SCH.type to SCT.string),
                "data" to mapOf(SCH.dRef to "t.Keyed"),
            ),
        ),
        "t.UnkeyedBranch" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("data" to mapOf(SCH.dRef to "t.Unkeyed")),
        ),
        // A keyed *array* under `data`: not promotable -- "beside the choice" has no meaning for a list.
        "t.ArrayBranch" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "data" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "t.Keyed")),
            ),
        ),
    )

    private fun type(name: String) = parseSchemaTypes(defs()).getValue(name)

    @Test
    fun promotesAKeyedObjectPropertysKeyFields() {
        assertEquals(mapOf("data" to listOf("year")), promotableKeys(type("t.Branch")))
    }

    @Test
    fun anUnkeyedObjectPropertyDoesNotPromote() {
        assertTrue(promotableKeys(type("t.UnkeyedBranch")).isEmpty())
    }

    @Test
    fun aKeyedArrayPropertyDoesNotPromote() {
        assertTrue(promotableKeys(type("t.ArrayBranch")).isEmpty())
    }

    @Test
    fun theKeyedDataTypeItselfDeclaresItsKey() {
        // Sanity: the key really is on the data type the parent refs, so the promotion has something to read.
        assertEquals(listOf("year"), type("t.Keyed").primaryKey)
    }
}
