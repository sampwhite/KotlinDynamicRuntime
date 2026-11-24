package com.dynamicruntime.webapp

import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.schema.parseSchLayout
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a create page asks a counter for, and where an issued id lands (issue #1849). */
class CounterIdsTest {
    private val receipt: SchType = parseSchemaTypes(
        mapOf(
            "c.Receipt" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "number" to mapOf(SCH.type to SCT.string, SCH.description to "The id.", SCH.counter to "receipt"),
                    "note" to mapOf(SCH.type to SCT.string, SCH.description to "A note."),
                ),
            ),
        ),
    ).getValue("c.Receipt")

    @Test
    fun aFieldWithNoIdIsAskedForOnceATrait() {
        val traits = listOf("receipt" to receipt, "receipt" to receipt)
        assertEquals(listOf(CounterSlot("receipt", "number", "receipt")), counterSlotsToFill(traits) { emptyMap() })
        // One that holds an id already, or a trait the page has no values for, asks nothing.
        assertTrue(counterSlotsToFill(traits) { mapOf("number" to "R-0001") }.isEmpty())
        assertTrue(counterSlotsToFill(traits) { null }.isEmpty())
    }

    @Test
    fun eachEntryOfATraitIsGivenItsOwnId() {
        val entries = listOf(
            mapOf(GE.traitId to "receipt", GE.data to mapOf("note" to "a")),
            mapOf(GE.traitId to "receipt", GE.data to mapOf("number" to "R-0001")),
            mapOf(GE.traitId to "receipt", GE.data to emptyMap<String, Any?>()),
            mapOf(GE.traitId to "other", GE.data to emptyMap<String, Any?>()),
        )
        val slots = counterSlotsInEntries(entries) { if (it == "receipt") receipt else null }
        assertEquals(2, slots.size)
        // Filled in order: each id to the first entry of its trait still without one.
        val once = withCounterId(entries, slots[0], "R-0002")
        val twice = withCounterId(once, slots[1], "R-0003")
        assertEquals(listOf("R-0002", "R-0001", "R-0003", null), twice.map { (it[GE.data] as Map<*, *>)["number"] })
        assertEquals("a", (twice[0][GE.data] as Map<*, *>)["note"])
        // With every entry given one, a late id goes nowhere.
        assertEquals(twice, withCounterId(twice, slots[0], "R-0004"))
    }

    private val ticket: SchType = parseSchemaTypes(
        mapOf(
            "c.Ticket" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "ticketNo" to mapOf(SCH.type to SCT.string, SCH.description to "The id.", SCH.counter to "ticket"),
                    "stamp" to mapOf(SCH.type to SCT.string, SCH.description to "Computed.", SCH.derived to true),
                ),
            ),
        ),
    ).getValue("c.Ticket")

    /** A counter that gives its ids at save (issue #1852) is asked nothing as a form opens, and only at an edit's Save. */
    @Test
    fun aCounterGivingItsIdsAtSaveIsLeftToTheServer() {
        val onSave = countersOnSaveOf(
            mapOf("ticket" to mapOf(CTR.issue to CTR.issueOnSave), "receipt" to mapOf(CTR.issue to CTR.issueOnOpen)),
        )
        assertEquals(setOf("ticket"), onSave)
        assertTrue(countersOnSaveOf(null).isEmpty())
        val traits = listOf("receipt" to receipt, "ticket" to ticket)
        assertEquals(listOf("receipt"), counterSlotsToFill(traits, onSave) { emptyMap() }.map { it.counterId })
        val entries = listOf(mapOf(GE.traitId to "ticket", GE.data to emptyMap<String, Any?>()))
        assertTrue(counterSlotsInEntries(entries, onSave) { ticket }.isEmpty())
    }

    /** A section whose only field such a counter fills is not drawn until it holds the id; a derived field does not count. */
    @Test
    fun aSectionWithNothingButAnIdGivenAtSaveIsHidden() {
        val opts = FormOpts(friendly = true, countersOnSave = setOf("ticket", "receipt"))
        assertTrue(hiddenUntilSaved(ticket, emptyMap(), opts))
        assertTrue(!hiddenUntilSaved(ticket, mapOf("ticketNo" to "TK-001"), opts))
        assertTrue(!hiddenUntilSaved(ticket, emptyMap(), FormOpts(friendly = true)))
        // A section with a field of its own to fill is drawn, its id field left off.
        assertTrue(!hiddenUntilSaved(receipt, emptyMap(), opts))
        assertEquals(setOf("number"), fieldsGivenAtSave(receipt, emptyMap(), opts))
    }

    /** A field the form does not show for another reason is given no id: the save would be refused it. */
    @Test
    fun aFieldTheFormDoesNotOfferIsGivenNoId() {
        val refund = parseSchemaTypes(
            mapOf(
                "c.Refund" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "kind" to mapOf(SCH.type to SCT.string),
                        "refundNo" to mapOf(SCH.type to SCT.string, SCH.counter to "refund"),
                        "auditNo" to mapOf(SCH.type to SCT.string, SCH.counter to "refund", SCH.visibleWhen to "kdr:hasAdminLevel"),
                    ),
                    SCH.kIf to mapOf(SCH.required to listOf("kind"), SCH.properties to mapOf("kind" to mapOf(SCH.const to "refund"))),
                    SCH.kThen to mapOf(SCH.required to listOf("refundNo")),
                    SCH.kElse to mapOf(SCH.not to mapOf(SCH.required to listOf("refundNo"))),
                ),
            ),
        ).getValue("c.Refund")
        val opts = FormOpts(friendly = true, gateAllows = { false }, countersOnSave = setOf("refund"))
        assertEquals(setOf("refundNo"), fieldsGivenAtSave(refund, mapOf("kind" to "refund"), opts))
        assertTrue(fieldsGivenAtSave(refund, mapOf("kind" to "sale"), opts).isEmpty())
        // A layout that chooses its fields and leaves this one out.
        val layout = parseSchLayout(
            "Refund", mapOf(SL.mode to SchLayoutMode.authoritative.name, SL.schemaFields to listOf(mapOf(SL.field to "kind"))),
        )
        val laidOut = FormOpts(friendly = true, fieldLayouts = mapOf("c.Refund" to layout), countersOnSave = setOf("refund"))
        assertTrue(fieldsGivenAtSave(refund, mapOf("kind" to "refund"), laidOut).isEmpty())
        // The client's check asks nothing for a field about to be given its id.
        assertTrue(checkFormInput(refund, mapOf("kind" to "refund"), emptyMap()).isValid)
    }

    /** The ids such counters gave a stored form, which the listing names. */
    @Test
    fun theIdsGivenAtSaveAreReadOffTheStoredForm() {
        val stored = listOf(
            mapOf(GE.traitId to "receipt", GE.data to mapOf("number" to "R-0001")),
            mapOf(GE.traitId to "ticket", GE.data to mapOf("ticketNo" to "TK-001")),
        )
        val types = mapOf("receipt" to receipt, "ticket" to ticket)
        assertEquals(listOf("TK-001"), idsGivenAtSave(stored, setOf("ticket")) { types[it] })
        assertTrue(idsGivenAtSave(stored, emptySet()) { types[it] }.isEmpty())
    }

    @Test
    fun aSaveSendsTheTokensItWasGiven() {
        val body = workflowSaveBody("create", "first", "save", emptyList(), counterTokens = listOf("t1"))
        assertEquals(listOf("t1"), body[CTR.counterTokens])
        assertTrue(CTR.counterTokens !in workflowSaveBody("create", "first", "save", emptyList()))
    }
}
