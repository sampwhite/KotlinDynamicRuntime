package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.user.PERSONA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The person behind a user in the Users editor (issue #770): the sibling rows and the summary. */
class UserIdentityViewTest {
    private fun user(
        id: Long, client: String, persona: String = PERSONA.member, suffix: String = "",
        enabled: Boolean = true, registered: Boolean = true,
    ) = AdminUser(
        userId = id, primaryId = "p@example.com", username = "@p", roles = listOf("user"), client = client,
        persona = persona, personaSuffix = suffix, org = null, isEntity = false, name = null, enabled = enabled,
        hasPassword = false, registered = registered, deleted = false,
    )

    @Test
    fun aPersonWithOneUserHasNoSiblingList() {
        assertTrue(identitySiblings(listOf(user(1, CL.hub)), 1L).isEmpty())
    }

    @Test
    fun siblingsKeepTheirOrderAndMarkTheOpenOne() {
        val users = listOf(user(1, CL.public), user(2, CL.hub), user(3, CL.hub, suffix = "B"))
        val rows = identitySiblings(users, 2L)
        assertEquals(listOf(1L, 2L, 3L), rows.map { it.user.userId })
        assertEquals(listOf(false, true, false), rows.map { it.selected })
        // Each says what tells it apart, as the badge does.
        assertEquals(listOf("public · Member", "hub · Member", "hub · Member B"), rows.map { it.label })
    }

    @Test
    fun aSiblingsStatusIsTheListsStatus() {
        val rows = identitySiblings(listOf(user(1, CL.hub), user(2, CL.hub, suffix = "B", registered = false)), 1L)
        assertEquals("enabled", rows[0].status)
        assertEquals("enabled, unclaimed", rows[1].status)
    }

    @Test
    fun theSummarySaysWhoALoginLandsOn() {
        val me = user(1, CL.hub)
        val other = user(2, CL.hub, suffix = "B")
        fun signsInAs(id: Long?) =
            identitySummary(AdminIdentity(null, false, id, listOf(me, other)), me).toMap().getValue("Signs in as")
        assertEquals("This user", signsInAs(1L))
        assertEquals("Member B", signsInAs(2L))
        // Absent when it is nobody the viewer administers.
        assertEquals("—", signsInAs(null))
    }

    @Test
    fun theSummaryStatesTheIdentitysFacts() {
        val me = user(1, CL.hub)
        val summary = identitySummary(AdminIdentity(null, false, 1L, listOf(me)), me).toMap()
        assertEquals("Not yet proven", summary.getValue("Address"))
        assertEquals("Not set", summary.getValue("Password"))
        assertEquals("—", summary.getValue("Registered"))
        val proven = identitySummary(AdminIdentity("2026-09-23T10:00:00Z", true, 1L, listOf(me)), me).toMap()
        assertTrue(proven.getValue("Address").startsWith("Proven "))
        assertEquals("Set", proven.getValue("Password"))
    }
}
