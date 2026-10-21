package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.layoutFieldProblems
import com.dynamicruntime.common.schema.parseSchLayout
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A client's own trait as the traits editor designs it (issue #1118): keys made once from labels, the schema a design
 * becomes and what it carries over from the stored one, a stored trait read back as a design, and the rules a design
 * is held to. In `commonTest`, so the editor's preview and the backend's write agree.
 */
class TraitDesignTest {
    private fun field(label: String, kind: TraitFieldKind, key: String? = null, vararg choices: String, required: Boolean = false) =
        TraitFieldDesign(key, label, kind, required = required, choices = choices.map { TraitChoiceDesign(null, it) })

    private val design = TraitDesign(
        null, "Site safety", "How a site is kept safe.",
        listOf(
            field("Do you inspect weekly?", TraitFieldKind.yesNo, required = true),
            field("Notes", TraitFieldKind.longText),
            field("Hazards", TraitFieldKind.choices, null, "Falls", "Fire", "Fire!"),
            field("Inspected on", TraitFieldKind.date),
        ),
    )

    @Test
    fun aKeyIsMadeOnceFromItsLabel() {
        assertEquals("doYouSmoke", keyFromLabel("Do you smoke?", emptySet(), "field"))
        assertEquals("notes2", keyFromLabel("Notes", setOf("notes"), "field"))
        assertEquals("notes3", keyFromLabel("notes", setOf("notes", "notes2"), "field"))
        // Nothing usable, or a leading digit: the fallback carries it.
        assertEquals("field", keyFromLabel("???", emptySet(), "field"))
        assertEquals("field2024Plans", keyFromLabel("2024 plans", emptySet(), "field"))
        assertEquals(maxKeyLength, keyFromLabel("a".repeat(60), emptySet(), "field").length)
    }

    @Test
    fun aDesignGetsItsKeysAndKeepsTheOnesItHas() {
        val keyed = withKeys(design, setOf("siteSafety"))
        assertEquals("siteSafety2", keyed.traitId)
        assertEquals(listOf("doYouInspectWeekly", "notes", "hazards", "inspectedOn"), keyed.fields.map { it.key })
        // Two choices that read alike are two values.
        assertEquals(listOf("falls", "fire", "fire2"), keyed.fields[2].choices.map { it.value })
        // A relabel keeps the key.
        val relabeled = TraitDesign(keyed.traitId, "Safety", null, listOf(TraitFieldDesign("notes", "Remarks", TraitFieldKind.text)))
        assertEquals("notes", withKeys(relabeled, emptySet()).fields.single().key)
    }

    @Test
    fun aDesignBecomesASchemaWithItsLabelsInTheLayout() {
        val schema = withKeys(design, emptySet()).toDataSchema()
        val props = schema[SCH.properties].toJsonMapOrEmpty()
        assertEquals(SCT.boolean, props["doYouInspectWeekly"].toJsonMapOrEmpty()[SCH.type])
        assertEquals(SFMT.date, props["inspectedOn"].toJsonMapOrEmpty()[SCH.format])
        val hazards = props["hazards"].toJsonMapOrEmpty()
        assertEquals(SCT.array, hazards[SCH.type])
        assertEquals(3, hazards[SCH.items].toJsonMapOrEmpty()[SCH.options].toJsonListOfMaps().size)
        assertEquals(listOf("doYouInspectWeekly"), schema[SCH.required])
        val layout = parseSchLayout("Type 'x.SiteSafetyData'", schema[SCH.layout].toJsonMapOrEmpty())
        assertEquals("Site safety", layout.label)
        assertEquals(listOf("Do you inspect weekly?", "Notes", "Hazards", "Inspected on"), layout.fields.map { it.label })
        assertEquals(listOf(false, true, false, false), layout.fields.map { it.multiline })
        // And it parses, and its layout is sound against it.
        val type = parseSchemaTypes(mapOf("x.SiteSafetyData" to schema)).getValue("x.SiteSafetyData")
        assertTrue(layoutFieldProblems("Type 'x.SiteSafetyData'", layout, type).isEmpty())
        assertNotNull(collectLayouts(mapOf("x.SiteSafetyData" to schema))["x.SiteSafetyData"])
    }

