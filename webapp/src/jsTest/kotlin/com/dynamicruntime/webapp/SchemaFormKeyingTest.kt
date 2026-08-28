package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage (issue #161) for the keyed-element labeling the forms UI draws (issue #487): a keyed
 * trait's entries read as their primary-key value ("Yearly — 2024") rather than a bare index. The engine is
 * generic -- it keys off [SchType.primaryKey], not any gedra vocabulary -- so these build plain schema maps and
 * ask the two pure helpers ([keyedElementLabel], [elementRowHeader]) for their verdict. No React, no DOM.
 */
class SchemaFormKeyingTest {

    // A union whose discriminator is `kind`: a `yearly` branch whose `data` is a type keyed on `year`, and a
    // `name` branch whose `data` is not keyed. This mirrors a real trait entry (the key lives on `data`'s type,
    // its fields one level down under `data`) without importing the gedra constants the engine itself avoids.
    private fun unionDefs(): Map<String, Any?> = mapOf(
        "t.YearlyData" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("year" to mapOf(SCH.type to SCT.integer), "note" to mapOf(SCH.type to SCT.string)),
            SCH.required to listOf("year"),
            SCH.primaryKey to listOf("year"),
        ),
        "t.NameData" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)),
        ),
        "t.YearlyEntry" to mapOf(
            SCH.type to SCT.kObject,
            SCH.title to "Yearly",
            SCH.properties to mapOf(
                "kind" to mapOf(SCH.type to SCT.string, SCH.const to "yearly"),
                "data" to mapOf(SCH.dRef to "t.YearlyData"),
            ),
        ),
        "t.NameEntry" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "kind" to mapOf(SCH.type to SCT.string, SCH.const to "name"),
                "data" to mapOf(SCH.dRef to "t.NameData"),
            ),
        ),
        "t.Union" to mapOf(
            SCH.oneOf to listOf(mapOf(SCH.dRef to "t.YearlyEntry"), mapOf(SCH.dRef to "t.NameEntry")),
            SCH.discriminator to mapOf(SCH.propertyName to "kind"),
        ),
    )

    private fun union(): SchType = parseSchemaTypes(unionDefs()).getValue("t.Union")

    /** A keyed branch is labeled by the key value read from its `data`; an unkeyed branch has no label. */
    @Test
    fun labelsAKeyedUnionElementByItsKey() {
        val u = union()
        assertEquals("2024", keyedElementLabel(u, mapOf("kind" to "yearly", "data" to mapOf("year" to 2024))))
        // The unkeyed `name` branch: nothing identifies one entry from another, so no key label.
        assertNull(keyedElementLabel(u, mapOf("kind" to "name", "data" to mapOf("name" to "My form"))))
        // A JSON number arrives as a Double on the wire; it still reads as the plain integer, not "2024.0".
        assertEquals("2024", keyedElementLabel(u, mapOf("kind" to "yearly", "data" to mapOf("year" to 2024.0))))
    }

    /** An element type keyed directly (not a union) is labeled by its own key -- a plain keyed object list. */
    @Test
    fun labelsADirectlyKeyedElementType() {
        val yearlyData = parseSchemaTypes(unionDefs()).getValue("t.YearlyData")
        assertEquals("2025", keyedElementLabel(yearlyData, mapOf("year" to 2025)))
        // No key value present: nothing to show (a fresh, empty row).
        assertNull(keyedElementLabel(yearlyData, mapOf("note" to "hi")))
    }

    /** A composite key joins every field, in the order the key declares them. */
    @Test
    fun joinsACompositeKey() {
        val defs = mapOf(
            "t.Keyed" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "client" to mapOf(SCH.type to SCT.string),
                    "year" to mapOf(SCH.type to SCT.integer),
                ),
                SCH.required to listOf("client", "year"),
                SCH.primaryKey to listOf("client", "year"),
            ),
        )
        val keyed = parseSchemaTypes(defs).getValue("t.Keyed")
        assertEquals("acme, 2024", keyedElementLabel(keyed, mapOf("client" to "acme", "year" to 2024)))
        // A half-filled composite key yields no label -- it must not read as complete, or two rows with only
        // their first field entered would collide. The row falls back to its index until the key is whole.
        assertNull(keyedElementLabel(keyed, mapOf("client" to "acme")))
    }

    /** The row header keeps its index and adds the key beside it; an unkeyed element is the bare index. */
    @Test
    fun composesTheRowHeader() {
        val u = union()
        val yearly = mapOf("kind" to "yearly", "data" to mapOf("year" to 2024))
        // The index stays -- a failure named "edits[1].data.year" still points at a row on screen -- with the
        // key added beside it.
        assertEquals("[0] Yearly — 2024", elementRowHeader(u, yearly, 0, friendly = true))
        // Not friendly: the raw discriminator value stands in for the title.
        assertEquals("[0] yearly — 2024", elementRowHeader(u, yearly, 0, friendly = false))
        // The unkeyed branch is the index it always showed.
        assertEquals("[1]", elementRowHeader(u, mapOf("kind" to "name", "data" to mapOf("name" to "x")), 1, friendly = true))
        // A keyed branch whose key is not yet filled also falls back to the bare index.
        assertEquals("[2]", elementRowHeader(u, mapOf("kind" to "yearly", "data" to emptyMap<String, Any?>()), 2, friendly = true))
    }
}
