package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.test.TEP
import kotlin.test.Test
import kotlin.test.assertEquals

/** Becoming someone on a test instance (issue #1183): the deployment admin's request, and the identities kept. */
class BecomeUserTest {
    private fun identity(email: String, client: String = "acme", persona: String = "member", suffix: String = "", dataId: String? = "d1") =
        RecentIdentity(email, client, persona, suffix, name = null, dataId = dataId, at = "2026-10-13T12:00:00Z")

    @Test
    fun theDeploymentAdminIsAnAdministratorOfEveryClient() {
        assertEquals(
            mapOf(TEP.email to TEP.deploymentAdminEmail, TEP.level to ROLE.admin, TEP.capabilities to listOf(ROLE.allClients)),
            deploymentAdminBody(),
        )
    }

    /** The address and personaSuffix come from the request, the client, persona and name from the user it became. */
    @Test
    fun anIdentityIsWhatWasAskedForWhereItLanded() {
        val got = recentIdentityOf(
            mapOf(TEP.email to "pat@acme.test", TEP.personaSuffix to "B"),
            mapOf(UPF.client to "acme", UPF.persona to "admin", UPF.name to "Pat Lee"),
            dataId = "d1", at = "now",
        )
        assertEquals("pat@acme.test|acme|admin|B", got.identityKey)
        assertEquals("pat@acme.test · acme / Admin B -- Pat Lee", identityLabel(got))
        // Becoming them again names the address and which of its users, not the level a create would take.
        assertEquals(
            mapOf(TEP.email to "pat@acme.test", TEP.client to "acme", TEP.persona to "admin", TEP.personaSuffix to "B"),
            becomeAgainBody(got),
        )
    }

    @Test
    fun theNewestComesFirstOnceAndTheListIsBounded() {
        val a = identity("a@acme.test")
        val b = identity("b@acme.test")
        assertEquals(listOf("a@acme.test", "b@acme.test"), rememberIdentity(listOf(a, b), a).map { it.email })
        assertEquals(listOf("b@acme.test", "a@acme.test"), rememberIdentity(listOf(a), b).map { it.email })
        // Another of the same address's users is another identity.
        assertEquals(2, rememberIdentity(listOf(a), identity("a@acme.test", persona = "admin")).size)
        val many = (1..20).fold(emptyList<RecentIdentity>()) { acc, i -> rememberIdentity(acc, identity("u$i@acme.test")) }
        assertEquals(recentIdentitiesLimit, many.size)
        assertEquals("u20@acme.test", many.first().email)
    }

    @Test
    fun identitiesFromDataSinceResetAreToldApart() {
        val (current, stale) = identitiesFor(listOf(identity("a@acme.test"), identity("b@acme.test", dataId = "old")), "d1")
        assertEquals(listOf("a@acme.test"), current.map { it.email })
        assertEquals(listOf("b@acme.test"), stale.map { it.email })
        // A node serving no data id keeps every identity current.
        assertEquals(2, identitiesFor(current + stale, null).first.size)
    }

    @Test
    fun identitiesSurviveStorageAndBadTextReadsAsNone() {
        val kept = listOf(identity("a@acme.test", suffix = "2"), identity("b@acme.test", dataId = null))
        val back = decodeIdentities(encodeIdentities(kept))
        assertEquals(kept.map { it.identityKey }, back.map { it.identityKey })
        assertEquals(null, back[1].dataId)
        assertEquals(emptyList(), decodeIdentities(null))
        assertEquals(emptyList(), decodeIdentities("not json"))
        assertEquals(emptyList(), decodeIdentities("[{\"client\": \"acme\"}]"))
    }
}
