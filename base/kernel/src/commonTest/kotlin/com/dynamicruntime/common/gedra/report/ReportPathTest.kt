package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.canonicalKey
import com.dynamicruntime.common.gedra.entryAddress
import com.dynamicruntime.common.gedra.entryKeyValuesOrNull
import com.dynamicruntime.common.gedra.keyValueText
import com.dynamicruntime.common.util.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The report path's grammar (issue #977). Shared source, so the parser is held to one answer on the JVM and in JS. */
class ReportPathTest {
    private fun ok(text: String): ReportPath = assertIs<Parsed.Ok<ReportPath>>(parseReportPath(text), text).value

    private fun problem(text: String): Pair<ReportPathProblem, Int?> {
        val p = assertIs<Parsed.Failed>(parseReportPath(text), text).problems.single()
        return (p.code as ReportPathProblem) to p.location?.offset
    }

    @Test
    fun aFormPathNamesATraitAndAFieldOfItsData() {
        val path = assertIs<FormPath>(ok("form.acmeSiteAudit.auditor"))
        assertEquals(ReportSource.form, path.source)
        assertEquals("acmeSiteAudit", path.traitId)
        assertNull(path.selector)
        assertEquals(listOf("auditor"), path.fields)
        // A path into a trait's data has no fixed kind: the trait's schema says.
        assertNull(path.attr)
        // Nested data, as `pluckDataPath` takes it.
        assertEquals("address.city", assertIs<FormPath>(ok("form.site.address.city")).dataPath)
    }

    @Test
    fun aGlobalTraitIdKeepsItsRoot() {
        // A colon roots a global id, which is why the separator between a trait and its key is a bracket.
        val path = assertIs<FormPath>(ok("form.kdr:name.name"))
        assertEquals("kdr:name", path.traitId)
        val keyed = assertIs<FormPath>(ok("form.sample:yearly[2024].note"))
        assertEquals("sample:yearly", keyed.traitId)
        assertEquals(listOf("2024"), assertIs<KeySelector.Positional>(keyed.selector).values)
    }

    @Test
    fun aSelectorPicksEntriesOfAKeyedTrait() {
        assertIs<KeySelector.All>(assertIs<FormPath>(ok("form.yearly[*].year")).selector)
        // Positional, in the key's order. Values are the text written: the parser does not know a key field's type.
        val two = assertIs<KeySelector.Positional>(assertIs<FormPath>(ok("form.quarterly[2024,Q1].amount")).selector)
        assertEquals(listOf("2024", "Q1"), two.values)
        assertEquals(listOf("-3", "2.5"), assertIs<KeySelector.Positional>(assertIs<FormPath>(ok("form.t[-3,2.5].v")).selector).values)
        // Named, and possibly only part of the key.
        val named = assertIs<KeySelector.Named>(assertIs<FormPath>(ok("form.quarterly[year=2024].amount")).selector)
        assertEquals(mapOf("year" to "2024"), named.values)
        assertEquals(
            mapOf("year" to "2024", "quarter" to "Q1"),
            assertIs<KeySelector.Named>(assertIs<FormPath>(ok("form.quarterly[year=2024,quarter=Q1].amount")).selector).values,
        )
    }

    @Test
    fun aKeyValueIsKeptAsWritten() {
        // A zip or a code keeps its leading zeros: read as a number it would be another key, and match nothing.
        val zip = assertIs<KeySelector.Positional>(assertIs<FormPath>(ok("form.site[02134].name")).selector)
        assertEquals(listOf("02134"), zip.values)
        assertEquals("form.site[02134].name", ok("form.site[02134].name").canonicalText)
        assertEquals("form.t[2024.0].v", ok("form.t[2024.0].v").canonicalText)
        // Text and number spell the same entry under the canonical key, so no type has to be guessed to match one.
        assertEquals(canonicalKey(2024L), canonicalKey("2024"))
        assertEquals(canonicalKey(2134L), canonicalKey(2134))
    }

