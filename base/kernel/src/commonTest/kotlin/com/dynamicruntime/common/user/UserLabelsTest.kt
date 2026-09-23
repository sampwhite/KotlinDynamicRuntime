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
}
