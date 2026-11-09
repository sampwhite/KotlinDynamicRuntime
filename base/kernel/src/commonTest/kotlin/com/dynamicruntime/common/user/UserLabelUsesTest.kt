package com.dynamicruntime.common.user

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a label does, said the one way (issues #1658, #1661), on JVM and JS alike. */
class UserLabelUsesTest {
    private val approves = UserLabelUse(UserLabelUseKind.approves, "plan", "Plan", "approve", "Approve it", "kdr:reviewer")
    private val saves = UserLabelUse(UserLabelUseKind.saves, "visit", "Site visit", "followUp", "Record the follow-up", "kdr:reviewer")
    private val sees = UserLabelUse(UserLabelUseKind.sees, "plan", "Plan")
    private val shows = UserLabelUse(UserLabelUseKind.tests, "plan", "Plan", "arrange", "Arrange", "kdr:reviewer")

    @Test
    fun aUseRoundTripsAndAnUnknownKindIsSkipped() {
        val back = UserLabelUse.fromJson(saves.toJsonMap())!!
        assertEquals(saves.toJsonMap(), back.toJsonMap())
        assertEquals("Saves “Record the follow-up” in “Site visit”", back.sentence)
        assertEquals(null, UserLabelUse.fromJson(saves.toJsonMap() + (ULU.kind to "somethingNewer")))
        // A task with no label is named by its id.
        assertEquals("Changes what “arrange” in “Plan” shows", UserLabelUse(UserLabelUseKind.tests, "plan", "Plan", "arrange").sentence)
    }

    @Test
    fun nobodyCarryingALabelSaysWhatGoesUndone() {
        val details = listOf(
            UserLabelDetail("reviewer", listOf(approves, sees, shows), carriers = 0),
            UserLabelDetail("siteLead", listOf(saves), carriers = 0),
            UserLabelDetail("carried", listOf(approves), carriers = 2),
            UserLabelDetail("idle", listOf(shows), carriers = 0),
        )
        assertEquals(
            listOf(
                "Nobody carries “reviewer”, so nobody can approve “Approve it” in “Plan”, and only the client's " +
                    "administrators can see “Plan”.",
                "Nobody carries “siteLead”, so nobody can save “Record the follow-up” in “Site visit”.",
            ),
            nobodyCarriesWarnings(details),
        )
    }
}
