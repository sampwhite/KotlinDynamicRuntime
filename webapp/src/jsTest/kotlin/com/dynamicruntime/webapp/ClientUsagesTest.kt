package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUS
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for a client's listing columns on its page (issue #1491): the catalog read, the
 * editor's draft -- a field picked writes `${field}` and decides how the column reads -- its checks, and the requests.
 */
class ClientUsagesTest {
    private fun field(key: String, label: String, kind: UsageKind, choices: Boolean = false) = buildMap {
        put(CUS.field, key)
        put(CUS.label, label)
        put(CUS.readsAs, kind.name)
        if (choices) put(CUS.hasChoices, true)
    }

    private fun column(label: String, display: String, readsAs: UsageKind, extra: Map<String, Any?> = emptyMap()) =
        mapOf(CCT.traitId to "talk", CCT.label to label, CCT.display to display, CCT.substring to false, CUS.readsAs to readsAs.name) +
            extra

    private val catalog = parseUsageCatalog(
        mapOf(
            CLD.client to "acme",
            CUS.defaults to false,
            CUS.newColumnsIn to "main",
            CUS.columns to listOf(
                column("Minutes", $$"${minutes}", UsageKind.number, mapOf(CUS.configName to "main")),
                column(
                    "Level", $$"${level}", UsageKind.string,
                    mapOf(CUS.configName to "main", CCT.columnId to "level", CCT.substring to true),
                ),
                column(
                    "Speaker", $$"${first} ${last}", UsageKind.string,
                    mapOf(CUS.notEditable to "It is declared in source code, where it is edited."),
                ),
            ),
            CUS.traits to listOf(
                mapOf(
                    CCT.traitId to "talk", CUS.description to "A talk.",
                    CUS.fields to listOf(
                        field("minutes", "Minutes", UsageKind.number),
                        field("level", "Level", UsageKind.string, choices = true),
                        field("firstTalk", "First talk", UsageKind.boolean),
                        field("note", "Note", UsageKind.string),
                    ),
                ),
            ),
        ),
    )
    private val talk = catalog.trait("talk")

    @Test
    fun readsTheCatalogAndWhatEachColumnShows() {
        assertEquals(listOf("talk|Minutes", "talk|Level", "talk|Speaker"), catalog.columns.map { it.key })
        assertEquals("level", catalog.columns[1].usage.columnId)
        assertTrue(catalog.columns[1].usage.substring)
        assertEquals("Minutes", usageShowsText(catalog.columns[0].usage, talk))
        // A hand-written value is shown as written.
        assertEquals($$"${first} ${last}", usageShowsText(catalog.columns[2].usage, talk))
        assertTrue(usageEditable(catalog.columns[0]))
        assertFalse(usageEditable(catalog.columns[2]))
        assertEquals("Yes or no", usageKindText(UsageKind.boolean))
    }

    @Test
    fun pickingAFieldWritesItsValueAndHeadingAndReadsAsTheField() {
        val draft = newUsageDraft(catalog).withField(talk!!.field("firstTalk")!!)
        assertEquals($$"${firstTalk}", draft.display)
        assertEquals("First talk", draft.label)
        assertEquals(UsageKind.boolean, usageDraftReadsAs(draft, talk))
        // A heading already written is kept, and a kind of its own is dropped for the field's.
        val kept = draft.copy(label = "Debut", kind = UsageKind.date).withField(talk.field("minutes")!!)
        assertEquals("Debut", kept.label)
        assertNull(kept.kind)
        assertEquals(UsageKind.number, usageDraftReadsAs(kept, talk))
        assertEquals(emptyList(), usageOptionsInForce(kept, talk))
    }

    @Test
    fun aHandWrittenValueIsTextUnlessItSaysAndIsNamedUnderMoreOptions() {
        val draft = usageDraftOf(catalog.columns[2])
        assertNull(usageField(draft.display, talk))
        assertEquals(UsageKind.string, usageDraftReadsAs(draft, talk))
        assertEquals(UsageKind.number, usageDraftReadsAs(draft.copy(kind = UsageKind.number), talk))
        assertEquals(listOf("value written by hand"), usageOptionsInForce(draft, talk))
        assertEquals(listOf("value written by hand", "reads as number"), usageOptionsInForce(draft.copy(kind = UsageKind.number), talk))
        val presented = usageDraftOf(catalog.columns[0]).copy(presentation = $$"${value} min")
        assertEquals(listOf("presentation"), usageOptionsInForce(presented, talk))
    }

