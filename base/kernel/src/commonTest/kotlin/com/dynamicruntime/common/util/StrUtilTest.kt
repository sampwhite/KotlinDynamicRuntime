package com.dynamicruntime.common.util

import kotlin.test.assertEquals
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

    /** The one stored form (issue #743): trimmed, lowercased, and still a valid address afterwards. */
    @Test
    fun normalizesAnAddressToItsStoredForm() {
        assertEquals("ada@example.com", "  Ada@Example.COM\t".normalizeEmail())
        assertEquals("ada+acme@example.com", "Ada+Acme@Example.com".normalizeEmail())
        assertTrue(" Ada@Example.com ".normalizeEmail().isEmailAddress())
        // Idempotent.
        assertEquals("ada@example.com", "ada@example.com".normalizeEmail().normalizeEmail())
    }

    /** A login id is an address (normalized) or a username (trimmed only -- its case is its own). */
    @Test
    fun normalizesALoginIdOnlyWhenItIsAnAddress() {
        assertEquals("ada@example.com", " Ada@Example.com ".normalizeLoginId())
        assertEquals("Ada_L", " Ada_L ".normalizeLoginId())
        assertEquals("", "   ".normalizeLoginId())
    }

    /** Names joined as a sentence says them, as the trait-lock messages do. */
    @Test
    fun joinsNamesAsAPhrase() {
        assertEquals("", joinAsPhrase(emptyList()))
        assertEquals("Audit review", joinAsPhrase(listOf("Audit review")))
        assertEquals("Audit review and Site follow-up", joinAsPhrase(listOf("Audit review", "Site follow-up")))
        assertEquals("A, B or C", joinAsPhrase(listOf("A", "B", "C"), last = "or"))
    }

    @Test
    fun aByteSizeReadsRoundedUpInTheUnitAPersonWouldUse() {
        assertEquals("0 bytes", byteSizeText(0))
        assertEquals("1 byte", byteSizeText(1))
        assertEquals("640 bytes", byteSizeText(640))
        assertEquals("1023 bytes", byteSizeText(1023))
        assertEquals("1 KB", byteSizeText(1024))
        assertEquals("2 KB", byteSizeText(1025))
        assertEquals("256 KB", byteSizeText(256 * 1024))
        assertEquals("1023 KB", byteSizeText(1023 * 1024))
        // The last kilobyte before a megabyte rounds up to the megabyte, never to "1024 KB".
        assertEquals("1 MB", byteSizeText(1023 * 1024 + 1))
        assertEquals("1 MB", byteSizeText(1024 * 1024 - 1))
        assertEquals("1 MB", byteSizeText(1024 * 1024))
        assertEquals("1.1 MB", byteSizeText(1024 * 1024 + 1))
        assertEquals("1.5 MB", byteSizeText(1536 * 1024))
        assertEquals("2 MB", byteSizeText(2 * 1024 * 1024))
        assertEquals("32 MB", byteSizeText(32L * 1024 * 1024))
    }
}
