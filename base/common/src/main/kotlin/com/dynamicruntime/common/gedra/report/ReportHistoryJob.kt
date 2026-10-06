package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunCxt
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobTaskResult

/** The report-history job's names (issue #1035). Each name matches its value. */
@Suppress("ConstPropertyName")
object RHJ {
    /** The job type. */
    const val jobType = "reportHistory"

    /** Its profile: a deployment tunes it under `jobProfiles.reportHistory`. */
    const val profile = "reportHistory"

    /** The scenario a snapshot refused for the client's size fails with: more forms than a report run reads. */
    const val scanLimit = "reportScanLimit"
}

/**
 * The nightly report-history job (issue #1035): for every client, a snapshot of each report that asks for history
 * (`history = true`, issue #1033), so what its groups were on a day is there to chart. The Reports page's Snapshot
 * now takes the same snapshot by hand ([ReportHistoryWriter]); this is what makes a series without anybody pressing.
 *
 * **One task per report, keyed by its id.** A client's reports are the global ones and its own, so a global report
 * asking for history is snapshotted for every client the node carries.
 *
 * - **Once per launch.** A task first asks whether its launch already took the report's snapshot for the client
 *   ([ReportSnapshotRows.exists]) and answers nothing-to-do when it did: a client's row adopted after a failure
 *   resumes its task list from the start, and a scheduled slot carries one name on every node, so a resumed or
 *   joined launch never stores a day twice.
 * - **A report dropped, or no longer asking, since the listing** is nothing to do, with a note in the trace.
 * - **A client with more forms than a report run reads** fails the task ([RHJ.scanLimit]) and the launch goes on:
 *   a snapshot of part of a client would mislead, as a run of part would.
 * - **A dry run** notes the snapshot it would take and stores nothing.
 * - **The task total is the report count**, so the job may be launched synchronously.
 *
 * Scheduled at 03:30 UTC, after the 03:00 derived-state recompute ([com.dynamicruntime.common.gedra.stateRecomputeJob]),
 * so a report grouping by a form's status snapshots the statuses that recompute just brought up to date.
 */
fun reportHistoryJob(): JobDef = JobDef(
    jobType = RHJ.jobType,
    description = "Stores a snapshot of each report that asks for history, for every client, so a report's groups " +
        "can be charted over time.",
    profile = JobProfile(RHJ.profile),
    tasks = { run, client -> historyReportIds(run.cxt, client) },
    countTasks = { run, client -> historyReportIds(run.cxt, client).size },
    runTask = ::snapshotOne,
    schedule = JobSchedule.daily("03:30"),
)

/** The ids of [client]'s reports -- the global ones and its own -- that ask for history, in id order. */
private fun historyReportIds(cxt: KdrCxt, client: String): List<String> =
    ReportService.get(cxt).forClient(client).reports.values.filter { it.bound.report.history }.map { it.bound.reportId }.sorted()

private fun snapshotOne(run: JobRunCxt, reportId: String): JobTaskResult {
    val client = run.client ?: return JobTaskResult.nothingToDo
    val bound = ReportService.get(run.cxt).forClient(client).report(reportId)?.bound?.takeIf { it.report.history }
    if (bound == null) {
        run.trace("The report is gone, or no longer asks for history, since the launch listed it.", reportId)
        return JobTaskResult.nothingToDo
    }
    if (ReportSnapshotRows.exists(run.cxt, client, reportId, run.launch.name)) return JobTaskResult.nothingToDo
    if (run.dryRun) {
        run.trace("No snapshot for this launch yet; a real run would take one.", reportId)
        return JobTaskResult.done
    }
    run.checkAbort()
    try {
        ReportHistoryWriter.snapshot(run.cxt, client, bound, ReportSnapshotTrigger.scheduled, launchName = run.launch.name)
    } catch (e: KdrException) {
        // The run's own refusal of a client past the scan limit: this report's task fails, and the others go on.
        if (e.code != EXC.badInput) throw e
        throw KdrException.mkJob(e.message ?: "The report could not be run.", JobHandling.skipTask, RHJ.scanLimit, cause = e)
    }
    return JobTaskResult.done
}
