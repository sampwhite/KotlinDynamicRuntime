package com.dynamicruntime.common.gedra.workflow

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trait-lock refusal wording (issue #857), in `commonTest` so the source the backend's lock guard and the raw
 * editor both run is checked on both targets: names only, grouped by the workflow holding each lock.
 */
class TraitLockCopyTest {
    private val audit = LockNames("Site audit", "Audit review")
    private val followUp = LockNames("Site follow-up", "Site follow-up")

    @Test
    fun oneWorkflowReadsAsOneSentence() {
        assertEquals("Site audit is locked by Audit review and can't be changed now.", TraitLockCopy.changeRefused(listOf(audit)))
        assertEquals(
            "Site audit and Notes are locked by Audit review and can't be changed now.",
            TraitLockCopy.changeRefused(listOf(audit, LockNames("Notes", "Audit review"))),
        )
        assertEquals("You can override the lock by giving a reason.", TraitLockCopy.overrideOffer(1))
    }

    @Test
    fun severalWorkflowsSayWhichLocksWhich() {
        assertEquals(
            "Audit review locks Site audit and Site follow-up locks Site follow-up, so they can't be changed now.",
            TraitLockCopy.changeRefused(listOf(audit, followUp)),
        )
        assertEquals(
            "The form can't be deleted while it's in Audit review and Site follow-up, which lock its Site audit and " +
                "Site follow-up.",
            TraitLockCopy.deleteRefused(listOf(audit, followUp)),
        )
    }

    @Test
    fun anOverrideRefusalNeedsNoPossessive() {
        assertEquals(
            "You can't override the lock Site checks holds on Site audit.",
            TraitLockCopy.overrideRefused(listOf(LockNames("Site audit", "Site checks"))),
        )
    }
}
