package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The refresh re-reads that answer the same keep what they built (issue #1313). */
class ConfigReuseTest {
    private fun config(buildId: String = "b1", name: String = "Pat") = UiConfig(
        fragment = FragmentRef("home", buildId),
        features = mapOf("canManageUsers" to false),
        settings = mapOf("idleBumpIntervalMs" to 60000),
        state = mapOf("userInfo" to mapOf("name" to name, "roles" to listOf("user"))),
    )

    @Test
    fun theSameAnswerIsTheSame() {
        // Two parses of one response are different objects with the same content.
        assertTrue(config().answersSameAs(config()))
    }

    @Test
    fun anyDifferenceIsADifferentAnswer() {
        assertFalse(config().answersSameAs(null))
        assertFalse(config(buildId = "b2").answersSameAs(config()))
        // A sign-in changes the state, so the config built for the previous caller is never kept for the next.
        assertFalse(config(name = "Lee").answersSameAs(config()))
    }

    @Test
    fun aBoundedMapKeepsWhatItMadeAndForgetsTheOldest() {
        val map = BoundedMap<String, Any>(2)
        val first = Any()
        assertSame(first, map.getOrPut("a") { first })
        assertSame(first, map.getOrPut("a") { Any() })
        map.getOrPut("b") { Any() }
        map.getOrPut("c") { Any() }
        assertEquals(2, map.size)
        // The oldest went, so asking for it again makes a new one.
        val again = Any()
        assertSame(again, map.getOrPut("a") { again })
    }
}
