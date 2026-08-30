package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.evalTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage (issue #161) for [Copy], the widget-group copy lookup: `t` returns the value or the
 * given default, `opt` returns the value or null, and [Copy.empty] falls back on every lookup. Plus
 * [Copy.fragmentResolver], the frontend `@t` resolver over a delivered copy (issue #505).
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

    @Test
    fun fragmentResolverResolvesTwoPartKeysOverTheCopy() {
        val r = copy.fragmentResolver()
        assertEquals("Sign in", r.resolve("auth.title"))
        assertNull(r.resolve("auth.missing"))
        assertNull(r.resolve("absent.title"))
        assertNull(r.resolve("title"), "a one-part key names no value")
    }

    @Test
    fun aTemplateAtPullResolvesThroughTheResolver() {
        // The end-to-end frontend path in miniature: a `${@t("ns.key")}` string resolved against this copy.
        val rendered = $$"""Copy says: ${@t("auth.subtitle")}""".evalTemplate(emptyMap(), resolver = copy.fragmentResolver())
        assertEquals("Copy says: Welcome back", rendered)
    }
}
