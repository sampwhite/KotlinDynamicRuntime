package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.JOIN
import com.dynamicruntime.common.user.PERSONA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-logic coverage for the Join page (issue #1248): what the endpoint's answer reads as, and what the page says. */
class JoinApiTest {

    private val envelope = mapOf(
        EP.items to listOf(
            mapOf(
                JOIN.client to "confdemo",
                JOIN.clientName to "Conference demo",
                JOIN.description to "A conference's call for talks.",
                JOIN.roles to listOf(
                    mapOf(JOIN.name to "speaker", JOIN.label to "Speaker", JOIN.persona to PERSONA.member, JOIN.summary to "Submit a talk."),
                    mapOf(JOIN.name to "committee", JOIN.label to "Committee member", JOIN.persona to PERSONA.admin, JOIN.summary to "Review.", JOIN.userId to 42),
                ),
            ),
            // One with no id is not a client; it is skipped rather than drawn.
            mapOf(JOIN.clientName to "Nameless"),
        ),
    )

    @Test
    fun theEndpointsAnswerReadsAsClientsWithTheirRoles() {
        val rows = parseJoinable(envelope)
        assertEquals(listOf("confdemo"), rows.map { it.client })
        val demo = rows.single()
        assertEquals("Conference demo", demo.name)
        assertEquals(listOf("speaker", "committee"), demo.roles.map { it.name })
        assertNull(demo.roles[0].userId)
        assertEquals(42L, demo.roles[1].userId)
        assertTrue(demo.roles[1].isAdmin)
        assertTrue(!demo.roles[1].removed)
    }

    @Test
    fun aRoleWhoseUserWasRemovedSaysSo() {
        val removed = parseJoinable(
            mapOf(EP.items to listOf(mapOf(JOIN.client to "acme", JOIN.roles to listOf(mapOf(JOIN.name to "admin", JOIN.removed to true))))),
        ).single().roles.single()
        assertTrue(removed.removed)
        assertNull(removed.userId)
    }

    @Test
    fun theDisclosureSaysWhatOtherVisitorsSee() {
        val text = joinDisclosure(parseJoinable(envelope).single()).joinToString(" ")
        assertTrue("shared demo space" in text, text)
        assertTrue("name and email address" in text, text)
        assertTrue("deleted whenever this site's data is cleared" in text, text)
        // A client offering an administrator's role says the visitor, as one, can do the same.
        assertTrue("As one, you can do the same to theirs." in text, text)
    }

    @Test
    fun aClientOfferingNoAdministratorRoleDoesNotSayTheVisitorIsOne() {
        val members = JoinableView("x", "X", null, listOf(JoinRoleView("speaker", "Speaker", PERSONA.member, "", null)))
        val text = joinDisclosure(members).joinToString(" ")
        assertTrue("As one" !in text, text)
    }

    @Test
    fun theJoinPageIsLinkedByItsClient() {
        assertEquals("#page=join&c=confdemo", joinHref("confdemo"))
    }
}