    @Test
    fun aQuotedKeyValueHoldsAnything() {
        val values = assertIs<KeySelector.Positional>(
            assertIs<FormPath>(ok("form.region[\"North, \\\"Upper\\\" ] East\",\"2024\"].total")).selector,
        ).values
        // The comma, the quote and the bracket are the value's; quoted digits stay text.
        assertEquals(listOf<Any>("North, \"Upper\" ] East", "2024"), values)
        // Text and number spell the same entry all the same: a key matches by its canonical form.
        assertEquals(canonicalKey(2024L), canonicalKey("2024"))
    }

    @Test
    fun anEnvelopeFieldReadsTheEntryNotItsData() {
        val path = assertIs<FormPath>(ok("form.yearly[2024].@updatedAt"))
        assertEquals("updatedAt", path.envField)
        assertTrue(path.fields.isEmpty())
        assertEquals(ReportKind.date, path.attr?.kind)
        // The names are the entry's own.
        assertEquals(GE.updatedAt, RENV.updatedAt)
    }

    @Test
    fun aWorkflowPathReadsAComputedFactOrAnApproval() {
        val category = assertIs<WorkflowPath>(ok("workflow.auditReview.category"))
        assertEquals("auditReview", category.workflowId)
        assertEquals(RWF.category, category.attrName)
        assertNull(category.taskId)
        assertEquals(ReportKind.string, category.attr?.kind)
        val approval = assertIs<WorkflowPath>(ok("workflow.kdr:review.approval[kdr:approve].approvedAt"))
        assertEquals("kdr:review", approval.workflowId)
        assertEquals("kdr:approve", approval.taskId)
        assertEquals(ReportKind.date, approval.attr?.kind)
        // An approval's attributes are not a workflow's, and the other way round.
        assertEquals(ReportPathProblem.unknownAttribute, problem("workflow.w.approval[t].category").first)
        assertEquals(ReportPathProblem.unknownAttribute, problem("workflow.w.approvedAt").first)
    }

    @Test
    fun userAndMetaPathsNameOneAttribute() {
        val email = assertIs<UserPath>(ok("user.email"))
        assertEquals(ReportKind.string, email.attr?.kind)
        assertTrue(assertIs<UserPath>(ok("user.labels")).attr?.multiValued == true)
        val status = assertIs<MetaPath>(ok("meta.formStatus"))
        assertEquals(ReportSource.meta, status.source)
        assertEquals(ReportKind.date, assertIs<MetaPath>(ok("meta.createdAt")).attr?.kind)
        // Every vocabulary name parses, and carries its kind.
        for (name in RUSR.attrs.keys) assertEquals(name, assertIs<UserPath>(ok("user.$name")).attrName)
        for (name in RMETA.attrs.keys) assertEquals(name, assertIs<MetaPath>(ok("meta.$name")).attrName)
        for (name in RWF.attrs.keys) assertEquals(name, assertIs<WorkflowPath>(ok("workflow.w.$name")).attrName)
        for (name in RENV.attrs.keys) assertEquals(name, assertIs<FormPath>(ok("form.t.@$name")).envField)
    }

    @Test
    fun canonicalTextSurvivesTheRoundTrip() {
        val spellings = mapOf(
            "form.acmeSiteAudit.auditor" to "form.acmeSiteAudit.auditor",
            "form.sample:yearly[2024].note" to "form.sample:yearly[2024].note",
            "form.yearly[*].year" to "form.yearly[*].year",
            // A quoted value that could be bare is written bare; quotes are only for what bare text cannot hold.
            "form.q[\"Q1\",2024].v" to "form.q[Q1,2024].v",
            "form.q[\"2024\"].v" to "form.q[2024].v",
            "form.q[2024.0].v" to "form.q[2024.0].v",
            // Named values in name order.
            "form.q[year=2024,quarter=\"Q 1\"].v" to "form.q[quarter=\"Q 1\",year=2024].v",
            "form.yearly[2024].@updatedAt" to "form.yearly[2024].@updatedAt",
            "workflow.auditReview.approval[approveAudit].approved" to "workflow.auditReview.approval[approveAudit].approved",
            "user.email" to "user.email",
            "meta.formStatus" to "meta.formStatus",
        )
        for ((text, canonical) in spellings) {
            val path = ok(text)
            assertEquals(canonical, path.canonicalText, text)
            // Parse, render, parse: the same path.
            assertEquals(path, ok(path.canonicalText))
            assertEquals(canonical, path.toString())
        }
        // Two spellings of one path are equal: quoted or bare, and named values in either order.
        assertEquals(ok("form.q[\"Q1\"].v"), ok("form.q[Q1].v"))
        assertEquals(ok("form.y[\"2024\"].v"), ok("form.y[2024].v"))
        assertEquals(ok("form.q[year=2024,quarter=Q1].v"), ok("form.q[quarter=Q1,year=2024].v"))
    }

