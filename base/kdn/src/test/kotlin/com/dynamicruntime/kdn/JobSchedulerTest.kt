package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.BCHKJ
import com.dynamicruntime.common.job.JPF
import com.dynamicruntime.common.job.JSCH
import com.dynamicruntime.common.job.JobClaim
import com.dynamicruntime.common.job.JobConfig
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobLaunch
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobRunStatus
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.job.JobStatusRows
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.job.JobTick
import com.dynamicruntime.common.job.JobTickAction
import com.dynamicruntime.common.job.JobTraceLevel
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The scheduler (issue #870) and the layering of job configuration it arrived with.
 *
 * Each case boots its own node, registering exactly the scheduled jobs it is about, since a tick considers every
 * scheduled job there is. The clock is frozen and set by hand, and ticks are called directly with runs finishing
 * inside them ([JobRunMode.pooledOnCaller]), so nothing waits on the timer.
 */
class JobSchedulerTest : StringSpec({
    val boots = AtomicInteger()

    /** A node with [defs] registered, its clock frozen at [at]. */
    fun node(vararg defs: JobDef, at: String = "2026-09-25T01:59:00Z", overlay: Map<String, Any?> = emptyMap()): KdrCxt {
        val n = boots.incrementAndGet()
        val cxt = Startup.mkTestBootCxt("jobSched$n", "jobSchedulerTest$n", overlay, listOf(SchedFixture(defs.toList())))
        cxt.instanceConfig.clock.freeze()
        cxt.instanceConfig.clock.setAbsolute(Instant.parse(at))
        return cxt
    }

    fun tick(cxt: KdrCxt): List<JobTick> = JobService.get(cxt).scheduledTick(cxt, JobRunMode.pooledOnCaller)

    fun scheduled(type: String, schedule: JobSchedule, profile: JobProfile = JobProfile("schedTest")) = JobDef(
        type, "Scheduled test job $type.", profile,
        tasks = { _, client -> JobScripts.of(type).tasks[client].orEmpty() },
        runTask = { run, key -> JobScripts.of(type).body(run, key) },
        schedule = schedule,
    )

    "a daily job runs once its slot opens, under the slot's name, and not again once complete" {
        JobScripts.set("jsDaily", mapOf(CL.hub to listOf("a1", "a2"))) { _, _ -> JobTaskResult.done }
        val cxt = node(scheduled("jsDaily", JobSchedule.daily("02:00", window = 1.hours)))

        tick(cxt).single().action shouldBe JobTickAction.notInWindow

        cxt.instanceConfig.clock.advanceBy(6.minutes)
        val ran = tick(cxt).single()
        ran.action shouldBe JobTickAction.launched
        ran.slotName shouldBe "slot-2026-09-25T02:00:00.000Z"
        ran.result.shouldNotBeNull().outcome shouldBe JobLaunchOutcome.completed
        val row = JobStatusRows.readLaunch(cxt, "jsDaily", JobLaunchKind.scheduled).shouldNotBeNull()
        row.launchName shouldBe ran.slotName
        row.runStatus shouldBe JobRunStatus.complete

        cxt.instanceConfig.clock.advanceBy(1.minutes)
        tick(cxt).single().action shouldBe JobTickAction.complete
    }

    "a slot left unfinished is picked up by a later tick, which adopts its work" {
        var broken = true
        JobScripts.set("jsResume", mapOf(CL.hub to listOf("a1", "x1"))) { _, key ->
            if (key == "x1" && broken) throw KdrException.mkJob("Not today.", JobHandling.abortJob, "testFatal", key)
            JobTaskResult.done
        }
        val cxt = node(scheduled("jsResume", JobSchedule.daily("02:00", window = 1.hours)), at = "2026-09-25T02:01:00Z")
        tick(cxt).single().result.shouldNotBeNull().outcome shouldBe JobLaunchOutcome.aborted
        val first = JobStatusRows.readLaunch(cxt, "jsResume", JobLaunchKind.scheduled).shouldNotBeNull()

        broken = false
        cxt.instanceConfig.clock.advanceBy(1.minutes)
        tick(cxt).single().result.shouldNotBeNull().outcome shouldBe JobLaunchOutcome.completed
        val second = JobStatusRows.readLaunch(cxt, "jsResume", JobLaunchKind.scheduled).shouldNotBeNull()
        second.generationId shouldBe first.generationId
        second.launchName shouldBe first.launchName
    }

    "a slot another launch holds live is left alone" {
        JobScripts.set("jsHeld", mapOf(CL.hub to listOf("a1"))) { _, _ -> JobTaskResult.done }
        val schedule = JobSchedule.daily("02:00", window = 1.hours)
        val cxt = node(scheduled("jsHeld", schedule), at = "2026-09-25T02:01:00Z")
        val slotName = schedule.launchName(schedule.slotAt(cxt.instanceNow()))
        // Another node's claim on the same slot, whose lease is live.
        JobStatusRows.claimLaunch(cxt, JobLaunch("jsHeld", JobLaunchKind.scheduled, slotName), 30.seconds)
            .shouldBeInstanceOf<JobClaim.Claimed>()
        tick(cxt).single().action shouldBe JobTickAction.heldLive
    }

    "a run stops when its slot's window closes, and the slot is then final" {
        JobScripts.set("jsWindow", mapOf(CL.hub to listOf("a1", "a2", "a3"))) { run, key ->
            // The work runs past the end of the window.
            if (key == "a1") run.cxt.instanceConfig.clock.advanceBy(10.minutes)
            JobTaskResult.done
        }
        val cxt = node(scheduled("jsWindow", JobSchedule.daily("02:00", window = 5.minutes)), at = "2026-09-25T02:01:00Z")
        val result = tick(cxt).single().result.shouldNotBeNull()
        result.outcome shouldBe JobLaunchOutcome.aborted
        result.reason.shouldNotBeNull() shouldContain "window closed"
        tick(cxt).single().action shouldBe JobTickAction.notInWindow
    }

    "a node runs one scheduled job at a time, and the next follows on a later tick" {
        val nested = mutableListOf<JobTick>()
        lateinit var cxt: KdrCxt
        JobScripts.set("jsFirst", mapOf(CL.hub to listOf("a1"))) { _, _ ->
            // A tick arriving while this scheduled run is in progress on this node looks no further.
            nested.addAll(JobService.get(cxt).scheduledTick(cxt, JobRunMode.pooledOnCaller))
            JobTaskResult.done
        }
        JobScripts.set("jsSecond", mapOf(CL.hub to listOf("a1"))) { _, _ -> JobTaskResult.done }
        val every = JobSchedule.every(10.minutes)
        cxt = node(scheduled("jsFirst", every), scheduled("jsSecond", every), at = "2026-09-25T02:01:00Z")

        tick(cxt).map { it.jobType to it.action } shouldContainExactly listOf("jsFirst" to JobTickAction.launched)
        nested.map { it.action } shouldContainExactly listOf(JobTickAction.nodeBusy)
        tick(cxt).map { it.jobType to it.action } shouldContainExactly
            listOf("jsFirst" to JobTickAction.complete, "jsSecond" to JobTickAction.launched)
    }

    "an interval job's next slot is new work" {
        JobScripts.set("jsEvery", mapOf(CL.hub to listOf("a1"))) { _, _ -> JobTaskResult.done }
        val cxt = node(scheduled("jsEvery", JobSchedule.every(10.minutes)), at = "2026-09-25T02:01:00Z")
        tick(cxt).single().result.shouldNotBeNull().outcome shouldBe JobLaunchOutcome.completed
        val first = JobStatusRows.readLaunch(cxt, "jsEvery", JobLaunchKind.scheduled).shouldNotBeNull()

        cxt.instanceConfig.clock.advanceBy(10.minutes)
        val next = tick(cxt).single()
        next.slotName shouldBe "slot-2026-09-25T02:10:00.000Z"
        next.result.shouldNotBeNull().outcome shouldBe JobLaunchOutcome.completed
        JobStatusRows.readLaunch(cxt, "jsEvery", JobLaunchKind.scheduled).shouldNotBeNull().generationId shouldBe
            first.generationId + 1
    }

    "a component's job settings fill only what the deployment left unset" {
        JobScripts.set("jsLayered", emptyMap()) { _, _ -> JobTaskResult.done }
        // The deployment (the boot overlay, as a customConfig object's settings arrive) chose the trace level.
        val cxt = node(
            scheduled("jsLayered", JobSchedule.every(10.minutes), JobProfile(SchedFixture.layeredProfile)),
            overlay = mapOf(JPF.jobProfiles to mapOf(SchedFixture.layeredProfile to mapOf(JPF.trace to JobTraceLevel.launch.name))),
        )
        val profile = JobProfile(SchedFixture.layeredProfile).resolve(cxt)
        profile.leaseTimeout shouldBe 5.seconds
        profile.trace shouldBe JobTraceLevel.launch
        JobConfig.tickInterval(cxt) shouldBe 1234.milliseconds
    }

    "a sub-minute schedule boots only on a test instance, and no schedule may be finer than the tick" {
        JobScripts.set("jsFast", emptyMap()) { _, _ -> JobTaskResult.done }
        val fast = scheduled("jsFast", JobSchedule.every(20.seconds, window = 10.seconds))

        // A test instance may: the rule itself allows down to a second.
        node(fast).instanceConfig.isTestInstance shouldBe true
        // A real node may not.
        shouldThrow<KdrException> { node(fast, overlay = mapOf(ACFG.isTestInstance to false)) }
            .message.shouldNotBeNull() shouldContain "only for test instances"
        // Where the scheduler runs, slots and windows it could miss between ticks are refused on any node.
        val ticking = mapOf(JSCH.jobScheduler to mapOf(JSCH.enabled to true, JSCH.tickMs to 30_000))
        val refused = shouldThrow<KdrException> { node(fast, overlay = ticking) }.message.shouldNotBeNull()
        refused shouldContain "closer than the scheduler's tick"
        refused shouldContain "shorter than the scheduler's tick"
    }

    "a job setting no profile has, or a profile no job uses, refuses the boot -- or warns when told to" {
        JobScripts.set("jsChecked", emptyMap()) { _, _ -> JobTaskResult.done }
        fun checked(overlay: Map<String, Any?>) = node(scheduled("jsChecked", JobSchedule.every(10.minutes)), overlay = overlay)
        val badKey = mapOf(JPF.jobProfiles to mapOf("schedTest" to mapOf("leaseTimeout" to 5000)))
        shouldThrow<KdrException> { checked(badKey) }.message.shouldNotBeNull() shouldContain "not a profile setting"
        val badProfile = mapOf(JPF.jobProfiles to mapOf("noSuchProfile" to mapOf(JPF.retryLimit to 1)))
        shouldThrow<KdrException> { checked(badProfile) }.message.shouldNotBeNull() shouldContain "no registered job"
        val badScheduler = mapOf(JSCH.jobScheduler to mapOf("tick" to 5))
        shouldThrow<KdrException> { checked(badScheduler) }.message.shouldNotBeNull() shouldContain "does not exist"

        val warned = checked(badKey + (JobConfig.checkEnvVar.name to "warn"))
        BootCheckRegistry.get(warned).results().single { it.name == BCHKJ.jobConfig }.findings.single() shouldContain
            "not a profile setting"
    }
})

/** Registers the jobs one [JobSchedulerTest] case is about, and contributes job settings as a component would. */
private class SchedFixture(private val defs: List<JobDef>) : ComponentDefinition {
    override val providerName: String = "jobSchedulerFixture"

    override fun applyInstanceConfig(cxt: KdrCxt) {
        // Only where a job uses the profile: the job-config check refuses settings for a profile nobody uses.
        if (defs.none { it.profile.name == layeredProfile }) return
        JobConfig.contributeProfile(cxt, layeredProfile) {
            leaseTimeoutMs = 5_000
            // Overruled by the deployment's own choice in the layering case.
            trace = JobTraceLevel.task
        }
        JobConfig.contributeScheduler(cxt) { tickMs = 1234 }
    }

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        defs.forEach { collector.addJob(it) }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val layeredProfile = "schedLayered"
    }
}
