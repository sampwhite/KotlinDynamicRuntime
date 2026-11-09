package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Clients page's Form states section (issue #1412): when it offers a recalculation, and what it says one did. */
class FormStatesTest {
    // The count held to the limit is the backend's own; `forms` differs here to show the page judges by that one.
    private fun row(
        count: Int,
        limit: Int = 2000,
        status: ClientStatus = ClientStatus.present,
        autoLimit: Int = 200,
        manual: Boolean = false,
    ) = parseClientOverview(
        listOf(
            mapOf(
                CLD.clientId to "acme", CLD.name to "Acme", CLD.status to status.name, CLD.origin to GedraConfigOrigin.stored.name,
                CLD.forms to 1, FSR.count to count, FSR.limit to limit, FSR.autoLimit to autoLimit,
            ) + (if (manual) mapOf(CLD.manualFormStates to true) else emptyMap()),
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
    fun itSaysWhetherASavedChangeBringsTheFormsUpToDate() {
        assertEquals(200, row(5).formStatesAutoLimit)
        assertTrue("brings its forms up to date as it is saved, while it holds no more than 200" in formStatesOffer(row(200)).auto!!)
        formStatesOffer(row(201)).auto!!.let { assertTrue("this one holds 201" in it && "nightly" in it) }
        assertTrue("definition asks" in formStatesOffer(row(5, manual = true)).auto!!)
        assertTrue("brings no client's forms" in formStatesOffer(row(5, autoLimit = 0)).auto!!)
        assertNull(formStatesOffer(row(5, status = ClientStatus.notEnabled)).auto)
    }

    @Test
    fun aSaveSaysWhatBecameOfTheForms() {
        fun auto(client: String = "acme", limit: Long? = 200, vararg results: Pair<String, Any?>) =
            formStatesAutoOf(mapOf(FSR.auto to mapOf(FSR.client to client, FSR.autoLimit to limit, *results)))
        assertNull(formStatesAutoOf(emptyMap()))
        assertEquals(
            "Brought all 3 of this client's forms up to date with the change.",
            formStatesAutoText(auto(results = arrayOf(FSR.outcome to FSR.completed, FSR.updated to 3, FSR.total to 3))),
        )
        assertEquals(
            "Brought 1 of the sandbox's 4 forms up to date with the change; the rest already were.",
            formStatesAutoText(
                auto("acme:sandbox", results = arrayOf(FSR.outcome to FSR.completed, FSR.updated to 1, FSR.current to 3, FSR.total to 4)),
            ),
        )
        assertEquals(
            "This client's 2 forms were already up to date with the change.",
            formStatesAutoText(auto(results = arrayOf(FSR.outcome to FSR.completed, FSR.current to 2, FSR.total to 2))),
        )
        // Every form failed: not "brought 0 ... the rest already were".
        formStatesAutoText(auto(results = arrayOf(FSR.outcome to FSR.completed, FSR.failed to 2, FSR.total to 2)))!!.let {
            assertTrue(it.startsWith("None of this client's 2 forms was brought up to date with the change."))
            assertTrue("could not be stored" in it)
        }
        formStatesAutoText(auto(results = arrayOf(FSR.outcome to FSR.tooMany, FSR.total to 201)))!!.let {
            assertTrue("201 forms are more than the 200" in it && "nightly" in it)
        }
        assertTrue("by hand" in formStatesAutoText(auto(limit = null, results = arrayOf(FSR.outcome to FSR.manual)))!!)
        assertTrue("This deployment" in formStatesAutoText(auto(limit = null, results = arrayOf(FSR.outcome to FSR.off)))!!)
        assertTrue("already running" in formStatesAutoText(auto(limit = null, results = arrayOf(FSR.outcome to FSR.busy)))!!)
        assertNull(formStatesAutoText(auto(limit = null, results = arrayOf(FSR.outcome to FSR.noForms))))
        // The save's own note leads, a draft's words after it, then the forms.
        assertEquals(
            "Saved. Saved as a draft: the client's sandbox shows it, and it goes live once published. " +
                "Brought all 3 of the sandbox's forms up to date with the change.",
            savedNote(
                "Saved.",
                EDM.draft,
                auto("acme:sandbox", results = arrayOf(FSR.outcome to FSR.completed, FSR.updated to 3, FSR.total to 3)),
            ),
        )
        assertEquals("Published base.", publishedNote(listOf("base"), null))
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
