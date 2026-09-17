package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for the content/envelope line the read-only form views draw (issue #712):
 * [FormOpts.revealsDerivedAt] decides whether a `derived` field is shown read-only (a trait's computed value,
 * like an expense total) or stays hidden (a system stamp, like a gedra id or an entry's audit fields). The
 * render that then draws a revealed field is browser-driven; this pins the decision it rests on.
 */
class DerivedRevealTest {
    // The survey View Info: the whole form root is one trait's data type, so every derived-with-value field is
    // content.
    private val surveyOpts = FormOpts(showDerivedValues = true, derivedRootIsTraitData = true)

    // The raw read-only view over a whole document: trait content lives under a `data` payload; everything
    // outside it (the document id, an entry's stamps) is envelope.
    private val rawOpts = FormOpts(showDerivedValues = true, traitDataField = "data")

    @Test
    fun surveyRootRevealsADerivedContentValue() {
        // Root of a trait data form, a non-blank derived value, read-only.
        assertTrue(surveyOpts.revealsDerivedAt("totalAmount", editable = false, value = 50))
    }

    @Test
    fun aBlankDerivedValueStaysHidden() {
        assertFalse(surveyOpts.revealsDerivedAt("totalAmount", editable = false, value = null))
        assertFalse(surveyOpts.revealsDerivedAt("totalAmount", editable = false, value = ""))
    }

    @Test
    fun anEditableFormNeverReveals() {
        // No control to draw for a derived field, so editing hides it regardless of value.
        assertFalse(surveyOpts.revealsDerivedAt("totalAmount", editable = true, value = 50))
    }

    @Test
    fun optingOutRevealsNothing() {
        // showDerivedValues off, or no boundary set: the master switch and a boundary are both required.
        assertFalse(FormOpts().revealsDerivedAt("totalAmount", editable = false, value = 50))
        assertFalse(FormOpts(showDerivedValues = true).revealsDerivedAt("entries[0].data.totalAmount", editable = false, value = 50))
    }

    @Test
    fun rawViewRevealsContentUnderTheDataBoundary() {
        // A derived value inside a trait's data payload is content and shows.
        assertTrue(rawOpts.revealsDerivedAt("entries[0].data.totalAmount", editable = false, value = 50))
    }

    @Test
    fun rawViewHidesEnvelopeOutsideTheDataBoundary() {
        // The document id at the root, and an entry's audit stamps beside (not under) its data, are envelope.
        assertFalse(rawOpts.revealsDerivedAt("gedraId", editable = false, value = "gd.fd.acme.x"))
        assertFalse(rawOpts.revealsDerivedAt("entries[0].entryId", editable = false, value = "e-1"))
        assertFalse(rawOpts.revealsDerivedAt("entries[0].createdAt", editable = false, value = "2026-01-01T00:00:00Z"))
    }

    @Test
    fun rawViewRevealsContentNestedBelowData() {
        // A derived value deeper inside the data payload (a computed field on a nested object) is still content.
        assertTrue(rawOpts.revealsDerivedAt("entries[0].data.summary.total", editable = false, value = 7))
    }
}
