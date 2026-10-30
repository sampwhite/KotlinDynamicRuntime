package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.test.TEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Becoming someone on a test instance (issues #1183, #1511): the requests, and the identities kept. */
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

    private fun adminUser(
        id: Long = 7L, enabled: Boolean = true, registered: Boolean = true, deleted: Boolean = false, opened: Boolean = false,
        persona: String = "member", suffix: String = "",
    ) = AdminUser(
        userId = id, primaryId = "pat@acme.test", username = "@pat", roles = listOf("user"), client = "acme", persona = persona,
        personaSuffix = suffix, org = null, isEntity = false, name = null, enabled = enabled, hasPassword = false,
        registered = registered, deleted = deleted, opened = opened,
    )

    @Test
    fun theUsersPageOffersToBecomeAUserOnlyWhereItCanWork() {
        fun offered(user: AdminUser, testAccess: Boolean = true, caller: Long? = 1L, carried: Boolean = true) =
            becomeOffered(user, testAccess, caller, carried)
        assertTrue(offered(adminUser()))
        // The fixture exists only for a caller with test access.
        assertFalse(offered(adminUser(), testAccess = false))
        // Not the caller's own user.
        assertFalse(offered(adminUser(id = 1L)))
        // Nobody signs in to these.
        assertFalse(offered(adminUser(enabled = false)))
        assertFalse(offered(adminUser(deleted = true)))
        assertFalse(offered(adminUser(opened = true)))
        assertFalse(offered(adminUser(), carried = false))
        // An unclaimed user is offered, and asks first: becoming it claims it.
        assertTrue(offered(adminUser(registered = false)))
        assertTrue(becomeClaims(adminUser(registered = false)))
        assertFalse(becomeClaims(adminUser()))
    }

    @Test
    fun becomingFromTheUsersPageNamesExactlyThatUser() {
        // By its whole key, so a second user of the address is the one become, not the address's default.
        assertEquals(
            mapOf(
                TEP.email to "pat@acme.test", TEP.client to "acme", TEP.persona to "admin", TEP.personaSuffix to "B",
                // One gone since the page read it is refused, rather than made anew in its place.
                TEP.failIfUserMissing to true,
            ),
            becomeUserBody(adminUser(persona = "admin", suffix = "B")),
        )
        assertFalse(TEP.personaSuffix in becomeUserBody(adminUser()))
    }

    @Test
    fun forgettingDropsOnlyThatUserAndLeavesTheOrder() {
        val ada = identity("ada@acme.test")
        val adaB = identity("ada@acme.test", suffix = "B")
        val pat = identity("pat@acme.test")
        // By the whole key: the address's other user stays.
        assertEquals(listOf(ada, pat), forgetIdentity(listOf(ada, adaB, pat), adaB.identityKey))
        assertEquals(emptyList(), forgetIdentity(listOf(ada), ada.identityKey))
        // One not in the list changes nothing.
        assertEquals(listOf(ada, pat), forgetIdentity(listOf(ada, pat), adaB.identityKey))
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
