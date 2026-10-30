package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.ScriptEnv
import com.dynamicruntime.common.util.ScriptZone
import com.dynamicruntime.common.util.loneFieldPath
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A listing cell read through its column's presentation (issue #1323), and the choices a column's field offers (issue
 * #1500). In `commonTest`, since the browser draws the cells and the backend's tests read the same rule.
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
            ListingColumn("amount", "amount", "Amount", UsageKind.number, $$"""${currency(value, "USD")}"""),
            // A second column of the trait (issue #1490), under a name of its own.
            ListingColumn("amountDue", "amount", "Due", UsageKind.date, null),
        )
        val read = ListingColumn.listFrom(columns.map { it.toMap() })
        fun fields(c: ListingColumn) = listOf(c.columnId, c.traitId, c.label, c.kind, c.presentation)
        assertEquals(columns.map { fields(it) }, read.map { fields(it) })
        // No presentation is no key.
        assertEquals(setOf(UF.columnId, UF.traitId, UF.label, UF.kind), columns[1].toMap().keys)
        // Choices travel in their order (issue #1500), and none is no key.
        val level = ListingColumn("level", "q", "Level", UsageKind.string, null, levels)
        assertEquals(levels, ListingColumn.listFrom(listOf(level.toMap())).single().choices)
        assertEquals(null, columns[0].toMap()[UF.choices])
    }

    private val levels = listOf(SchOption("beginner", "Beginners"), SchOption("advanced", "Experts"))

    /** A trait's data type: a choice field, a boolean, text, an integer, a number, a date, a list, and a nested object. */
    private val dataType = parseSchemaTypes(
        mapOf(
            "t.Data" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "level" to mapOf(
                        SCH.type to SCT.string,
                        SCH.options to levels.map { mapOf(SCH.label to it.label, SCH.value to it.value) },
                    ),
                    "firstTalk" to mapOf(SCH.type to SCT.boolean),
                    "note" to mapOf(SCH.type to SCT.string, SCH.minLength to 5),
                    "count" to mapOf(SCH.type to SCT.integer, SCH.minimum to 1),
                    "amount" to mapOf(SCH.type to SCT.number),
                    "due" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.dateTime),
                    "tags" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string)),
                    "venue" to mapOf(
                        SCH.type to SCT.kObject,
                        SCH.properties to mapOf(
                            "room" to mapOf(
                                SCH.type to SCT.string,
                                SCH.options to listOf(mapOf(SCH.label to "Lab A", SCH.value to "labA")),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    ).getValue("t.Data")

    @Test
    fun aLoneSubstitutionNamesItsFieldAndNothingElseDoes() {
        assertEquals(listOf("level"), $$"${level}".loneFieldPath())
        assertEquals(listOf("venue", "room"), $$"${ venue.room }".loneFieldPath())
        assertNull($$"Level: ${level}".loneFieldPath())
        assertNull($$"${level} ".loneFieldPath())
        assertNull($$"${upper(level)}".loneFieldPath())
        assertNull($$"${level}${note}".loneFieldPath())
        assertNull($$"${level".loneFieldPath())
        assertNull("level".loneFieldPath())
    }

    /** [display] over [dataType] as a column, with the [kind] the usage declares. */
    private fun column(display: String, kind: UsageKind? = null) = resolveUsageColumn(ClientTraitUsage("t", "T", display, kind), dataType)

    /** A column's search shape in brief: its kind, and its choices' values. */
    private fun shapeOf(display: String, kind: UsageKind? = null) =
        column(display, kind).shape.let { it.kind to it.choices.map { c -> c.value } }

    @Test
    fun aColumnOfOneFieldIsSearchedAsItsSchemaSays() {
        assertEquals(levels, column($$"${level}").choices)
        assertEquals(Pair(UsageKind.boolean, emptyList()), shapeOf($$"${firstTalk}"))
        assertEquals(listOf(SchOption("labA", "Lab A")), column($$"${venue.room}").choices)
        // Numbers and dates are ranges (issue #1508), an integer's as any number's; the field's own bounds are not carried.
        assertEquals(Pair(UsageKind.number, emptyList()), shapeOf($$"${count}"))
        assertEquals(Pair(UsageKind.number, emptyList()), shapeOf($$"${amount}"))
        assertEquals(Pair(UsageKind.date, emptyList()), shapeOf($$"${due}"))
        assertEquals(Pair(UsageKind.string, emptyList()), shapeOf($$"${note}"))
    }

    @Test
    fun aComputedColumnOrAFieldWithNoShapeIsItsDeclaredKindElseText() {
        assertEquals(Pair(UsageKind.string, emptyList()), shapeOf($$"${upper(level)}"))
        assertEquals(Pair(UsageKind.number, emptyList()), shapeOf($$"${count} min", UsageKind.number))
        assertEquals(Pair(UsageKind.string, emptyList()), shapeOf($$"${tags}"))
        assertEquals(Pair(UsageKind.string, emptyList()), shapeOf($$"${missing}"))
        assertEquals(UsageKind.date, resolveUsageColumn(ClientTraitUsage("t", "T", $$"${level}", UsageKind.date), null).kind)
    }

    @Test
    fun aDeclaredKindAgreeingWithItsFieldKeepsItsShapeAndOneContradictingAnythingButPlainTextIsFlagged() {
        // Agreeing: the field's shape, its choices included, still applies.
        assertEquals(Pair(UsageKind.number, emptyList()), shapeOf($$"${count}", UsageKind.number))
        assertEquals(levels, column($$"${level}", UsageKind.string).choices)
        assertFalse(column($$"${count}", UsageKind.number).kindConflict)
        // Contradicting: the declared kind stands, and the column says so.
        val text = column($$"${count}", UsageKind.string)
        assertEquals(Pair(UsageKind.string, emptyList()), shapeOf($$"${count}", UsageKind.string))
        assertTrue(text.kindConflict)
        // A computed display has no field to contradict, and plain text may be read as a number or a day.
        assertFalse(column($$"${count} min", UsageKind.string).kindConflict)
        assertFalse(column($$"${note}", UsageKind.date).kindConflict)
        // A boolean or a choice field is not plain text: a range over one would match nothing.
        assertTrue(column($$"${firstTalk}", UsageKind.date).kindConflict)
        assertTrue(column($$"${level}", UsageKind.number).kindConflict)
        assertEquals(Pair(UsageKind.date, emptyList()), shapeOf($$"${note}", UsageKind.date))
    }

    @Test
    fun aBooleanColumnReadsAsYesOrNoUnlessItHasAPresentation() {
        assertEquals("Yes", presentColumnValue("true", UsageKind.boolean, null, emptyMap(), env))
        assertEquals("No", presentColumnValue("false", UsageKind.boolean, null, emptyMap(), env))
        // Unanswered stays blank; a value that is not a boolean is shown as it is.
        assertEquals("", presentColumnValue("", UsageKind.boolean, null, emptyMap(), env))
        assertEquals("maybe", presentColumnValue("maybe", UsageKind.boolean, null, emptyMap(), env))
        // Its own presentation reads the value as a boolean, to choose its own words.
        val words = $$"""${value ? "First talk" : "Returning"}"""
        assertEquals("First talk", presentColumnValue("true", UsageKind.boolean, words, emptyMap(), env))
    }

    @Test
    fun aColumnWithChoicesShowsTheLabelUnlessItHasAPresentation() {
        assertEquals("Beginners", presentColumnValue("beginner", UsageKind.string, null, emptyMap(), env, levels))
        // A value from before the choices narrowed is shown as it is; a blank stays blank.
        assertEquals("intermediate", presentColumnValue("intermediate", UsageKind.string, null, emptyMap(), env, levels))
        assertEquals("", presentColumnValue("", UsageKind.string, null, emptyMap(), env, levels))
        // The column's own presentation wins over the label.
        assertEquals("[beginner]", presentColumnValue("beginner", UsageKind.string, $$"[${value}]", emptyMap(), env, levels))
    }
}
