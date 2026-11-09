package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The label rule the backend stores by and the admin console edits by (issue #786), on JVM and JS alike. */
class UserLabelsTest {
    @Test
    fun labelsAreTrimmedDedupedAndKeepTheirCase() {
        assertEquals(
            listOf("reviewer", "siteLead", "Reviewer"),
            normalizeUserLabels(listOf("  reviewer ", "siteLead", "reviewer", "", "   ", "Reviewer")),
        )
    }

    @Test
    fun anEditorOffersTheSuggestionsThenWhatTheUserAlreadyCarries() {
        // An off-list label the user carries stays offered, so it can be seen and removed.
        assertEquals(
            listOf("reviewer", "siteLead", "adHoc"),
            userLabelChoices(listOf("reviewer", "siteLead"), listOf("adHoc", "reviewer")),
        )
    }

    @Test
    fun theLabelsInUseFollowTheClientsOwnAndAreOfferedOnceIgnoringCase() {
        // Issue #1481: the client's spelling wins over a user's, and two users' spellings collapse to the first.
        assertEquals(
            listOf("Reviewer", "siteLead", "adHoc"),
            mergeLabelSuggestions(listOf("Reviewer", "siteLead"), listOf("adHoc", "ADHOC", "reviewer", " siteLead ")),
        )
    }

    @Test
    fun aClientsSuggestionsAreHeldToTheSameRule() {
        fun client(labels: List<String>) = ClientDef(
            clientId = "labels", name = "Labels", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
            enabledEnvironments = setOf(ENV.unit), userLabels = labels,
        )
        assertEquals(listOf("reviewer", "siteLead"), client(listOf("reviewer", "siteLead")).userLabels)
        // Refused where it is written, naming the offending entries -- rather than surfacing later as a workflow's
        // literal `reviewer` failing the suggestion check, blaming the workflow for the client's typo.
        val e = assertFailsWith<KdrException> { client(listOf(" reviewer", "siteLead", "siteLead", "")) }
        val message = e.message.orEmpty()
        assertTrue(message.contains("' reviewer'") && message.contains("'siteLead'") && message.contains("''"), message)
    }
    @Test
    fun labelNotesAreWrittenOnePerLineAndReadBack() {
        val text = "reviewer: Approves plans\n\nno colon here\n  siteLead :  Runs a site: and its follow-ups \n: no label\nempty:"
        val notes = labelNotesOfText(text)
        // The label before the first colon; a later colon is the note's own.
        assertEquals(mapOf("reviewer" to "Approves plans", "siteLead" to "Runs a site: and its follow-ups"), notes)
        assertEquals(notes, labelNotesOfText(labelNotesText(notes)))
    }

    @Test
    fun aClientsLabelNoteNamesALabelItSuggestsAndSaysSomething() {
        fun client(notes: Map<String, String>) = ClientDef(
            clientId = "notes", name = "Notes", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
            enabledEnvironments = setOf(ENV.unit), userLabels = listOf("reviewer"), userLabelNotes = notes,
        )
        assertEquals("Approves plans", client(mapOf("reviewer" to "Approves plans")).userLabelNotes["reviewer"])
        val unsuggested = assertFailsWith<KdrException> { client(mapOf("siteLead" to "Runs a site")) }
        assertTrue(unsuggested.message.orEmpty().contains("'siteLead'"), unsuggested.message)
        val blank = assertFailsWith<KdrException> { client(mapOf("reviewer" to "  ")) }
        assertTrue(blank.message.orEmpty().contains("blank"), blank.message)
    }
}
