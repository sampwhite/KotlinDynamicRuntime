package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.ProblemCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Binding a report to a scope (issue #980): each check over a scope built by hand -- traits, compiled types and
 * workflows, no boot -- refusing with one problem, and a report that holds up binding every column.
 */
class ReportBindTest {
    private val cxt = LiteCxt()
    private val ns = "rpt.test"

    private val config = gedraConfig(cxt, "reports", ns) {
        trait("AuditEntry", "audit", setOf(GedraDataType.formDoc), "A site audit.") {
            property("auditor", "Who audited.")
            property("visitedOn", "The visit.") { dayOnlyDate() }
            property("score", "A score.") { type = SCT.integer }
            property("passed", "Whether it passed.") { type = SCT.boolean }
            property("tags", "Tags.") { type = SCT.array; items { type = SCT.string } }
            property("findings", "Findings.") {
                type = SCT.array
                items {
                    type = SCT.kObject
                    property("severity", "How bad.")
                    property("cost", "What it costs.") { type = SCT.number }
                }
            }
            property("contact", "Who to call.") { ref("Contact") }
            property("payment", "How it was paid.") { ref("Payment") }
        }
        type("Contact") {
            type = SCT.kObject
            property("email", "An email.")
        }
        trait("YearlyEntry", "yearly", setOf(GedraDataType.formDoc), "One year.", primaryKey = listOf("year")) {
            property("year", "The year.", required = true) { type = SCT.integer }
            property("note", "A note.")
        }
        trait("QuarterlyEntry", "quarterly", setOf(GedraDataType.formDoc), "One quarter.", primaryKey = listOf("year", "quarter")) {
            property("year", "The year.", required = true) { type = SCT.integer }
            property("quarter", "The quarter.", required = true)
            property("amount", "An amount.") { type = SCT.number }
        }
        trait("ProfileEntry", "profile", setOf(GedraDataType.userData), "A user's profile.") {
            property("bio", "A bio.")
        }
        workflow("review", WfEntry.normal) {
            task("collect", "Collect") { trait("audit"); save("s", "Save", WfSaveKind.edit) }
            task("signoff", "Sign off") { approval("signedOff", "Approve?", "Approve") }
        }
    }

    // A discriminated union, written raw: the entry's `payment` is a card or cash, each with its own fields.
    private val unionDefs = mapOf(
        "$ns.Payment" to mapOf(
            SCH.type to SCT.kObject,
            SCH.oneOf to listOf(mapOf(SCH.dRef to "#/\$defs/$ns.Card"), mapOf(SCH.dRef to "#/\$defs/$ns.Cash")),
            SCH.discriminator to mapOf(SCH.propertyName to "kind"),
        ),
        // Both declare `total` as a number; they disagree on `settled`, a flag on a card and a count on cash.
        "$ns.Card" to branch("card", "last4" to SCT.string, "total" to SCT.number, "settled" to SCT.boolean),
        "$ns.Cash" to branch("cash", "tendered" to SCT.number, "total" to SCT.number, "settled" to SCT.integer),
    )

    private fun branch(kind: String, vararg fields: Pair<String, String>) = mapOf(
        SCH.type to SCT.kObject,
        SCH.properties to mapOf("kind" to mapOf(SCH.type to SCT.string, SCH.const to kind)) +
            fields.associate { (name, type) -> name to mapOf(SCH.type to type) },
    )

    private val scope = ReportScope(
        formTraits = config.traits.filterValues { GedraDataType.formDoc in it.appliesTo },
        types = parseSchemaTypes(config.defs + unionDefs),
        workflows = config.workflows,
    )

    private fun report(
        vararg columns: ReportColumn,
        groupBy: List<String> = emptyList(),
        excludeEmpty: List<String> = emptyList(),
        label: String = "A report",
    ) = ClientReport("sites", label, columns.toList(), groupBy = groupBy, excludeEmpty = excludeEmpty)

    private fun col(id: String, path: String, kind: ReportKind? = null, combine: ReportCombine? = null, rollup: ReportCombine? = null) =
        ReportColumn(id, id.replaceFirstChar { it.uppercase() }, path, kind, combine, rollup)

    private fun bound(path: String): BoundPath = assertIs<Parsed.Ok<BoundPath>>(bindReportPath(path, scope)).value

    /** The one problem binding [path] reports: its code, and that its message names [mentions]. */
    private fun refusal(path: String, code: ProblemCode, mentions: String) {
        val problem = assertIs<Parsed.Failed>(bindReportPath(path, scope)).problems.single()
        assertEquals(code, problem.code, problem.message)
        assertTrue(mentions in problem.message, problem.message)
    }

    private fun reportRefusal(report: ClientReport, code: ProblemCode, mentions: String) {
        val problem = assertIs<Parsed.Failed>(bindReport(report, scope)).problems.single()
        assertEquals(code, problem.code, problem.message)
        assertTrue(mentions in problem.message, problem.message)
    }

    @Test
    fun aReportThatHoldsUpBindsEveryColumn() {
        val r = bindReport(
            report(
                col("auditor", "form.audit.auditor"),
                col("years", "form.yearly[*].year", combine = ReportCombine.count, rollup = ReportCombine.sum),
                col("status", "meta.formStatus"),
                groupBy = listOf("auditor"),
                excludeEmpty = listOf("years"),
            ),
            scope,
        )
        val columns = assertIs<Parsed.Ok<BoundReport>>(r).value.columns
        assertEquals(listOf("auditor", "years", "status"), columns.map { it.columnId })
        assertEquals(ReportCombine.first, columns[0].combine)
        assertEquals(ReportKind.number, columns[1].kind)
    }

    @Test
    fun formPathsBindToTheKindAndMultiplicityTheSchemaSays() {
        bound("form.audit.auditor").let { assertEquals(ReportKind.string, it.kind); assertFalse(it.multiValued) }
        assertEquals(ReportKind.date, bound("form.audit.visitedOn").kind)
        assertEquals(ReportKind.number, bound("form.audit.score").kind)
        assertEquals(ReportKind.boolean, bound("form.audit.passed").kind)
        // An array of values, and a field under an array of objects, are several.
        bound("form.audit.tags").let { assertEquals(ReportKind.string, it.kind); assertTrue(it.multiValued) }
        bound("form.audit.findings.cost").let { assertEquals(ReportKind.number, it.kind); assertTrue(it.multiValued) }
        // A `$ref` is followed, and a union's field is found in the branch that declares it.
        assertEquals(ReportKind.string, bound("form.audit.contact.email").kind)
        assertEquals(ReportKind.number, bound("form.audit.payment.tendered").kind)
        // Every branch declaring a field is consulted: agreeing, its kind; disagreeing, text, so no branch's values
        // are read as another's kind and shown blank.
        assertEquals(ReportKind.number, bound("form.audit.payment.total").kind)
        assertEquals(ReportKind.string, bound("form.audit.payment.settled").kind)
        // An envelope field has the vocabulary's kind.
        assertEquals(ReportKind.date, bound("form.audit.@updatedAt").kind)
    }

    @Test
    fun aKeyedTraitsSelectorDecidesHowManyEntriesAPathReads() {
        bound("form.yearly[2024].note").let { assertEquals(listOf("year"), it.pkFields); assertFalse(it.multiValued) }
        assertTrue(bound("form.yearly[*].note").multiValued)
        assertFalse(bound("form.quarterly[2024,Q1].amount").multiValued)
        // A named selector leaving a key field open reads every entry it matches.
        assertTrue(bound("form.quarterly[year=2024].amount").multiValued)
        assertFalse(bound("form.quarterly[quarter=Q1,year=2024].amount").multiValued)
    }

    @Test
    fun aFormPathMustNameATraitTheFormsCanCarry() {
        refusal("form.nothing.x", ReportBindProblem.unknownTrait, "'nothing'")
        // A trait that exists, but not on forms.
        refusal("form.profile.bio", ReportBindProblem.unknownTrait, "'profile'")
    }

    @Test
    fun aSelectorMustFitTheTraitsKey() {
        refusal("form.audit[1].auditor", ReportBindProblem.selector, "not keyed")
        refusal("form.yearly.note", ReportBindProblem.selector, "keyed by year")
        refusal("form.quarterly[2024].amount", ReportBindProblem.selector, "gives 1 value")
        refusal("form.quarterly[month=2].amount", ReportBindProblem.selector, "'month' is not a key field")
        // Each key value reads as its field's kind: the year is a number, the quarter text.
        refusal("form.yearly[last].note", ReportBindProblem.selector, "not a number")
        refusal("form.quarterly[year=soon].amount", ReportBindProblem.selector, "'soon'")
    }

    @Test
    fun theFieldPathMustExistAndEndAtAValue() {
        refusal("form.audit.inspector", ReportBindProblem.unknownField, "no field 'inspector'")
        refusal("form.audit.contact.phone", ReportBindProblem.unknownField, "'contact' has no field 'phone'")
        refusal("form.audit.payment.cheque", ReportBindProblem.unknownField, "'payment' has no field 'cheque'")
        refusal("form.audit.contact", ReportBindProblem.notScalar, "'contact' is an object")
        refusal("form.audit.findings", ReportBindProblem.notScalar, "'findings' is an object")
    }

    @Test
    fun aWorkflowPathMustNameAWorkflowAndAnApprovalTaskTheScopeHas() {
        assertEquals(ReportKind.string, bound("workflow.review.category").kind)
        assertEquals(ReportKind.boolean, bound("workflow.review.approval[signoff].approved").kind)
        refusal("workflow.elsewhere.category", ReportBindProblem.unknownWorkflow, "'elsewhere'")
        refusal("workflow.review.approval[later].approved", ReportBindProblem.unknownWorkflow, "no task 'later'")
        refusal("workflow.review.approval[collect].approved", ReportBindProblem.unknownWorkflow, "not an approval task")
    }

    @Test
    fun userAndMetaPathsBindFromTheirVocabulary() {
        bound("user.labels").let { assertEquals(ReportKind.string, it.kind); assertTrue(it.multiValued) }
        assertEquals(ReportKind.date, bound("meta.createdAt").kind)
        // The parser's own refusal comes through unchanged.
        refusal("user.shoeSize", ReportPathProblem.unknownAttribute, "'shoeSize'")
    }

    @Test
    fun theReportsShapeIsChecked() {
        reportRefusal(report(), ReportBindProblem.shape, "no columns")
        reportRefusal(report(col("a", "user.email"), label = " "), ReportBindProblem.shape, "no label")
        reportRefusal(report(col("a", "user.email"), col("a", "user.name")), ReportBindProblem.shape, "'a' is used twice")
        reportRefusal(report(ReportColumn("a", "", "user.email")), ReportBindProblem.shape, "'a' has no label")
        reportRefusal(report(col("a", "user.email"), groupBy = listOf("b")), ReportBindProblem.shape, "groupBy names 'b'")
        reportRefusal(report(col("a", "user.email"), excludeEmpty = listOf("a", "a")), ReportBindProblem.shape, "twice")
    }

    @Test
    fun aColumnsPathProblemNamesTheColumnAndThePath() {
        reportRefusal(
            report(col("ok", "user.email"), col("who", "form.audit.inspector")),
            ReportBindProblem.unknownField,
            "Column 'who' reads 'form.audit.inspector'",
        )
        // A path that does not parse is reported with the parser's code.
        reportRefusal(report(col("bad", "nowhere.x")), ReportPathProblem.unknownSource, "Column 'bad'")
    }

    @Test
    fun aDeclaredKindMustBeOneTheValueReadsAs() {
        // Anything reads as text, and text as anything the author says it holds.
        assertIs<Parsed.Ok<BoundReport>>(bindReport(report(col("s", "form.audit.score", ReportKind.string)), scope))
        assertIs<Parsed.Ok<BoundReport>>(bindReport(report(col("y", "form.audit.auditor", ReportKind.number)), scope))
        reportRefusal(report(col("p", "form.audit.passed", ReportKind.number)), ReportBindProblem.kindMismatch, "declared number")
    }

    @Test
    fun combineAndRollupMustSuitTheKind() {
        reportRefusal(report(col("a", "form.audit.tags", combine = ReportCombine.sum)), ReportBindProblem.badCombine, "combines by sum")
        reportRefusal(report(col("a", "form.audit.auditor", rollup = ReportCombine.avg)), ReportBindProblem.badCombine, "rolls up by avg")
        // A list rolls up only by count.
        reportRefusal(report(col("a", "form.audit.tags", rollup = ReportCombine.max)), ReportBindProblem.badCombine, "only by count")
        assertIs<Parsed.Ok<BoundReport>>(bindReport(report(col("a", "form.audit.tags", rollup = ReportCombine.count)), scope))
        // A count rolls up as the number it is.
        assertIs<Parsed.Ok<BoundReport>>(
            bindReport(report(col("a", "form.audit.tags", combine = ReportCombine.count, rollup = ReportCombine.sum)), scope),
        )
    }

    @Test
    fun aGroupedByColumnMustHaveOneValue() {
        reportRefusal(report(col("tags", "form.audit.tags"), groupBy = listOf("tags")), ReportBindProblem.groupByList, "'tags'")
        assertIs<Parsed.Ok<BoundReport>>(
            bindReport(report(col("tags", "form.audit.tags", combine = ReportCombine.first), groupBy = listOf("tags")), scope),
        )
    }
}
