package com.dynamicruntime.sample.simulation

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.report.RHIS
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportSnapshotTrigger
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.gedra.SC
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The sample's simulations (issue #997): `report-demo`, declared by the sample, is listed beside `common`'s where the
 * sample is loaded, adds the forms its report says on every run, and its acme administrator sees them in a report.
 */
class SampleSimulationTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "sampleSimulations", "sampleSimulation997", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val tester = TestUser.create(cxt, "tester@sampleSimulation997.test")
    val reportDemo = SIM.pathRoot + ReportDemo.simulationName
    val historyDemo = SIM.pathRoot + ReportHistoryDemo.simulationName

    fun formsInAcme(admin: TestUser): Int =
        (admin.client.sendJsonGetRequest(UADEP.reportRun, mapOf(RRUN.reportId to SC.formRoster))[EP.numAvailable] as Number).toInt()

    "the sample's simulation is listed beside common's" {
        val paths = tester.getData(SIM.list)[EI.endpoints].toJsonListOfMaps().map { it[EI.path] }
        paths shouldContainAll listOf(reportDemo, historyDemo, SIM.pathRoot + DesignDemo.simulationName)
    }

    "report-demo adds the forms it reports, on every run, for its administrator to see" {
        val report = tester.postData(reportDemo, emptyMap())
        report[SIM.clients].toJsonListOfStrings() shouldBe listOf(SC.acme, SC.globex)
        val users = report[SIM.users].toJsonListOfMaps()
        val adminEntry = users.single { it[SIM.email] == ReportDemo.acmeAdmin }
        users.single { it[SIM.email] == ReportDemo.overseer }[SIM.capabilities].toJsonListOfStrings() shouldBe listOf(ROLE.allClients)

        // Signing in as the reported administrator, the forms are there; a rerun adds as many again.
        val admin = TestUser.create(cxt, adminEntry[SIM.email] as String, level = ROLE.admin, userClient = SC.acme)
        val first = formsInAcme(admin)
        first shouldBe ReportDemo.acmeForms
        tester.postData(reportDemo, emptyMap())
        formsInAcme(admin) shouldBe first + ReportDemo.acmeForms
    }

    // Last: it adds forms, which the count above does not expect.
    "report-history-demo leaves five dated days of snapshots, growing day by day, and a rerun keeps one a past day" {
        val report = tester.postData(historyDemo, emptyMap())
        report[SIM.startPage] shouldBe "page=reports&rpt=${SC.expensesByYear}&view=history"
        report[SIM.users].toJsonListOfMaps().map { it[SIM.email] } shouldContainAll listOf(ReportDemo.acmeAdmin, ReportDemo.overseer)

        // As the reported administrator, the history each report's view will draw: newest first.
        val admin = TestUser.create(cxt, ReportDemo.acmeAdmin, level = ROLE.admin, userClient = SC.acme)
        fun series(reportId: String): List<Map<String, Any?>> =
            admin.client.sendJsonGetRequest(UADEP.reportHistory, mapOf(RRUN.reportId to reportId))[EP.items].toJsonListOfMaps()
        fun dayOf(snapshot: Map<String, Any?>) = snapshot[RHIS.takenAt].toOptStr().shouldNotBeNull().take(10)
        fun scanned(snapshot: Map<String, Any?>) = (snapshot[RRUN.scanned] as Number).toInt()
        for (reportId in ReportHistoryDemo.reports) {
            val snapshots = series(reportId)
            snapshots.size shouldBe ReportHistoryDemo.days
            snapshots.map { dayOf(it) }.toSet().size shouldBe ReportHistoryDemo.days
            snapshots.map { dayOf(it) } shouldBe snapshots.map { dayOf(it) }.sortedDescending()
            // Each day read the day before's forms and its own six more.
            snapshots.zipWithNext { newer, older -> scanned(newer) - scanned(older) }.toSet() shouldBe setOf(ReportHistoryDemo.formsPerDay)
            snapshots.all { it[RHIS.trigger] == ReportSnapshotTrigger.scheduled.name && it[RHIS.launchName] == ReportHistoryDemo.simulationName } shouldBe true
        }
        scanned(series(SC.expensesByYear).first()) shouldBe formsInAcme(admin)

        // A rerun: a newer snapshot for each day -- a past day keeps only its latest, today both.
        val before = series(SC.expensesByYear)
        tester.postData(historyDemo, emptyMap())
        val after = series(SC.expensesByYear)
        after.size shouldBe ReportHistoryDemo.days + 1
        after.map { it[RHIS.snapshotId] }.count { id -> before.any { it[RHIS.snapshotId] == id } } shouldBe 1
        scanned(after.first()) shouldBe formsInAcme(admin)
    }
})