    @Test
    fun eachProblemIsNamedAndLocated() {
        assertEquals(ReportPathProblem.blank to 0, problem(""))
        assertEquals(ReportPathProblem.blank to 0, problem("   "))
        assertEquals(ReportPathProblem.unknownSource to 0, problem("forms.t.v"))
        assertEquals(ReportPathProblem.unknownSource to 0, problem(" form.t.v"))
        // The path stops before it names a value.
        assertEquals(ReportPathProblem.missingField to 4, problem("form"))
        assertEquals(ReportPathProblem.missingField to 6, problem("form.t"))
        assertEquals(ReportPathProblem.missingField to 12, problem("form.t[2024]"))
        assertEquals(ReportPathProblem.missingField to 10, problem("workflow.w"))
        assertEquals(ReportPathProblem.missingField to 19, problem("workflow.w.approval"))
        assertEquals(ReportPathProblem.missingField to 22, problem("workflow.w.approval[t]"))
        // Names that cannot be names, at the name.
        assertEquals(ReportPathProblem.badIdentifier to 5, problem("form.a-b.v"))
        assertEquals(ReportPathProblem.badIdentifier to 5, problem("form..v"))
        assertEquals(ReportPathProblem.badIdentifier to 5, problem("form.a:b:c.v"))
        assertEquals(ReportPathProblem.badIdentifier to 7, problem("form.t.a b"))
        assertEquals(ReportPathProblem.badIdentifier to 9, problem("form.t.a.\"q\""))
        assertEquals(ReportPathProblem.badIdentifier to 7, problem("form.t."))
        assertEquals(ReportPathProblem.badIdentifier to 9, problem("workflow.9w.category"))
        assertEquals(ReportPathProblem.badIdentifier to 20, problem("workflow.w.approval[a b].approved"))
        // Selectors.
        assertEquals(ReportPathProblem.unclosedSelector to 6, problem("form.t[2024.v"))
        assertEquals(ReportPathProblem.unclosedSelector to 6, problem("form.t[*"))
        assertEquals(ReportPathProblem.unclosedSelector to 7, problem("form.t[\"open].v"))
        assertEquals(ReportPathProblem.unclosedSelector to 19, problem("workflow.w.approval[t.approved"))
        assertEquals(ReportPathProblem.badKeyValue to 7, problem("form.t[].v"))
        assertEquals(ReportPathProblem.badKeyValue to 12, problem("form.t[2024,].v"))
        assertEquals(ReportPathProblem.badKeyValue to 8, problem("form.t[a b].v"))
        assertEquals(ReportPathProblem.badKeyValue to 6, problem("form.t[*,2].v"))
        assertEquals(ReportPathProblem.badKeyValue to 14, problem("form.t[year=1,year=2].v"))
        assertEquals(ReportPathProblem.badKeyValue to 7, problem("form.t[\"bad \\u12\"].v"))
        assertEquals(ReportPathProblem.mixedSelectorForms to 12, problem("form.t[2024,quarter=Q1].v"))
        assertEquals(ReportPathProblem.mixedSelectorForms to 17, problem("form.t[year=2024,Q1].v"))
        // No index into a list, and no selector on a workflow.
        assertEquals(ReportPathProblem.selectorNotAllowed to 12, problem("form.t.items[0].amount"))
        assertEquals(ReportPathProblem.selectorNotAllowed to 10, problem("workflow.w[1].category"))
        // Names outside a source's vocabulary.
        assertEquals(ReportPathProblem.unknownAttribute to 5, problem("user.password"))
        assertEquals(ReportPathProblem.unknownAttribute to 5, problem("meta.nope"))
        assertEquals(ReportPathProblem.unknownAttribute to 8, problem("form.t.@deletedAt"))
        // Left over after a complete path.
        assertEquals(ReportPathProblem.trailingText to 10, problem("user.email.domain"))
        assertEquals(ReportPathProblem.trailingText to 17, problem("form.t.@updatedAt.x"))
        assertEquals(ReportPathProblem.trailingText to 19, problem("workflow.w.category.x"))
    }

