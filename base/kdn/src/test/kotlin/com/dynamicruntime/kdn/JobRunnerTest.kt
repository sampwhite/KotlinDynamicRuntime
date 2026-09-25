package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.JOBEP
import com.dynamicruntime.common.job.JOBF
import com.dynamicruntime.common.job.JobClaim
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobLaunch
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobLaunchResult
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunCxt
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobRunStatus
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.job.JobStatusRows
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * The batch-job runner (issue #869), driven through synchronous launches so nothing here waits: a synchronous
 * run finishes before `launch` returns, heartbeats included, and a test moves the (frozen) instance clock from
 * inside a task to make a heartbeat fall due, a lease lapse, or a run stall exactly when it wants.
 *
 * The jobs are [JobScripts]: each job type's tasks, per client, and what a task does, are set by the test that
 * uses it, so every case gets a job shaped to its question. The fixture component registers them all at boot.
 */
class JobRunnerTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("jobRunner", "jobRunnerTest", additionalComponents = listOf(JobFixture()))
    cxt.instanceConfig.clock.freeze()

    fun service() = JobService.get(cxt)

    fun sync(jobType: String, name: String, dryRun: Boolean = false): JobLaunchResult =
        service().launch(cxt, jobType, name, mode = JobRunMode.sync, clients = listOf(CL.hub, CL.public), dryRun = dryRun)

    /** A task body that does its work, recording the key -- the common case. */
    fun doing(ran: MutableList<String>): (JobRunCxt, String) -> JobTaskResult = { _, key ->
        synchronized(ran) { ran.add(key) }
        JobTaskResult.done
    }

    "a synchronous run counts every task's result and completes both its rows and the launch's" {
        val ran = mutableListOf<String>()
        val seenClients = ConcurrentHashMap<String, String>()
        JobScripts.set("jtCounts", mapOf(CL.hub to listOf("a1", "a2", "n1", "f1"), CL.public to listOf("b1"))) { run, key ->
            seenClients[key] = run.cxt.client
            when {
                key.startsWith("n") -> JobTaskResult.nothingToDo
                key.startsWith("f") -> throw KdrException.mkJob("It broke.", JobHandling.skipTask, "testBroke", key)
                else -> doing(ran)(run, key)
            }
        }
        val result = sync("jtCounts", "run1")
        result.outcome shouldBe JobLaunchOutcome.completed
        val counts = result.counts.shouldNotBeNull()
        counts.completed shouldBe 3L
        counts.skipped shouldBe 1L
        counts.failed shouldBe 1L
        counts.total shouldBe 5L
        ran shouldContainExactly listOf("a1", "a2", "b1")
        // Each client's tasks run in a context bound to that client.
        seenClients["a1"] shouldBe CL.hub
        seenClients["b1"] shouldBe CL.public

        JobStatusRows.readLaunch(cxt, "jtCounts", JobLaunchKind.endpoint).shouldNotBeNull().runStatus shouldBe JobRunStatus.complete
        val hub = JobStatusRows.readClient(cxt, "jtCounts", CL.hub).shouldNotBeNull()
        hub.runStatus shouldBe JobRunStatus.complete
        hub.counts.failed shouldBe 1L
        hub.counts.total shouldBe 4L

        // The same name again: the work is done, so nothing runs.
        sync("jtCounts", "run1").outcome shouldBe JobLaunchOutcome.alreadyComplete
        ran shouldContainExactly listOf("a1", "a2", "b1")
    }

    "a retryable failure is retried up to the profile's limit, then counts as failed" {
        val attempts = ConcurrentHashMap<String, AtomicInteger>()
        JobScripts.set("jtRetry", mapOf(CL.hub to listOf("twice", "always"))) { _, key ->
            val n = attempts.getOrPut(key) { AtomicInteger() }.incrementAndGet()
            if (key == "always" || n <= 2) throw KdrException.mkJob("Not yet.", JobHandling.retryTask, "testFlaky", key)
            JobTaskResult.done
        }
        val result = sync("jtRetry", "run1")
        result.outcome shouldBe JobLaunchOutcome.completed
        result.counts.shouldNotBeNull().completed shouldBe 1L
        result.counts.shouldNotBeNull().failed shouldBe 1L
        attempts.getValue("twice").get() shouldBe 3
        attempts.getValue("always").get() shouldBe 1 + JobFixture.retryLimit
    }

    "an aborting failure stops the run, and a relaunch by name finishes it" {
        val ran = mutableListOf<String>()
        var broken = true
        JobScripts.set("jtAbort", mapOf(CL.hub to listOf("a1", "x1", "a2"), CL.public to listOf("b1"))) { run, key ->
            if (key == "x1" && broken) throw KdrException.mkJob("Stop everything.", JobHandling.abortJob, "testFatal", key)
            doing(ran)(run, key)
        }
        val result = sync("jtAbort", "run1")
        result.outcome shouldBe JobLaunchOutcome.aborted
        result.reason.shouldNotBeNull() shouldContain "Stop everything."
        ran shouldContainExactly listOf("a1")
        JobStatusRows.readClient(cxt, "jtAbort", CL.hub).shouldNotBeNull().runStatus shouldBe JobRunStatus.aborted
        JobStatusRows.readClient(cxt, "jtAbort", CL.public) shouldBe null

        // Fixed, and launched again under the same name: the aborted work is adopted and carried to the end.
        broken = false
        val again = sync("jtAbort", "run1")
        again.outcome shouldBe JobLaunchOutcome.completed
        JobStatusRows.readClient(cxt, "jtAbort", CL.public).shouldNotBeNull().runStatus shouldBe JobRunStatus.complete
    }

    "a synchronous launch is refused before anything is claimed when it cannot be counted or is over the cap" {
        JobScripts.set("jtUncounted", mapOf(CL.hub to listOf("a1"))) { _, _ -> JobTaskResult.done }
        shouldThrow<KdrException> { sync("jtUncounted", "run1") }.code shouldBe EXC.badInput
        JobStatusRows.readLaunch(cxt, "jtUncounted", JobLaunchKind.endpoint) shouldBe null

        JobScripts.set("jtCapped", mapOf(CL.hub to listOf("a1", "a2"), CL.public to listOf("b1", "b2"))) { _, _ -> JobTaskResult.done }
        shouldThrow<KdrException> { sync("jtCapped", "run1") }.message.shouldNotBeNull() shouldContain "4 tasks"
        JobStatusRows.readLaunch(cxt, "jtCapped", JobLaunchKind.endpoint) shouldBe null
    }

    "a synchronous run admitted on a low count stops at its hard cap" {
        val ran = mutableListOf<String>()
        // The fixture's counter for this job always answers one, whatever the tasks.
        JobScripts.set("jtUndercounted", mapOf(CL.hub to (1..6).map { "t$it" }), doing(ran))
        val result = sync("jtUndercounted", "run1")
        result.outcome shouldBe JobLaunchOutcome.aborted
        result.reason.shouldNotBeNull() shouldContain "hard cap"
        ran.size shouldBe JobFixture.hardCap
    }

    "an abort requested on the row stops the run at its next heartbeat" {
        val ran = mutableListOf<String>()
        JobScripts.set("jtRequested", mapOf(CL.hub to listOf("a1", "a2", "a3"))) { run, key ->
            if (key == "a1") {
                // As another node's operator would: on the row only, which the holder learns of by heartbeat.
                JobStatusRows.requestAbort(cxt, "jtRequested", JobLaunchKind.endpoint) shouldBe true
                cxt.instanceConfig.clock.advanceBy(3.seconds)
            }
            doing(ran)(run, key)
        }
        val result = sync("jtRequested", "run1")
        result.outcome shouldBe JobLaunchOutcome.aborted
        result.reason shouldBe "Abort requested."
        ran shouldContainExactly listOf("a1")
    }

    "a run whose launch was taken over is fenced out, and leaves the taker's row alone" {
        val ran = mutableListOf<String>()
        var taker: JobClaim? = null
        JobScripts.set("jtFenced", mapOf(CL.hub to listOf("a1", "a2", "a3"))) { run, key ->
            if (key == "a1") {
                // The run stalls past its lease, and another launch of the same name adopts the launch row.
                cxt.instanceConfig.clock.advanceBy(31.seconds)
                taker = JobStatusRows.claimLaunch(cxt, JobLaunch("jtFenced", JobLaunchKind.endpoint, "run1"), 30.seconds)
            }
            doing(ran)(run, key)
        }
        val result = sync("jtFenced", "run1")
        result.outcome shouldBe JobLaunchOutcome.aborted
        result.reason.shouldNotBeNull() shouldContain "fenced out"
        ran shouldContainExactly listOf("a1")
        val takerLease = taker.shouldBeInstanceOf<JobClaim.Claimed>().lease
        val row = JobStatusRows.readLaunch(cxt, "jtFenced", JobLaunchKind.endpoint).shouldNotBeNull()
        row.leaseId shouldBe takerLease.leaseId
        row.runStatus shouldBe JobRunStatus.active
    }

    "a run that goes half its lease without finishing a task stops itself as too slow" {
        val ran = mutableListOf<String>()
        JobScripts.set("jtSlow", mapOf(CL.hub to listOf("a1", "a2", "a3"))) { run, key ->
            // Sixteen seconds against a thirty-second lease: short of lapsing, past half.
            if (key == "a2") cxt.instanceConfig.clock.advanceBy(16.seconds)
            doing(ran)(run, key)
        }
        val result = sync("jtSlow", "run1")
        result.outcome shouldBe JobLaunchOutcome.aborted
        result.reason.shouldNotBeNull() shouldStartWith "Too slow"
        ran shouldContainExactly listOf("a1", "a2")
    }

    "a dry run is told so, and keeps to its own rows" {
        val sawDryRun = mutableListOf<Boolean>()
        JobScripts.set("jtDry", mapOf(CL.hub to listOf("a1"))) { run, _ ->
            sawDryRun.add(run.dryRun)
            JobTaskResult.nothingToDo
        }
        sync("jtDry", "run1", dryRun = true).outcome shouldBe JobLaunchOutcome.completed
        sawDryRun shouldContainExactly listOf(true)
        JobStatusRows.readLaunch(cxt, "jtDry", JobLaunchKind.endpoint, dryRun = true).shouldNotBeNull().dryRun shouldBe true
        JobStatusRows.readLaunch(cxt, "jtDry", JobLaunchKind.endpoint) shouldBe null
    }

    "pooled runs spread a client's tasks across the node's pool, or across virtual threads" {
        val threads = ConcurrentHashMap<String, Thread>()
        val keys = (1..8).map { "p$it" }
        JobScripts.set("jtPooled", mapOf(CL.hub to keys)) { _, key ->
            threads[key] = Thread.currentThread()
            JobTaskResult.done
        }
        val pooled = service().launch(cxt, "jtPooled", "run1", mode = JobRunMode.pooledOnCaller, clients = listOf(CL.hub))
        pooled.outcome shouldBe JobLaunchOutcome.completed
        pooled.counts.shouldNotBeNull().completed shouldBe 8L
        threads.keys shouldBe keys.toSet()
        threads.values.forEach { it.name shouldStartWith "kdr-job-pool-" }

        threads.clear()
        JobScripts.set("jtVirtual", mapOf(CL.hub to keys)) { _, key ->
            threads[key] = Thread.currentThread()
            JobTaskResult.done
        }
        val virtual = service().launch(cxt, "jtVirtual", "run1", mode = JobRunMode.pooledOnCaller, clients = listOf(CL.hub))
        virtual.outcome shouldBe JobLaunchOutcome.completed
        threads.values.forEach { it.isVirtual shouldBe true }
    }

    "the operator surface launches, reports and aborts, and is closed to anyone else" {
        JobScripts.set("jtEndpoint", mapOf(CL.hub to listOf("a1", "a2"))) { _, _ -> JobTaskResult.done }
        val opal = TestUser.createOperator(cxt, "jobs-opal@example.com")

        val launched = opal.postData(
            JOBEP.launch,
            mapOf(JOBF.jobType to "jtEndpoint", JOBF.name to "fromOps", JOBF.mode to JobRunMode.sync.name, JOBF.clients to listOf(CL.hub)),
        )
        launched[JOBF.outcome] shouldBe JobLaunchOutcome.completed.name
        launched["completed"] shouldBe 2L
        launched.getValue(JOBF.status).toString() shouldContain "complete"

        val status = opal.getItems(JOBEP.status, mapOf(JOBF.jobType to "jtEndpoint")).single()
        status[JOBF.launches].toJsonListOfMaps().single()["launchName"] shouldBe "fromOps"
        status[JOBF.clientRows].toJsonListOfMaps().map { it["client"].toOptStr() } shouldContainExactly listOf(CL.hub)

        opal.postData(JOBEP.abort, mapOf(JOBF.jobType to "jtEndpoint"))[JOBF.requested] shouldBe false
        opal.expectError(EXC.notFound, JOBEP.abort, mapOf(JOBF.jobType to "noSuchJob"))

        val plain = TestUser.create(cxt, "jobs-plain@example.com")
        plain.expectError(EXC.notAuthorized, JOBEP.status)
    }
})

