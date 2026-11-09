package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFRAG
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

    /**
     * The row header: where a failure is named by its path, the index with the key beside it, and the bare index for an
     * unkeyed element; on a user-facing form, the key, else a union's branch name, else the entry's heading for its
     * number (issue #1714).
     */
    @Test
    fun composesTheRowHeader() {
        val u = union()
        val yearly = mapOf("kind" to "yearly", "data" to mapOf("year" to 2024))
        val unkeyed = mapOf("kind" to "name", "data" to mapOf("name" to "x"))
        val keyNotFilled = mapOf("kind" to "yearly", "data" to emptyMap<String, Any?>())
        // Not friendly: the index stays -- a failure named "edits[1].data.year" still points at a row on screen -- with
        // the key added beside it, and the raw discriminator value standing in for the title.
        assertEquals("[0] yearly — 2024", elementRowHeader(u, yearly, 0, friendly = false))
        assertEquals("[1]", elementRowHeader(u, unkeyed, 1, friendly = false))
        assertEquals("[2]", elementRowHeader(u, keyNotFilled, 2, friendly = false))
        // Friendly: no path is shown outside debug, so no index. A keyed entry is its key; an entry of a union without
        // one (an edit form's section) is its branch's name; any other is its number, counting from 1.
        assertEquals("Yearly — 2024", elementRowHeader(u, yearly, 0, friendly = true))
        assertEquals("Name", elementRowHeader(u, unkeyed, 1, friendly = true))
        assertEquals("Yearly", elementRowHeader(u, keyNotFilled, 2, friendly = true))
        // A section whose branch is not chosen yet has no name to give.
        assertEquals("Entry 4", elementRowHeader(u, emptyMap(), 3, friendly = true))
        // A plain list's entry, by its number -- or as the field's layout names its entries.
        val speaker = parseSchemaTypes(mapOf("t.Speaker" to mapOf(SCH.type to SCT.kObject))).getValue("t.Speaker")
        assertEquals("Entry 2", elementRowHeader(speaker, mapOf("name" to "Pat"), 1, friendly = true))
        val named = elementRowHeader(speaker, mapOf("name" to "Pat"), 1, friendly = true, entryLabel = $$"Co-speaker ${n}")
        assertEquals("Co-speaker 2", named)
    }

    /** An entry's heading (issue #1714): its number put in the template; a template that fails gives the shipped one. */
    @Test
    fun headsAnEntryByItsNumber() {
        assertEquals("Entry 1", entryHeading(HFRAG.defaultEntryLabel, 0))
        assertEquals("Co-speaker 3", entryHeading($$"Co-speaker ${n}", 2))
        assertEquals("Panelist #10", entryHeading($$"Panelist #${n}", 9))
        // A client's copy may compute on it.
        assertEquals("First speaker", entryHeading($$"${n == 1 ? 'First' : 'Another'} speaker", 0))
        // A client's line that names what it is not given costs the client its words, not the form.
        assertEquals("Entry 2", entryHeading($$"Speaker ${name}", 1))
        // Words with no number are taken as written.
        assertEquals("Speaker", entryHeading("Speaker", 4))
    }

    /** Headings that read alike on a friendly list are numbered apart, the rest left as they are (issue #1714). */
    @Test
    fun numbersApartHeadingsThatReadAlike() {
        assertEquals(listOf("Session", "Talk title"), distinctHeadings(listOf("Session", "Talk title")))
        assertEquals(
            listOf("Questionnaire (1)", "Session", "Questionnaire (2)", "Questionnaire (3)"),
            distinctHeadings(listOf("Questionnaire", "Session", "Questionnaire", "Questionnaire")),
        )
    }
}
