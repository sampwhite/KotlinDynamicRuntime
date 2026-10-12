package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.parseDay
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A report's generated row and group types (issue #1086): named so no two reports share one, built from the report
 * as bound, and strict -- over a report bound by hand, parsed against nothing and judged by the validator the
 * backend and the browser both run.
 */
class ReportRowSchemaTest {
    private val cxt = LiteCxt()

    private fun column(
        id: String, path: String, kind: ReportKind, multi: Boolean = false, dayOnly: Boolean = false,
        combine: ReportCombine? = null, rollup: ReportCombine? = null,
    ) = BoundColumn(
        ReportColumn(id, id.replaceFirstChar { it.uppercase() }, path, combine = combine, rollup = rollup),
        BoundPath(parseReportPathOrThrow(path), if (path.contains('[')) listOf("year") else emptyList(), kind, multi, dayOnly),
        combine ?: ReportCombine.defaultFor(multi),
    )

    private val columns = listOf(
        column("auditor", "form.audit.auditor", ReportKind.string),
        column("visited", "form.audit.visitedOn", ReportKind.date, dayOnly = true, rollup = ReportCombine.max),
        column("created", "meta.createdAt", ReportKind.date, rollup = ReportCombine.min),
        column("tags", "form.audit.tags", ReportKind.string, multi = true),
        column("years", "form.yearly[*].year", ReportKind.number, multi = true, combine = ReportCombine.count, rollup = ReportCombine.sum),
        column("total", "form.audit.total", ReportKind.number, rollup = ReportCombine.sum),
        column("passed", "form.audit.passed", ReportKind.boolean),
        column("revisits", "form.audit.revisits", ReportKind.date, multi = true, dayOnly = true, combine = ReportCombine.distinct, rollup = ReportCombine.count),
        column("names", "form.audit.auditor", ReportKind.string, rollup = ReportCombine.distinct),
    )
    private val report = BoundReport(
        ClientReport("sites", "Sites", columns.map { it.column }, groupBy = listOf("auditor", "visited")),
        columns,
    )

    private val defs = reportRowDefs(cxt, "acme", report)
    // Against nothing: the types refer only to themselves, which is what lets them be published.
    private val types = parseSchemaTypes(defs)
    private val row: SchType = types.getValue(RGEN.rowType("acme", "sites"))
    private val group: SchType = types.getValue(RGEN.groupType("acme", "sites"))

    private val day = "2026-03-01".parseDay()
    private val moment = "2026-03-01T12:00:00Z".parseDate()

    private fun rowOf(vararg values: Pair<String, Any?>) = mapOf(RRUN.gedraId to "gd.fd.acme.a1", RRUN.values to mapOf(*values))
    private fun groupOf(key: Map<String, Any?>, count: Any?, vararg values: Pair<String, Any?>) =
        mapOf(RRUN.group to key, RRUN.count to count, RRUN.values to mapOf(*values))

    /** The paths [data] fails [type] at. */
    private fun failedAt(type: SchType, data: Map<String, Any?>): List<String> = validate(type, data).map(SchFailure::path)

    @Test
    fun theTypesAreNamedForTheirClientAndReport() {
        assertEquals(setOf("kdr.report.client.acme.sitesRow", "kdr.report.client.acme.sitesGroup"), defs.keys)
        // A sandbox is a client of its own here, where its own namespace would be its parent's.
        assertEquals("kdr.report.client.acme.sandbox.sitesRow", RGEN.rowType("acme:sandbox", "sites"))
        assertEquals("kdr.report.client.acme.sandbox", RGEN.namespaceFor("acme:sandbox"))
        // A component's report keeps its root, which no client's bare id can spell.
        assertEquals("kdr.report.client.acme.kdr.formsByStatusGroup", RGEN.groupType("acme", "kdr:formsByStatus"))
        assertEquals(RGEN.groupType("acme", "kdr:formsByStatus"), reportRowDefs(cxt, "acme", BoundReport(
            ClientReport("kdr:formsByStatus", "By status", columns.map { it.column }), columns,
        )).keys.last())
    }

