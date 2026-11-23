package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.PRES
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchGKeywords
import com.dynamicruntime.common.schema.SchKeywordPlace
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.layoutFieldProblems
import com.dynamicruntime.common.schema.parseSchLayout
import com.dynamicruntime.common.schema.parseSchLayoutResult
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.Parsed
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
        assertEquals("ownersEmail", keyFromLabel("Owner’s email", emptySet(), "field"))
        assertEquals("ownersEmail", keyFromLabel("Owner's email", emptySet(), "field"))
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
        // Long text is the schema's mark (issue #1550), on the property, not the layout's.
        assertEquals(listOf(null, true, null, null), props.values.map { it.toJsonMapOrEmpty()[SCH.longText] })
        assertEquals(TraitFieldKind.longText, traitDesignOf("siteSafety", null, schema).design!!.fields[1].kind)
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
        // A counter's field (issue #1849), which would otherwise read as free text.
        val numbered = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf("number" to mapOf(SCH.type to SCT.string, SCH.counter to "receipt")),
        )
        assertNotNull(traitDesignOf("receipt", null, numbered).notEditable)
    }

    /**
     * Every `g-` keyword, decided for the traits editor (issue #1854): one that changes what a field holds maps to a kind
     * or makes the field one the editor refuses, one the editor may keep as written survives a save, and one never on a
     * field the editor designs says why. A keyword added to `SchGKeywords` fails here until somebody decides, since read
     * as text, a field built on it would be offered as free text, as a counter's once was. Theme: #SLTKW.
     */
    @Test
    fun everyKeywordIsDecidedForTheEditor() {
        val text = mapOf(SCH.type to SCT.string)
        // What a field built on the keyword reads as: its kind, or null when the editor refuses it.
        val shaping: Map<String, Pair<Map<String, Any?>, TraitFieldKind?>> = mapOf(
            SCH.options to Pair(text + (SCH.options to listOf(mapOf(SCH.value to "a"))), TraitFieldKind.choice),
            SCH.openOptions to Pair(text + (SCH.options to listOf(mapOf(SCH.value to "a"))) + (SCH.openOptions to true), null),
            SCH.longText to Pair(text + (SCH.longText to true), TraitFieldKind.longText),
            SCH.counter to Pair(text + (SCH.counter to "receipt"), null),
            SCH.optionsSource to Pair(text + (SCH.optionsSource to "clients"), null),
            SCH.fileKinds to Pair(text + (SCH.format to SFMT.dataFile) + (SCH.fileKinds to listOf("png")), null),
            SCH.fileMaxSize to Pair(text + (SCH.format to SFMT.dataFile) + (SCH.fileMaxSize to 1000), null),
            // A table field (issue #1882) is a file field, which the editor refuses as it does any.
            SCH.fileRows to Pair(text + (SCH.format to SFMT.dataFile) + (SCH.fileRows to mapOf(SCH.type to SCT.kObject)), null),
            SCH.fileMaxRows to Pair(text + (SCH.format to SFMT.dataFile) + (SCH.fileMaxRows to 10), null),
            SCH.schemaDocument to Pair(mapOf(SCH.type to SCT.kObject, SCH.schemaDocument to true), null),
        )
        // Kept on a text field as written, since the design owns none of them.
        val kept: Map<String, Any?> = mapOf(
            SCH.allowCoerce to true,
            SCH.emptyIsAbsent to true,
            SCH.visibleOnly to true,
            SCH.outerWhitespace to "trim",
            SCH.derived to true,
            SCH.visibleWhen to "isAdmin",
            SCH.sensitive to true,
            SCH.errors to mapOf("required" to "Say what it is."),
            SCH.presentation to PRES.identifier,
        )
        val elsewhere: Map<String, String> = mapOf(
            SCH.primaryKey to "It keys the items of an array of objects, which the editor never designs.",
            SCH.optionalContents to "It is written on an object field, which the editor never designs.",
        )
        // A keyword written only on a named type is the trait's, not a field's.
        val typeOnly = SchGKeywords.places.filterValues { it == SchKeywordPlace.namedType }.keys

        for (keyword in SchGKeywords.keywords) {
            val decisions = listOf(keyword in shaping, keyword in kept, keyword in elsewhere, keyword in typeOnly).count { it }
            assertEquals(
                1, decisions,
                "Keyword $keyword: decide what the traits editor does with it -- map it to a kind or refuse it in fieldKindOf " +
                    "(TraitDesign.kt), or keep it as written -- and list it once in this test.",
            )
        }
        fun traitOf(field: Map<String, Any?>) = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("f" to field))
        for ((keyword, case) in shaping) {
            val (field, kind) = case
            val read = traitDesignOf("t", null, traitOf(field)).design?.fields?.single()?.kind
            assertEquals(kind, read, "Keyword $keyword: the editor reads its field as $read")
        }
        for ((keyword, value) in kept) {
            val stored = traitOf(text + (keyword to value))
            val design = assertNotNull(traitDesignOf("t", null, stored).design, "Keyword $keyword: the editor refuses its field")
            val saved = design.toDataSchema(stored)[SCH.properties].toJsonMapOrEmpty()["f"].toJsonMapOrEmpty()
            assertEquals(value, saved[keyword], "Keyword $keyword: a save from the editor drops it")
        }
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
    fun aLayoutSizesOnlyLongText() {
        // Issue #1550: `lines` says how tall a long text field's box starts; on any other field it sizes nothing.
        val type = parseSchemaTypes(
            mapOf(
                "t.A" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("n" to mapOf(SCH.type to SCT.string), "s" to mapOf(SCH.type to SCT.string, SCH.longText to true)),
                ),
            ),
        ).getValue("t.A")
        val layout = parseSchLayout("Type 't.A'", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "n", SL.lines to 5), mapOf(SL.field to "s", SL.lines to 6))))
        assertTrue(layoutFieldProblems("Type 't.A'", layout, type).single().message.contains("does not mark it"))
        // And the number round-trips, absent when unset.
        assertEquals(6, layout.fields[1].toJsonMap()[SL.lines])
        val off = parseSchLayout("Type 't.A'", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "s"))))
        assertNull(off.fields.single().toJsonMap()[SL.lines])
        // A box with no lines, or a fraction of one, is refused.
        for (bad in listOf(0, 2.5, "3")) {
            assertTrue(parseSchLayoutResult("Type 't.A'", mapOf(SL.schemaFields to listOf(mapOf(SL.field to "s", SL.lines to bad)))) is Parsed.Failed)
        }
    }
}
