package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.report.REP
import com.dynamicruntime.common.gedra.report.RHJ
import com.dynamicruntime.common.gedra.report.ReportHistoryWriter
import com.dynamicruntime.common.gedra.report.ReportService
import com.dynamicruntime.common.gedra.report.ReportSnapshotRow
import com.dynamicruntime.common.gedra.report.ReportSnapshotRows
import com.dynamicruntime.common.gedra.report.ReportSnapshotTrigger
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobLaunchResult
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.job.JobTraceEvent
import com.dynamicruntime.common.job.JobTraceRows
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The nightly report-history job (issue #1035), run against the sample's acme client, two of whose reports ask for
 * history. Runs are driven from the calling thread, so each finishes before the launch returns; what each task did
 * is read from the trace, by report.
 */
class ReportHistoryJobTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "reportHistoryJob", "reportHistoryJobTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    fun acme(): KdrCxt = cxt.mkSubContext("rhj", SC.acme).also { it.userId = 103501L }

    // A form, so a snapshot has a group to hold.
    GedraDataService.get(cxt).createGedra(
        acme(), GedraDataType.formDoc, listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2024))),
    )

    fun launch(name: String, dryRun: Boolean = false): JobLaunchResult = JobService.get(cxt).launch(
        cxt, RHJ.jobType, name, mode = JobRunMode.pooledOnCaller, clients = listOf(SC.acme), dryRun = dryRun,
    )

    /** What the task for [reportId] did under [name], by trace event. */
    fun eventsFor(name: String, reportId: String, dryRun: Boolean = false): List<String?> =
        JobTraceRows.read(cxt, RHJ.jobType, JobLaunchKind.endpoint, name, dryRun).filter { it.taskKey == reportId }.map { it.event?.name }

    /** The snapshots [name] took of [reportId] for acme. */
    fun takenBy(name: String, reportId: String): List<ReportSnapshotRow> =
        ReportSnapshotRows.list(cxt, SC.acme, reportId).filter { it.launchName == name }

    "a launch snapshots each report that asks for history, under its name, and no other" {
        launch("run1").outcome shouldBe JobLaunchOutcome.completed
        for (reportId in listOf(SC.expensesByYear, SC.auditOverview)) {
            eventsFor("run1", reportId) shouldBe listOf(JobTraceEvent.taskDone.name)
            val taken = takenBy("run1", reportId).single()
            taken.trigger shouldBe ReportSnapshotTrigger.scheduled.name
            taken.client shouldBe SC.acme
        }
        // The roster asks for no history: not a task, and nothing stored.
        eventsFor("run1", SC.formRoster).shouldBeEmpty()
        ReportSnapshotRows.list(cxt, SC.acme, SC.formRoster).shouldBeEmpty()
        // The launch's work is complete: launched again under its name, nothing runs.
        launch("run1").outcome shouldBe JobLaunchOutcome.alreadyComplete
        takenBy("run1", SC.expensesByYear).size shouldBe 1
    }

    "a report its launch already snapshotted is nothing to do, so a resumed launch never stores a day twice" {
        // As a launch that failed after its first report leaves things: one snapshot under its name, the rest to do.
        val bound = ReportService.get(cxt).forClient(SC.acme).report(SC.expensesByYear).shouldNotBeNull().bound
        ReportHistoryWriter.snapshot(acme(), SC.acme, bound, ReportSnapshotTrigger.scheduled, launchName = "resumed1")
        launch("resumed1").outcome shouldBe JobLaunchOutcome.completed
        eventsFor("resumed1", SC.expensesByYear) shouldBe listOf(JobTraceEvent.taskNothingToDo.name)
        eventsFor("resumed1", SC.auditOverview) shouldBe listOf(JobTraceEvent.taskDone.name)
        takenBy("resumed1", SC.expensesByYear).size shouldBe 1
        takenBy("resumed1", SC.auditOverview).size shouldBe 1
    }

    "a dry run notes the snapshots it would take and stores none" {
        launch("dry1", dryRun = true).outcome shouldBe JobLaunchOutcome.completed
        eventsFor("dry1", SC.expensesByYear, dryRun = true) shouldBe listOf(JobTraceEvent.jobNote.name, JobTraceEvent.taskDone.name)
        takenBy("dry1", SC.expensesByYear).shouldBeEmpty()
        takenBy("dry1", SC.auditOverview).shouldBeEmpty()
    }

    "a client with more forms than a run reads fails its reports' tasks, and the launch still completes" {
        cxt.instanceConfig.put(REP.scanLimitEnvVar.name, "0")
        try {
            launch("limit1").outcome shouldBe JobLaunchOutcome.completed
            eventsFor("limit1", SC.expensesByYear) shouldBe listOf(JobTraceEvent.taskFailed.name)
            eventsFor("limit1", SC.auditOverview) shouldBe listOf(JobTraceEvent.taskFailed.name)
            // Failed as the scan limit, in the run's own words naming the variable -- not as an unexplained fault.
            val failure = JobTraceRows.read(cxt, RHJ.jobType, JobLaunchKind.endpoint, "limit1").single { it.taskKey == SC.expensesByYear }
            failure.data[KdrException.scenarioKey] shouldBe RHJ.scanLimit
            failure.message.shouldNotBeNull() shouldContain REP.scanLimitEnvVar.name
            takenBy("limit1", SC.expensesByYear).shouldBeEmpty()
        } finally {
            cxt.instanceConfig.put(REP.scanLimitEnvVar.name, null)
        }
    }

    "the job is registered to run nightly, after the state recompute" {
        JobService.get(cxt).def(RHJ.jobType).schedule.shouldBeInstanceOf<JobSchedule.Daily>().minutesOfDay shouldBe listOf(3 * 60 + 30)
    }
})
