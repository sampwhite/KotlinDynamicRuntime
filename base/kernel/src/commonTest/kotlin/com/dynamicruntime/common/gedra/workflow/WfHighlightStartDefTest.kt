package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A workflow's highlighted Start (issue #1662) as definition data: declared, parsed and round-tripped, absent by default,
 * and refused on a workflow no form engages with.
 */
class WfHighlightStartDefTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun def(entry: WfEntry = WfEntry.normal, highlight: Boolean = true) =
        WfDefBuilder("review", entry).apply {
            highlightStart = highlight
            task("record", "Record") { trait("a"); save("s", "S", WfSaveKind.edit) }
        }.build()

    @Test
    fun itParsesAndRoundTripsAndIsOffByDefault() {
        val on = parseWfDef(cxt, def())
        assertTrue(on.highlightStart)
        assertEquals(true, on.toJsonMap()[WFD.highlightStart])
        assertTrue(parseWfDef(cxt, on.toJsonMap()).highlightStart)
        // Off, it is not written at all, so a definition that never asked for it stores as it did.
        val off = parseWfDef(cxt, def(highlight = false))
        assertFalse(off.highlightStart)
        assertFalse(WFD.highlightStart in off.toJsonMap())
    }

    @Test
    fun onlyANormalWorkflowMayHighlightItsStart() {
        assertFailsWith<KdrException> { parseWfDef(cxt, def(entry = WfEntry.coreDetails)) }
    }
}
