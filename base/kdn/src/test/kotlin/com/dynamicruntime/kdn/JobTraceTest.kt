package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.JOB
import com.dynamicruntime.common.job.JOBEP
import com.dynamicruntime.common.job.JOBF
import com.dynamicruntime.common.job.JOBT
import com.dynamicruntime.common.job.JPF
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobLaunch
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.job.JobTraceEvent
import com.dynamicruntime.common.job.JobTraceLevel
import com.dynamicruntime.common.job.JobTraceRows
import com.dynamicruntime.common.job.JobTracer
import com.dynamicruntime.common.job.jobTopic
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The job trace (issue #879): what each level records, how the level is chosen, that an entry is written outside
 * the caller's transaction, and that the trace stays bounded. Launches are synchronous, as in [JobRunnerTest], so
 * a run's whole trace is written by the time `launch` returns.
 */
class JobTraceTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "jobTrace", "jobTraceTest",
        // The deployment's own default for one profile: launch level, which a launch may still override.
        mapOf(JPF.jobProfiles to mapOf(TraceFixture.configuredProfile to mapOf(JPF.trace to JobTraceLevel.launch.name))),
        additionalComponents = listOf(TraceFixture()),
    )

    fun launch(jobType: String, name: String, trace: JobTraceLevel? = null) =
        JobService.get(cxt).launch(cxt, jobType, name, mode = JobRunMode.sync, clients = listOf(CL.hub), trace = trace)

    fun events(jobType: String, name: String): List<String?> =
        JobTraceRows.read(cxt, jobType, JobLaunchKind.endpoint, name).map { it.event?.name }

    val launchOnly = listOf(
        JobTraceEvent.launchClaimed.name, JobTraceEvent.clientClaimed.name,
        JobTraceEvent.clientEnded.name, JobTraceEvent.launchEnded.name,
    )

    "a test instance traces every task by default, failures and retries included" {
        val tries = AtomicInteger()
        JobScripts.set("jtrTraced", mapOf(CL.hub to listOf("a1", "n1", "f1", "r1"))) { _, key ->
            when (key) {
                "n1" -> JobTaskResult.nothingToDo
                "f1" -> throw KdrException.mkJob("It broke.", JobHandling.skipTask, "testBroke", "gd.fd.x1")
                "r1" -> if (tries.incrementAndGet() == 1) {
                    throw KdrException.mkJob("Not yet.", JobHandling.retryTask, "testFlaky", key)
                } else {
                    JobTaskResult.done
                }
                else -> JobTaskResult.done
            }
        }
        launch("jtrTraced", "run1").outcome shouldBe JobLaunchOutcome.completed

        val trace = JobTraceRows.read(cxt, "jtrTraced", JobLaunchKind.endpoint, "run1")
        trace.map { it.event?.name } shouldContainExactly listOf(
            JobTraceEvent.launchClaimed, JobTraceEvent.clientClaimed,
            JobTraceEvent.taskDone, JobTraceEvent.taskNothingToDo, JobTraceEvent.taskFailed,
            JobTraceEvent.taskRetry, JobTraceEvent.taskDone,
            JobTraceEvent.clientEnded, JobTraceEvent.launchEnded,
        ).map { it.name }
        val failed = trace.single { it.event == JobTraceEvent.taskFailed }
        failed.taskKey shouldBe "f1"
        failed.client shouldBe CL.hub
        failed.data[KdrException.scenarioKey] shouldBe "testBroke"
        failed.data[KdrException.resourceIdKey] shouldBe "gd.fd.x1"
        trace.single { it.event == JobTraceEvent.taskRetry }.data[JOBT.attempt] shouldBe 1L
        trace.first().data[JOB.adopted] shouldBe false
        // Every entry carries the launch claim it was written under.
        trace.map { it.leaseId }.toSet().size shouldBe 1
    }

    "a launch may ask for the launch level, or for nothing" {
        JobScripts.set("jtrLevels", mapOf(CL.hub to listOf("a1", "a2"))) { _, _ -> JobTaskResult.done }
        launch("jtrLevels", "launchLevel", JobTraceLevel.launch)
        events("jtrLevels", "launchLevel") shouldContainExactly launchOnly

        launch("jtrLevels", "off", JobTraceLevel.off)
        events("jtrLevels", "off").shouldBeEmpty()
    }

    "a profile's deployment default applies when the launch does not say, and the launch overrides it" {
        JobScripts.set("jtrConfigured", mapOf(CL.hub to listOf("a1"))) { _, _ -> JobTaskResult.done }
        launch("jtrConfigured", "byConfig")
        events("jtrConfigured", "byConfig") shouldContainExactly launchOnly

        launch("jtrConfigured", "byLaunch", JobTraceLevel.task)
        events("jtrConfigured", "byLaunch").count { it == JobTraceEvent.taskDone.name } shouldBe 1
    }

    "a relaunch of finished work is traced as already complete" {
        JobScripts.set("jtrRelaunch", mapOf(CL.hub to listOf("a1"))) { _, _ -> JobTaskResult.done }
        launch("jtrRelaunch", "run1")
        launch("jtrRelaunch", "run1").outcome shouldBe JobLaunchOutcome.alreadyComplete
        events("jtrRelaunch", "run1").last() shouldBe JobTraceEvent.launchAlreadyComplete.name
    }

    "a job's own note is recorded against its task" {
        JobScripts.set("jtrNote", mapOf(CL.hub to listOf("a1"))) { run, key ->
            run.trace("Checked the form; it needs recomputing.", key, mapOf("reason" to "stale"))
            JobTaskResult.done
        }
        launch("jtrNote", "run1")
        val note = JobTraceRows.read(cxt, "jtrNote", JobLaunchKind.endpoint, "run1").single { it.event == JobTraceEvent.jobNote }
        note.taskKey shouldBe "a1"
        note.data["reason"] shouldBe "stale"
    }

    "a flush inside the caller's transaction is written on a connection of its own, and survives a rollback" {
        val launch = JobLaunch("jtrRollback", JobLaunchKind.endpoint, "run1")
        val tracer = JobTracer(cxt, JobTraceLevel.task, launch, maxEntries = 100, keepLaunches = 20)
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        shouldThrow<KdrException> {
            SqlTopicTranProvider.executeTopicTran(
                sqlCxt, "traceRollbackTest", null,
                mapOf(JOB.jobType to "jtrRollback", JOB.launchKind to JobLaunchKind.endpoint.name, JOB.dryRun to false),
                tranTableName = JOB.jobStatus,
            ) {
                tracer.record(JobTraceEvent.jobNote, message = "Written mid-transaction.")
                tracer.flush()
                throw KdrException("Roll it back.")
            }
        }
        JobTraceRows.read(cxt, "jtrRollback", JobLaunchKind.endpoint, "run1").map { it.message } shouldContainExactly
            listOf("Written mid-transaction.")
    }

    "a trace that cannot be written is dropped without failing its caller" {
        val launch = JobLaunch("jtrBroken", JobLaunchKind.endpoint, "run1")
        val tracer = JobTracer(cxt, JobTraceLevel.task, launch, 100, 20) { _, _ -> throw KdrException("The trace table is gone.") }
        tracer.record(JobTraceEvent.jobNote, message = "Lost.")
        tracer.flush(final = true)
        JobTraceRows.read(cxt, "jtrBroken", JobLaunchKind.endpoint, "run1").shouldBeEmpty()
    }

    "a launch's trace stops recording tasks at its cap, and marks where" {
        JobScripts.set("jtrCapped", mapOf(CL.hub to (1..6).map { "t$it" })) { _, _ -> JobTaskResult.done }
        launch("jtrCapped", "run1")
        events("jtrCapped", "run1") shouldContainExactly listOf(
            JobTraceEvent.launchClaimed, JobTraceEvent.clientClaimed, JobTraceEvent.taskDone, JobTraceEvent.truncated,
            JobTraceEvent.clientEnded, JobTraceEvent.launchEnded,
        ).map { it.name }
    }

    "only the most recent launches' traces are kept" {
        JobScripts.set("jtrPruned", mapOf(CL.hub to listOf("a1"))) { _, _ -> JobTaskResult.done }
        for (i in 1..5) launch("jtrPruned", "run$i")
        (1..5).map { events("jtrPruned", "run$it").isNotEmpty() } shouldContainExactly listOf(false, false, true, true, true)
    }

    "the operator surface reads a launch's trace" {
        JobScripts.set("jtrRead", mapOf(CL.hub to listOf("a1"))) { _, _ -> JobTaskResult.done }
        launch("jtrRead", "run1")
        val opal = TestUser.createOperator(cxt, "trace-opal@example.com")
        val entries = opal.getItems(JOBEP.trace, mapOf(JOBF.jobType to "jtrRead", JOBF.name to "run1"))
        entries.map { it[JOBT.event].toOptStr() } shouldContainExactly
            listOf(JobTraceEvent.launchClaimed, JobTraceEvent.clientClaimed, JobTraceEvent.taskDone, JobTraceEvent.clientEnded, JobTraceEvent.launchEnded)
                .map { it.name }
    }
})

/** Registers the scripted job types [JobTraceTest] uses. */
private class TraceFixture : ComponentDefinition {
    override val providerName: String = "jobTraceFixture"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        val profile = JobProfile("jobTraceTest", retryBackoff = 0.seconds)
        for (type in listOf("jtrTraced", "jtrLevels", "jtrRelaunch", "jtrNote", "jtrRead")) collector.addJob(scripted(type, profile))
        collector.addJob(scripted("jtrConfigured", JobProfile(configuredProfile)))
        collector.addJob(scripted("jtrCapped", JobProfile("jobTraceCapped", traceMaxEntries = 3)))
        collector.addJob(scripted("jtrPruned", JobProfile("jobTracePruned", traceKeepLaunches = 3)))
    }

    private fun scripted(type: String, profile: JobProfile) = JobDef(
        type, "Scripted trace-test job $type.", profile,
        tasks = { _, client -> JobScripts.of(type).tasks[client].orEmpty() },
        runTask = { run, key -> JobScripts.of(type).body(run, key) },
        countTasks = { _, client -> JobScripts.of(type).tasks[client].orEmpty().size },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val configuredProfile = "jobTraceConfigured"
    }
}
