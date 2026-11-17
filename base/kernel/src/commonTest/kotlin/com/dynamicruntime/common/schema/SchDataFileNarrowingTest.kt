package com.dynamicruntime.common.schema

import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.filekind.FileKinds
import kotlin.test.Test
import kotlin.test.assertEquals

/** A client's alteration narrows a file field as it does a choice list: fewer kinds, smaller files (issue #1834). */
class SchDataFileNarrowingTest {
    private fun type(vararg field: Pair<String, Any?>): Map<String, Any?> = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf("clip" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.dataFile) + field),
    )

    private val base = type(SCH.fileKinds to listOf(FKIND.mp4, FKIND.webm), SCH.fileMaxSize to 1000)

    private fun codes(variant: Map<String, Any?>, from: Map<String, Any?> = base) =
        narrowingProblems("ns.T", from, variant).map { it.code }

    @Test
    fun fewerKindsAndALowerSizeNarrow() {
        assertEquals(emptyList(), codes(type(SCH.fileKinds to listOf(FKIND.mp4), SCH.fileMaxSize to 500)))
        assertEquals(emptyList(), codes(base))
    }

    @Test
    fun moreKindsOrNoneWiden() {
        assertEquals(
            listOf(NarrowingError.addsFileKind),
            codes(type(SCH.fileKinds to listOf(FKIND.mp4, FKIND.png), SCH.fileMaxSize to 1000)),
        )
        assertEquals(listOf(NarrowingError.addsFileKind), codes(type(SCH.fileMaxSize to 1000)))
    }

    @Test
    fun aRaisedOrRemovedSizeWidens() {
        val kinds = SCH.fileKinds to listOf(FKIND.mp4)
        assertEquals(listOf(NarrowingError.raisesFileMaxSize), codes(type(kinds, SCH.fileMaxSize to 2000)))
        assertEquals(listOf(NarrowingError.raisesFileMaxSize), codes(type(kinds)))
    }

    @Test
    fun aSizeWhereThereWasNoneNarrows() {
        val open = type(SCH.fileKinds to listOf(FKIND.mp4))
        assertEquals(emptyList(), codes(type(SCH.fileKinds to listOf(FKIND.mp4), SCH.fileMaxSize to 10), from = open))
    }

    @Test
    fun aSizeIsReadAsTheParserReadsItNumberOrText() {
        val textBase = type(SCH.fileKinds to listOf(FKIND.mp4), SCH.fileMaxSize to "1000")
        val kinds = SCH.fileKinds to listOf(FKIND.mp4)
        assertEquals(listOf(NarrowingError.raisesFileMaxSize), codes(type(kinds, SCH.fileMaxSize to 2000), from = textBase))
        assertEquals(listOf(NarrowingError.raisesFileMaxSize), codes(type(kinds, SCH.fileMaxSize to "2000"), from = textBase))
        assertEquals(emptyList(), codes(type(kinds, SCH.fileMaxSize to "500"), from = textBase))
        assertEquals(emptyList(), codes(type(kinds, SCH.fileMaxSize to 1000), from = textBase))
    }

    @Test
    fun aSizeIsJudgedByTheLimitInForceForEachKindKept() {
        // A PNG's own largest (20 MB) is below the base's 100 MB, so a field keeping only PNG needs no size to narrow.
        val hundredMb = 100L * 1024 * 1024
        val mixed = type(SCH.fileKinds to listOf(FKIND.png, FKIND.mp4), SCH.fileMaxSize to hundredMb)
        assertEquals(emptyList(), codes(type(SCH.fileKinds to listOf(FKIND.png)), from = mixed))
        assertEquals(emptyList(), codes(type(SCH.fileKinds to listOf(FKIND.png), SCH.fileMaxSize to FileKinds.png.maxSize), from = mixed))
        // Keeping MP4 with no size lets it reach its own 2 GB, above the base's 100 MB.
        assertEquals(listOf(NarrowingError.raisesFileMaxSize), codes(type(SCH.fileKinds to listOf(FKIND.mp4)), from = mixed))
    }
}