    @Test
    fun aFieldMayBeNamedAsDataNamesIt() {
        // Wider than a variable name: data arriving from elsewhere has properties like these.
        assertEquals(listOf("first-name"), assertIs<FormPath>(ok("form.t.first-name")).fields)
        assertEquals(listOf("byYear", "2024", "total"), assertIs<FormPath>(ok("form.t.byYear.2024.total")).fields)
        assertEquals(listOf("\$id"), assertIs<FormPath>(ok("form.t.\$id")).fields)
        assertTrue(isReportFieldName("a_b-c"))
        assertTrue(!isReportFieldName("") && !isReportFieldName("a b") && !isReportFieldName("a\"b"))
    }

    @Test
    fun aPathHasABoundedNumberOfFields() {
        val most = (1..RPT.maxFields).joinToString(".") { "f$it" }
        assertEquals(RPT.maxFields, assertIs<FormPath>(ok("form.t.$most")).fields.size)
        assertEquals(ReportPathProblem.tooManyFields, problem("form.t.$most.more").first)
    }

    @Test
    fun theThrowingFormThrowsTheProblem() {
        assertEquals("user.email", parseReportPathOrThrow("user.email").canonicalText)
        val e = assertFailsWith<KdrException> { parseReportPathOrThrow("user.password") }
        assertEquals(ReportPathProblem.unknownAttribute, e.extraData[KdrException.errorCodeKey])
    }

    @Test
    fun anEntryAddressIsItsTraitAndItsKey() {
        assertEquals("kdr:name", entryAddress("kdr:name", emptyList()))
        assertEquals("sample:yearly[2024]", entryAddress("sample:yearly", listOf(2024)))
        assertEquals("quarterly[2024,Q1]", entryAddress("quarterly", listOf(2024L, "Q1")))
        // A year held as a double, or as text, is the same year; text holding what a bare value cannot is quoted.
        assertEquals("t[2024]", entryAddress("t", listOf(2024.0)))
        assertEquals("t[2024]", entryAddress("t", listOf("2024")))
        assertEquals("t[02134]", entryAddress("t", listOf("02134")))
        assertEquals("t[\"North, East\"]", entryAddress("t", listOf("North, East")))
        assertEquals("\"\"", keyValueText(""))
        // A fully keyed path is the entry's address, then the field.
        assertEquals("form.${entryAddress("quarterly", listOf(2024L, "Q 1"))}.amount", ok("form.quarterly[2024,\"Q 1\"].amount").canonicalText)
    }

    @Test
    fun keyValuesAreReadWithoutThrowing() {
        val entry = mapOf(GE.traitId to "quarterly", GE.data to mapOf("year" to 2024, "quarter" to "Q1", "amount" to 5))
        assertEquals(listOf<Any>(2024, "Q1"), entryKeyValuesOrNull(entry, listOf("year", "quarter")))
        assertEquals(emptyList(), entryKeyValuesOrNull(entry, emptyList()))
        // An entry stored before its trait was keyed carries no key: null, where `entryKeyValues` throws.
        assertNull(entryKeyValuesOrNull(entry, listOf("year", "region")))
        assertNull(entryKeyValuesOrNull(mapOf(GE.traitId to "quarterly"), listOf("year")))
    }
}
