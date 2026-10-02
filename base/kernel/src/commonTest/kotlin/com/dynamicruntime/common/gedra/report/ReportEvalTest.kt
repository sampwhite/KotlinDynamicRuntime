package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.SVYS
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.currentApprovals
import com.dynamicruntime.common.gedra.workflow.recordedApprovals
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.parseDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Evaluating a report column over one form (issue #978): reading each source, coercing, combining, emptiness and
 * order. Shared source, so the JVM and JS are held to one answer.
 */
class ReportEvalTest {
    private val t0 = "2026-03-01T10:00:00Z".parseDate()
    private val t1 = "2026-03-02T10:00:00Z".parseDate()
    private val t2 = "2026-03-03T10:00:00Z".parseDate()

    private fun entry(traitId: String, data: Map<String, Any?>, vararg env: Pair<String, Any?>): Map<String, Any?> =
        mapOf(GE.traitId to traitId, GE.data to data) + env

    private val entries = listOf(
        entry(
            "acmeSiteAudit",
            mapOf(
                "auditor" to "Smith", "findings" to "open", "score" to 7, "tags" to listOf("a", "b"),
                "address" to mapOf("city" to "Austin"),
                "items" to listOf(mapOf("amount" to 1), mapOf("amount" to 2.5), mapOf("note" to "none")),
            ),
            GE.updatedAt to t1, GE.source to "user", GE.createdBy to 12L,
        ),
        // Out of key order, as stored: the evaluator orders by key.
        entry("yearly", mapOf("year" to 2010, "note" to "ten", "amount" to 10)),
        entry("yearly", mapOf("year" to 2009, "note" to "nine", "amount" to 5)),
        entry("yearly", mapOf("year" to 2024, "note" to "", "amount" to 2.5)),
        // Stored before the trait was keyed: it has no year.
        entry("yearly", mapOf("note" to "orphan", "amount" to 1000)),
        entry("site", mapOf("zip" to "02134", "name" to "Allston")),
        entry("site", mapOf("zip" to "2134", "name" to "Other")),
        entry("quarterly", mapOf("year" to 2024, "quarter" to "Q1", "amount" to 3)),
        entry("quarterly", mapOf("year" to 2024, "quarter" to "Q2", "amount" to 4)),
        entry("quarterly", mapOf("year" to 2023, "quarter" to "Q1", "amount" to 9)),
    )

    private fun state(traitId: String, data: Map<String, Any?>): Map<String, Any?> = mapOf(GE.traitId to traitId, GE.data to data)

    private fun states(engaged: Boolean = true, engagedAt: Any? = t1, approvedAt: Any? = t2, tasksDone: Boolean = false) = listOf(
        state(WFS.workflowEngagement, mapOf(WFD.workflowId to "auditReview", WFS.engaged to engaged, WFS.lastEngagedAt to engagedAt)),
        state(
            WFS.workflowState,
            mapOf(WFD.workflowId to "auditReview", WFS.eligible to true, WFS.ctaTask to "approveAudit", WFS.tasksDone to tasksDone),
        ),
        state(WFS.workflowState, mapOf(WFD.workflowId to "other", WFS.eligible to false)),
        state(WFS.workflowApproval, mapOf(WFD.workflowId to "auditReview", WFS.taskId to "approveAudit", WFS.approvedAt to approvedAt, WFS.approvedBy to 12L)),
        state(SVY.surveyCompletion, mapOf(SVY.valid to true, SVY.complete to true)),
        state(GT.cfacts, mapOf(GT.facts to listOf("big", WSC.needsReview))),
    )

    private val owner = mapOf<String, Any?>(
        RUSR.userId to 12L, RUSR.name to "Ada", RUSR.email to "ada@acme.test", RUSR.labels to listOf("vip", "pilot"),
        RUSR.enabled to true, RUSR.org to null,
    )

    private val meta = mapOf<String, Any?>(RMETA.gedraId to "gd.fd.acme.e1", RMETA.client to "acme", RMETA.ownerId to 12L, RMETA.createdAt to t0)

    private fun subject(
        states: List<Map<String, Any?>> = states(),
        owner: Map<String, Any?>? = this.owner,
        phaseOf: (String) -> WfPhase? = { if (it == "retired") null else WfPhase.engageable },
    ) = ReportSubject(meta, entries, states, owner, phaseOf)

    /** A path bound the way the registry will bind it: the kind and key given by hand. */
    private fun bound(text: String, kind: ReportKind? = null, pk: List<String> = emptyList(), multi: Boolean = false): BoundPath {
        val path = parseReportPathOrThrow(text)
        return BoundPath(path, pk, kind ?: path.attr?.kind ?: ReportKind.string, multi || path.attr?.multiValued == true)
    }

    private fun value(text: String, kind: ReportKind? = null, pk: List<String> = emptyList(), combine: ReportCombine? = null, multi: Boolean = false, s: ReportSubject = subject()): Any? {
        val b = bound(text, kind, pk, multi)
        return if (combine == null) b.valueOf(s) else b.valueOf(s, combine)
    }

    // --- the form source ---------------------------------------------------------------------------------------

    @Test
    fun aFormPathReadsAFieldOfTheTraitsData() {
        assertEquals("Smith", value("form.acmeSiteAudit.auditor"))
        assertEquals(7L, value("form.acmeSiteAudit.score", ReportKind.number))
        assertEquals("Austin", value("form.acmeSiteAudit.address.city"))
        // No such trait on the form, or no such field: a blank, not an error.
        assertNull(value("form.missing.auditor"))
        assertNull(value("form.acmeSiteAudit.nope"))
        assertNull(value("form.acmeSiteAudit.address.nope"))
    }

    @Test
    fun aListInTheDataIsReadWhole() {
        assertEquals(listOf("a", "b"), value("form.acmeSiteAudit.tags", multi = true))
        // Spread across a list of objects; the item with no amount contributes nothing.
        assertEquals(listOf<Any>(1L, 2.5), value("form.acmeSiteAudit.items.amount", ReportKind.number, multi = true))
        assertEquals(3.5, value("form.acmeSiteAudit.items.amount", ReportKind.number, combine = ReportCombine.sum, multi = true))
        // Even a path nobody marked multi-valued never drops values silently when asked for a list.
        assertEquals("a", value("form.acmeSiteAudit.tags"))
    }

    @Test
    fun anEnvelopeFieldReadsTheEntry() {
        assertEquals(t1, value("form.acmeSiteAudit.@updatedAt"))
        assertEquals("user", value("form.acmeSiteAudit.@source"))
        assertEquals(12L, value("form.acmeSiteAudit.@createdBy"))
        assertNull(value("form.acmeSiteAudit.@createdAt"))
    }

    @Test
    fun aSelectorPicksOneEntryOfAKeyedTrait() {
        val pk = listOf("year")
        // The key is stored as a number; the path's text names it whichever way it is written.
        assertEquals("ten", value("form.yearly[2010].note", pk = pk))
        assertEquals("ten", value("form.yearly[\"2010\"].note", pk = pk))
        assertEquals("ten", value("form.yearly[2010.0].note", pk = pk))
        assertEquals("ten", value("form.yearly[year=2010].note", pk = pk))
        assertNull(value("form.yearly[1999].note", pk = pk))
        // An empty string is no value.
        assertNull(value("form.yearly[2024].note", pk = pk))
        assertEquals(2.5, value("form.yearly[2024].amount", ReportKind.number, pk = pk))
    }

    @Test
    fun aStringKeyIsMatchedAsWritten() {
        val pk = listOf("zip")
        assertEquals("Allston", value("form.site[02134].name", pk = pk))
        assertEquals("Other", value("form.site[2134].name", pk = pk))
        assertTrue(keyTextMatches(2024, "2024") && keyTextMatches(2024L, "2024.0") && keyTextMatches("02134", "02134"))
        // Text is never read as a number against a string key.
        assertFalse(keyTextMatches("02134", "2134"))
        assertFalse(keyTextMatches("2134", "02134"))
    }

    @Test
    fun everyEntryOfAKeyedTraitComesInKeyOrder() {
        val pk = listOf("year")
        // 2009 before 2010 before 2024, however stored; the entry with no key is skipped, not thrown.
        assertEquals(listOf<Any>(2009L, 2010L, 2024L), value("form.yearly[*].year", ReportKind.number, pk, multi = true))
        assertEquals(listOf("nine", "ten"), value("form.yearly[*].note", pk = pk, multi = true))
        assertEquals("nine", value("form.yearly[*].note", pk = pk, combine = ReportCombine.first, multi = true))
        assertEquals(17.5, value("form.yearly[*].amount", ReportKind.number, pk, ReportCombine.sum, multi = true))
        assertEquals(3L, value("form.yearly[*].amount", ReportKind.number, pk, ReportCombine.count, multi = true))
        assertEquals(10L, value("form.yearly[*].amount", ReportKind.number, pk, ReportCombine.max, multi = true))
    }

    @Test
    fun aNamedSelectorMayNamePartOfTheKey() {
        val pk = listOf("year", "quarter")
        assertEquals(3L, value("form.quarterly[2024,Q1].amount", ReportKind.number, pk))
        assertEquals(4L, value("form.quarterly[quarter=Q2,year=2024].amount", ReportKind.number, pk))
        // Every quarter of 2024, in key order; every first quarter, the earlier year first.
        assertEquals(listOf<Any>(3L, 4L), value("form.quarterly[year=2024].amount", ReportKind.number, pk, multi = true))
        assertEquals(listOf<Any>(9L, 3L), value("form.quarterly[quarter=Q1].amount", ReportKind.number, pk, multi = true))
        assertEquals(16L, value("form.quarterly[*].amount", ReportKind.number, pk, ReportCombine.sum, multi = true))
        // A positional selector of the wrong length, or a name that is no key field, picks nothing.
        assertNull(value("form.quarterly[2024].amount", ReportKind.number, pk))
        assertNull(value("form.quarterly[region=N].amount", ReportKind.number, pk))
    }

    // --- the workflow source -----------------------------------------------------------------------------------

    @Test
    fun aWorkflowPathReadsWhatTheFormsWorkflowCellShows() {
        assertEquals(WfColumnCategory.engaged.name, value("workflow.auditReview.category"))
        assertEquals(true, value("workflow.auditReview.engaged"))
        assertEquals(true, value("workflow.auditReview.eligible"))
        assertEquals(false, value("workflow.auditReview.finished"))
        assertEquals(false, value("workflow.auditReview.tasksDone"))
        assertEquals("approveAudit", value("workflow.auditReview.ctaTask"))
        assertEquals(t1, value("workflow.auditReview.lastEngagedAt"))
        // Every task done: finished, and no task left to point at.
        val done = subject(states(tasksDone = true))
        assertEquals(WfColumnCategory.finished.name, value("workflow.auditReview.category", s = done))
        assertEquals(true, value("workflow.auditReview.finished", s = done))
        // Not engaged: the form could start `auditReview`, and cannot start `other`.
        val idle = subject(states(engaged = false))
        assertEquals(WfColumnCategory.eligible.name, value("workflow.auditReview.category", s = idle))
        assertEquals(false, value("workflow.auditReview.engaged", s = idle))
        assertNull(value("workflow.auditReview.ctaTask", s = idle))
        assertEquals(WfColumnCategory.ineligible.name, value("workflow.other.category"))
        assertEquals(false, value("workflow.other.eligible"))
    }

    @Test
    fun aWorkflowTheFormHasNoStateForIsBlank() {
        assertNull(value("workflow.neverHeardOf.category"))
        assertNull(value("workflow.neverHeardOf.eligible"))
        // "Is it engaged / finished?" still has an answer: no.
        assertEquals(false, value("workflow.neverHeardOf.engaged"))
        assertEquals(false, value("workflow.neverHeardOf.finished"))
        // A workflow the client no longer has (no phase) is not shown, whatever state is left behind.
        val retired = subject(states() + state(WFS.workflowState, mapOf(WFD.workflowId to "retired", WFS.eligible to true)))
        assertNull(value("workflow.retired.category", s = retired))
    }

    @Test
    fun anApprovalCountsOnlyInTheCurrentEngagement() {
        assertEquals(true, value("workflow.auditReview.approval[approveAudit].approved"))
        assertEquals(t2, value("workflow.auditReview.approval[approveAudit].approvedAt"))
        assertEquals(12L, value("workflow.auditReview.approval[approveAudit].approvedBy"))
        assertEquals(false, value("workflow.auditReview.approval[otherTask].approved"))
        assertNull(value("workflow.auditReview.approval[otherTask].approvedAt"))
        // Given before the form was last engaged: history, and no longer an approval.
        val stale = subject(states(approvedAt = t0))
        assertEquals(false, value("workflow.auditReview.approval[approveAudit].approved", s = stale))
        assertNull(value("workflow.auditReview.approval[approveAudit].approvedAt", s = stale))
        // Not engaged: none count.
        assertEquals(false, value("workflow.auditReview.approval[approveAudit].approved", s = subject(states(engaged = false))))
        // The kernel's rule is the one the backend's `WorkflowApprovals` delegates to.
        assertEquals(setOf("approveAudit"), currentApprovals(states(), "auditReview").keys)
        assertTrue(currentApprovals(states(approvedAt = t0), "auditReview").isEmpty())
        assertEquals(setOf("approveAudit"), recordedApprovals(states(approvedAt = t0), "auditReview").keys)
        // An engagement with no start invalidates nothing.
        assertEquals(setOf("approveAudit"), currentApprovals(states(engagedAt = null), "auditReview").keys)
    }

    // --- the user and meta sources -----------------------------------------------------------------------------

    @Test
    fun userAndMetaPathsReadTheSubject() {
        assertEquals("ada@acme.test", value("user.email"))
        assertEquals(12L, value("user.userId"))
        assertEquals(true, value("user.enabled"))
        assertEquals(listOf("vip", "pilot"), value("user.labels"))
        assertEquals(2L, value("user.labels", combine = ReportCombine.count))
        assertNull(value("user.org"))
        // An owner the caller may not see: every user column is blank.
        assertNull(value("user.email", s = subject(owner = null)))
        assertEquals("gd.fd.acme.e1", value("meta.gedraId"))
        assertEquals(t0, value("meta.createdAt"))
        assertNull(value("meta.updatedAt"))
        // Status and cfacts come from the state entries, by the same readers the forms listing uses.
        assertEquals(SVYS.needsReview, value("meta.formStatus"))
        assertEquals(SVYS.valid, value("meta.surveyStatus"))
        assertEquals(listOf("big", WSC.needsReview), value("meta.cfacts"))
        assertNull(value("meta.formStatus", s = subject(states = emptyList())))
    }

    // --- coercion, emptiness, combining, order -----------------------------------------------------------------

    @Test
    fun aValueIsCoercedToItsKindOrIsBlank() {
        assertEquals(7L, coerceReportValue(7, ReportKind.number))
        assertEquals(7L, coerceReportValue(" 7 ", ReportKind.number))
        assertEquals(2.5, coerceReportValue("2.5", ReportKind.number))
        assertEquals(2.5, coerceReportValue(2.5f, ReportKind.number))
        // Whole or not is decided by value, so the JVM and JS agree: a whole double is the same number as the long.
        assertEquals(7L, coerceReportValue(7.0, ReportKind.number))
        assertEquals(7L, coerceReportValue("7.0", ReportKind.number))
        assertEquals(-3L, coerceReportValue(-3, ReportKind.number))
        assertNull(coerceReportValue(Double.NaN, ReportKind.number))
        assertNull(reportNumber(Double.POSITIVE_INFINITY))
        // Past what a double holds exactly, it is left a double rather than given digits it never had.
        assertEquals(1.0e20, reportNumber(1.0e20))
        assertNull(coerceReportValue("seven", ReportKind.number))
        assertNull(coerceReportValue(true, ReportKind.number))
        assertEquals("7", coerceReportValue(7, ReportKind.string))
        assertEquals("true", coerceReportValue(true, ReportKind.string))
        assertNull(coerceReportValue(mapOf("a" to 1), ReportKind.string))
        assertEquals(t1, coerceReportValue(t1, ReportKind.date))
        assertEquals(t1, coerceReportValue("2026-03-02T10:00:00Z", ReportKind.date))
        // A day written as a day stays a day.
        assertEquals("2026-03-02".parseDay(), coerceReportValue("2026-03-02", ReportKind.date))
        assertNull(coerceReportValue("soon", ReportKind.date))
        assertNull(coerceReportValue(5, ReportKind.date))
        assertEquals(true, coerceReportValue("Yes", ReportKind.boolean))
        assertEquals(false, coerceReportValue("0", ReportKind.boolean))
        assertNull(coerceReportValue("maybe", ReportKind.boolean))
        assertNull(coerceReportValue(1, ReportKind.boolean))
    }

    @Test
    fun emptyIsNothingToShowAndZeroAndFalseAreValues() {
        for (empty in listOf(null, "", "   ", emptyList<Any>(), listOf(null, ""), listOf(listOf<Any>()))) {
            assertTrue(isEmptyReportValue(empty), "$empty")
        }
        for (value in listOf(0, 0L, 0.0, false, "0", "x", listOf(""," a"), listOf(0))) {
            assertFalse(isEmptyReportValue(value), "$value")
        }
    }

    @Test
    fun eachCombineIsLegalForSomeKindsOnly() {
        for (combine in ReportCombine.entries) {
            for (kind in ReportKind.entries) {
                val legal = when (combine) {
                    ReportCombine.sum, ReportCombine.avg -> kind == ReportKind.number
                    ReportCombine.min, ReportCombine.max -> kind != ReportKind.boolean
                    else -> true
                }
                assertEquals(legal, combine.allows(kind), "$combine over $kind")
                assertEquals(if (combine == ReportCombine.count) ReportKind.number else kind, combine.resultKind(kind))
            }
        }
        assertTrue(ReportCombine.list.yieldsList && ReportCombine.distinct.yieldsList)
        assertFalse(ReportCombine.first.yieldsList || ReportCombine.count.yieldsList || ReportCombine.sum.yieldsList)
        assertEquals(ReportCombine.first, ReportCombine.defaultFor(multiValued = false))
        assertEquals(ReportCombine.list, ReportCombine.defaultFor(multiValued = true))
        // A combine its kind does not allow yields a blank; the registry refuses the column long before.
        assertNull(combineReportValues(listOf("a", "b"), ReportKind.string, ReportCombine.sum))
        assertNull(combineReportValues(listOf(true, false), ReportKind.boolean, ReportCombine.max))
    }

    @Test
    fun valuesAreCombinedWithinAForm() {
        val numbers = listOf<Any>(3L, 1L, 2L, 3L)
        assertEquals(3L, combineReportValues(numbers, ReportKind.number, ReportCombine.first))
        assertEquals(numbers, combineReportValues(numbers, ReportKind.number, ReportCombine.list))
        assertEquals(listOf<Any>(1L, 2L, 3L), combineReportValues(numbers, ReportKind.number, ReportCombine.distinct))
        assertEquals(4L, combineReportValues(numbers, ReportKind.number, ReportCombine.count))
        // A sum of whole numbers stays whole; one fraction makes it a double.
        assertEquals(9L, combineReportValues(numbers, ReportKind.number, ReportCombine.sum))
        assertEquals(6.5, combineReportValues(listOf<Any>(4L, 2.5), ReportKind.number, ReportCombine.sum))
        assertEquals(1L, combineReportValues(numbers, ReportKind.number, ReportCombine.min))
        assertEquals(3L, combineReportValues(numbers, ReportKind.number, ReportCombine.max))
        assertEquals(2.25, combineReportValues(numbers, ReportKind.number, ReportCombine.avg))
        // A result that comes out whole is whole, whatever went in.
        assertEquals(2L, combineReportValues(listOf<Any>(1L, 2L, 3L), ReportKind.number, ReportCombine.avg))
        assertEquals(6L, combineReportValues(listOf<Any>(2.5, 3.5), ReportKind.number, ReportCombine.sum))
        // 2 and 2.0 are one value; text keeps its own.
        assertEquals(listOf<Any>(2L, 3.5), combineReportValues(listOf<Any>(2L, 3.5, 2.0), ReportKind.number, ReportCombine.distinct))
        assertEquals(listOf("a", "B", "b"), combineReportValues(listOf("b", "a", "B", "a"), ReportKind.string, ReportCombine.distinct))
        assertEquals("a", combineReportValues(listOf("b", "a", "C"), ReportKind.string, ReportCombine.min))
        assertEquals("C", combineReportValues(listOf("b", "a", "C"), ReportKind.string, ReportCombine.max))
        assertEquals(t0, combineReportValues(listOf(t1, t0, t2), ReportKind.date, ReportCombine.min))
        assertEquals(t2, combineReportValues(listOf(t1, t0, t2), ReportKind.date, ReportCombine.max))
        // Nothing to combine is a blank -- except a count, which is then zero.
        assertNull(combineReportValues(emptyList(), ReportKind.number, ReportCombine.sum))
        assertNull(combineReportValues(emptyList(), ReportKind.string, ReportCombine.list))
        assertEquals(0L, combineReportValues(emptyList(), ReportKind.string, ReportCombine.count))
        assertEquals(0L, value("form.missing.auditor", combine = ReportCombine.count))
    }

    @Test
    fun combinedNumbersFormatAlikeOnEveryPlatform() {
        // The JVM writes a whole double "2.0" and JS writes "2"; `fmt` is what a report is written with.
        assertEquals("2", combineReportValues(listOf<Any>(1L, 2L, 3L), ReportKind.number, ReportCombine.avg).fmt())
        assertEquals("2.25", combineReportValues(listOf<Any>(3L, 1L, 2L, 3L), ReportKind.number, ReportCombine.avg).fmt())
        assertEquals("6.5", combineReportValues(listOf<Any>(4L, 2.5), ReportKind.number, ReportCombine.sum).fmt())
        assertEquals("9", combineReportValues(listOf<Any>(4L, 5L), ReportKind.number, ReportCombine.sum).fmt())
    }

    @Test
    fun valuesHaveATotalOrderWithNullsLast() {
        fun sorted(vararg v: Any?) = v.toList().sortedWith(::compareReportValues)
        assertEquals(listOf(1L, 2.5, 10L, null), sorted(null, 10L, 2.5, 1L))
        assertEquals(listOf(false, true, null), sorted(true, null, false))
        // Case is ignored first and then decides, so two strings differing only in case still have an order.
        assertEquals(listOf("a", "B", "b", "c"), sorted("b", "c", "B", "a"))
        assertTrue(compareReportValues("B", "b") != 0)
        assertEquals(listOf(t0, t1, t2), sorted(t2, t0, t1))
        // A day stands before the instants within it, and after the day before.
        val day = "2026-03-02".parseDay()
        assertEquals(listOf<Any?>(t0, day, t1), sorted(t1, day, t0))
        assertEquals(0, compareReportValues(2L, 2.0))
        // Key tuples part by part, the shorter first, nulls last within a part.
        assertTrue(compareReportKeys(listOf(2024L, "Q1"), listOf(2024L, "Q2")) < 0)
        assertTrue(compareReportKeys(listOf(2023L, "Q4"), listOf(2024L, "Q1")) < 0)
        assertTrue(compareReportKeys(listOf(2024L), listOf(2024L, "Q1")) < 0)
        assertTrue(compareReportKeys(listOf("a", null), listOf("a", "b")) > 0)
        assertEquals(0, compareReportKeys(listOf("a", 1L), listOf("a", 1L)))
    }
}
