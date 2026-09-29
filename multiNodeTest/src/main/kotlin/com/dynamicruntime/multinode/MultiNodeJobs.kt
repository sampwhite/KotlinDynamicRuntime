package com.dynamicruntime.multinode

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunCxt
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.tableModule
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration.Companion.seconds

/** The jobs fixture's names. Each name matches its value. */
@Suppress("ConstPropertyName")
object MNJ {
    const val topic = "multiNode"
    const val workTable = "MultiNodeWork"

    /** The fake job the scenarios launch, shaped per launch by its work areas. */
    const val workJob = "mnWork"

    /** The fake job that runs on its own, every fifteen seconds. */
    const val scheduledJob = "mnScheduled"

    const val execId = "execId"
    const val jobType = "jobType"
    const val launchName = "launchName"
    const val taskKey = "taskKey"
    const val node = "node"
    const val startedMs = "startedMs"
    const val endedMs = "endedMs"

    // The work endpoint.
    const val workPath = "/operator/multiNode/work"
    const val workReportType = "MultiNodeWorkReport"
    const val executions = "executions"
    const val ended = "ended"
    const val incomplete = "incomplete"
    const val maxPerTask = "maxPerTask"
    const val overlaps = "overlaps"
    const val nodes = "nodes"

    /** Work-area prefixes a launch shapes `mnWork` with: `tasks:<n>` per client, `taskMs:<ms>` per task. */
    const val tasksArea = "tasks:"
    const val taskMsArea = "taskMs:"
}

/**
 * The batch-jobs fixture (issue #872): two fake jobs whose "work" is to record itself, so the harness can check
 * afterwards that no task was ever run by two executions at once -- the one thing the job framework exists to
 * prevent, and the one no in-process test can see.
 *
 * Each task execution writes a row as it starts and records its end however it ends -- finished, or stopped by a
 * fence, an abort or a shutdown -- with the node and the wall-clock times (the nodes share one machine, so one
 * clock). [findOverlaps] then reports any two *ended* executions of one task whose times overlap. Only an execution
 * cut off by a killed node has no end, and it is not counted as overlapping the one that later redoes its task:
 * that redo is the framework working, not failing.
 */
@Suppress("ConstPropertyName")
object MultiNodeJobs {
    const val profileName = "multiNodeTest"

