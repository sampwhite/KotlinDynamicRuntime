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
    private fun survey(complete: Boolean, valid: Boolean) = mapOf(
        GE.traitId to SVY.surveyCompletion,
        GE.data to mapOf(SVY.complete to complete, SVY.valid to valid),
    )

    private fun cfacts(vararg facts: String) = mapOf(GE.traitId to GT.cfacts, GE.data to mapOf(GT.facts to facts.toList()))

    @Test
    fun theSurveyStatusStandsWhenNoWorkflowSaysMore() {
        assertEquals(SVYS.valid, formStatusOf(listOf(survey(complete = true, valid = true))))
        assertEquals(SVYS.needsInfo, formStatusOf(listOf(survey(complete = false, valid = true))))
        assertEquals(SVYS.invalid, formStatusOf(listOf(survey(complete = true, valid = false))))
        // No survey state, no status -- whatever a workflow emits.
        assertNull(formStatusOf(listOf(cfacts(WSC.needsReview))))
    }

    @Test
    fun aSingletonTakesThePlaceOfValidOnly() {
        assertEquals(SVYS.needsReview, formStatusOf(listOf(survey(true, true), cfacts(WSC.needsReview))))
        assertEquals(SVYS.finished, formStatusOf(listOf(survey(true, true), cfacts(WSC.finished))))
        // The owner's own work first: Needs Info and Invalid are not replaced.
        assertEquals(SVYS.needsInfo, formStatusOf(listOf(survey(false, true), cfacts(WSC.needsReview))))
        assertEquals(SVYS.invalid, formStatusOf(listOf(survey(true, false), cfacts(WSC.finished))))
    }

    @Test
    fun needsReviewWinsOverFinished() {
        assertEquals(SVYS.needsReview, formStatusOf(listOf(survey(true, true), cfacts(WSC.finished, WSC.needsReview))))
    }
}
