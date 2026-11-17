package com.dynamicruntime.common.schema

import com.dynamicruntime.common.filekind.FKIND
import kotlin.test.Test
import kotlin.test.assertEquals

/** A client's alteration narrows a file field as it does a choice list: fewer kinds, a lower size (issue #1834). */
class SchDataFileNarrowingTest {
    private fun type(vararg field: Pair<String, Any?>): Map<String, Any?> = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf("clip" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.dataFile) + field),
    )

    private val base = type(SCH.fileKinds to listOf(FKIND.mp4, FKIND.webm), SCH.fileMaxSize to 1000)

    private fun codes(variant: Map<String, Any?>) = narrowingProblems("ns.T", base, variant).map { it.code }

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
        assertEquals(emptyList(), narrowingProblems("ns.T", open, type(SCH.fileKinds to listOf(FKIND.mp4), SCH.fileMaxSize to 10)).map { it.code })
    }
}
