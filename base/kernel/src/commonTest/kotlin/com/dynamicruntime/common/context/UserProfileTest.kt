package com.dynamicruntime.common.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the [UserProfile] `toUserInfo` <-> `fromUserInfo` round-trip -- the
 * mapping the frontend relies on to turn a `UserInfo` payload back into the typed object. Lives in the kernel's
 * `commonTest`, so it runs on both the jvm and js targets against the same source the frontend compiles.
 */
class UserProfileTest {

    @Test
    fun roundTripsAPopulatedProfile() {
        val original = UserProfile(
            authId = "auth-42",
            userId = 42L,
            client = "acme",
            roles = setOf("admin", "editor"),
            publicName = "Ada",
            hasPassword = true,
        )
        val restored = UserProfile.fromUserInfo(original.toUserInfo())

        assertEquals(original.authId, restored.authId)
        assertEquals(original.userId, restored.userId)
        assertEquals(original.client, restored.client)
        assertEquals(original.roles, restored.roles)
        assertEquals(original.publicName, restored.publicName)
        assertEquals(original.hasPassword, restored.hasPassword)
        assertTrue(restored.isLoggedIn)
    }

    @Test
    fun anonymousRoundTripsAsNotLoggedIn() {
        val restored = UserProfile.fromUserInfo(UserProfile.anonymous().toUserInfo())
        assertEquals(UserProfile.anonymousAuthId, restored.authId)
        assertFalse(restored.isLoggedIn)
    }

    @Test
    fun omitsPublicNameAndHasPasswordWhenAbsent() {
        // The fast-path profile carries no publicName and an unknown (null) hasPassword; toUserInfo omits both,
        // and fromUserInfo restores them as null rather than inventing a value.
        val info = UserProfile(authId = "auth-7", userId = 7L, client = "acme").toUserInfo()
        assertFalse(info.containsKey(UPF.publicName))
        assertFalse(info.containsKey(UPF.hasPassword))

        val restored = UserProfile.fromUserInfo(info)
        assertNull(restored.publicName)
        assertNull(restored.hasPassword)
    }

    @Test
    fun emptyInfoFallsBackToConstructorDefaults() {
        val restored = UserProfile.fromUserInfo(emptyMap())
        assertNull(restored.authId)
        assertEquals(CL.systemUserId.toLong(), restored.userId)
        assertEquals(CL.local, restored.client)
        assertTrue(restored.roles.isEmpty())
        assertFalse(restored.isLoggedIn)
    }
}

/**
 * The primary organization on the profile (issue #225): it rides with the identity rather than in a column, so
 * it has to survive the same round trips roles and client do.
 */
class UserProfileOrgTest {
    @Test
    fun orgIsAbsentByDefaultAndSurvivesTheInfoRoundTrip() {
        val none = UserProfile(authId = "1", userId = 1L, client = "acme")
        assertNull(none.org)
        assertFalse(none.toUserInfo().containsKey(UPF.org)) // omitted, not written as null
        assertNull(UserProfile.fromUserInfo(none.toUserInfo()).org)

        val inOrg = UserProfile(authId = "1", userId = 1L, client = "acme", org = "eng")
        assertEquals("eng", inOrg.toUserInfo()[UPF.org])
        assertEquals("eng", UserProfile.fromUserInfo(inOrg.toUserInfo()).org)
    }
}

/**
 * Named accounts: `isEntity`/`name` ride on the profile like `org` does, and `displayName` is the one rule --
 * shared by the app bar and the profile page -- for which name to present.
 */
class UserProfileNameTest {

    @Test
    fun nameFieldsAreAbsentByDefaultAndSurviveTheRoundTrip() {
        val unnamed = UserProfile(authId = "1", userId = 1L, client = "acme", publicName = "ada")
        assertFalse(unnamed.isEntity)
        assertNull(unnamed.name)
        // Omitted, not written as false/null, so an unnamed account's payload does not carry them.
        assertFalse(unnamed.toUserInfo().containsKey(UPF.isEntity))
        assertFalse(unnamed.toUserInfo().containsKey(UPF.name))

        val biz = UserProfile(authId = "1", userId = 1L, client = "acme", publicName = "acme_co", isEntity = true, name = "Acme Co")
        val restored = UserProfile.fromUserInfo(biz.toUserInfo())
        assertTrue(restored.isEntity)
        assertEquals("Acme Co", restored.name)

        // A person's full name round-trips the same way, with no entity flag involved.
        val person = UserProfile(authId = "1", userId = 1L, client = "acme", publicName = "ada", name = "Ada Lovelace")
        val restoredPerson = UserProfile.fromUserInfo(person.toUserInfo())
        assertFalse(restoredPerson.isEntity)
        assertEquals("Ada Lovelace", restoredPerson.name)
    }