    @Test
    fun aRowTypeHasEveryColumnByItsKindTitledAndDescribed() {
        val body = defs.getValue(RGEN.rowType("acme", "sites")).toJsonMapOrEmpty()
        assertEquals(false, body[SCH.additionalProperties])
        assertEquals(listOf(RRUN.gedraId, RRUN.values), body[SCH.required])
        val values = body[SCH.properties].toJsonMapOrEmpty()[RRUN.values].toJsonMapOrEmpty()
        assertEquals(false, values[SCH.additionalProperties])
        // No column is required: one with nothing to show is null.
        assertFalse(values.containsKey(SCH.required))
        val props = values[SCH.properties].toJsonMapOrEmpty()
        assertEquals(columns.map { it.columnId }, props.keys.toList())
        fun prop(id: String) = props[id].toJsonMapOrEmpty()
        assertEquals("Auditor", prop("auditor")[SCH.title])
        assertEquals("form.audit.auditor (first)", prop("auditor")[SCH.description])
        assertEquals(SCT.string, prop("auditor")[SCH.type])
        // A day and a moment are told apart, and neither is coerced from the other.
        assertEquals(SFMT.date, prop("visited")[SCH.format])
        assertEquals(SFMT.dateTime, prop("created")[SCH.format])
        assertEquals(false, prop("visited")[SCH.allowCoerce])
        // A count is a whole number; a plain number is not held to be one.
        assertEquals(SCT.integer, prop("years")[SCH.type])
        assertEquals(SCT.number, prop("total")[SCH.type])
        assertEquals(SCT.boolean, prop("passed")[SCH.type])
        // A combine that yields a list makes the column one, of the kind.
        assertEquals(SCT.array, prop("tags")[SCH.type])
        assertEquals(SCT.string, prop("tags")[SCH.items].toJsonMapOrEmpty()[SCH.type])
        assertEquals(SFMT.date, prop("revisits")[SCH.items].toJsonMapOrEmpty()[SCH.format])
    }

    @Test
    fun aRowOfTheReportIsAcceptedInItsValuesAndAsItIsWritten() {
        val full = rowOf(
            "auditor" to "Smith", "visited" to day, "created" to moment, "tags" to listOf("north", "new"), "years" to 2L,
            "total" to 2.5, "passed" to true, "revisits" to listOf(day, "2026-03-09".parseDay()), "names" to "Smith",
        )
        assertEquals(emptyList(), validate(row, full))
        // As the run writes it out (`reportWireValue`): a day is its text.
        assertEquals(emptyList(), validate(row, rowOf("visited" to "2026-03-01", "revisits" to listOf("2026-03-01"))))
        // A column with nothing to show is null, or not there; a whole number is a number.
        assertEquals(emptyList(), validate(row, rowOf("auditor" to null, "total" to 7L)))
        assertEquals(emptyList(), validate(row, rowOf()))
    }

    @Test
    fun aValueNotOfItsColumnsKindIsRefused() {
        // The other form of a date: what a run without exact dates may hold, and this type does not allow.
        assertEquals(listOf("values.visited"), failedAt(row, rowOf("visited" to moment)))
        assertEquals(listOf("values.created"), failedAt(row, rowOf("created" to day)))
        assertEquals(listOf("values.revisits[1]"), failedAt(row, rowOf("revisits" to listOf(day, moment))))
        // Nothing is coerced: text that spells a number or a flag is still text.
        assertEquals(listOf("values.total"), failedAt(row, rowOf("total" to "12")))
        assertEquals(listOf("values.passed"), failedAt(row, rowOf("passed" to "yes")))
        assertEquals(listOf("values.years"), failedAt(row, rowOf("years" to 2.5)))
        assertEquals(listOf("values.auditor"), failedAt(row, rowOf("auditor" to 7L)))
        // A list column takes a list, and a single-valued one does not.
        assertEquals(listOf("values.tags"), failedAt(row, rowOf("tags" to "north")))
        assertEquals(listOf("values.auditor"), failedAt(row, rowOf("auditor" to listOf("Smith"))))
        // Closed: a column the report does not have, a key the row does not, a row with no form.
        assertEquals(listOf("values.other"), failedAt(row, rowOf("other" to 1L)))
        assertEquals(listOf("extra"), failedAt(row, rowOf() + ("extra" to 1L)))
        assertEquals(listOf(RRUN.gedraId), failedAt(row, mapOf(RRUN.values to emptyMap<String, Any?>())))
    }

