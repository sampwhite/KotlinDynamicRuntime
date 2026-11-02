package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.report.RCR
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportPathGroupKind
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for making a report on the Reports page (issue #1492): the paths read, which value a
 * column of several shows and how a totals report may combine it, what is wrong with a draft, and the request.
 */
class ReportCreateTest {
    private fun path(path: String, label: String, kind: ReportKind, multiValued: Boolean = false, keyed: Boolean = false) = buildMap {
        put(RRUN.path, path)
        put(RRUN.label, label)
        put(RRUN.kind, kind.name)
        put(RRUN.multiValued, multiValued)
        if (keyed) put(RCR.keyFields, listOf(mapOf(RCR.field to "year", RRUN.label to "Year", RRUN.kind to ReportKind.number.name)))
    }

    private val catalog = parseReportPathCatalog(
        mapOf(
            RCR.groups to listOf(
                mapOf(
                    RCR.source to ReportPathGroupKind.form.name, RRUN.label to "The form",
                    RCR.paths to listOf(
                        path("meta.createdAt", "Created", ReportKind.date),
                        path("user.email", "Owner's email", ReportKind.string),
                    ),
                ),
                mapOf(
                    RCR.source to ReportPathGroupKind.trait.name, RCR.sourceId to "talk", RRUN.label to "Talk",
                    RCR.paths to listOf(
                        path("form.talk.level", "Level", ReportKind.string),
                        path("form.talk.minutes", "Minutes", ReportKind.number),
                    ),
                ),
                mapOf(
                    RCR.source to ReportPathGroupKind.trait.name, RCR.sourceId to "yearly", RRUN.label to "Yearly",
                    RCR.paths to listOf(path("form.yearly[*].note", "Note", ReportKind.string, multiValued = true, keyed = true)),
                ),
                // A source this page does not know is left out rather than guessed at.
                mapOf(RCR.source to "somethingNew", RRUN.label to "New", RCR.paths to listOf(path("x.y", "Y", ReportKind.string))),
            ),
        ),
    )
    private val minutes = catalog.path("form.talk.minutes")!!
    private val note = catalog.path("form.yearly[*].note")!!

    @Test
    fun readsThePathsBySource() {
        assertEquals(listOf("The form", "Talk", "Yearly"), catalog.groups.map { it.label })
        assertEquals("Talk", catalog.groupOf("form.talk.level")?.label)
        assertEquals(listOf("year"), note.keyFields.map { it.field })
        assertEquals(ReportKind.number, note.keyFields.single().kind)
        assertTrue(note.multiValued)
        assertNull(catalog.path("x.y"))
    }

    @Test
    fun aColumnOfSeveralValuesSaysWhichAndThatDecidesHowItCombines() {
        assertEquals(emptyList(), valuePicks(minutes))
        assertEquals(ValuePick.entries, valuePicks(note))
        // A new column of several shows all of them: a list, which is totalled only by a count.
        val all = newReportColumn(1, note)
        assertEquals(ValuePick.all, all.pick)
        assertTrue(reportColumnIsList(all))
        assertEquals(listOf(ReportCombine.count), rollupChoices(all, note))
        assertNull(all.rollup)
        // How many is a number, which totals by default.
        val count = all.withPick(ValuePick.count, note)
        assertEquals(ReportKind.number, reportColumnKind(count, note))
        assertEquals(ReportCombine.sum, count.rollup)
        // One by its key starts with a blank value per key field.
        assertEquals(listOf(""), all.withPick(ValuePick.byKey, note).key)
        // A number totals by default, a date takes the latest, text nothing.
        assertEquals(ReportCombine.sum, newReportColumn(2, minutes).rollup)
        assertEquals(ReportCombine.max, newReportColumn(3, catalog.path("meta.createdAt")!!).rollup)
        assertNull(newReportColumn(4, catalog.path("form.talk.level")!!).rollup)
        assertEquals("Latest", rollupText(ReportCombine.max, ReportKind.date))
        assertEquals("Highest", rollupText(ReportCombine.max, ReportKind.number))
    }

    @Test
    fun aTotalsReportGroupsByItsFirstColumnWithOneValueUntilChosen() {
        val draft = ReportDraft(name = "Talks").withShape(ReportShape.totals)
            .withColumn(newReportColumn(1, note))
            .withColumn(newReportColumn(2, catalog.path("form.talk.level")!!))
            .withColumn(newReportColumn(3, minutes))
        assertEquals(2, draft.groupBy)
        assertEquals(listOf(2, 3), groupableColumns(draft).map { it.id })
        // Removed, the grouping goes with it; a column made a list is no longer grouped by.
        assertNull(draft.withoutColumn(2).groupBy)
        val noted = draft.copy(groupBy = 1).withColumnChanged(1) { it.withPick(ValuePick.first, note) }
        assertEquals(1, noted.groupBy)
        assertNull(noted.withColumnChanged(1) { it.withPick(ValuePick.all, note) }.groupBy)
        // A row per form groups by nothing.
        assertNull(draft.withShape(ReportShape.perForm).groupBy)
    }

    @Test
    fun saysWhatIsWrongWithADraft() {
        assertEquals(listOf("Give the report a name.", "Add at least one column."), reportDraftProblems(ReportDraft()))
        val twice = ReportDraft(name = "R").withColumn(newReportColumn(1, minutes)).withColumn(newReportColumn(2, minutes))
        assertEquals(listOf("Two columns are headed “Minutes”; give each its own."), reportDraftProblems(twice))
        val keyed = ReportDraft(name = "R").withColumn(newReportColumn(1, note).withPick(ValuePick.byKey, note))
        assertEquals(1, reportDraftProblems(keyed).size)
        assertTrue(reportDraftProblems(keyed).single().startsWith("Say which entry “Note” shows"))
        val listOnly = ReportDraft(name = "R").withShape(ReportShape.totals).withColumn(newReportColumn(1, note))
        assertEquals(listOf("Choose the column to group by."), reportDraftProblems(listOnly))
    }

    @Test
    fun theRequestSendsPathsAsOfferedWithTotalsOnlyForATotalsReport() {
        val draft = ReportDraft(name = " Talks by level ", description = " ")
            .withColumn(newReportColumn(1, catalog.path("form.talk.level")!!))
            .withColumn(newReportColumn(2, minutes).copy(mustHaveValue = true))
            .withColumn(newReportColumn(3, note).withPick(ValuePick.byKey, note).copy(key = listOf(" 2024 "), label = "2024"))
            .withShape(ReportShape.totals)
            .copy(history = true)
        val request = reportCreateRequest(null, draft, EDM.live)
        assertFalse(RRUN.client in request)
        assertFalse(RDEF.description in request)
        assertEquals("Talks by level", request[RDEF.label])
        assertEquals(true, request[RDEF.history])
        assertEquals(EDM.live, request[CPY.mode])
        val columns = request[RDEF.columns].toJsonListOfMaps()
        // The grouped-by column carries no rollup; a key is sent for the server to write into the path.
        assertEquals(mapOf(RDEF.label to "Level", RDEF.path to "form.talk.level", RCR.groupedBy to true), columns[0])
        assertEquals(
            mapOf(RDEF.label to "Minutes", RDEF.path to "form.talk.minutes", RDEF.rollup to "sum", RCR.mustHaveValue to true),
            columns[1],
        )
        assertEquals(mapOf(RDEF.label to "2024", RDEF.path to "form.yearly[*].note", RCR.key to listOf("2024")), columns[2])
        // A row per form sends no grouping, rollups or history.
        val perForm = reportCreateRequest("acme", draft.withShape(ReportShape.perForm), EDM.draft)
        assertEquals("acme", perForm[RRUN.client])
        assertFalse(RDEF.history in perForm)
        assertTrue(perForm[RDEF.columns].toJsonListOfMaps().none { RDEF.rollup in it || RCR.groupedBy in it })
    }

    @Test
    fun readsTheOfferFromTheListing() {
        val sandboxed = parseReportCreateOffer(mapOf(RCR.canCreate to false, RCR.createFrom to "acme:sandbox"))
        assertFalse(sandboxed.canCreate)
        assertEquals("acme:sandbox", sandboxed.createFrom)
        assertTrue(parseReportCreateOffer(mapOf(RCR.canCreate to true, RCR.createsDraft to true)).createsDraft)
    }

    @Test
    fun offersToRemoveOnlyAReportTheClientStoresWhereItIsEdited() {
        fun report(stored: Boolean, template: String? = null) =
            ReportInfo("talks", "Talks", null, emptyList(), emptyList(), emptyList(), "reports", template, stored = stored)
        val here = ReportCreateOffer(canCreate = true, createsDraft = false, createFrom = null)
        assertTrue(reportRemovable(report(stored = true), here))
        assertFalse(reportRemovable(report(stored = false), here))
        assertFalse(reportRemovable(report(stored = true, template = "base"), here))
        val elsewhere = ReportCreateOffer(canCreate = false, createsDraft = false, createFrom = "acme:sandbox")
        assertFalse(reportRemovable(report(stored = true), elsewhere))
        assertEquals(
            "Remove the report “Talks” from acme's sandbox? Its stored snapshots are kept.",
            reportRemoveQuestion(report(stored = true), "acme:sandbox"),
        )
        assertEquals(mapOf(RRUN.reportId to "talks", CPY.mode to EDM.draft), reportRemoveRequest(null, "talks", EDM.draft))
    }
}