    @Test
    fun anEditCarriesWhatTheDesignDoesNotOwn() {
        val keyed = withKeys(design, emptySet())
        val stored = keyed.toDataSchema().toMutableMap()
        // What Design View or a hand adds: a field keyword, a layout hint, a form's restated choices, a form string.
        val props = stored[SCH.properties].toJsonMapOrEmpty().toMutableMap()
        props["notes"] = props["notes"].toJsonMapOrEmpty() + (SCH.maxLength to 500)
        stored[SCH.properties] = props
        val layout = stored[SCH.layout].toJsonMapOrEmpty().toMutableMap()
        layout[SL.schemaFields] = layout[SL.schemaFields].toJsonListOfMaps().map {
            when (it[SL.field]) {
                "notes" -> it + (SL.hint to "Anything else.")
                "hazards" -> it + (SL.choices to listOf(mapOf(SL.value to "fire"), mapOf(SL.value to "falls")))
                else -> it
            }
        }
        layout[SL.strings] = mapOf("formErrorHint" to "Check it.")
        stored[SCH.layout] = layout

        // Relabel a field, drop the "fire" choice, drop the date.
        val edited = TraitDesign(
            keyed.traitId, keyed.label, keyed.description,
            listOf(
                keyed.fields[0],
                TraitFieldDesign("notes", "Remarks", TraitFieldKind.longText),
                TraitFieldDesign("hazards", "Hazards", TraitFieldKind.choices, choices = keyed.fields[2].choices.filter { it.value != "fire" }),
            ),
        ).toDataSchema(stored)
        assertEquals(500, edited[SCH.properties].toJsonMapOrEmpty()["notes"].toJsonMapOrEmpty()[SCH.maxLength])
        assertNull(edited[SCH.properties].toJsonMapOrEmpty()["inspectedOn"])
        val after = edited[SCH.layout].toJsonMapOrEmpty()
        assertEquals(mapOf("formErrorHint" to "Check it."), after[SL.strings])
        val entries = after[SL.schemaFields].toJsonListOfMaps().associateBy { it[SL.field] }
        assertEquals("Remarks", entries["notes"]?.get(SL.label))
        assertEquals("Anything else.", entries["notes"]?.get(SL.hint))
        // A form's restated choices keep only choices there are.
        assertEquals(listOf(mapOf(SL.value to "falls")), entries["hazards"]?.get(SL.choices))
        assertNull(entries["inspectedOn"])
    }

    @Test
    fun aStoredTraitReadsBackAsItsDesign() {
        val keyed = withKeys(design, emptySet())
        val read = traitDesignOf("siteSafety", "How a site is kept safe.", keyed.toDataSchema())
        val back = assertNotNull(read.design)
        assertEquals(keyed.toInfo() + (CIT.traitId to "siteSafety"), back.toInfo())
        // A field not of the editor's kinds makes the trait one it cannot edit.
        val nested = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("address" to mapOf(SCH.type to SCT.kObject, SCH.properties to emptyMap<String, Any?>())),
        )
        assertNotNull(traitDesignOf("place", null, nested).notEditable)
        assertNotNull(traitDesignOf("shared", null, mapOf(SCH.dRef to $$"#/$defs/x.Shared")).notEditable)
    }

    @Test
    fun aDesignIsHeldToItsRules() {
        val keyed = withKeys(design, emptySet())
        assertEquals(emptyList(), traitDesignProblems(keyed))
        val noChoices = TraitDesign("t", "T", null, listOf(TraitFieldDesign("c", "C", TraitFieldKind.choice)))
        assertTrue(traitDesignProblems(noChoices).single().contains("at least one choice"))
        assertTrue(traitDesignProblems(TraitDesign("t", " ", null, emptyList())).containsAll(
            listOf("The trait needs a name.", "The trait needs at least one field."),
        ))
        // A kept field keeps its kind -- text and long text excepted.
        val retyped = TraitDesign(keyed.traitId, keyed.label, null, listOf(TraitFieldDesign("notes", "Notes", TraitFieldKind.number)))
        assertTrue(traitDesignProblems(retyped, keyed).single().contains("never changes"))
        val shortened = TraitDesign(keyed.traitId, keyed.label, null, listOf(TraitFieldDesign("notes", "Notes", TraitFieldKind.text)))
        assertEquals(emptyList(), traitDesignProblems(shortened, keyed))
        // And the trait keeps its id.
        val renamed = TraitDesign("other", keyed.label, null, keyed.fields)
        assertTrue(traitDesignProblems(renamed, keyed).single().contains("never changes"))
    }

    @Test
    fun longTextIsForPlainTextOnly() {
        val type = parseSchemaTypes(
            mapOf("t.A" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("n" to mapOf(SCH.type to SCT.integer)))),
        ).getValue("t.A")
        val layout = parseSchLayout("Type 't.A'", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "n", SL.multiline to true))))
        assertTrue(layoutFieldProblems("Type 't.A'", layout, type).single().message.contains("not a plain text field"))
        // And the mark round-trips, absent when off.
        assertEquals(true, layout.fields.single().toJsonMap()[SL.multiline])
        val off = parseSchLayout("Type 't.A'", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "n"))))
        assertNull(off.fields.single().toJsonMap()[SL.multiline])
    }
}
