package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bundle editor page's pure half (issue #1436): reading the editor's answer, and how each entry stands. */
class BundleEditorTest {
    private fun revision(version: Int, published: Boolean, slots: Map<String, List<Map<String, Any?>>>) = mapOf(
        CFEP.version to version, CFEP.published to published, CFEP.slots to slots,
    )

    private val memo = mapOf(CCT.traitId to "memo", CCT.description to "A memo.")
    private val note = mapOf(CCT.traitId to "note", CCT.description to "A note.")
    private val gone = mapOf(CCT.traitId to "gone", CCT.description to "Gone.")
    private val keys = mapOf(CCT.traitDef to listOf(CCT.traitId), CCT.clientDef to emptyList(), CCT.cfactDef to listOf(CCT.name))

    private val view = parseBundleEditor(
        mapOf(
            CFEP.client to "acme",
            CFEP.configs to listOf(
                mapOf(
                    CFEP.name to "traits",
                    CFEP.revisionLatest to revision(3, false, mapOf(CCT.traitDef to listOf(memo + (CCT.description to "Changed."), note))),
                    CFEP.revisionPublished to revision(2, true, mapOf(CCT.traitDef to listOf(memo, gone), CCT.cfactDef to listOf(mapOf(CCT.name to "ready"))))
                ),
                mapOf(CFEP.name to "main", CFEP.revisionLatest to revision(1, true, mapOf(CCT.clientDef to listOf(mapOf("clientId" to "acme"))))),
            ),
            CFEP.slotKeys to keys,
        ),
    )

    @Test
    fun aDraftsEntriesStandAgainstThePublishedOnesByKey() {
        val traits = view.configs.single { it.name == "traits" }
        val slots = editorSlots(traits, view.slotKeys)
        // The slots in the page's order; one only the published revision has is listed, all of it removed.
        assertEquals(listOf(CCT.traitDef, CCT.cfactDef), slots.map { it.slot })
        val entries = slots.first().entries.associate { it.label to it.change }
        assertEquals(mapOf("memo" to EntryChange.changed, "note" to EntryChange.added, "gone" to EntryChange.removed), entries)
        assertEquals(listOf(EntryChange.removed), slots[1].entries.map { it.change })
        assertEquals("Traits (kdr:traitDef) — 2 entries · 3 changed in the draft", slotSummaryText(slots.first()))
    }

    @Test
    fun aPublishedConfigurationHasNothingToCompareWith() {
        val main = view.configs.single { it.name == "main" }
        assertNull(main.published)
        val entry = editorSlots(main, view.slotKeys).single().entries.single()
        // A single-instance slot's one entry is named by its slot.
        assertEquals("Client definition", entry.label)
        assertNull(entry.change)
        assertNull(entryChangeText(entry.change))
    }

    @Test
    fun aConfigurationsLineSaysWhereItStands() {
        assertEquals(
            "traits — version 3, a draft over published version 2 · 1 slot, 2 entries",
            bundleSummaryText(view.configs.single { it.name == "traits" }),
        )
        assertEquals("main — version 1, published · 1 slot, 1 entry", bundleSummaryText(view.configs.single { it.name == "main" }))
        val never = EditorBundle("extra", EditorRevision(1, false, emptyMap(), 2), null)
        assertTrue(bundleSummaryText(never).contains("a draft, never published") && bundleSummaryText(never).endsWith("2 issues"))
    }

    @Test
    fun theDefinitionsBundleComesFirstAndTheRestByName() {
        fun bundle(name: String, vararg slots: String) = EditorBundle(name, EditorRevision(1, true, slots.associateWith { emptyList() }, 0), null)
        // As the read answers them: most recently written first.
        val read = listOf(bundle("workflow_review", CCT.workflowDef), bundle("traits", CCT.traitDef), bundle("main", CCT.clientDef), bundle("copy", CCT.fragmentDef))
        assertEquals(listOf("main", "copy", "traits", "workflow_review"), editorBundlesInOrder(read).map { it.name })
    }

    @Test
    fun theClosedPublishingLineSaysWhetherThereIsAnythingToPublish() {
        fun summary(vararg published: Boolean) = parseConfigSummaries(
            published.mapIndexed { i, p -> mapOf(CFEP.name to "c$i", CFEP.version to 1, CFEP.published to p) },
        )
        assertEquals("Show the table — 2 of 3 configurations unpublished", publishingSummaryText(summary(false, true, false)))
        assertEquals("Show the table — all 3 published", publishingSummaryText(summary(true, true, true)))
        assertEquals("Show the table — its one configuration published", publishingSummaryText(summary(true)))
        assertEquals("Show the table — 1 of 1 configuration unpublished", publishingSummaryText(summary(false)))
    }

