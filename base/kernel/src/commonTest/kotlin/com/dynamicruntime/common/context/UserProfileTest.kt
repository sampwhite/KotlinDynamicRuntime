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
