package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The form status a forms-list chip shows and its filter matches (issues #694, #789), on the JVM and under
 * Kotlin/JS alike -- the frontend's chip and the backend's filter run this one rule.
 */
class FormStatusTest {
    private fun coreDetails(complete: Boolean, valid: Boolean) = mapOf(
        GE.traitId to CDF.coreDetailsCompletion,
        GE.data to mapOf(CDF.complete to complete, CDF.valid to valid),
    )

    private fun cfacts(vararg facts: String) = mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to facts.toList()))

    @Test
    fun theCoreDetailsStatusStandsWhenNoWorkflowSaysMore() {
        assertEquals(CDS.valid, formStatusOf(listOf(coreDetails(complete = true, valid = true))))
        assertEquals(CDS.needsInfo, formStatusOf(listOf(coreDetails(complete = false, valid = true))))
        assertEquals(CDS.invalid, formStatusOf(listOf(coreDetails(complete = true, valid = false))))
        // No core details state, no status -- whatever a workflow emits.
        assertNull(formStatusOf(listOf(cfacts(WSC.needsReview))))
    }

    @Test
    fun aSingletonTakesThePlaceOfValidOnly() {
        assertEquals(CDS.needsReview, formStatusOf(listOf(coreDetails(true, true), cfacts(WSC.needsReview))))
        assertEquals(CDS.finished, formStatusOf(listOf(coreDetails(true, true), cfacts(WSC.finished))))
        // The owner's own work first: Needs Info and Invalid are not replaced.
        assertEquals(CDS.needsInfo, formStatusOf(listOf(coreDetails(false, true), cfacts(WSC.needsReview))))
        assertEquals(CDS.invalid, formStatusOf(listOf(coreDetails(true, false), cfacts(WSC.finished))))
    }

    @Test
    fun needsReviewWinsOverFinished() {
        assertEquals(CDS.needsReview, formStatusOf(listOf(coreDetails(true, true), cfacts(WSC.finished, WSC.needsReview))))
    }
}
