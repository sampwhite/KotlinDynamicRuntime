package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.gedra.TraitNarrowing
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.schema.collectLayouts
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for narrowing and extending a global trait (issue #1119): the fields the narrowing
 * editor lists, what each change does and which it refuses, the preview of the trait as the client would take it, the
 * result and its draft impact, and an extension's preview.
 */
class TraitNarrowerTest {
    private val typeName = "fixture.traits.SurveyData"
    private val base = TraitBaseView(
        typeName,
        mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "name" to mapOf(SCH.type to SCT.string),
                "topic" to mapOf(SCH.type to SCT.string, SCH.options to listOf(mapOf(SCH.value to "a", SCH.label to "A"), mapOf(SCH.value to "b", SCH.label to "B"))),
                "notes" to mapOf(SCH.type to SCT.string, SCH.title to "Remarks"),
            ),
            SCH.required to listOf("name"),
        ),
        SchLayout(null, "Survey", listOf(SchLayoutField("name", "Who answers", null, null))),
    )
    private val empty = TraitNarrowing("fixture:survey")

    @Test
    fun theFieldsAreListedWithTheirLabelsAndChoices() {
        val rows = narrowFieldRows(base)
        assertEquals(listOf("name", "topic", "notes"), rows.map { it.field })
        // The layout's label, else the schema's title, else the key in words.
        assertEquals(listOf("Who answers", "Topic", "Remarks"), rows.map { it.label })
        assertEquals(listOf(true, false, false), rows.map { it.baseRequired })
        assertEquals(listOf("a" to "A", "b" to "B"), rows[1].choices)
        assertNull(rows[2].choices)
    }

    @Test
    fun onlyMovesThatNarrowAreOffered() {
        val rows = narrowFieldRows(base).associateBy { it.field }
        // The trait requires "name": it is shown and required whatever the client does.
        assertNotNull(hideLockedReason(rows.getValue("name"), empty))
        assertNotNull(requireLockedReason(rows.getValue("name"), empty))
        // A field required here must be shown; a hidden one must be shown to be required.
        val requiring = empty.withRequired("notes", true)
        assertNotNull(hideLockedReason(rows.getValue("notes"), requiring))
        val hiding = empty.withHidden("notes", true)
        assertNotNull(requireLockedReason(rows.getValue("notes"), hiding))
        assertNull(hideLockedReason(rows.getValue("topic"), empty))
        assertEquals(setOf("notes"), hiding.hidden)
        assertEquals(emptySet(), hiding.withHidden("notes", false).hidden)
    }

    @Test
    fun keepingEveryChoiceIsNoRestriction() {
        val offered = listOf("a", "b")
        assertEquals(mapOf("topic" to listOf("b")), empty.withChoices("topic", listOf("b"), offered).choices)
        // In the trait's order, whatever order they were picked in.
        assertEquals(emptyMap(), empty.withChoices("topic", listOf("b", "a"), offered).choices)
        assertTrue(narrowProblems(empty.withChoices("topic", emptyList(), offered), base).single().contains("at least one"))
        assertTrue(narrowingChanged(empty.withHidden("notes", true), empty))
    }

    @Test
    fun thePreviewIsTheTraitAsTheClientWouldTakeIt() {
        val narrowing = TraitNarrowing("fixture:survey", hidden = setOf("notes"), required = setOf("topic"), choices = mapOf("topic" to listOf("b")))
        val defs = narrowPreviewDefs(base, narrowing)
        val type = parseSchemaTypes(defs).getValue(typeName)
        assertEquals(listOf("name", "topic"), type.required.toList())
        assertEquals(listOf("b"), type.properties.getValue("topic").valueType.options?.map { it.value })
        val layout = collectLayouts(defs).getValue(typeName)
        assertEquals(SchLayoutMode.authoritative, layout.mode)
        assertEquals(listOf("name", "topic"), layout.fieldNames)
        // The trait's own label survives the narrowing.
        assertEquals("Who answers", layout.fieldFor("name")?.label)
        // Nothing narrowed: the trait as declared.
        assertEquals(base.bodyWithLayout, narrowPreviewDefs(base, empty)[typeName])
    }

    @Test
    fun theResultSaysADraftsImpact() {
        val report = mapOf(
            IMP.client to "acme", IMP.name to "narrow_x", IMP.version to 2, IMP.scanned to 3, IMP.tooLarge to false,
            IMP.findings to listOf(mapOf(IMP.kind to "dataInvalid", IMP.traitId to "fixture:survey", IMP.count to 1, IMP.sampleIds to listOf("f1"))),
        )
        val result = parseTraitNarrowResult(
            mapOf(CIT.configName to "narrow_x", CIT.narrowing to empty.toInfo(), CPY.mode to EDM.draft, CIT.draftImpact to report),
        )
        assertEquals(EDM.draft, result.mode)
        val said = assertNotNull(draftImpactText(result.draftImpact))
        assertTrue(said.contains("Publishing it would affect forms acme already stores"))
        assertTrue(said.contains("1 form holds entries of trait fixture:survey"))
        assertNull(draftImpactText(null as ImpactReportView?))
        assertEquals(true, traitNarrowRequest("acme", empty, EDM.live, acknowledge = true)[IMP.acknowledgeImpact])
    }

    @Test
    fun anExtensionsPreviewIsTheBasePlusItsFields() {
        val draft = TraitDesign(null, "Survey plus", null, listOf(TraitFieldDesign(null, "Name", TraitFieldKind.integer)), "fixture:survey")
        val defs = previewDefs(draft, base = base)
        val type = parseSchemaTypes(defs).getValue(previewTypeName)
        // The base's "name" is kept, and the added "Name" takes the next key.
        assertEquals(listOf("name", "topic", "notes", "name2"), type.properties.keys.toList())
        assertEquals(listOf("name"), type.required.toList())
        val layout = collectLayouts(defs).getValue(previewTypeName)
        assertEquals("Who answers", layout.fieldFor("name")?.label)
        assertEquals("Name", layout.fieldFor("name2")?.label)
        // Starting from nothing again keeps the fields.
        assertNull(draft.extending(null).extendsTrait)
        assertEquals(1, draft.extending(null).fields.size)
    }
}