    @Test
    fun aGroupTypeHasTheGroupedByColumnsAndTheRolledUpOnes() {
        val props = defs.getValue(RGEN.groupType("acme", "sites")).toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        fun inner(name: String) = props[name].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
        // The report's own grouping, in its order; then only the columns that roll up.
        assertEquals(listOf("auditor", "visited"), inner(RRUN.group).keys.toList())
        assertEquals(listOf("visited", "created", "years", "total", "revisits", "names"), inner(RRUN.values).keys.toList())
        fun rolled(id: String) = inner(RRUN.values)[id].toJsonMapOrEmpty()
        // Typed by what the rollup yields: the latest day is a day, a sum is a number, a count is whole whatever it
        // counts, and a rollup that yields a list is one.
        assertEquals(SFMT.date, rolled("visited")[SCH.format])
        assertEquals(SFMT.dateTime, rolled("created")[SCH.format])
        assertEquals(SCT.number, rolled("years")[SCH.type])
        assertEquals(SCT.integer, rolled("revisits")[SCH.type])
        assertEquals(SCT.array, rolled("names")[SCH.type])
        assertEquals("form.audit.visitedOn (first, rolled up by max)", rolled("visited")[SCH.description])
        assertEquals(SCT.integer, props[RRUN.count].toJsonMapOrEmpty()[SCH.type])
    }

    @Test
    fun aGroupOfTheReportIsAcceptedAndWhatIsNotOneRefused() {
        val key = mapOf<String, Any?>("auditor" to "Smith", "visited" to day)
        assertEquals(emptyList(), validate(group, groupOf(key, 3L, "visited" to day, "created" to moment, "years" to 5L, "total" to 12.5, "revisits" to 4L, "names" to listOf("Smith"))))
        // The forms with no value for a grouped-by column are a group under null; a rollup of nothing is null.
        assertEquals(emptyList(), validate(group, groupOf(mapOf("auditor" to null), 1L, "total" to null)))
        assertEquals(emptyList(), validate(group, groupOf(emptyMap(), 0L)))
        // A key holds grouped-by columns only, and `values` rolled-up ones only.
        assertEquals(listOf("group.total"), failedAt(group, groupOf(mapOf("total" to 1L), 1L)))
        assertEquals(listOf("values.auditor"), failedAt(group, groupOf(key, 1L, "auditor" to "Smith")))
        // The key's dates are of the column's form too, and the count is a whole number, uncoerced.
        assertEquals(listOf("group.visited"), failedAt(group, groupOf(mapOf("visited" to moment), 1L)))
        assertEquals(listOf(RRUN.count), failedAt(group, groupOf(key, "3")))
        assertEquals(listOf(RRUN.count), failedAt(group, mapOf(RRUN.group to key, RRUN.values to emptyMap<String, Any?>())))
        assertTrue(failedAt(group, groupOf(key, 2.5)).contains(RRUN.count))
    }

    @Test
    fun aReportThatGroupsByNothingAndRollsUpNothingStillHasBothTypes() {
        val plain = listOf(column("auditor", "form.audit.auditor", ReportKind.string))
        val made = parseSchemaTypes(reportRowDefs(cxt, "acme", BoundReport(ClientReport("plain", "Plain", plain.map { it.column }), plain)))
        // Its grouped run is one total row: an empty key, a count, and no values.
        val total = made.getValue(RGEN.groupType("acme", "plain"))
        assertEquals(emptyList(), validate(total, groupOf(emptyMap(), 4L)))
        assertEquals(listOf("group.auditor"), failedAt(total, groupOf(mapOf("auditor" to "Smith"), 4L)))
        assertEquals(listOf("values.auditor"), failedAt(total, groupOf(emptyMap(), 4L, "auditor" to "Smith")))
    }
}
