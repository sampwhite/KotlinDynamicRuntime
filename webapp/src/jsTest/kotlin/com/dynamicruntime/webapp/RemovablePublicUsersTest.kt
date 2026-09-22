package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.UserChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which `public` users the profile page offers to remove (issue #752). */
class RemovablePublicUsersTest {
    private fun user(id: Long, client: String, suffix: String = "") =
        UserChoice(userId = id, client = client, persona = PERSONA.member, personaSuffix = suffix)

    @Test
    fun everyPublicUserIsOfferedAndNoOtherOne() {
        val users = listOf(user(1, CL.public), user(2, CL.public, "B"), user(3, "acme"))
        assertEquals(listOf(1L, 2L), removablePublicUsers(users).map { it.userId })
        assertTrue(removablePublicUsers(listOf(user(3, "acme"))).isEmpty())
    }

    @Test
    fun aPersonsOnlyPublicUserIsOfferedToo() {
        // They registered themselves, so they may remove everything and register again.
        assertEquals(listOf(1L), removablePublicUsers(listOf(user(1, CL.public))).map { it.userId })
    }

    @Test
    fun theCopySaysWhenTheWholeRegistrationGoes() {
        assertTrue(removalEndsEverything(listOf(user(1, CL.public))))
        assertTrue(!removalEndsEverything(listOf(user(1, CL.public), user(2, CL.hub))))
        assertTrue(!removalEndsEverything(listOf(user(1, CL.public), user(2, CL.public, "B"))))
    }
}
