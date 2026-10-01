package com.dynamicruntime.common.gedra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The keyed-edit core a data patch and a config patch share (issue #909, phase D): the three actions, and the
 * addressing rule. In `commonTest`, so it holds on the JVM and under Kotlin/JS alike.
 */
class KeyedEditTest {
    private val stored = mapOf("year" to 2024L, "note" to "old", "kept" to true)

    @Test
    fun eachActionDoesWhatItsNameSays() {
        val supplied = mapOf<String, Any?>("year" to 2024L, "note" to "new")
        assertEquals(KeyedEdit.Remove, GedraEditAction.deleteOrNoOp.applyTo(stored, emptyMap()))
        assertEquals(KeyedEdit.NoOp, GedraEditAction.deleteOrNoOp.applyTo(null, emptyMap()))
        assertEquals(supplied, assertIs<KeyedEdit.Put>(GedraEditAction.addOrReplace.applyTo(stored, supplied)).data)
        // A merge folds the supplied keys over the stored ones and keeps the rest.
        assertEquals(
            mapOf("year" to 2024L, "note" to "new", "kept" to true),
            assertIs<KeyedEdit.Put>(GedraEditAction.addOrMerge.applyTo(stored, supplied)).data,
        )
        // Against no entry, both adds create it from what was supplied.
        assertEquals(supplied, assertIs<KeyedEdit.Put>(GedraEditAction.addOrMerge.applyTo(null, supplied)).data)
    }

    @Test
    fun anEditNamesItsEntryByEveryKeyField() {
        val pk = listOf("year", "region")
        assertEquals(listOf("region"), missingKeyFields(pk, mapOf("year" to 2024L)))
        assertEquals(pk, missingKeyFields(pk, null))
        assertTrue(missingKeyFields(emptyList(), null).isEmpty())
        // Key values compare as `canonicalKey` does, so 2024 and 2024.0 name the same entry.
        assertTrue(addressesEntry(listOf("year"), stored, mapOf("year" to 2024.0)))
        assertFalse(addressesEntry(listOf("year"), stored, mapOf("year" to 2025L)))
        // No key: the one entry there is.
        assertTrue(addressesEntry(emptyList(), stored, emptyMap()))
    }
}