    @Test
    fun aBundlesPortableFormNamesNoClientButKeepsTheDefinition() {
        val entry = mapOf(CCT.traitId to "memo", CCT.typeName to "client.acme.MemoEntry", "dataType" to mapOf(SCH.dRef to "#/${SCH.dDefs}/client.acme.Memo"))
        val bundle = EditorBundle(
            "traits",
            EditorRevision(2, false, mapOf(CCT.traitDef to listOf(entry), CCT.clientDef to listOf(mapOf("clientId" to "acme", "homeNs" to "client.acme"))), 0, "client.acme"),
            null,
        )
        val portable = portableBundle(bundle, "acme")
        assertEquals("client.@self", portable[CFEP.namespaceField])
        val slots = portable[CFEP.slots].toJsonMapOrEmpty()
        val written = slots[CCT.traitDef].toJsonListOfMaps().single()
        assertEquals("client.@self.MemoEntry", written[CCT.typeName])
        assertEquals(mapOf(SCH.dRef to "#/${SCH.dDefs}/client.@self.Memo"), written["dataType"])
        // The definition is the client's own, and stays as stored.
        assertEquals("client.acme", slots[CCT.clientDef].toJsonListOfMaps().single()["homeNs"])
    }

    @Test
    fun theReadIsTheCallersOwnOrTheFullScopeOneNamingTheOwner() {
        assertEquals(CFEP.editor, bundleEditorPath("acme", acrossClients = false, ownClient = "acme"))
        assertNull(bundleEditorPath("globex", acrossClients = false, ownClient = "acme"))
        assertEquals("${ACEP.editor}?client=acme", bundleEditorPath("acme:sandbox", acrossClients = true, ownClient = "hub"))
    }

    // --- the code layer (issue #1616) ---

    private val codeView = parseBundleEditor(
        mapOf(
            CFEP.client to "kid",
            CFEP.configs to listOf(
                mapOf(CFEP.name to "main", CFEP.revisionLatest to revision(1, true, mapOf(CCT.traitDef to listOf(note)))),
            ),
            CFEP.slotKeys to keys,
            CFEP.codeConfigs to listOf(
                mapOf(
                    CFEP.name to "kid", CFEP.origin to CFEP.originCode, CFEP.namespaceField to "client.kid",
                    CFEP.slots to mapOf(CCT.traitDef to listOf(memo)),
                ),
                mapOf(
                    CFEP.name to "from_tpl_base", CFEP.origin to CFEP.originTemplate, CFEP.template to "tpl",
                    CFEP.namespaceField to "client.tpl",
                    CFEP.slots to mapOf(CCT.traitDef to listOf(gone), CCT.cfactDef to listOf(mapOf(CCT.name to "ready"))),
                ),
            ),
            CFEP.replacements to listOf(
                mapOf(
                    CFEP.name to "main", CFEP.slot to CCT.traitDef, CFEP.key to mapOf(CCT.traitId to "note"), CFEP.template to "tpl",
                    CFEP.templateName to "base", CFEP.templateEntries to listOf(note + (CCT.description to "The template's note.")),
                ),
                mapOf(
                    CFEP.name to "main", CFEP.slot to CCT.usageDef, CFEP.key to emptyMap<String, Any?>(), CFEP.template to "tpl",
                    CFEP.templateName to "base", CFEP.templateEntries to listOf(mapOf("traitId" to "a"), mapOf("traitId" to "b")),
                ),
            ),
        ),
    )

    @Test
    fun theCodeLayerIsReadWithWhereEachBundleComesFrom() {
        assertEquals(listOf("kid" to null, "from_tpl_base" to "tpl"), codeView.codeConfigs.map { it.name to it.template })
        assertEquals("kid — from the client's code, read-only · 1 slot, 1 entry", codeBundleLineText(codeView.codeConfigs[0]))
        assertEquals(
            "from_tpl_base — a copy of template tpl's code, read-only · 2 slots, 2 entries",
            codeBundleLineText(codeView.codeConfigs[1]),
        )
        assertTrue(codeBundleNoteText(codeView.codeConfigs[1]).contains("replaces the template's whole"))
        // A code bundle is listed as a stored one is, with nothing beside it to compare.
        val slots = editorSlots(codeView.codeConfigs[1].asEditorBundle(), codeView.slotKeys)
        assertEquals(listOf(CCT.traitDef, CCT.cfactDef), slots.map { it.slot })
        assertNull(slots.first().entries.single().change)
    }

    @Test
    fun anEntryThatReplacesItsTemplatesIsFoundByItsKeyAndColumnsAsASet() {
        val r = assertNotNullOf(replacementOf(codeView.replacements, "main", CCT.traitDef, listOf(CCT.traitId), note))
        assertEquals("The template's note.", r.templateEntries.single()[CCT.description])
        // Another key, another bundle, or another slot replaces nothing.
        assertNull(replacementOf(codeView.replacements, "main", CCT.traitDef, listOf(CCT.traitId), memo))
        assertNull(replacementOf(codeView.replacements, "kid", CCT.traitDef, listOf(CCT.traitId), note))
        // The columns replace as one set, so no entry of theirs is matched, and the slot is.
        assertNull(replacementOf(codeView.replacements, "main", CCT.usageDef, emptyList(), mapOf("traitId" to "a")))
        val set = assertNotNullOf(slotReplacement(codeView.replacements, "main", CCT.usageDef))
        assertTrue(slotReplacementText(set).startsWith("These replace all of template tpl's listing columns (2, in its base bundle)"))
        assertEquals(
            listOf(
                "Traits note, from template tpl's base bundle, in main",
                "all of the template's listing columns, from template tpl's base bundle, in main",
            ),
            replacingText(codeView.replacements),
        )
    }

    private fun <T : Any> assertNotNullOf(value: T?): T = kotlin.test.assertNotNull(value)
}
