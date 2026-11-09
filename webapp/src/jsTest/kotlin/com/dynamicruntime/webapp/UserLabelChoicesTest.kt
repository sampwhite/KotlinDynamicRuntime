package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.ULU
import com.dynamicruntime.common.user.UserLabelDetail
import com.dynamicruntime.common.user.UserLabelUse
import com.dynamicruntime.common.user.UserLabelUseKind
import com.dynamicruntime.common.user.joinedWithOr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Users editor's Labels group (issue #1659): a row per suggested label saying what the client's workflows do with
 * it, free entry for any other, and the prompt for a user with none -- as read from the label-suggestions answer
 * (issue #1658), the client's notes included (issue #1660).
 */
class UserLabelChoicesTest {
    private val approves = UserLabelUse(UserLabelUseKind.approves, "plan", "Plan the logistics", "approve", "Approve the plan", "kdr:reviewer")
    private val sees = UserLabelUse(UserLabelUseKind.sees, "plan", "Plan the logistics")
    private val shows = UserLabelUse(UserLabelUseKind.tests, "plan", "Plan the logistics", "arrange", "Arrange", "kdr:reviewer")
    private val details = listOf(
        UserLabelDetail("reviewer", listOf(approves, sees), carriers = 2, note = "Whoever signs off plans."),
        UserLabelDetail("siteLead", listOf(shows), carriers = 0),
        UserLabelDetail("spare", emptyList(), carriers = 1),
    )

    @Test
    fun theAnswerIsReadIntoWhatTheControlOffersAndEachLabelsDetails() {
        val read = labelSuggestionsFrom(
            mapOf(
                ADF.labels to listOf("reviewer"),
                ADF.labelsInUse to listOf("Reviewer", "adHoc"),
                ULU.details to listOf(details[0].toJsonMap()),
            ),
        )
        assertEquals(listOf("reviewer", "adHoc"), read.offered)
        val detail = read.details.single()
        assertEquals("Whoever signs off plans.", detail.note)
        assertEquals(listOf(UserLabelUseKind.approves, UserLabelUseKind.sees), detail.uses.map { it.kind })
        assertEquals(2, detail.carriers)
        // A backend that sends no details leaves the rows empty, not the page broken.
        assertEquals(emptyList(), labelSuggestionsFrom(mapOf(ADF.labels to listOf("reviewer"))).details)
    }

    @Test
    fun eachSuggestedLabelIsARowSayingWhatItDoes() {
        val rows = labelRows(details, listOf(" reviewer", "adHoc"))
        assertEquals(listOf("reviewer", "siteLead", "spare"), rows.map { it.label })
        assertEquals(listOf(true, false, false), rows.map { it.checked })
        assertEquals("Whoever signs off plans.", rows[0].note)
        assertEquals(
            listOf(
                "Approves “Approve the plan” in “Plan the logistics”",
                "Sees “Plan the logistics”, which is restricted to the holders of its labels",
            ),
            rows[0].uses,
        )
        assertEquals("2 users carry it.", rows[0].carriers)
        assertNull(rows[1].note)
        assertEquals("Nobody carries it yet.", rows[1].carriers)
        assertEquals("1 user carries it.", rows[2].carriers)
    }

    @Test
    fun checkingAndTypingKeepTheOtherHalfOfTheDraft() {
        val draft = listOf("adHoc", "reviewer")
        assertEquals(listOf("adHoc"), otherLabels(details, draft))
        assertEquals(listOf("adHoc", "reviewer", "spare"), withLabel(draft, "spare", on = true))
        assertEquals(listOf("adHoc"), withLabel(draft, "reviewer", on = false))
        // Typing replaces only the labels the client does not suggest.
        assertEquals(listOf("reviewer", "other"), withOtherLabels(details, draft, listOf("other")))
    }

    @Test
    fun aUserWithNoLabelsIsToldWhatTheyCannotDo() {
        assertEquals(
            listOf("Without “reviewer”, this user can't approve “Approve the plan” in “Plan the logistics” or see “Plan the logistics”."),
            missingLabelsPrompt(details, emptyList()),
        )
        // Once any label is chosen the rows have been seen; and a label that only changes what a task shows withholds nothing.
        assertEquals(emptyList(), missingLabelsPrompt(details, listOf("spare")))
        assertEquals(emptyList(), missingLabelsPrompt(listOf(details[1]), emptyList()))
    }

    @Test
    fun aListIsJoinedAsASentenceListsIt() {
        assertEquals("a", joinedWithOr(listOf("a")))
        assertEquals("a or b", joinedWithOr(listOf("a", "b")))
        assertEquals("a, b, or c", joinedWithOr(listOf("a", "b", "c")))
    }
}
