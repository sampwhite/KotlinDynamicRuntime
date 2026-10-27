package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Clients page's Form states section (issue #1412): when it offers a recalculation, and what it says one did. */
class FormStatesTest {
    // The count held to the limit is the backend's own; `forms` differs here to show the page judges by that one.
    private fun row(count: Int, limit: Int = 2000, status: ClientStatus = ClientStatus.present) = parseClientOverview(
        listOf(
            mapOf(
                CLD.clientId to "acme", CLD.name to "Acme", CLD.status to status.name, CLD.origin to GedraConfigOrigin.stored.name,
                CLD.forms to 1, FSR.count to count, FSR.limit to limit,
            ),
        ),
    ).single()

    @Test
    fun itIsOfferedWithinTheLimitAndThePageSaysWhyNotOtherwise() {
        assertEquals(2000, row(5).formStatesRecalcLimit)
        formStatesOffer(row(2000)).let {
            assertTrue(it.recalculate)
            assertTrue("2000 forms" in it.text)
        }
        formStatesOffer(row(2001)).let {
            assertFalse(it.recalculate)
            assertTrue("more than the 2000" in it.text && "nightly" in it.text)
        }
        assertFalse(formStatesOffer(row(0)).recalculate)
        assertFalse(formStatesOffer(row(5, status = ClientStatus.notEnabled)).recalculate)
        assertTrue("1 form " in formStatesOffer(row(1)).text)
    }

    @Test
    fun itSaysWhatARunDidIncludingNothing() {
        fun said(vararg results: Pair<String, Any?>) = formStatesRecalcText(parseFormStatesRecalc(mapOf(*results)))
        assertEquals(
            "Updated 3 of 10 forms; the rest were already current.",
            said(FSR.outcome to FSR.completed, FSR.updated to 3, FSR.current to 7, FSR.failed to 0, FSR.total to 10),
        )
        assertEquals("All 10 forms were already current.", said(FSR.outcome to FSR.completed, FSR.current to 10, FSR.total to 10))
        assertEquals("Its one form was already current.", said(FSR.outcome to FSR.completed, FSR.current to 1, FSR.total to 1))
        assertEquals("Updated all 500 forms.", said(FSR.outcome to FSR.completed, FSR.updated to 500, FSR.total to 500))
        assertTrue(
            "could not be stored" in said(FSR.outcome to FSR.completed, FSR.updated to 1, FSR.failed to 1, FSR.current to 1, FSR.total to 3),
        )
        assertEquals("Held.", said(FSR.outcome to FSR.busy, FSR.reason to "Held."))
        assertEquals(
            "Stopped after updating 2 of 5 forms: The run hit its cap.",
            said(FSR.outcome to FSR.stopped, FSR.updated to 2, FSR.total to 5, FSR.reason to "The run hit its cap."),
        )
    }
}
