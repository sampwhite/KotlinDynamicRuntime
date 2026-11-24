package com.dynamicruntime.common.schema

import com.dynamicruntime.common.filekind.FKIND
import kotlin.test.Test
import kotlin.test.assertEquals

/** A client's alteration narrows a table field as it does an array: its rows' type, and fewer of them (issue #1882). */
class SchDataTableNarrowingTest {
    private val amount = mapOf(SCH.type to SCT.number, SCH.minimum to 0)
    private val kind = mapOf(
        SCH.type to SCT.string,
        SCH.options to listOf(mapOf(SCH.value to "meal"), mapOf(SCH.value to "taxi")),
    )

    private fun rows(vararg columns: Pair<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> =
        mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf(*columns), SCH.required to required)

    private fun type(vararg field: Pair<String, Any?>): Map<String, Any?> = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf(
            "lines" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.dataFile, SCH.fileKinds to listOf(FKIND.csv)) + field,
        ),
    )

    private val inline = type(SCH.fileRows to rows("amount" to amount, "kind" to kind), SCH.fileMaxRows to 500)

    private fun codes(variant: Map<String, Any?>, from: Map<String, Any?> = inline) =
        narrowingProblems("ns.T", from, variant).map { it.code }

    @Test
    fun fewerRowsNarrow() {
        assertEquals(emptyList(), codes(inline))
        assertEquals(emptyList(), codes(type(SCH.fileRows to rows("amount" to amount, "kind" to kind), SCH.fileMaxRows to 100)))
    }

    @Test
    fun moreRowsWiden() {
        assertEquals(
            listOf(NarrowingError.raisesFileMaxRows),
            codes(type(SCH.fileRows to rows("amount" to amount, "kind" to kind), SCH.fileMaxRows to 501)),
        )
        // Unset is the default, 1,000: more than this field's 500, and at most the default where the base left it unset.
        assertEquals(listOf(NarrowingError.raisesFileMaxRows), codes(type(SCH.fileRows to rows("amount" to amount, "kind" to kind))))
        val open = type(SCH.fileRows to rows("amount" to amount))
        assertEquals(emptyList(), codes(type(SCH.fileRows to rows("amount" to amount), SCH.fileMaxRows to 1000), from = open))
        assertEquals(
            listOf(NarrowingError.raisesFileMaxRows),
            codes(type(SCH.fileRows to rows("amount" to amount), SCH.fileMaxRows to 1001), from = open),
        )
    }

    @Test
    fun anInlineRowTypeNarrowsByItsOwnRules() {
        val fewerChoices = kind + (SCH.options to listOf(mapOf(SCH.value to "meal")))
        assertEquals(emptyList(), codes(type(SCH.fileRows to rows("amount" to amount, "kind" to fewerChoices), SCH.fileMaxRows to 500)))
        assertEquals(
            emptyList(),
            codes(type(SCH.fileRows to rows("amount" to amount, "kind" to kind, required = listOf("amount")), SCH.fileMaxRows to 500)),
        )
        val moreChoices = kind + (SCH.options to listOf(mapOf(SCH.value to "meal"), mapOf(SCH.value to "taxi"), mapOf(SCH.value to "hotel")))
        assertEquals(
            listOf(NarrowingError.addsChoice),
            codes(type(SCH.fileRows to rows("amount" to amount, "kind" to moreChoices), SCH.fileMaxRows to 500)),
        )
        assertEquals(
            listOf(NarrowingError.addsProperty),
            codes(type(SCH.fileRows to rows("amount" to amount, "kind" to kind, "note" to mapOf(SCH.type to SCT.string)), SCH.fileMaxRows to 500)),
        )
    }

    @Test
    fun aReferencedRowTypeStaysTheSame() {
        val byRef = type(SCH.fileRows to mapOf(SCH.dRef to "ns.Line"))
        assertEquals(emptyList(), codes(byRef, from = byRef))
        assertEquals(listOf(NarrowingError.changesKeyword), codes(type(SCH.fileRows to mapOf(SCH.dRef to "ns.Other")), from = byRef))
    }
}
