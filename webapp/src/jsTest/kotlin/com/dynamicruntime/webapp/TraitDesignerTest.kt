package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.TraitChoiceDesign
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the traits editor (issue #1118): what each change does to the draft, which
 * kinds a saved field may take, what the draft is held to, the plan's limits as the editor says them, and the
 * preview's schema.
 */
class TraitDesignerTest {
    private val saved = TraitDesign(
        "siteSafety", "Site safety", null,
        listOf(
            TraitFieldDesign("notes", "Notes", TraitFieldKind.text),
            TraitFieldDesign("hazards", "Hazards", TraitFieldKind.choice, choices = listOf(TraitChoiceDesign("fire", "Fire"))),
        ),
    )

    @Test
    fun changesMakeTheDraftTheyName() {
        val draft = newTraitDraft()
        assertEquals(1, draft.fields.size)
        val two = draft.withNewField()
        assertEquals(2, two.fields.size)
        val labeled = two.withField(1, two.fields[1].withLabel("Second"))
        assertEquals(listOf("Second", ""), labeled.withFieldMoved(0, 1).fields.map { it.label })
        assertEquals(listOf("Second", ""), labeled.withFieldMoved(1, -1).fields.map { it.label })
        // Moving past either end changes nothing.
        assertEquals(labeled, labeled.withFieldMoved(1, 5))
        assertEquals(1, labeled.withoutField(0).fields.size)
        assertEquals(null, labeled.withHeading("Name", " ").description)
    }

    @Test
    fun aChoiceFieldKeepsItsChoicesAndOthersHaveNone() {
        val text = TraitFieldDesign(null, "T", TraitFieldKind.text)
        // Becoming a choice field starts it with one empty choice.
        assertEquals(1, text.withKind(TraitFieldKind.choice).choices.size)
        val choices = saved.fields[1].withKind(TraitFieldKind.choices)
        assertEquals(listOf("fire"), choices.choices.map { it.value })
        assertTrue(choices.withKind(TraitFieldKind.number).choices.isEmpty())
    }

    @Test
    fun aSavedFieldTakesOnlyKindsThatKeepItsData() {
        assertEquals(listOf(TraitFieldKind.text, TraitFieldKind.longText), kindsFor(saved.fields[0], saved))
        assertEquals(listOf(TraitFieldKind.choice), kindsFor(saved.fields[1], saved))
        assertEquals(TraitFieldKind.entries, kindsFor(TraitFieldDesign(null, "New", TraitFieldKind.text), saved))
    }

    @Test
    fun theDraftIsHeldToTheKernelsRules() {
        assertTrue(draftProblems(newTraitDraft(), null).containsAll(listOf("The trait needs a name.", "Every field needs a label.")))
        assertEquals(emptyList(), draftProblems(saved, saved))
        assertFalse(draftChanged(saved, saved))
        assertTrue(draftChanged(saved.withHeading("Safety", ""), saved))
        assertTrue(draftChanged(newTraitDraft(), null))
    }

    @Test
    fun thePlansLimitsAreSaidAndHeld() {
        val quotas = TraitQuotasView("free", schemasUsed = 3, schemasLimit = 10, attributesLimit = 10)
        assertEquals("The free plan: 2 of 10 fields; 4 of 10 schemas with this one.", draftQuotaText(quotas, 2, isNew = true))
        assertEquals("The free plan: 2 of 10 fields.", draftQuotaText(quotas, 2, isNew = false))
        assertNull(draftQuotaText(null, 2, isNew = true))
        assertNull(addFieldRefusal(quotas, 9))
        assertNotNull(addFieldRefusal(quotas, 10))
        assertNull(newTraitRefusal(quotas))
        assertNotNull(newTraitRefusal(TraitQuotasView("free", 10, 10, 10)))
        assertEquals(2, draftAttributeCount(saved))
    }

    @Test
    fun thePreviewIsTheSchemaTheBackendWillWrite() {
        val draft = TraitDesign(null, "Safety", null, listOf(TraitFieldDesign(null, "Your notes", TraitFieldKind.longText)))
        val defs = previewDefs(draft)
        val type = parseSchemaTypes(defs).getValue(previewTypeName)
        assertEquals(setOf("yourNotes"), type.properties.keys)
        val layout = collectLayouts(defs).getValue(previewTypeName)
        assertEquals("Your notes", layout.fields.single().label)
        assertTrue(layout.fields.single().multiline)
    }

    @Test
    fun theRequestSaysHowItTakesEffect() {
        val request = traitDesignRequest("acme", saved, EDM.draft, acknowledge = false)
        assertEquals("acme", request[CLD.client])
        assertEquals(EDM.draft, request[CPY.mode])
        assertEquals(saved.toInfo(), request[CIT.design])
        val result = parseTraitDesignResult(mapOf(CIT.configName to "trait_siteSafety", CIT.design to saved.toInfo(), CPY.mode to EDM.live))
        assertEquals("trait_siteSafety", result.configName)
        assertEquals("siteSafety", result.design.traitId)
    }
}
