package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the developer hint beside the Google sign-in button (issue #250).
 *
 * What is worth pinning here is not the sentence but its *stance*. The component cannot tell a working origin
 * from a rejected one — an `error_callback` on `initialize` never fires for an unregistered origin — so the
 * hint has to read as a standing requirement rather than a detected fault. A future edit that sharpens it into
 * "this origin is not registered" would be a red warning on every healthy dev instance, and that is exactly
 * the change these assertions are here to catch.
 */
class GoogleOriginHintTest {

    @Test
    fun namesTheOriginItWasGiven() {
        // The whole point is handing over the exact string to paste into the Cloud Console.
        assertContains(googleOriginHint("http://localhost:8080"), "http://localhost:8080")
        assertContains(googleOriginHint("http://127.0.0.1:7071"), "http://127.0.0.1:7071")
    }

    @Test
    fun saysWhatToRegisterItAs() {
        // The Console's own wording, so the sentence and the field a developer is hunting for match.
        assertContains(googleOriginHint("http://localhost:7070"), "Authorized JavaScript origin")
    }

    /** Both observed symptoms are named, since either one is where a developer arrives from. */
    @Test
    fun namesTheSymptomsThatLeadHere() {
        val hint = googleOriginHint("http://localhost:8080")
        assertContains(hint, "Access blocked")
        assertContains(hint, "does nothing")
    }

    /**
     * It must not assert that anything is wrong. The component has no way to know, and a message that cries
     * wolf on a healthy instance is one nobody reads on the day it is right.
     */
    @Test
    fun doesNotClaimTheOriginIsUnregistered() {
        val hint = googleOriginHint("http://localhost:8080").lowercase()
        assertTrue(hint.contains("requires"), "should state a requirement")
        assertFalse(hint.contains("is not registered"), "must not claim a fault it cannot detect")
        assertFalse(hint.contains("error"), "must not read as an error")
        assertFalse(hint.contains("failed"), "must not read as a failure")
    }
}
