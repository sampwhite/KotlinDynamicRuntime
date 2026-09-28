package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraStateContext
import com.dynamicruntime.common.gedra.GedraStateDeriver
import com.dynamicruntime.common.gedra.SRJ
import com.dynamicruntime.common.job.JOB
import com.dynamicruntime.common.job.JOBX
import com.dynamicruntime.common.job.JobExceptionRows
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobLaunchResult
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.job.JobTraceEvent
import com.dynamicruntime.common.job.JobTraceRows
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The derived-state recompute job (issue #793), run against the sample's acme client, whose derivers compute a
 * year-presence projection and survey state for every form.
 *
 * A form's state is made stale the way time and configuration make it stale in life -- its derived entries no
 * longer what a recompute would give -- by writing its state directly. Runs are driven from the calling thread
 * across the job pool, so each finishes before the launch returns; outcomes are read per form from the trace, since
 * the sample's own forms share the client.
 */
class StateRecomputeJobTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "stateRecompute", "stateRecomputeJobTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent(), BrokenStateFixture()),
    )
    val scope = ReadScope.ofClient(SC.acme)

    fun service() = GedraDataService.get(cxt)

    fun acme(): KdrCxt = cxt.mkSubContext("srj", SC.acme).also { it.userId = 90801L }

    fun expenseForm(): GedraId = service().createGedra(
        acme(), GedraDataType.formDoc, listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2024))),
    ).gedraId

    fun traitIds(gid: GedraId): List<String?> = service().readState(acme(), gid, scope).map { it[GE.traitId].toOptStr() }

    /** Leaves [gid] with only an asserted entry: its derived state gone, as stale as state gets. */
    fun makeStale(gid: GedraId) {
        service().writeState(
            acme(), gid,
            listOf(mapOf(GE.traitId to ST.externalId, GE.data to mapOf(ST.externalSource to "salesforce", ST.externalRef to "SF-9"))),
        )
    }

    fun launch(name: String, dryRun: Boolean = false): JobLaunchResult = JobService.get(cxt).launch(
        cxt, SRJ.jobType, name, mode = JobRunMode.pooledOnCaller, clients = listOf(SC.acme), dryRun = dryRun,
    )

    /** What each task of [name] did to [gid], by trace event. */
    fun eventsFor(name: String, gid: GedraId, dryRun: Boolean = false): List<String?> =
        JobTraceRows.read(cxt, SRJ.jobType, JobLaunchKind.endpoint, name, dryRun)
            .filter { it.taskKey == gid.fullId }.map { it.event?.name }

    "a form whose derived state went stale is recomputed, and a current one is left alone" {
        val stale = expenseForm()
        val current = expenseForm()
        makeStale(stale)
        traitIds(stale) shouldNotContain ST.traitPresenceByYear
        val currentBefore = service().readState(acme(), current, scope)

        launch("run1").outcome shouldBe JobLaunchOutcome.completed

        eventsFor("run1", stale) shouldBe listOf(JobTraceEvent.taskDone.name)
        traitIds(stale) shouldContain ST.traitPresenceByYear // derived: back
        traitIds(stale) shouldContain ST.externalId          // asserted: kept
        eventsFor("run1", current) shouldBe listOf(JobTraceEvent.taskNothingToDo.name)
        // Not rewritten at all: the stored entries, stamps included, are as they were.
        service().readState(acme(), current, scope) shouldBe currentBefore
    }

    "a dry run counts a stale form and changes nothing" {
        val stale = expenseForm()
        makeStale(stale)
        launch("dry1", dryRun = true).outcome shouldBe JobLaunchOutcome.completed
        eventsFor("dry1", stale, dryRun = true) shouldBe listOf(JobTraceEvent.jobNote.name, JobTraceEvent.taskDone.name)
        traitIds(stale) shouldNotContain ST.traitPresenceByYear
    }

    "a form whose recomputed state does not validate fails its task, recorded against it until a later run succeeds" {
        val broken = service().createGedra(
            acme(), GedraDataType.formDoc,
            listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to BrokenStateFixture.markerYear))),
        ).gedraId
        BrokenStateFixture.breaking = true
        try {
            launch("broken1").outcome shouldBe JobLaunchOutcome.completed
            eventsFor("broken1", broken) shouldBe listOf(JobTraceEvent.taskFailed.name)
            val entry = JobExceptionRows.read(cxt, broken.fullId, scope).single()[GE.data].toJsonMapOrEmpty()
            entry[JOB.jobType] shouldBe SRJ.jobType
            entry[JOBX.scenario] shouldBe SRJ.invalidState
        } finally {
            BrokenStateFixture.breaking = false
        }

        launch("fixed1").outcome shouldBe JobLaunchOutcome.completed
        JobExceptionRows.read(cxt, broken.fullId, scope).shouldBeEmpty()
    }

    "the job is registered to run nightly" {
        JobService.get(cxt).def(SRJ.jobType).schedule.shouldBeInstanceOf<JobSchedule.Daily>().minutesOfDay shouldBe listOf(3 * 60)
    }
})

/**
 * A deriver whose output never validates, for forms marked by [markerYear] while [breaking] is set: a year-presence
 * entry for a year below the trait's minimum. Stands in for a deriver that has gone wrong.
 */
private class BrokenStateFixture : ComponentDefinition {
    override val providerName: String = "brokenStateFixture"

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addStateDeriver(
            object : GedraStateDeriver {
                override val appliesTo: Set<GedraDataType> = setOf(GedraDataType.formDoc)

                override fun derive(cxt: KdrCxt, state: GedraStateContext): List<Map<String, Any?>> {
                    val marked = state.row.entries.any { it[GE.data].toJsonMapOrEmpty()[ST.year].toOptLong() == markerYear.toLong() }
                    if (!breaking || !marked) return emptyList()
                    return listOf(mapOf(GE.traitId to ST.traitPresenceByYear, GE.data to mapOf(ST.year to 1500)))
                }
            },
        )
    }

    companion object {
        /** The expense year that marks a form for breaking; no other test uses it. */
        const val markerYear = 2003

        @Volatile
        var breaking = false
    }
}
