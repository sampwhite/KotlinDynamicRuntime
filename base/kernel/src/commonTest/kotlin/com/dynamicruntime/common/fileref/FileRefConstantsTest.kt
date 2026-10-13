package com.dynamicruntime.common.fileref

import com.dynamicruntime.common.gedra.CCT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The names a file ref goes by (issue #1153), in `commonTest` so the address the frontend builds and the one the
 * backend serves are the same source run on both targets.
 */
class FileRefConstantsTest {
    private val hash = "ab".repeat(32)

    @Test
    fun aRefIsNamedByItsIdAndItsContentAndServedUnderTheRefMarker() {
        assertEquals("logo:$hash", FREF.versionedName("logo", hash))
        assertEquals("/kdr.en/ref/logo:$hash", FREF.staticPath("kdr.en", FREF.versionedName("logo", hash)))
    }

    @Test
    fun aRefIdIsAClientsBareVariableNameInAsciiOfBoundedLength() {
        for (good in listOf("logo", "_x", "hero2", "A_b_3", "a".repeat(FREF.maxRefIdLength))) {
            assertNull(FREF.refIdProblem(good), "should accept '$good'")
        }
        // Each would mislead where the id goes: a path, an extension, another owner's name, a URL that needs encoding.
        for (bad in listOf("", "2logo", "a/b", "logo.png", "kdr:logo", "my logo", "logo-2", "lögo", "a".repeat(FREF.maxRefIdLength + 1))) {
            assertNotNull(FREF.refIdProblem(bad), "should refuse '$bad'")
        }
        assertTrue(FREF.refIdProblem("kdr:logo")!!.contains("another owner"))
        assertTrue(FREF.refIdProblem("a".repeat(FREF.maxRefIdLength + 1))!!.contains("too long"))
    }

    @Test
    fun aHashIsSixtyFourLowercaseHexCharacters() {
        assertTrue(FREF.isHash(hash))
        assertTrue(FREF.isHash("0123456789abcdef".repeat(4)))
        for (bad in listOf("", "ab", hash.uppercase(), hash.dropLast(1), hash + "a", "g" + hash.drop(1), " $hash".take(64))) {
            assertFalse(FREF.isHash(bad), "should refuse '$bad'")
        }
    }

    @Test
    fun anEntryHoldsWhatWasAuthoredAndNoneOfTheStamp() {
        val ref = FileRefDef("logo", FREF.sourceStore, hash, "The logo.", configName = "copy", stored = true)
        assertEquals(
            mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to hash, CCT.description to "The logo."),
            ref.toEntry(),
        )
        val back = FileRefDef.fromEntry(ref.toEntry(), configName = "other", stored = false)
        assertEquals("other", back.configName)
        assertFalse(back.stored)
        assertEquals(ref.toEntry(), back.toEntry())
        // Forgiving of an entry that holds too little: it is still a ref, for the load to report by itself.
        val bare = FileRefDef.fromEntry(mapOf(CCT.refId to "x"), null, stored = true)
        assertEquals("", bare.source)
        assertNull(bare.hash)
        assertEquals(mapOf(CCT.refId to "x", CCT.source to ""), bare.toEntry())
    }
}
