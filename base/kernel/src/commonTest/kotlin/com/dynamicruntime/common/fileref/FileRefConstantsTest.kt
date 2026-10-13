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

    // The slot's schema holds an id and a hash to a pattern, and the code to a function: two statements of one
    // rule, held together here so that neither can be changed without the other.
    @Test
    fun thePatternsASchemaUsesSayWhatTheFunctionsSay() {
        val refId = Regex(FREF.refIdPattern)
        for (id in listOf("logo", "_x", "hero2", "A_b_3", "", "2logo", "a/b", "logo.png", "kdr:logo", "my logo", "logo-2", "l\u00f6go", "logo\n")) {
            assertEquals(FREF.refIdProblem(id) == null, refId.matches(id), "the pattern and the rule disagree on '$id'")
        }
        val hashRule = Regex(FREF.hashPattern)
        for (h in listOf(hash, "0123456789abcdef".repeat(4), "", "ab", hash.uppercase(), hash.dropLast(1), hash + "a", "g" + hash.drop(1), "$hash\n")) {
            assertEquals(FREF.isHash(h), hashRule.matches(h), "the pattern and the rule disagree on '$h'")
        }
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

    // Issue #1154. A local file's ref is named by a root and a path, and by no hash: there is none to author.
    @Test
    fun aLocalRefsEntryHoldsARootAndAPathAndNoHash() {
        val ref = FileRefDef("logo", FREF.sourceLocal, hash = null, "The logo.", configName = "main", stored = false, root = "brand", path = "marks/logo.svg")
        assertEquals(
            mapOf(CCT.refId to "logo", CCT.source to FREF.sourceLocal, CCT.root to "brand", CCT.path to "marks/logo.svg", CCT.description to "The logo."),
            ref.toEntry(),
        )
        val back = FileRefDef.fromEntry(ref.toEntry(), configName = "copy", stored = true)
        assertEquals("brand", back.root)
        assertEquals("marks/logo.svg", back.path)
        assertNull(back.hash)
        assertTrue(back.extra.isEmpty(), "a root and a path are fields of the entry, not strays carried beside it")
        assertEquals(ref.toEntry(), back.toEntry())
    }

    // What a ref points at, as one thing to compare: whether two entries name the same file, whatever else they say.
    @Test
    fun aRefsPointerIsWhereItsBytesAreAndNothingElse() {
        val stored = FileRefDef("logo", FREF.sourceStore, hash)
        val local = FileRefDef("logo", FREF.sourceLocal, hash = null, root = "brand", path = "logo.png")
        assertEquals("store:$hash", stored.pointer)
        assertEquals("local:brand/logo.png", local.pointer)
        assertEquals(local.pointer, FileRefDef("other", FREF.sourceLocal, null, "Reworded.", root = "brand", path = "logo.png").pointer)
        assertFalse(local.pointer == FileRefDef("logo", FREF.sourceLocal, null, root = "brand", path = "logo2.png").pointer)
        assertFalse(local.pointer == FileRefDef("logo", FREF.sourceLocal, null, root = "shared", path = "logo.png").pointer)
        assertFalse(local.pointer == stored.pointer)
        // A client's own ref (issue #1164) names its file by the same two fields and is a different pointer: the
        // same root and path, read from the client's own directory. Turning one kind into the other is a re-pointing.
        val own = FileRefDef("logo", FREF.sourceClientLocal, hash = null, root = "brand", path = "logo.png")
        assertEquals("clientLocal:brand/logo.png", own.pointer)
        assertFalse(own.pointer == local.pointer)
        assertEquals(mapOf(CCT.refId to "logo", CCT.source to FREF.sourceClientLocal, CCT.root to "brand", CCT.path to "logo.png"), own.toEntry())
        assertEquals(own.pointer, FileRefDef.fromEntry(own.toEntry(), null, stored = true).pointer)
    }

    @Test
    fun aRootsNameIsAPlainNameAndThePatternSaysWhatTheFunctionSays() {
        val pattern = Regex(FREF.rootPattern)
        val names = listOf(
            "brand", "acme-repo", "shared_2", "A", "9lives", "", ".", "..", ".hidden", "my brand", "brand/sub", "brand\\sub",
            "/etc", "br\u00e4nd", "brand\n", "a.b",
        )
        for (name in names) {
            assertEquals(FREF.rootProblem(name) == null, pattern.matches(name), "the pattern and the rule disagree on '$name'")
        }
        for (good in listOf("brand", "acme-repo", "shared_2", "A", "9lives")) assertNull(FREF.rootProblem(good), "should accept '$good'")
        for (bad in listOf("", ".", "..", ".hidden", "my brand", "brand/sub", "/etc", "a.b")) assertNotNull(FREF.rootProblem(bad), "should refuse '$bad'")
        assertEquals(listOf(FREF.sourceStore, FREF.sourceLocal, FREF.sourceClientLocal), FREF.sources)
        assertEquals(setOf(FREF.sourceLocal, FREF.sourceClientLocal), FREF.localSources)
    }

    // A field this release does not read is a later release's: an edit of anything else in the configuration, made
    // on a node one release behind, writes every ref back, and must write that field back with it.
    @Test
    fun aFieldThisReleaseDoesNotReadIsCarriedAndWrittenBack() {
        val entry = mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to hash, "alt" to "Our logo", "focus" to mapOf("x" to 1))
        val ref = FileRefDef.fromEntry(entry, "copy", stored = true)
        assertEquals(mapOf("alt" to "Our logo", "focus" to mapOf("x" to 1)), ref.extra)
        assertEquals(entry, ref.toEntry())
    }
}
