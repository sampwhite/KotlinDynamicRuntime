package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.report.REP
import com.dynamicruntime.common.gedra.report.RHIS
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportHistoryWriter
import com.dynamicruntime.common.gedra.report.ReportService
import com.dynamicruntime.common.gedra.report.ReportSnapshotRows
import com.dynamicruntime.common.gedra.report.ReportSnapshotTrigger
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.addDays
import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Report history (issue #1034): a snapshot of a report's grouped run, taken by hand, stored and listed newest first by
 * cursor; the report's own grouping over the whole client, whoever asks; and who may ask. On a booted node with the
 * sample and response-schema validation on.
 */
class ReportHistoryTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "reportHistory", "reportHistoryTest", mapOf("KDR_LOAD_SAMPLE" to "true", REP.historyKeepEnvVar.name to "3"),
        additionalComponents = listOf(SampleComponent()),
    )
    val admin = TestUser.create(cxt, "hist-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
    val full = TestUser.createFullAdmin(cxt, "hist-full@example.com")
    val owner = TestUser.create(cxt, "hist-owner@acme.test", userClient = SC.acme)

    fun audit(auditor: String) = mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to auditor, SC.findings to "closed"))

    fun expense(year: Long, perItem: Double = 10.0, count: Long = 2) = mapOf(
        GE.traitId to ST.expenseReport,
        GE.data to mapOf(ST.year to year, ST.perItemAmount to perItem, ST.itemCount to count),
    )

    fun create(vararg entries: Map<String, Any?>): String =
        owner.postItem(clientPath(GEP.formDocCreate, SC.acme), mapOf(GDF.entries to entries.toList()))[GDF.gedraId].toOptStr().shouldNotBeNull()

    fun snapshot(user: TestUser, reportId: String, more: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        user.postData(UADEP.reportSnapshot, mapOf(RRUN.reportId to reportId) + more)

    fun history(user: TestUser, reportId: String, more: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        user.client.sendJsonGetRequest(UADEP.reportHistory, mapOf(RRUN.reportId to reportId) + more)

    fun itemsOf(env: Map<String, Any?>) = env[EP.items].toJsonListOfMaps()
    fun rowsOf(snapshot: Map<String, Any?>) = snapshot[RHIS.rows].toJsonListOfMaps()
    fun groupOf(row: Map<String, Any?>) = row[RRUN.group].toJsonMapOrEmpty()

    create(expense(2024, 12.5, 4))
    create(expense(2023))
    create(expense(2024))

    "a snapshot stores the report's grouped run, over its own grouping, and reads back the same groups" {
        val taken = snapshot(admin, SC.expensesByYear)
        taken[RRUN.reportId] shouldBe SC.expensesByYear
        taken[RRUN.client] shouldBe SC.acme
        taken[RHIS.trigger] shouldBe ReportSnapshotTrigger.manual.name
        taken[RHIS.launchName].shouldBeNull()
        taken[RRUN.groupBy].toJsonListOfStrings() shouldBe listOf("year")
        taken[RHIS.truncated] shouldBe false
        val rows = rowsOf(taken)
        rows.map { groupOf(it)["year"] } shouldBe listOf(2023L, 2024L)
        rows.map { it[RRUN.count] } shouldBe listOf(1L, 2L)
        // The same groups a grouped run answers.
        val run = admin.client.sendJsonGetRequest(UADEP.reportRun, mapOf(RRUN.reportId to SC.expensesByYear, RRUN.aggregate to true))
        itemsOf(run).map { groupOf(it)["year"] to it[RRUN.count] } shouldBe rows.map { groupOf(it)["year"] to it[RRUN.count] }
        // The listing says whether the job snapshots a report: none of the sample's asks yet (the job is #1035).
        val listed = itemsOf(admin.client.sendJsonGetRequest(UADEP.reports)).associateBy { it[RRUN.reportId] }
        listed.getValue(SC.expensesByYear)[RRUN.history] shouldBe false
        listed.getValue(SC.formRoster)[RRUN.history] shouldBe false
    }

    "the history lists newest first and pages by cursor; a day's snapshots are all kept until the day is over" {
        val reportId = SC.auditOverview
        val ids = (1..4).map { snapshot(admin, reportId)[RHIS.snapshotId] }
        val first = history(admin, reportId, mapOf(EP.limit to 3))
        val summary = first[EP.summary].toJsonMapOrEmpty()
        summary[RRUN.reportId] shouldBe reportId
        summary[RRUN.history] shouldBe false
        // KDR_REPORT_HISTORY_KEEP is 3 days on this node, and these four are today's: none is pruned.
        summary[RHIS.numSnapshots] shouldBe 4L
        first[EP.numAvailable] shouldBe 4L
        itemsOf(first).map { it[RHIS.snapshotId] } shouldBe listOf(ids[3], ids[2], ids[1])
        itemsOf(first).all { it[RHIS.sameDefinition] == true } shouldBe true
        val next = first[EP.next].toOptStr().shouldNotBeNull()
        val second = history(admin, reportId, mapOf(EP.limit to 3, EP.after to next))
        itemsOf(second).map { it[RHIS.snapshotId] } shouldBe listOf(ids[0])
        second[EP.next].shouldBeNull()
        // A report with no snapshots lists none, and still says what it is.
        val empty = history(admin, SC.formRoster)
        itemsOf(empty).shouldBeEmpty()
        empty[EP.summary].toJsonMapOrEmpty()[RHIS.numSnapshots] shouldBe 0L
    }

    "past days keep their latest snapshot, the oldest days go beyond the kept count, and a backdated one is read back" {
        val reportId = SC.yearlyNotes
        val acme = cxt.mkSubContext("history", SC.globex)
        val bound = ReportService.get(cxt).forClient(SC.globex).report(reportId).shouldNotBeNull().bound
        val now = cxt.instanceNow()
        fun take(daysAgo: Int): Long =
            ReportHistoryWriter.snapshot(acme, SC.globex, bound, ReportSnapshotTrigger.manual, takenAt = now.addDays(-daysAgo)).snapshotId
        // Five days ago, twice four days ago, three days ago, and twice today -- written in that order, each read back
        // whatever the pruning after it does (the five-days-ago one is the oldest of too many days by the end).
        val d5 = take(5)
        val d4a = take(4)
        val d4b = take(4)
        val d3 = take(3)
        val t1 = take(0)
        val t2 = take(0)
        // Three days are kept: today, three days ago and four days ago -- the latter's latest alone -- and today's both.
        ReportSnapshotRows.keys(acme, SC.globex, reportId).map { it.second } shouldBe listOf(t2, t1, d3, d4b)
        listOf(d5, d4a).forEach { ReportSnapshotRows.read(acme, SC.globex, it).shouldBeNull() }
        // Listed through the endpoint in the same order, by an administrator who sees every client.
        val listed = history(full, reportId, mapOf(RRUN.client to SC.globex))
        itemsOf(listed).map { it[RHIS.snapshotId] } shouldBe listOf(t2, t1, d3, d4b)
    }

    "a snapshot keeps a date rollup, a group with no value, and says when its rows were cut" {
        // Forms with site audits, and one with none: the no-auditor group, and a latest-audit instant to roll up.
        create(audit("Smith"), expense(2025))
        create(audit("Jones"), expense(2025))
        val taken = snapshot(admin, SC.auditOverview)
        val rows = rowsOf(taken)
        rows.map { groupOf(it)["auditor"] } shouldBe listOf("Jones", "Smith", null)
        // The expense report's latest audit activity: an instant, stored and read back as a date.
        val expenses = snapshot(admin, SC.expensesByYear)
        val latest = rowsOf(expenses).single { groupOf(it)["year"] == 2025L }[RRUN.values].toJsonMapOrEmpty()["lastAudited"]
        latest.toOptStr().shouldNotBeNull().parseDateOrNull().shouldNotBeNull()
        // Cut at one group: the first in key order, and said to be cut.
        val bound = ReportService.get(cxt).forClient(SC.acme).report(SC.auditOverview).shouldNotBeNull().bound
        val cut = ReportHistoryWriter.snapshot(cxt.mkSubContext("history", SC.acme), SC.acme, bound, ReportSnapshotTrigger.manual, maxGroups = 1)
        cut.data[RHIS.rows].toJsonListOfMaps().map { groupOf(it)["auditor"] } shouldBe listOf("Jones")
        cut.data[RHIS.truncated] shouldBe true
        cut.groupCount shouldBe 3
    }

    "a report grouping by nothing is snapshotted as its one total row" {
        val taken = snapshot(admin, SC.formRoster)
        taken[RRUN.groupBy].toJsonListOfStrings().shouldBeEmpty()
        val row = rowsOf(taken).single()
        groupOf(row).isEmpty() shouldBe true
        (row[RRUN.count] as Number).toInt() shouldBe taken[RRUN.scanned]
    }

    "an unknown report, another client's, and an organization-confined administrator are refused" {
        admin.expectError(EXC.notFound, UADEP.reportSnapshot, mapOf(RRUN.reportId to "noSuchReport"))
        admin.expectError(EXC.notFound, UADEP.reportHistory, args = mapOf(RRUN.reportId to "noSuchReport"))
        admin.expectError(EXC.notAuthorized, UADEP.reportSnapshot, mapOf(RRUN.reportId to SC.yearlyNotes, RRUN.client to SC.globex))
        // An administrator who sees every client names any.
        snapshot(full, SC.yearlyNotes, mapOf(RRUN.client to SC.globex))[RRUN.client] shouldBe SC.globex
        // Confined to an organization: the history is client-wide, so neither taking nor viewing is theirs.
        val orgAdmin = TestUser.create(cxt, "hist-org@acme.test", level = ROLE.admin, userClient = SC.acme)
        full.postData(UADEP.userSetOrg, mapOf(ADF.userId to orgAdmin.userId, ADF.org to "west"))
        orgAdmin.expectError(EXC.notAuthorized, UADEP.reportSnapshot, mapOf(RRUN.reportId to SC.expensesByYear)).toString() shouldContain "west"
        orgAdmin.expectError(EXC.notAuthorized, UADEP.reportHistory, args = mapOf(RRUN.reportId to SC.expensesByYear))
    }
})
