package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.util.ScriptEnv
import com.dynamicruntime.common.util.ScriptZone
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A listing cell read through its column's presentation (issue #1323). In `commonTest`, since the browser draws the
 * cells and the backend's tests read the same rule.
 */
class ListingColumnsTest {
    private val env = ScriptEnv(
        ScriptZone.of(FixedOffsetTimeZone(UtcOffset(hours = -5)), "CT"),
        Instant.parse("2026-10-17T22:00:00Z"),
    )

    @Test
    fun aPresentationReadsTheTraitsDataAndTheValue() {
        val data = mapOf("city" to "Austin", "state" to "TX", "amount" to 12)
        assertEquals("Austin, TX", presentColumnValue("Austin", UsageKind.string, $$"${city}, ${state}", data, env))
        // A number column's value is a number to its script, so the number functions take it.
        assertEquals("$1,234.50", presentColumnValue("1234.5", UsageKind.number, $$"""${currency(value, "USD")}""", data, env))
        assertEquals("1,200 kg", presentColumnValue("1200", UsageKind.number, $$"${formatNumber(value)} kg", data, env))
        // `value` is the column's, over a field of the same name.
        assertEquals("v=7", presentColumnValue("7", UsageKind.number, $$"v=${value}", mapOf("value" to 99), env))
    }

    @Test
    fun aDateColumnWithNoPresentationReadsRelativeToNow() {
        assertEquals("in 3h", presentColumnValue("2026-10-18T01:00:00Z", UsageKind.date, null, emptyMap(), env))
        assertEquals("12m ago", presentColumnValue("2026-10-17T21:48:00Z", UsageKind.date, "", emptyMap(), env))
        // A day has no time to be relative from: it is shown as it is.
        assertEquals("2026-10-20", presentColumnValue("2026-10-20", UsageKind.date, null, emptyMap(), env))
        // Its own presentation wins.
        assertEquals(
            "Oct 18, 2026, 8:00 PM CT",
            presentColumnValue("2026-10-19T01:00:00Z", UsageKind.date, $$"""${formatDate(value, "moment")}""", emptyMap(), env),
        )
        // A string or number column with none shows its value.
        assertNull(columnPresentation(UsageKind.number, null))
        assertEquals("42", presentColumnValue("42", UsageKind.number, null, emptyMap(), env))
    }

    @Test
    fun aBlankValueOrABrokenPresentationLeavesTheValue() {
        assertEquals("", presentColumnValue("", UsageKind.date, null, emptyMap(), env))
        assertEquals("", presentColumnValue(" ", UsageKind.string, $$"${city}", mapOf("city" to "Austin"), env).trim())
        // A field the row lacks, or a value its function cannot take: the value, never a blank cell.
        assertEquals("Austin", presentColumnValue("Austin", UsageKind.string, $$"${city}, ${state}", mapOf("city" to "Austin"), env))
        assertEquals("n/a", presentColumnValue("n/a", UsageKind.number, $$"${formatNumber(value, 2)}", emptyMap(), env))
    }

    @Test
    fun theColumnsRoundTripTheWire() {
        val columns = listOf(
            ListingColumn("amount", "Amount", UsageKind.number, $$"""${currency(value, "USD")}"""),
            ListingColumn("due", "Due", UsageKind.date, null),
        )
        val read = ListingColumn.listFrom(columns.map { it.toMap() })
        assertEquals(columns.map { listOf(it.traitId, it.label, it.kind, it.presentation) }, read.map { listOf(it.traitId, it.label, it.kind, it.presentation) })
        // No presentation is no key.
        assertEquals(setOf(UF.traitId, UF.label, UF.kind), columns[1].toMap().keys)
    }
}