    @Test
    fun saysWhatIsWrongWithADraft() {
        val blank = newUsageDraft(catalog)
        assertEquals(
            listOf("Give the column a heading.", "Pick the field it shows, or write its value under More options."),
            usageDraftProblems(blank, catalog, null),
        )
        // A heading its trait already has, except the column's own.
        val taken = blank.withField(talk!!.field("minutes")!!)
        assertEquals(listOf("Trait talk already has a column headed “Minutes”."), usageDraftProblems(taken, catalog, null))
        assertEquals(emptyList(), usageDraftProblems(usageDraftOf(catalog.columns[0]), catalog, catalog.columns[0]))
        val broken = usageDraftOf(catalog.columns[0]).copy(presentation = $$"${value")
        assertEquals(1, usageDraftProblems(broken, catalog, catalog.columns[0]).size)
        assertTrue(usageDraftProblems(broken, catalog, catalog.columns[0]).single().startsWith("The presentation does not read"))
        // Opened and untouched, nothing to save; a column id the draft does not carry is no change.
        assertFalse(usageDraftChanged(usageDraftOf(catalog.columns[1]), catalog.columns[1]))
        assertTrue(usageDraftChanged(usageDraftOf(catalog.columns[1]).copy(label = "Audience"), catalog.columns[1]))
    }

    @Test
    fun theSaveSendsTheColumnAndTheHeadingItChanges() {
        val draft = usageDraftOf(catalog.columns[1]).copy(label = " Audience ")
        val request = usageSaveRequest("acme", draft, talk, "Level", EDM.draft)
        assertEquals("Level", request[CUS.previousLabel])
        assertEquals(EDM.draft, request[CPY.mode])
        val sent = request[CUS.column].toJsonMapOrEmpty()
        assertEquals("Audience", sent[CCT.label])
        assertEquals(true, sent[CCT.substring])
        // The server keeps a column's id; the page does not send one.
        assertFalse(CCT.columnId in sent)
        // A contains search is sent only for a text column.
        val length = newUsageDraft(catalog).withField(talk!!.field("minutes")!!).copy(label = "Length", substring = true)
        val number = usageSaveRequest("acme", length, talk, null, EDM.live)
        assertEquals(false, number[CUS.column].toJsonMapOrEmpty()[CCT.substring])
        assertFalse(CUS.previousLabel in number)
    }

    @Test
    fun aRemovalSaysWhatComesBack() {
        val one = parseUsageCatalog(
            mapOf(
                CUS.defaults to false,
                CUS.columns to listOf(column("Minutes", $$"${minutes}", UsageKind.number, mapOf(CUS.configName to "main"))),
            ),
        )
        assertTrue(usageRemoveConsequence(one.columns.single(), one, draft = false).contains("default columns come back"))
        val defaults = parseUsageCatalog(
            mapOf(
                CUS.defaults to true,
                CUS.columns to listOf(column("Name", $$"${name}", UsageKind.string), column("Year", $$"${year}", UsageKind.number)),
            ),
        )
        assertTrue(usageRemoveConsequence(defaults.columns[0], defaults, draft = true).startsWith("The other default columns become"))
        assertNull(usageRemoveBlocked(defaults))
        val lone = parseUsageCatalog(mapOf(CUS.defaults to true, CUS.columns to listOf(column("Name", $$"${name}", UsageKind.string))))
        assertTrue(usageRemoveBlocked(lone)!!.startsWith("It is the deployment's default"))
        assertEquals(
            "Remove the column “Name” from the draft of acme?",
            usageRemoveQuestion(lone.columns.single(), "acme:sandbox", draft = true),
        )
        val request = usageRemoveRequest("acme", catalog.columns[0], EDM.live)
        assertEquals("talk" to "Minutes", request[CCT.traitId] to request[CCT.label])
    }
}
