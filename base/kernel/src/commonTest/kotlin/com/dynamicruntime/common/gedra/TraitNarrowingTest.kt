package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SLM
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.layoutFieldProblems
import com.dynamicruntime.common.schema.narrowingProblems
import com.dynamicruntime.common.schema.overlayType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.resolveExtensions
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A client's narrowing of a global trait (issue #1119) -- hiding in the layout, requiring and restricting choices in
 * the data -- as the alteration it writes, which the narrowing rules admit; read back; and held to its rules. Then an
 * extension's data type: the base plus the fields it adds. In `commonTest`, so the editor's preview and the backend's
 * write agree.
 */
class TraitNarrowingTest {
    private val typeName = "kdr.core.SurveyData"
    private val base = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf(
            "name" to mapOf(SCH.type to SCT.string),
            "topic" to mapOf(
                SCH.type to SCT.string,
                SCH.options to listOf(mapOf(SCH.value to "a", SCH.label to "A"), mapOf(SCH.value to "b", SCH.label to "B")),
                SCH.openOptions to true,
            ),
            "tags" to mapOf(
                SCH.type to SCT.array,
                SCH.items to mapOf(SCH.type to SCT.string, SCH.options to listOf(mapOf(SCH.value to "x"), mapOf(SCH.value to "y"))),
            ),
            "notes" to mapOf(SCH.type to SCT.string),
        ),
        SCH.required to listOf("name"),
    )

    private val narrowing = TraitNarrowing(
        "kdr:survey", hidden = setOf("notes"), required = setOf("topic"), choices = mapOf("topic" to listOf("b"), "tags" to listOf("x")),
    )

    @Test
    fun aNarrowingIsAnAlterationTheRulesAdmit() {
        val alteration = assertNotNull(narrowing.toAlteration(base))
        assertEquals(listOf("name", "topic"), alteration[SCH.required])
        val props = alteration[SCH.properties].toJsonMapOrEmpty()
        assertEquals(setOf("topic", "tags"), props.keys)
        val topic = props["topic"].toJsonMapOrEmpty()
        assertEquals(listOf("b"), topic[SCH.options].toJsonListOfMaps().map { it[SCH.value] })
        // Restricting an open list closes it.
        assertNull(topic[SCH.openOptions])
        val layout = alteration[SCH.layout].toJsonMapOrEmpty()
        assertEquals(SLM.authoritative, layout[SL.mode])
        assertEquals(listOf("name", "topic", "tags"), layout[SL.schemaFields].toJsonListOfMaps().map { it[SL.field] })
        // The rules the load holds every alteration to find nothing to refuse, and the layout is sound on the result.
        assertEquals(emptyList(), narrowingProblems(typeName, base, alteration))
        val merged = overlayType(base, alteration)
        val type = parseSchemaTypes(mapOf(typeName to merged)).getValue(typeName)
        val mergedLayout = collectLayouts(mapOf(typeName to merged)).getValue(typeName)
        assertTrue(layoutFieldProblems(typeName, mergedLayout, type).isEmpty())
        assertEquals(setOf("name", "topic", "tags", "notes"), type.properties.keys)
    }

    @Test
    fun anEmptyNarrowingIsNoAlteration() {
        assertNull(TraitNarrowing("kdr:survey").toAlteration(base))
        // What a stored alteration says beyond the narrowing -- a field's copy -- survives the narrowing being emptied.
        val stored = mapOf(SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "name", SL.label to "Who"))))
        val kept = assertNotNull(TraitNarrowing("kdr:survey").toAlteration(base, stored))
        assertEquals(listOf(mapOf(SL.field to "name", SL.label to "Who")), kept[SCH.layout].toJsonMapOrEmpty()[SL.schemaFields])
        // And hiding a field keeps that copy in the list.
        val hiding = assertNotNull(TraitNarrowing("kdr:survey", hidden = setOf("notes")).toAlteration(base, stored))
        assertEquals(
            mapOf(SL.field to "name", SL.label to "Who"),
            hiding[SCH.layout].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps().first(),
        )
    }

    @Test
    fun aStoredNarrowingReadsBack() {
        val read = traitNarrowingOf("kdr:survey", base, narrowing.toAlteration(base))
        val back = assertNotNull(read.narrowing)
        assertEquals(narrowing.toInfo(), back.toInfo())
        assertEquals(TraitNarrowing("kdr:survey").toInfo(), assertNotNull(traitNarrowingOf("kdr:survey", base, null).narrowing).toInfo())
        // An alteration that restates the fields, or drops one from the data, was written by hand.
        val restated = mapOf(SCH.properties to mapOf("name" to emptyMap<String, Any?>()))
        assertNotNull(traitNarrowingOf("kdr:survey", base, restated).notEditable)
        val dropping = mapOf(SCH.merge to mapOf(SCH.properties to "merge"), SCH.properties to mapOf("notes" to null))
        assertNotNull(traitNarrowingOf("kdr:survey", base, dropping).notEditable)
    }

    @Test
    fun aNarrowingIsHeldToItsRules() {
        assertEquals(emptyList(), traitNarrowingProblems(narrowing, base))
        assertTrue(traitNarrowingProblems(TraitNarrowing("t", hidden = setOf("name")), base).single().contains("required by the trait"))
        assertTrue(traitNarrowingProblems(TraitNarrowing("t", hidden = setOf("notes"), required = setOf("notes")), base).single().contains("both"))
        assertTrue(traitNarrowingProblems(TraitNarrowing("t", choices = mapOf("notes" to listOf("a"))), base).single().contains("no choices"))
        assertTrue(traitNarrowingProblems(TraitNarrowing("t", choices = mapOf("topic" to emptyList())), base).single().contains("at least one"))
        assertTrue(traitNarrowingProblems(TraitNarrowing("t", choices = mapOf("topic" to listOf("z"))), base).single().contains("does not offer"))
        assertTrue(traitNarrowingProblems(TraitNarrowing("t", required = setOf("nosuch")), base).single().contains("no field"))
    }

    @Test
    fun anExtensionIsTheBasePlusWhatItAdds() {
        val design = withKeys(
            TraitDesign(null, "Survey plus", null, listOf(TraitFieldDesign(null, "Name", TraitFieldKind.text, required = true)), "kdr:survey"),
            emptySet(),
            takenFieldKeys = setOf("name", "topic", "tags", "notes"),
        )
        // The base's own key is taken, so the added field gets the next.
        assertEquals("name2", design.fields.single().key)
        val ext = design.toExtension(typeName, listOf("name"))
        assertEquals(typeName, ext[SCH.extends])
        assertEquals(listOf("name", "name2"), ext[SCH.required])
        val resolved = resolveExtensions(mapOf(typeName to base, "client.x.SurveyPlusData" to ext))
        assertTrue(resolved.refused.isEmpty())
        val type = parseSchemaTypes(resolved.defs).getValue("client.x.SurveyPlusData")
        assertEquals(setOf("name", "topic", "tags", "notes", "name2"), type.properties.keys)
        // Read back: the fields it adds, and what it extends.
        val back = assertNotNull(traitExtensionDesignOf("surveyPlus", null, ext, "kdr:survey", listOf("name")).design)
        assertEquals("kdr:survey", back.extendsTrait)
        assertEquals(listOf("name2"), back.fields.map { it.key })
        assertTrue(back.fields.single().required)
        // What it extends never changes.
        val rebased = TraitDesign(back.traitId, back.label, null, back.fields, "kdr:other")
        assertTrue(traitDesignProblems(rebased, back).any { it.contains("extends never changes") })
    }
}
