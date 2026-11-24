package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.UNQ
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a form asks of a unique field as it is left (issue #1851). */
class UniqueChecksTest {
    private val budget: SchType = parseSchemaTypes(
        mapOf(
            "c.Budget" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "title" to mapOf(SCH.type to SCT.string, SCH.unique to UNQ.owner),
                    "note" to mapOf(SCH.type to SCT.string),
                ),
            ),
        ),
    ).getValue("c.Budget")

    private val typeOf: (String) -> SchType? = { if (it == "budget") budget else null }

    @Test
    fun onlyAUniqueFieldHoldingSomethingToCompareIsAskedAbout() {
        val data = mapOf("title" to "Q3 Budget", "note" to "n")
        assertEquals(UniqueAsk("budget", "title", "Q3 Budget"), uniqueAskOf("budget", budget, "title", data))
        assertNull(uniqueAskOf("budget", budget, "note", data))
        assertNull(uniqueAskOf("budget", budget, "title", emptyMap()))
        assertNull(uniqueAskOf("budget", budget, "title", mapOf("title" to " !!! ")))
    }

    @Test
    fun theEntryAtAPathIsTheOneAskedAbout() {
        val entries = listOf(
            mapOf(GE.traitId to "other", GE.data to mapOf("title" to "x")),
            mapOf(GE.traitId to "budget", GE.data to mapOf("title" to "Q3")),
        )
        val ask = UniqueAsk("budget", "title", "Q3")
        assertEquals(ask, uniqueAskInEntries(mapOf(GDF.entries to entries), "${GDF.entries}[1].data.title", typeOf))
        // A patch's edits are entries too.
        assertEquals(ask, uniqueAskInEntries(mapOf(GPF.edits to entries), "${GPF.edits}[1].data.title", typeOf))
        for (path in listOf("entries[0].data.title", "entries[5].data.title", "entries[1].data.title.deep", "entries[1].traitId", "user")) {
            assertNull(uniqueAskInEntries(mapOf(GDF.entries to entries), path, typeOf), path)
        }
    }

    @Test
    fun anAnswerIsShownOnlyForTheOwnerAndTheValueItWasAbout() {
        val answers = mapOf(
            UniqueAsked("mine", "title", "Q3") to "Taken.",
            UniqueAsked("mine", "note", "n") to null,
            UniqueAsked("theirs", "title", "Q4") to "Theirs.",
        )
        fun shown(owner: String, title: String) =
            takenFailures(answers, owner) { if (it == "title") UniqueAsk("budget", it, title) else null }.map { it.message }
        assertEquals(listOf("Taken."), shown("mine", "Q3"))
        assertEquals(emptyList(), shown("mine", "Q4"))
        assertEquals(emptyList(), shown("theirs", "Q3"))
        assertEquals(listOf("Theirs."), shown("theirs", "Q4"))
    }

    @Test
    fun aTakenValueIsShownBesideWhatTheFormHasFound() {
        val taken = listOf(SchFailure("title", SchFailCode.badValue, "Taken."))
        val missing = listOf(SchFailure("note", SchFailCode.missingRequired, "Missing."))
        assertEquals(missing + taken, withTaken(missing, taken))
        assertEquals(taken, withTaken(null, taken))
        // A form not checked yet stays unchecked, rather than drawing a clean result.
        assertNull(withTaken(null, emptyList()))
    }
}
