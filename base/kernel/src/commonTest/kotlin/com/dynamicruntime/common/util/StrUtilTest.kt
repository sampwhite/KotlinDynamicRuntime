package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isEmailAddress] in `commonTest`, so the exact source that the admin create form runs in the browser and the
 * `userCreate` endpoint runs on the JVM is exercised on both targets -- the two must agree on what an address
 * is, or one would accept what the other refuses.
 */
class StrUtilTest {

    @Test
    fun acceptsOrdinaryAddresses() {
        for (good in listOf(
            "ada@example.com",
            "ada.lovelace@example.com",
            "ada+acme@example.co.uk",
            "a_b-c@sub.domain.io",
            "user123@my-host.dev",
            "x@y.io",
        )) {
            assertTrue(good.isEmailAddress(), "should accept '$good'")
        }
    }

    @Test
    fun rejectsTheObviousNonAddresses() {
        for (bad in listOf(
            "test_august",           // the reported bug: a bare username, no '@'
            "",                       // empty
            "@example.com",           // no local part
            "ada@",                   // no domain
            "ada@localhost",          // dotless domain -- a host, not an address
            "ada@@example.com",       // two '@'
            "ada example@x.com",      // internal space
            " ada@x.com",             // leading space (checked as given, not trimmed)
            "ada@x.c",                // one-letter TLD
            "ada@x.123",              // numeric TLD
            "ada@x..com",             // empty label
            "ada@-x.com",             // label starts with hyphen
            ".ada@x.com",             // local starts with dot
            "ada.@x.com",             // local ends with dot
            "ad..a@x.com",            // doubled dot in local
        )) {
            assertFalse(bad.isEmailAddress(), "should reject '$bad'")
        }
    }
}