    fun tables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "multiNode", topic = MNJ.topic) {
        table(MNJ.workTable, "One execution of a fake task, as the harness checks it (#872).") {
            column(MNJ.execId, "This execution's id.", required = true)
            column(MNJ.jobType, "The fake job.", required = true)
            column(MNJ.launchName, "The launch it ran under.", required = true)
            column(PF.client, "The client it ran for.", required = true)
            column(MNJ.taskKey, "The task.", required = true)
            column(MNJ.node, "The node that ran it.", required = true)
            column(MNJ.startedMs, "When it started, epoch milliseconds.", required = true) { type = SCT.integer }
            column(MNJ.endedMs, "When it ended; absent for one cut off before it could.") { type = SCT.integer }
            primaryKey(MNJ.execId)
            index(MNJ.jobType, MNJ.launchName)
        }
    }

    fun jobs(): List<JobDef> = listOf(
        fakeJob(MNJ.workJob, "A fake job the multi-node scenarios launch, shaped by its work areas.", schedule = null),
        fakeJob(
            MNJ.scheduledJob, "A fake job that runs every fifteen seconds, for scheduler contention.",
            schedule = JobSchedule.every(15.seconds, window = 10.seconds),
        ),
    )

    private fun fakeJob(type: String, description: String, schedule: JobSchedule?): JobDef = JobDef(
        jobType = type,
        description = description,
        profile = JobProfile(profileName),
        tasks = { run, _ -> (1..setting(run, MNJ.tasksArea, defaultTasks)).map { "t$it" } },
        countTasks = { run, _ -> setting(run, MNJ.tasksArea, defaultTasks) },
        runTask = { run, key -> work(run, key) },
        schedule = schedule,
    )

    private const val defaultTasks = 6
    private const val defaultTaskMs = 150

    /** A `<prefix><n>` work area's number, else [default]. */
    private fun setting(run: JobRunCxt, prefix: String, default: Int): Int =
        run.workAreas.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() ?: default

    /** One fake task: record the start, "work" for a while in slices that notice a stop, record the end. */
    private fun work(run: JobRunCxt, key: String): JobTaskResult {
        val cxt = run.cxt
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, MNJ.topic)
        val table = cxt.getSchema().tables[MNJ.workTable] ?: throw KdrException("${MNJ.workTable} is not registered.")
        val execId = cxt.mkUniqueId()
        val row = linkedMapOf<String, Any?>(
            MNJ.execId to execId, MNJ.jobType to run.launch.jobType, MNJ.launchName to run.launch.name,
            PF.client to run.client, MNJ.taskKey to key, MNJ.node to NodeService.get(cxt).nodeLabel,
            MNJ.startedMs to System.currentTimeMillis(),
        )
        SqlTopicUtil.prepForStdExecute(cxt, table, row)
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, table), row) }
        try {
            var left = setting(run, MNJ.taskMsArea, defaultTaskMs).toLong()
            while (left > 0) {
                run.checkAbort()
                val slice = minOf(left, 50L)
                Thread.sleep(slice)
                left -= slice
            }
        } finally {
            // Recorded however the execution ends -- finished, or stopped by a fence, an abort or a shutdown -- so a
            // stopped execution still counts in the overlap check. Only a killed node's never gets one. A failure
            // to record it must not replace the exception the task is ending with.
            runCatching {
                val end = SqlTopicUtil.mkPartialUpdateStmt(
                    sqlCxt, table, "uMultiNodeWorkEnd", "c:${MNJ.endedMs} = :${MNJ.endedMs}", "c:${MNJ.execId} = :${MNJ.execId}",
                )
                val bind = mutableMapOf<String, Any?>(MNJ.execId to execId, MNJ.endedMs to System.currentTimeMillis())
                SqlTopicUtil.prepForStdUpdate(cxt, table, bind, priorRow = null)
                sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatement(cxt, end, bind) }
            }
        }
        return JobTaskResult.done
    }

    /** The fixture's operator surface: what the fake jobs recorded, and any overlap among it. */
    fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "multiNode") {
        type(MNJ.workReportType) {
            type = SCT.kObject
            description = "What a fake job's executions recorded, and whether any two of one task overlapped."
            property(MNJ.executions, "Executions recorded.", required = true) { type = SCT.integer }
            property(MNJ.ended, "Executions that ended: finished, or stopped part way.", required = true) { type = SCT.integer }
            property(MNJ.incomplete, "Executions with no recorded end, as a killed node leaves them.", required = true) {
                type = SCT.integer
            }
            property(MNJ.maxPerTask, "The most ended executions of any one task.", required = true) { type = SCT.integer }
            property(MNJ.overlaps, "Ended executions of one task that ran at the same time.", required = true) {
                type = SCT.array
                items {
                    type = SCT.kObject
                    additionalProperties = true
                }
            }
        }
        generalEndpoint(
            MNJ.workPath,
            "Reports what a fake job's executions recorded (issue #872), for one launch or all of the job's.",
            HttpMethod.GET,
            outputRef = MNJ.workReportType,
            inputFields = {
                field(MNJ.jobType, "The fake job.", required = true)
                field(MNJ.launchName, "Only this launch's executions.")
            },
            tags = setOf(ETAG.internal),
        ) { c, request ->
            val jobType = request[MNJ.jobType].toOptStr() ?: throw KdrException.mkInput("A ${MNJ.jobType} is required.")
            workReport(readExecutions(c, jobType, request[MNJ.launchName].toOptStr()))
        }
    }

    private fun readExecutions(cxt: KdrCxt, jobType: String, launchName: String?): List<WorkExecution> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, MNJ.topic)
        val table = cxt.getSchema().tables[MNJ.workTable] ?: throw KdrException("${MNJ.workTable} is not registered.")
        val byLaunch = if (launchName != null) " and c:${MNJ.launchName} = :${MNJ.launchName}" else ""
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qMultiNodeWork${if (launchName != null) "ByLaunch" else ""}", table.columns,
            "select * from t:${MNJ.workTable} where c:${MNJ.jobType} = :${MNJ.jobType}$byLaunch",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(MNJ.jobType to jobType, MNJ.launchName to launchName))
        }
        return rows.map {
            WorkExecution(
                launchName = it[MNJ.launchName].toOptStr().orEmpty(),
                client = it[PF.client].toOptStr().orEmpty(),
                taskKey = it[MNJ.taskKey].toOptStr().orEmpty(),
                node = it[MNJ.node].toOptStr().orEmpty(),
                startedMs = it[MNJ.startedMs].toOptLong() ?: 0,
                endedMs = it[MNJ.endedMs].toOptLong(),
            )
        }
    }
}

/** One recorded execution of a fake task. */
class WorkExecution(
    val launchName: String,
    val client: String,
    val taskKey: String,
    val node: String,
    val startedMs: Long,
    val endedMs: Long?,
)

/** Two ended executions of one task that ran at the same time. */
class WorkOverlap(val a: WorkExecution, val b: WorkExecution)

/**
 * Every pair of **ended** executions of one task -- one launch, client, and task key -- whose times overlap. An
 * execution with no end (cut off by a killed node) is left out: the task it began is rightly redone by whoever
 * adopts the work, and the two are not concurrent in any sense the framework could have prevented.
 */
fun findOverlaps(executions: List<WorkExecution>): List<WorkOverlap> =
    executions.filter { it.endedMs != null }
        .groupBy { Triple(it.launchName, it.client, it.taskKey) }
        .values
        .flatMap { runs ->
            val sorted = runs.sortedBy { it.startedMs }
            sorted.flatMapIndexed { i, a ->
                sorted.drop(i + 1).filter { b -> b.startedMs < (a.endedMs ?: Long.MAX_VALUE) }.map { b -> WorkOverlap(a, b) }
            }
        }

/** The work endpoint's report over [executions]. */
fun workReport(executions: List<WorkExecution>): Map<String, Any?> {
    val done = executions.filter { it.endedMs != null }
    return linkedMapOf(
        MNJ.executions to executions.size,
        MNJ.ended to done.size,
        MNJ.incomplete to executions.size - done.size,
        MNJ.maxPerTask to (done.groupingBy { Triple(it.launchName, it.client, it.taskKey) }.eachCount().values.maxOrNull() ?: 0),
        MNJ.overlaps to findOverlaps(executions).map {
            linkedMapOf(
                MNJ.launchName to it.a.launchName, PF.client to it.a.client, MNJ.taskKey to it.a.taskKey,
                MNJ.nodes to listOf(it.a.node, it.b.node),
            )
        },
    )
}
