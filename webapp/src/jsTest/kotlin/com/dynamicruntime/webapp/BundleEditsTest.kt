package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bundle editor's editing, its pure half (issue #1460): the gate, the checks as typed, and the one patch. */
class BundleEditsTest {
    private fun row(sandboxOf: String? = null, hasSandbox: Boolean = false, staticHere: Boolean = false) = ClientOverview(
        "acme", "Acme", "present", "stored", 2, 0, 1, 0, 0, false, emptyList(), 0, 0,
        sandboxOf = sandboxOf, hasSandbox = hasSandbox, staticHere = staticHere,
    )

    private fun trait(id: String, description: String = "The $id trait.") = mapOf(CCT.traitId to id, CCT.description to description)

    // A trait entry's data: a required id and a text description -- the slot's schema, as the read serves it.
    private val defs = mapOf(
        "t.TraitEntry" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf(GE.data to mapOf(SCH.dRef to "#/\$defs/t.TraitData"))),
        "t.TraitData" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(CCT.traitId to mapOf(SCH.type to SCT.string), CCT.description to mapOf(SCH.type to SCT.string)),
            SCH.required to listOf(CCT.traitId),
        ),
    )

    // A schema type: keyed by its name, which carries the client's namespace -- what portable form rewrites.
    private val memoType = mapOf(CCT.typeName to "client.acme.Memo", CCT.description to "A memo's shape.")

    private val view = BundleEditorView(
        client = "acme",
        configs = listOf(
            EditorBundle("traits", EditorRevision(2, false, mapOf(CCT.traitDef to listOf(trait("memo"), trait("note"))), 0), null, mapOf(CCT.traitDef to listOf("2026-10-01T00:00:00.000Z", "2026-10-02T00:00:00.000Z"))),
            EditorBundle(
                "more",
                EditorRevision(1, true, mapOf(CCT.traitDef to listOf(trait("extra")), CCT.schemaDef to listOf(memoType)), 0),
                null,
                mapOf(CCT.traitDef to listOf("2026-10-03T00:00:00.000Z"), CCT.schemaDef to listOf("2026-10-04T00:00:00.000Z")),
            ),
            EditorBundle("main", EditorRevision(1, true, mapOf(CCT.clientDef to listOf(mapOf("clientId" to "acme"))), 0), null),
        ),
        slotKeys = mapOf(CCT.traitDef to listOf(CCT.traitId), CCT.clientDef to emptyList(), CCT.cfactDef to listOf(CCT.name), CCT.schemaDef to listOf(CCT.typeName)),
        slotTypes = mapOf(CCT.traitDef to "t.TraitEntry", CCT.clientDef to "t.ClientEntry", CCT.cfactDef to "t.CfactEntry"),
        defs = defs,
    )
    private val types = slotDataTypes(view)

    private fun idOf(bundle: String, data: Map<String, Any?>, at: Int) = existingEntryId(bundle, CCT.traitDef, listOf(CCT.traitId), data, at)

    /** [view]'s entry [at] of `traits` in [bundle], as the page starts editing it. */
    private fun editing(bundle: String, at: Int, edits: BundleEdits = BundleEdits(), text: String? = null): BundleEdits {
        val config = view.configs.single { it.name == bundle }
        val data = config.latest.slots.getValue(CCT.traitDef)[at]
        val draft = EntryDraft(bundle, CCT.traitDef, data, config.stamps[CCT.traitDef]?.get(at), entryText(data))
        return edits.with(idOf(bundle, data, at), if (text == null) draft else draft.withText(text))
    }

    /** [view]'s schema type, as the page starts editing it in [portable] form. */
    private fun editingType(portable: Boolean, text: (String) -> String = { it }): Pair<String, BundleEdits> {
        val id = existingEntryId("more", CCT.schemaDef, listOf(CCT.typeName), memoType, 0)
        val draft = EntryDraft(
            "more", CCT.schemaDef, memoType, "2026-10-04T00:00:00.000Z", entryText(entryShown(memoType, CCT.schemaDef, "acme", portable)),
            client = "acme", portable = portable,
        )
        return id to BundleEdits().with(id, draft.withText(text(draft.text)))
    }

    @Test
    fun anEntryOpenedInPortableFormIsNoEditAndIsCheckedAndSentAsStored() {
        val (id, opened) = editingType(portable = true)
        val text = opened.drafts.getValue(id).text
        assertTrue("client.@self.Memo" in text && "client.acme" !in text, text)
        assertEquals(0, opened.count)

        // Edited in portable form: its key is the same once resolved, and the save sends it as stored.
        val (_, edited) = editingType(portable = true) { it.replace("A memo's shape.", "Changed.") }
        val checks = checkEdits(view, edited, types)
        assertTrue(checks.getValue(id).ok, checks.getValue(id).problems.toString())
        val request = assertNotNull(bundlePatchRequest("acme", false, view, edited, checks, dryRun = false))
        val sent = request.body[CFEP.configs].toJsonListOfMaps().single()[CFEP.edits].toJsonListOfMaps().single()
        assertEquals(mapOf(CCT.typeName to "client.acme.Memo", CCT.description to "Changed."), sent[GE.data])

        // A new entry typed in portable form is held to the keys as stored.
        val (added, addedId) = edited.adding("traits", CCT.schemaDef, """{"typeName": "client.@self.Memo"}""", "acme", portable = true)
        assertTrue(checkEntry(addedId, view, added, types).problems.single().contains("already in bundle more"))
    }

    @Test
    fun switchingFormRewritesTheTextsBeingEditedWhileTheyParse() {
        val (id, opened) = editingType(portable = false) { it.replace("A memo's shape.", "Changed.") }
        val portable = assertNotNull(opened.inForm(true))
        val draft = portable.drafts.getValue(id)
        assertTrue(draft.portable && "client.@self.Memo" in draft.text && "Changed." in draft.text, draft.text)
        assertEquals(1, portable.count)
        // And back again, unchanged otherwise.
        assertEquals(opened.drafts.getValue(id).text, assertNotNull(portable.inForm(false)).drafts.getValue(id).text)

        // A text that does not parse cannot be rewritten, unless it is being removed.
        val (_, broken) = editingType(portable = false) { "{" }
        assertFalse(formSwitchable(broken))
        assertNull(broken.inForm(true))
        val removing = broken.with(id, broken.drafts.getValue(id).withRemoved(true))
        assertTrue(formSwitchable(removing))
        assertNotNull(removing.inForm(true))
    }

    @Test
    fun aBundleAddedOnThePageIsDiscardedWithItsEntries() {
        val added = BundleEdits().addingBundle("facts").adding("facts", CCT.cfactDef, "{}").first
        assertEquals(0, added.withoutNewBundle("facts").let { it.drafts.size + it.newBundles.size })
    }

    @Test
    fun jsonPastedFromAnotherClientIsOfferedAsThisClients() {
        val pasted = mapOf(
            CCT.typeName to "client.globex.Memo",
            "dataType" to mapOf(SCH.dRef to "#/${SCH.dDefs}/client.initech.forms.Note"),
            // Its own, and the portable form, are not another client's.
            "mine" to "client.acme.Memo", "neutral" to "client.@self.Memo", "plain" to "a client.globex mention",
        )
        assertEquals(setOf("globex", "initech"), foreignClients(pasted, "acme"))
        assertTrue(foreignClientsText(listOf("globex"), "acme").contains("another client's namespace: client.globex"))

        val fixed = withClientsAsSelf(pasted, listOf("globex", "initech"))
        assertEquals("client.@self.Memo", fixed[CCT.typeName])
        assertEquals(mapOf(SCH.dRef to "#/${SCH.dDefs}/client.@self.forms.Note"), fixed["dataType"])
        assertEquals(emptySet(), foreignClients(fixed, "acme"))

        // Taken as this client's, a pasted schema type is checked as acme's: here, one the client already holds.
        val (added, id) = BundleEdits().adding("traits", CCT.schemaDef, entryText(withClientsAsSelf(memoType + (CCT.typeName to "client.globex.Memo"), listOf("globex"))), "acme")
        assertTrue(checkEntry(id, view, added, types).problems.single().contains("already in bundle more"))
    }

    @Test
    fun aClientWithASandboxIsEditedFromItsSandbox() {
        assertTrue(bundleEditGate(row()).editable)
        assertTrue(bundleEditGate(row(sandboxOf = "acme")).editable)
        val parent = bundleEditGate(row(hasSandbox = true))
        assertFalse(parent.editable)
        assertEquals("acme:sandbox", parent.sandbox)
        assertFalse(bundleEditGate(row(staticHere = true)).editable)
        assertFalse(bundleEditGate(null).editable)
    }

    @Test
    fun anEntrysJsonMustParseAndASyntaxErrorSaysItsLine() {
        val edits = editing("traits", 0, text = "{\n  \"traitId\": \"memo\",\n  \"description\": \n}")
        val check = checkEntry(idOf("traits", trait("memo"), 0), view, edits, types)
        assertNull(check.data)
        assertTrue(check.problems.single().startsWith("Line 4"), check.problems.toString())
        // Typed back to what was read, it is no edit.
        assertEquals(0, editing("traits", 0).count)
    }

    @Test
    fun anEntryIsHeldToItsSlotsSchemaByPath() {
        val edits = editing("traits", 0, text = """{"traitId": "memo", "description": {"not": "text"}}""")
        val problems = checkEntry(idOf("traits", trait("memo"), 0), view, edits, types).problems
        assertEquals(1, problems.size)
        assertTrue(problems.single().startsWith("${CCT.description}: "), problems.toString())
    }

    @Test
    fun anExistingEntrysKeyCannotChange() {
        val edits = editing("traits", 0, text = """{"traitId": "memo2", "description": "Renamed."}""")
        val problems = checkEntry(idOf("traits", trait("memo"), 0), view, edits, types).problems
        assertTrue(problems.single().contains("cannot change"), problems.toString())
    }

    @Test
    fun aNewEntrysKeyCannotBeOneTheClientHoldsUnlessItMovesHere() {
        fun adding(edits: BundleEdits, text: String): Pair<BundleEdits, String> = edits.adding("traits", CCT.traitDef, text)

        // Held in another bundle.
        val (taken, takenId) = adding(BundleEdits(), """{"traitId": "extra"}""")
        assertTrue(checkEntry(takenId, view, taken, types).problems.single().contains("already in bundle more"))

        // Removed from that bundle by the same save: a move, and fine.
        val moving = editing("more", 0).let { it.with(idOf("more", trait("extra"), 0), it.drafts.values.single().withRemoved(true)) }
        val (moved, movedId) = adding(moving, """{"traitId": "extra"}""")
        assertTrue(checkEntry(movedId, view, moved, types).ok)

        // Removed from this bundle: it should be edited instead.
        val removing = editing("traits", 1).let { it.with(idOf("traits", trait("note"), 1), it.drafts.values.single().withRemoved(true)) }
        val (again, againId) = adding(removing, """{"traitId": "note"}""")
        assertTrue(checkEntry(againId, view, again, types).problems.single().contains("edit that entry instead"))

        // Two new entries with one key, and a key left blank.
        val (first, _) = adding(BundleEdits(), """{"traitId": "fresh"}""")
        val (both, secondId) = adding(first, """{"traitId": "fresh"}""")
        assertTrue(checkEntry(secondId, view, both, types).problems.single().contains("also being added"))
        val (blank, blankId) = adding(BundleEdits(), newEntryText(listOf(CCT.traitId)))
        assertTrue(checkEntry(blankId, view, blank, types).problems.any { it.contains("must be given") })
    }

    @Test
    fun oneSaveCarriesEveryBundlesEditsEachSayingWhatItRead() {
        var edits = editing("traits", 0, text = """{"traitId": "memo", "description": "Changed."}""")
        edits = editing("traits", 1, edits).let { e -> e.with(idOf("traits", trait("note"), 1), e.drafts.getValue(idOf("traits", trait("note"), 1)).withRemoved(true)) }
        edits = edits.adding("traits", CCT.traitDef, """{"traitId": "fresh"}""").first
        edits = edits.addingBundle("facts").adding("facts", CCT.cfactDef, """{"name": "ready"}""").first
        // An entry opened and left as read is no edit, and is not sent.
        edits = editing("more", 0, edits)
        assertEquals(4, edits.count)

        val checks = checkEdits(view, edits, types)
        val request = assertNotNull(bundlePatchRequest("acme", false, view, edits, checks, dryRun = true))
        assertEquals(CFEP.bundlePatch, request.path)
        assertEquals(true, request.body[CFEP.dryRun])
        val configs = request.body[CFEP.configs].toJsonListOfMaps()
        assertEquals(listOf("traits", "facts"), configs.map { it[CFEP.name] })
        val sent = configs[0][CFEP.edits].toJsonListOfMaps()
        // The removal first, then the change, then the new entry.
        assertEquals(listOf(GedraEditAction.deleteOrNoOp, GedraEditAction.addOrReplace, GedraEditAction.addOrReplace).map { it.name }, sent.map { it[GED.action] })
        assertEquals(mapOf(CCT.traitId to "note"), sent[0][GE.data])
        assertEquals("2026-10-02T00:00:00.000Z", sent[0][CFEP.expectUpdatedAt])
        assertEquals("Changed.", sent[1][GE.data].toJsonMapOrEmpty()[CCT.description])
        assertEquals("2026-10-01T00:00:00.000Z", sent[1][CFEP.expectUpdatedAt])
        assertEquals(true, sent[2][CFEP.expectNew])

        // The real save reloads; from across clients it names the owner, a sandbox's parent.
        val across = assertNotNull(bundlePatchRequest("acme:sandbox", true, view, edits, checks, dryRun = false))
        assertEquals(ACEP.bundlePatch, across.path)
        assertEquals("acme", across.body[CFEP.client])
        assertEquals(true, across.body[CFEP.reloadField])
        assertNull(across.body[CFEP.dryRun])
        assertEquals("POST /kda${ACEP.bundlePatch}", patchCallLine(across, "/kda"))
    }

    @Test
    fun nothingIsSentWhileAnEditFailsItsChecks() {
        val edits = editing("traits", 0, text = "{")
        assertNull(bundlePatchRequest("acme", false, view, edits, checkEdits(view, edits, types), dryRun = false))
        assertNull(bundlePatchRequest("acme", false, view, BundleEdits(), emptyMap(), dryRun = false))
        assertEquals("1 unsaved edit · 1 fails its checks", editCountText(edits.count, 1))
    }

    @Test
    fun aStaleSaveNamesItsEntriesAndReadingAgainDropsTheirEdits() {
        val refusal = ApiError(
            "stale", false, 409, CFEP.staleCode, null,
            mapOf(CFEP.conflicts to listOf(mapOf(CFEP.name to "traits", CFEP.slot to CCT.traitDef, CFEP.key to mapOf(CCT.traitId to "memo"), CFEP.updatedAt to "x"))),
        )
        val stale = assertNotNull(staleEntriesOf(refusal))
        assertEquals("Traits (traitId memo) in bundle traits was changed since this page read it.", stale.single().text)
        assertNull(staleEntriesOf(ApiError("other", false, 400, null, null)))

        val edits = editing("traits", 1, editing("traits", 0, text = """{"traitId": "memo", "description": "Mine."}"""), text = """{"traitId": "note", "description": "Mine too."}""")
        assertEquals(listOf(idOf("traits", trait("memo"), 0)), staleDraftIds(stale, view, edits))
    }

    @Test
    fun aBundleIsGivenOnlySlotsItCanHold() {
        val slots = addableSlots("traits", view, BundleEdits())
        // It holds traits already, and the client has its one definition elsewhere.
        assertFalse(CCT.traitDef in slots)
        assertFalse(CCT.clientDef in slots)
        assertTrue(CCT.cfactDef in slots)
        assertEquals("The client already has a bundle named main.", newBundleNameProblem("main", view, BundleEdits()))
        assertNull(newBundleNameProblem("facts", view, BundleEdits()))
    }
}
