package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage (issue #161) for [Copy], the widget-group copy lookup: `t` returns the value or the
 * given default, `opt` returns the value or null, and [Copy.empty] falls back on every lookup.
 */
class CopyTest {

    private val copy = Copy(
        mapOf(
            "auth" to mapOf("title" to "Sign in", "subtitle" to "Welcome back"),
        ),
    )

    @Test
    fun tReturnsPresentValue() {
        assertEquals("Sign in", copy.t("auth", "title", "fallback"))
    }

    @Test
    fun tFallsBackWhenKeyOrNamespaceAbsent() {
        assertEquals("fallback", copy.t("auth", "missing", "fallback"))
        assertEquals("fallback", copy.t("absent", "title", "fallback"))
    }

    @Test
    fun optReturnsPresentValueOrNull() {
        assertEquals("Welcome back", copy.opt("auth", "subtitle"))
        assertNull(copy.opt("auth", "missing"))
        assertNull(copy.opt("absent", "title"))
    }

    @Test
    fun emptyCopyAlwaysFallsBack() {
        assertEquals("fallback", Copy.empty.t("auth", "title", "fallback"))
        assertNull(Copy.empty.opt("auth", "title"))
    }
}