    @Test
    fun displayNameIsTheAccountsOwnNameWhicheverKindItIs() {
        // A business shows its business name in place of the login/username...
        assertEquals(
            "Acme Co",
            UserProfile(authId = "1", publicName = "acme_co", isEntity = true, name = "Acme Co").displayName,
        )
        // ...and a person shows their full name, which the username used to stand in for.
        assertEquals(
            "Ada Lovelace",
            UserProfile(authId = "1", publicName = "ada", name = "Ada Lovelace").displayName,
        )
    }

    /** The flag says what the name *means*, not where to find it -- so it must not change what is displayed. */
    @Test
    fun theEntityFlagDoesNotChangeWhichNameIsDisplayed() {
        val asPerson = UserProfile(authId = "1", publicName = "acme_co", name = "Acme Co")
        val asBusiness = UserProfile(authId = "1", publicName = "acme_co", isEntity = true, name = "Acme Co")
        assertEquals(asPerson.displayName, asBusiness.displayName)
    }

    @Test
    fun anAccountWithoutAUsableNameFallsBackToThePublicName() {
        // Having no name yet is not a reason to show nothing: the login identity still stands in.
        assertEquals("acme_co", UserProfile(authId = "1", publicName = "acme_co", name = null).displayName)
        assertEquals("acme_co", UserProfile(authId = "1", publicName = "acme_co", name = "   ").displayName)
        assertEquals("acme_co", UserProfile(authId = "1", publicName = "acme_co", isEntity = true, name = null).displayName)
    }
}

/**
 * The copy-safety guarantees [UserProfile] became a data class for (issue #282).
 *
 * These are the tests that go on working when someone adds a field. Both assert on the **whole object**, not
 * field by field -- an enumerated assertion is the same shape as the bug, and would pass while quietly
 * ignoring the new field it does not mention.
 */
class UserProfileCopyTest {

    /** Every field set to a non-default, so nothing can pass by coinciding with a default. */
    private fun populated() = UserProfile(
        authId = "auth-42",
        userId = 42L,
        client = "acme",
        org = "engineering",
        roles = setOf("user", "admin"),
        publicName = "ada@example.com",
        isEntity = true,
        name = "Acme Co",
        hasPassword = true,
    )

    /**
     * The #282 bug in miniature: take a profile, change only the roles, and everything else must survive.
     * `refreshActingRoles` did this by hand and dropped `org`, then the entity fields.
     */
    @Test
    fun copyingWithNewRolesPreservesEverythingElse() {
        val original = populated()
        val copied = original.copy(roles = setOf("user"))

        assertEquals(setOf("user"), copied.roles)
        // Compared as whole objects: the point is the fields nobody thought to name.
        assertEquals(original, copied.copy(roles = original.roles))
    }

    /**
     * The serialization pair is the same hazard in another place, and structural equality makes this total:
     * a field added to the class but forgotten in `toUserInfo` or `fromUserInfo` fails here without anyone
     * remembering to extend the test.
     */
    @Test
    fun theUserInfoRoundTripCarriesEveryField() {
        val original = populated()
        assertEquals(original, UserProfile.fromUserInfo(original.toUserInfo()))
    }

    /**
     * Becoming a data class would otherwise have put the account's real name and email into any log line that
     * interpolated a profile, so [UserProfile.toString] is overridden. Assert what it must *not* carry.
     */
    @Test
    fun toStringDoesNotLeakNamesOrEmails() {
        val text = populated().toString()
        assertFalse(text.contains("ada@example.com"))
        assertFalse(text.contains("Acme Co"))
        // Still useful for the thing a profile is actually debugged for: who is acting, and what they may do.
        assertTrue(text.contains("42"))
        assertTrue(text.contains("admin"))
    }
}

