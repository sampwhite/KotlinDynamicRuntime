package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [sameOriginPath] in `commonTest`, so the exact source the frontend runs in the browser and the edge runs on
 * the JVM is exercised on both targets (issue #498). That is the whole reason the rule was moved here: the two
 * had a copy each, and a guard that differs between the perimeter and the app it protects is the one that will
 * be found from outside.
 */
class UrlUtilTest {

    @Test
    fun acceptsOrdinarySameOriginPaths() {
        for (good in listOf("/", "/wa", "/ec/login", "/ec/login?next=%2Fwa", "/a/b/c#frag")) {
            assertEquals(good, sameOriginPath(good), "should accept '$good'")
        }
    }

    @Test
    fun trimsBeforeDeciding() {
        assertEquals("/wa", sameOriginPath("  /wa  "))
    }

    @Test
    fun refusesWhatAnOpenRedirectWouldNeed() {
        for (bad in listOf(
            "//evil.example.com",     // protocol-relative: followed off-site, looks local
            "/\\evil.example.com",    // the same, smuggled past a `/`-only check
            "/a\\b",                  // a backslash anywhere, normalized to `/` by browsers
            "https://evil.example.com",
            "javascript:alert(1)",
            "data:text/html,x",
            "wa",                     // not absolute
            "../wa",
            "",
            "   ",
        )) {
            assertNull(sameOriginPath(bad), "should refuse '$bad'")
        }
    }

    @Test
    fun refusesControlCharactersThatSplitOrTruncate() {
        // isISOControl rather than a `< ' '` test, which would miss DEL and the C1 range. A trailing newline
        // is trimmed away before the check, so the cases that matter are interior ones.
        for (bad in listOf(
            "/wa\r\nLocation: /evil", // header splitting
            "/wa\u0000/b",            // NUL, truncating
            "/wa\u007F/b",            // DEL -- missed by a `< ' '` test
            "/wa\u0085/b",            // C1 NEL -- likewise
        )) {
            assertNull(sameOriginPath(bad), "should refuse a path with a control character")
        }
    }

    @Test
    fun treatsNullAsNothingAsked() {
        assertNull(sameOriginPath(null))
    }
}
