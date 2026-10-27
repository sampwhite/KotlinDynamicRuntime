package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-row action status the web asset and file ref sections share (issue #1388): each transition made whole. */
class RowActionStatusTest {
    private val error = DisplayError("The file is too large.", DisplayError.Kind.message)

    @Test
    fun startingAnActionMarksItsControlAndClearsTheLastFailure() {
        val failed = RowActionStatus.cleared.refused("logo", "Too large.")
        val running = failed.started("logo", WACT.upload)
        assertTrue(running.running)
        assertTrue(running.isBusy("logo", WACT.upload))
        // Its other controls are disabled, not shown busy; another row's likewise.
        assertFalse(running.isBusy("logo", WACT.remove))
        assertTrue(running.othersBusy("logo", WACT.remove))
        assertFalse(running.othersBusy("logo", WACT.upload))
        assertNull(running.problemAt("logo"))
        assertNull(running.failureAt("logo"))
    }

    @Test
    fun aFailureOrARefusalIsShownBesideItsRowAndReplacesTheOtherKind() {
        val failed = RowActionStatus.cleared.started("logo", WACT.upload).failed("logo", error).finished()
        assertFalse(failed.running)
        assertEquals(error, failed.failureAt("logo"))
        assertNull(failed.failureAt("banner"))
        val refused = failed.refused("banner", "Too large.")
        assertEquals("Too large.", refused.problemAt("banner"))
        assertNull(refused.failureAt("logo"))
        assertNull(refused.failure)
        // And a refusal gives way to a failure.
        assertNull(refused.failed("banner", error).problem)
    }

    @Test
    fun oneConfirmationIsOpenAtATimeAndAnotherClientClearsEverything() {
        val asking = RowActionStatus.cleared.confirming("logo")
        assertEquals("logo", asking.confirming)
        // An action started from the question keeps nothing else, but the confirmation is the caller's to close.
        assertEquals("logo", asking.started("logo", WACT.remove).confirming)
        assertEquals("banner", asking.confirming("banner").confirming)
        assertNull(asking.confirming(null).confirming)
        val cleared = RowActionStatus.cleared
        assertFalse(cleared.running)
        assertNull(cleared.confirming)
        assertNull(cleared.failedAt)
    }
}
