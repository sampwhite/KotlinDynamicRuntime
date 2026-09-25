package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.sql.DbEnv
import com.dynamicruntime.common.sql.SqlTopicService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The batch-job status rows (issue #868): claiming, adopting, resetting, and fencing, driven by hand.
 *
 * Two simulated **nodes**, each its own instance -- its own name and its own clock -- sharing one in-memory
 * database, which is all a second node is to these rows. Both clocks are frozen at one moment and moved together
 * by `advance`, so a lease expires exactly when a test says it does and nothing here waits. Each case uses its
 * own job type, since the database is shared across the spec.
 */
class JobStatusRowsTest : StringSpec({
    val timeout = 30.seconds

    fun node(name: String): KdrCxt {
        val cxt = KdrCxt.mkSimpleCxt(name)
        cxt.instanceConfig.put(DbEnv.dbName.name, "jobStatusRowsTest")
        cxt.instanceConfig.put(KdrSchemaStore.key, KdrSchemaStore(tables = jobTables(cxt).associateBy { it.tableName }))
        val service = SqlTopicService()
        cxt.instanceConfig.put(SqlTopicService.serviceName, service)
        service.checkInit(cxt)
        // Frozen first, then set: the other order lets the wall clock tick in between, leaving the nodes apart.
        cxt.instanceConfig.clock.freeze()
        cxt.instanceConfig.clock.setAbsolute(Instant.parse("2026-09-25T10:00:00Z"))
        return cxt
    }

    val a = node("jobNodeA")
    val b = node("jobNodeB")

    fun advance(by: Duration) {
        a.instanceConfig.clock.advanceBy(by)
        b.instanceConfig.clock.advanceBy(by)
    }

    fun launch(jobType: String, name: String, kind: JobLaunchKind = JobLaunchKind.endpoint, dryRun: Boolean = false, redoWindow: Duration? = null) =
        JobLaunch(jobType, kind, name, params = mapOf("note" to name), dryRun = dryRun, redoWindow = redoWindow)

    fun JobClaim.lease(): JobLease = shouldBeInstanceOf<JobClaim.Claimed>().lease

    "a claim takes a fresh row, and a second claim is locked out while the lease is live" {
        val lease = JobStatusRows.claimLaunch(a, launch("jtFresh", "run1"), timeout).lease()
        lease.adopted shouldBe false
        lease.generationId shouldBe 1L

        // Neither another launch nor a relaunch of the same name gets in while the lease is live.
        val other = JobStatusRows.claimLaunch(b, launch("jtFresh", "run2"), timeout).shouldBeInstanceOf<JobClaim.LockedOut>()
        other.holder shouldBe a.instanceConfig.instanceName
        other.launchName shouldBe "run1"
        JobStatusRows.claimLaunch(b, launch("jtFresh", "run1"), timeout).shouldBeInstanceOf<JobClaim.LockedOut>()

        // A scheduled launch of the same type has its own launch row, so it runs beside the endpoint one.
        JobStatusRows.claimLaunch(b, launch("jtFresh", "slot1", JobLaunchKind.scheduled), timeout).lease()

        val row = JobStatusRows.readLaunch(a, "jtFresh", JobLaunchKind.endpoint).shouldNotBeNull()
        row.runStatus shouldBe JobRunStatus.active
        row.leaseId shouldBe lease.leaseId
        row.params shouldBe mapOf("note" to "run1")
    }

    "a lapsed lease is adopted by a relaunch of the same name and reset by any other" {
        val first = JobStatusRows.claimLaunch(a, launch("jtLapse", "run1"), timeout).lease()
        JobStatusRows.heartbeat(a, first, JobCounts(completed = 3)).fenced shouldBe false
        advance(timeout + 1.seconds)

        // Same name: the work continues -- generation, counters and launch time all carry over.
        val adopted = JobStatusRows.claimLaunch(b, launch("jtLapse", "run1"), timeout).lease()
        adopted.adopted shouldBe true
        adopted.generationId shouldBe first.generationId
        adopted.counts.completed shouldBe 3L
        adopted.launchTime shouldBe first.launchTime
        val history = JobStatusRows.readLaunch(b, "jtLapse", JobLaunchKind.endpoint).shouldNotBeNull().history
        history shouldHaveSize 1
        history[0][JOB.end] shouldBe JobAttemptEnd.lapsed.name
        history[0][JOB.holder] shouldBe a.instanceConfig.instanceName

        // Another name, once that lease lapses too: a new body of work.
        advance(timeout + 1.seconds)
        val reset = JobStatusRows.claimLaunch(a, launch("jtLapse", "run2"), timeout).lease()
        reset.adopted shouldBe false
        reset.generationId shouldBe first.generationId + 1
        reset.counts.completed shouldBe 0L
        reset.launchTime shouldBe a.instanceNow()
    }

    "a stalled holder is fenced out after another node adopts its row" {
        val stalled = JobStatusRows.claimLaunch(a, launch("jtFence", "run1"), timeout).lease()
        advance(timeout + 1.seconds)
        val taker = JobStatusRows.claimLaunch(b, launch("jtFence", "run1"), timeout).lease()
        // The takeover adopted, so the generation is unchanged: it is the lease id that fences.
        taker.generationId shouldBe stalled.generationId

        JobStatusRows.heartbeat(a, stalled, JobCounts(completed = 99)).fenced shouldBe true
        JobStatusRows.finish(a, stalled, JobAttemptEnd.complete, JobCounts(completed = 99)) shouldBe false
        JobStatusRows.release(a, stalled, JobCounts(completed = 99)) shouldBe false

        // Nothing the stalled node tried landed; the row is still the taker's.
        val row = JobStatusRows.readLaunch(b, "jtFence", JobLaunchKind.endpoint).shouldNotBeNull()
        row.leaseId shouldBe taker.leaseId
        row.runStatus shouldBe JobRunStatus.active
        row.counts.completed shouldBe 0L
        JobStatusRows.heartbeat(b, taker, JobCounts(completed = 1)).fenced shouldBe false
    }

    "a scheduled and an endpoint launch of one type keep off each other's client" {
        val scheduled = JobStatusRows.claimLaunch(a, launch("jtClients", "slot1", JobLaunchKind.scheduled), timeout).lease()
        val endpoint = JobStatusRows.claimLaunch(b, launch("jtClients", "fix1"), timeout).lease()

        JobStatusRows.claimClient(a, scheduled, "acme", timeout).lease().client shouldBe "acme"
        JobStatusRows.claimClient(b, endpoint, "acme", timeout).shouldBeInstanceOf<JobClaim.LockedOut>().launchName shouldBe "slot1"
        JobStatusRows.claimClient(b, endpoint, "globex", timeout).lease().client shouldBe "globex"
    }

    "a per-client row is adopted within the redo window, measured from the launch time rather than now" {
        val nightly = JobStatusRows.claimLaunch(a, launch("jtWindow", "slot1", JobLaunchKind.scheduled), timeout).lease()
        val done = JobStatusRows.claimClient(a, nightly, "acme", timeout).lease()
        JobStatusRows.finish(a, done, JobAttemptEnd.complete, JobCounts(completed = 7)) shouldBe true

        // Launched 50 minutes after the nightly work began, with a one-hour window...
        advance(50.minutes)
        val fix = JobStatusRows.claimLaunch(b, launch("jtWindow", "fix1", redoWindow = 1.hours), timeout).lease()
        // ... and reaching the client 30 minutes later: 80 minutes after that work began, but 50 before the launch.
        advance(30.minutes)
        JobStatusRows.claimClient(b, fix, "acme", timeout).shouldBeInstanceOf<JobClaim.AlreadyComplete>().counts.completed shouldBe 7L

        // With no window, another launch's name resets the row: a new generation, counted from zero.
        JobStatusRows.finish(b, fix, JobAttemptEnd.complete, JobCounts.zero) shouldBe true
        val plain = JobStatusRows.claimLaunch(b, launch("jtWindow", "fix2"), timeout).lease()
        val redone = JobStatusRows.claimClient(b, plain, "acme", timeout).lease()
        redone.adopted shouldBe false
        redone.generationId shouldBe done.generationId + 1
    }

    "a per-client row whose work began before the redo window is reset" {
        val nightly = JobStatusRows.claimLaunch(a, launch("jtOld", "slot1", JobLaunchKind.scheduled), timeout).lease()
        val done = JobStatusRows.claimClient(a, nightly, "acme", timeout).lease()
        JobStatusRows.finish(a, done, JobAttemptEnd.complete, JobCounts(completed = 7)) shouldBe true

        advance(5.hours)
        val fix = JobStatusRows.claimLaunch(b, launch("jtOld", "fix1", redoWindow = 4.hours), timeout).lease()
        val redone = JobStatusRows.claimClient(b, fix, "acme", timeout).lease()
        redone.adopted shouldBe false
        redone.generationId shouldBe done.generationId + 1
        JobStatusRows.readClient(b, "jtOld", "acme").shouldNotBeNull().startedAt shouldBe b.instanceNow()
    }

    "a relaunch by name after an abort continues its counts and launch time, and finds complete work done" {
        val first = JobStatusRows.claimLaunch(a, launch("jtRelaunch", "run1"), timeout).lease()
        JobStatusRows.finish(a, first, JobAttemptEnd.aborted, JobCounts(completed = 5, skipped = 1), "Asked to.") shouldBe true

        advance(10.minutes)
        val again = JobStatusRows.claimLaunch(b, launch("jtRelaunch", "run1"), timeout).lease()
        again.adopted shouldBe true
        again.counts.completed shouldBe 5L
        again.counts.skipped shouldBe 1L
        again.launchTime shouldBe first.launchTime
        JobStatusRows.finish(b, again, JobAttemptEnd.complete, JobCounts(completed = 9, skipped = 1)) shouldBe true

        JobStatusRows.claimLaunch(a, launch("jtRelaunch", "run1"), timeout).shouldBeInstanceOf<JobClaim.AlreadyComplete>()
            .counts.completed shouldBe 9L
        val history = JobStatusRows.readLaunch(a, "jtRelaunch", JobLaunchKind.endpoint).shouldNotBeNull().history
        history.map { it[JOB.end] } shouldBe listOf(JobAttemptEnd.aborted.name, JobAttemptEnd.complete.name)
        history[0][JOB.reason] shouldBe "Asked to."
    }

    "a dry run keeps rows of its own, so it neither disturbs a real run nor is adopted by one" {
        val real = JobStatusRows.claimLaunch(a, launch("jtDry", "run1"), timeout).lease()
        val realClient = JobStatusRows.claimClient(a, real, "acme", timeout).lease()
        JobStatusRows.heartbeat(a, realClient, JobCounts(completed = 3)).fenced shouldBe false

        // A dry run of the same type and client, while the real one is live: not locked out, and not touching it.
        val dry = JobStatusRows.claimLaunch(b, launch("jtDry", "run1", dryRun = true), timeout).lease()
        val dryClient = JobStatusRows.claimClient(b, dry, "acme", timeout).lease()
        JobStatusRows.finish(b, dryClient, JobAttemptEnd.complete, JobCounts(completed = 8)) shouldBe true
        JobStatusRows.finish(b, dry, JobAttemptEnd.complete, JobCounts(completed = 8)) shouldBe true
        JobStatusRows.readClient(a, "jtDry", "acme", dryRun = true).shouldNotBeNull().dryRun shouldBe true

        val realRow = JobStatusRows.readClient(a, "jtDry", "acme").shouldNotBeNull()
        realRow.dryRun shouldBe false
        realRow.leaseId shouldBe realClient.leaseId
        realRow.generationId shouldBe realClient.generationId
        realRow.counts.completed shouldBe 3L

        // The dry run's complete work under the same name is not the real run's: a real relaunch does it.
        JobStatusRows.finish(a, realClient, JobAttemptEnd.aborted, JobCounts(completed = 3)) shouldBe true
        JobStatusRows.finish(a, real, JobAttemptEnd.aborted, JobCounts(completed = 3)) shouldBe true
        val again = JobStatusRows.claimLaunch(a, launch("jtDry", "run1"), timeout).lease()
        JobStatusRows.claimClient(a, again, "acme", timeout).lease().counts.completed shouldBe 3L
    }

    "a released row is claimable at once, without waiting out the lease" {
        val first = JobStatusRows.claimLaunch(a, launch("jtRelease", "run1"), timeout).lease()
        JobStatusRows.release(a, first, JobCounts(completed = 2)) shouldBe true

        val next = JobStatusRows.claimLaunch(b, launch("jtRelease", "run1"), timeout).lease()
        next.adopted shouldBe true
        next.counts.completed shouldBe 2L
        JobStatusRows.readLaunch(b, "jtRelease", JobLaunchKind.endpoint).shouldNotBeNull()
            .history.single()[JOB.end] shouldBe JobAttemptEnd.released.name
    }

    "an abort request reaches the holder's heartbeat, does not renew its lease, and is cleared by the next claim" {
        val lease = JobStatusRows.claimLaunch(a, launch("jtAbort", "run1"), timeout).lease()
        JobStatusRows.requestAbort(b, "jtAbort", JobLaunchKind.endpoint) shouldBe true
        JobStatusRows.heartbeat(a, lease, JobCounts.zero).abortRequested shouldBe true

        // Asking an unresponsive launch to abort must not keep it alive: once its lease lapses, it is claimable.
        advance(timeout + 1.seconds)
        JobStatusRows.requestAbort(b, "jtAbort", JobLaunchKind.endpoint) shouldBe true
        JobStatusRows.claimLaunch(b, launch("jtAbort", "run2"), timeout).lease()
        JobStatusRows.readLaunch(b, "jtAbort", JobLaunchKind.endpoint).shouldNotBeNull().abortRequested shouldBe false

        // A job that never ran has nothing to ask, and does not gain an empty row for being asked.
        JobStatusRows.requestAbort(b, "jtNothing", JobLaunchKind.endpoint) shouldBe false
        JobStatusRows.readLaunch(b, "jtNothing", JobLaunchKind.endpoint) shouldBe null
    }

    "a heartbeat records counts and the launch row's aggregate" {
        val lease = JobStatusRows.claimLaunch(a, launch("jtBeat", "run1"), timeout).lease()
        advance(10.seconds)
        val beat = JobStatusRows.heartbeat(a, lease, JobCounts(completed = 4, failed = 1, total = 10), mapOf("clientsDone" to 2))
        beat.fenced shouldBe false
        beat.abortRequested shouldBe false

        val row = JobStatusRows.readLaunch(a, "jtBeat", JobLaunchKind.endpoint).shouldNotBeNull()
        row.heartbeatAt shouldBe a.instanceNow()
        row.counts.total shouldBe 10L
        row.counts.failed shouldBe 1L
        row.aggregate["clientsDone"] shouldBe 2L
    }

    "the history keeps the newest attempts only" {
        for (i in 0..6) {
            val lease = JobStatusRows.claimLaunch(a, launch("jtHistory", "run$i"), timeout).lease()
            JobStatusRows.finish(a, lease, JobAttemptEnd.aborted, JobCounts.zero) shouldBe true
        }
        val history = JobStatusRows.readLaunch(a, "jtHistory", JobLaunchKind.endpoint).shouldNotBeNull().history
        history shouldHaveSize JOB.historyLimit
        history.map { it[JOB.launchName] } shouldBe listOf("run2", "run3", "run4", "run5", "run6")
    }
})