/** Per job type: its tasks for each client, and what a task does. Set by the test that uses the job. */
object JobScripts {
    class Script(val tasks: Map<String, List<String>>, val body: (JobRunCxt, String) -> JobTaskResult)

    private val scripts = ConcurrentHashMap<String, Script>()

    fun set(jobType: String, tasks: Map<String, List<String>>, body: (JobRunCxt, String) -> JobTaskResult) {
        scripts[jobType] = Script(tasks, body)
    }

    fun of(jobType: String): Script = scripts[jobType] ?: throw KdrException("No script for job '$jobType'.")
}

/** Registers the scripted job types [JobRunnerTest] uses, with profiles sized for its questions. */
private class JobFixture : ComponentDefinition {
    override val providerName: String = "jobRunnerFixture"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        val profile = JobProfile("jobTest", platformThreads = 4, retryBackoff = 0.seconds, retryLimit = retryLimit)
        for (type in listOf("jtCounts", "jtRetry", "jtAbort", "jtRequested", "jtFenced", "jtSlow", "jtDry", "jtPooled", "jtEndpoint")) {
            collector.addJob(scripted(type, profile))
        }
        collector.addJob(scripted("jtUncounted", profile, counted = false))
        collector.addJob(scripted("jtCapped", JobProfile("jobTestCapped", syncTaskCap = 3)))
        collector.addJob(scripted("jtUndercounted", JobProfile("jobTestHardCap", syncHardCap = hardCap), countAs = 1))
        collector.addJob(scripted("jtVirtual", JobProfile("jobTestVirtual", virtualThreads = 3)))
    }

    private fun scripted(type: String, profile: JobProfile, counted: Boolean = true, countAs: Int? = null) = JobDef(
        type, "Scripted test job $type.", profile,
        tasks = { _, client -> JobScripts.of(type).tasks[client].orEmpty() },
        runTask = { run, key -> JobScripts.of(type).body(run, key) },
        countTasks = if (!counted) null else { _, client -> countAs ?: JobScripts.of(type).tasks[client].orEmpty().size },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val retryLimit = 3
        const val hardCap = 4
    }
}
