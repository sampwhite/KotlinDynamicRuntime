package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.schema.JsonMappable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A report definition's model, schema and parser (issue #979). Shared source: the frontend reads the same shape. */
class ClientReportTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun built(more: ReportBuilder.() -> Unit = {}): Map<String, Any?> = ReportBuilder("auditOverview", "Audit overview").apply {
        column("auditor", "Auditor", "form.acmeSiteAudit.auditor")
        more()
    }.build()

    private fun problem(raw: Map<String, Any?>): String =
        assertIs<Parsed.Failed>(clientReportResult(cxt, raw)).problems.single().message

    @Test
    fun aBuiltReportParsesBackAsItWasDeclared() {
        val raw = built {
            description = "Each site's audit."
            column("years", "Years", "form.sample:yearly[*].year", kind = ReportKind.number, combine = ReportCombine.list, rollup = ReportCombine.count)
            groupBy = listOf("auditor")
            excludeEmpty = listOf("auditor", "years")
        }
        val report = parseClientReport(cxt, raw)
        assertEquals("auditOverview", report.reportId)
        assertEquals("Audit overview", report.label)
        assertEquals("Each site's audit.", report.description)
        assertEquals(listOf("auditor", "years"), report.columns.map { it.columnId })
        val years = report.columns[1]
        assertEquals("form.sample:yearly[*].year", years.path)
        assertEquals(ReportKind.number, years.kind)
        assertEquals(ReportCombine.list, years.combine)
        assertEquals(ReportCombine.count, years.rollup)
        // Unset, a column takes its target's kind and the default combine: nothing is written for them.
        assertNull(report.columns[0].kind)
        assertNull(report.columns[0].combine)
        assertEquals(listOf("auditor"), report.groupBy)
        assertEquals(listOf("auditor", "years"), report.excludeEmpty)
        // The JSON form is the one the parser reads: the round trip is exact.
        assertEquals(raw, report.toJsonMap())
        assertEquals(raw, parseClientReport(cxt, report.toJsonMap()).toJsonMap())
        // Both are JsonMappable, as the house rule asks of a class that hosts its own serialization.
        assertIs<JsonMappable>(report)
        assertIs<JsonMappable>(report.columns.first())
    }

    @Test
    fun aListMayArriveAsText() {
        val raw = built() + mapOf(RDEF.groupBy to "auditor,findings")
        assertEquals(listOf("auditor", "findings"), parseClientReport(cxt, raw).groupBy)
    }

    @Test
    fun onlyTheShapeIsCheckedHere() {
        // A path that is not one, a column id the report never had: the registry's to judge, against the client.
        val raw = built { column("odd", "Odd", "not a path at all"); groupBy = listOf("nowhere") }
        val report = parseClientReport(cxt, raw)
        assertEquals("not a path at all", report.columns[1].path)
        assertEquals(listOf("nowhere"), report.groupBy)
    }

    @Test
    fun aMalformedDefinitionIsRefusedWithWhatIsWrong() {
        val good = built()
        assertTrue(problem(good - RDEF.label).contains(RDEF.label))
        assertTrue(problem(good - RDEF.columns).contains(RDEF.columns))
        val noPath = good + mapOf(RDEF.columns to listOf(mapOf(RDEF.columnId to "a", RDEF.label to "A")))
        assertTrue(problem(noPath).contains(RDEF.path))
        val badKind = good + mapOf(RDEF.columns to listOf(mapOf(RDEF.columnId to "a", RDEF.label to "A", RDEF.path to "user.email", RDEF.kind to "text")))
        assertTrue(problem(badKind).contains(RDEF.kind))
        val badCombine = good + mapOf(RDEF.columns to listOf(mapOf(RDEF.columnId to "a", RDEF.label to "A", RDEF.path to "user.email", RDEF.rollup to "median")))
        assertTrue(problem(badCombine).contains(RDEF.rollup))
        // The throwing form throws the same problem.
        assertFailsWith<KdrException> { parseClientReport(cxt, good - RDEF.label) }
    }

    @Test
    fun aReportIdIsAnOwnedName() {
        // Bare for a client's, rooted for a component's; which one a config may use is the collector's to check.
        assertEquals("kdr:formsByStatus", parseClientReport(cxt, built() + mapOf(RDEF.reportId to "kdr:formsByStatus")).reportId)
        for (bad in listOf("a b", "9lives", "kdr:", "a:b:c", "")) {
            assertIs<Parsed.Failed>(clientReportResult(cxt, built() + mapOf(RDEF.reportId to bad)), bad)
        }
        assertFailsWith<KdrException> { ReportBuilder("a b", "Bad").build() }
    }
}
