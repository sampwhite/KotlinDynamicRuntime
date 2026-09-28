package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunCxt
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.startup.SchemaService

/** The derived-state recompute job's names (issue #793). Each name matches its value. */
@Suppress("ConstPropertyName")
object SRJ {
    /** The job type. */
    const val jobType = "stateRecompute"

    /** Its profile: a deployment tunes it under `jobProfiles.gedraState`. */
    const val profile = "gedraState"

    /** The scenario a recompute whose output does not validate fails with, as its exceptions entry records it. */
    const val invalidState = "derivedStateInvalid"
}

/**
 * The first real batch job (issue #793): recompute the **derived state** of every gedra a deriver applies to, in
 * every client, nightly and on demand.
 *
 * Why it is needed: stored derived state is only as current as a gedra's last data write. A form whose client's
 * configuration has since changed -- a workflow gaining a test, a relevancy window closing -- carries state
 * computed against what no longer holds, until something recomputes it. Every write path already recomputes
 * (the `DerivedStateWriteHook`); this recomputes the forms nobody has written.
 *
 * **One task per gedra, keyed by its id**, so the job's resource is its task key and a gedra whose state cannot be
 * stored gets an exceptions entry, cleared by a later success.
 *
 * - **A cheap look first.** The task compares what a recompute would produce with what is stored, from the
 *   resident caches and outside any lock, and skips a current form (counted as nothing to do). Only a form found
 *   out of date takes the lock, where the recompute decides again from the data as it stands (issue #862) and
 *   writes only if something still differs.
 * - **A recompute whose derivers produce state that does not validate** fails its task -- recorded against the
 *   gedra -- where a write path logs it and leaves the state as it was.
 * - **A dry run** counts the forms it would recompute, notes each in the trace, and writes nothing.
 * - **The task total comes from the cache**, so the job may run synchronously for a small client (a demo).
 *
 * Asserted state is never touched: engagement and approvals survive every recompute, which is what already
 * keeps an engaged workflow's entry and drops an unengaged one past its relevancy (#787, #790, #794).
 */
fun stateRecomputeJob(): JobDef = JobDef(
    jobType = SRJ.jobType,
    description = "Recomputes the derived state of every gedra a deriver applies to, so stored state follows " +
        "configuration and time as well as data writes.",
    profile = JobProfile(SRJ.profile),
    tasks = { run, client -> gedraIds(run.cxt, client) },
    countTasks = { run, client -> gedraIds(run.cxt, client).size },
    runTask = ::recomputeOne,
    schedule = JobSchedule.daily("03:00"),
    resourceOf = { it },
)

/** Every live gedra in [client] of a kind some registered deriver applies to. */
private fun gedraIds(cxt: KdrCxt, client: String): List<String> {
    val kinds = SchemaService.get(cxt).stateDerivers().flatMap { it.appliesTo }.distinct().sortedBy { it.name }
    val service = GedraDataService.get(cxt)
    return kinds.flatMap { service.liveGedraIds(cxt, it, client) }
}

private fun recomputeOne(run: JobRunCxt, key: String): JobTaskResult {
    val service = GedraDataService.get(run.cxt)
    val id = GedraId.parse(key)
    val kind = id.dataType ?: return JobTaskResult.nothingToDo
    // Gone since the listing: nothing to derive for.
    val row = service.queryGedra(run.cxt, key, kind, ReadScope.unrestricted) ?: return JobTaskResult.nothingToDo
    if (service.derivedStateCurrent(run.cxt, row)) return JobTaskResult.nothingToDo
    if (run.dryRun) {
        run.trace("Its derived state is out of date; a real run would recompute it.", key)
        return JobTaskResult.done
    }
    val result = service.recomputeDerivedState(run.cxt, row, skipIfUnchanged = true)
    return when (result.outcome) {
        StateRecomputeOutcome.written -> JobTaskResult.done
        StateRecomputeOutcome.unchanged, StateRecomputeOutcome.gone -> JobTaskResult.nothingToDo
        StateRecomputeOutcome.invalid -> throw KdrException.mkJob(
            "The recomputed state does not validate: ${result.message}", JobHandling.skipTask, SRJ.invalidState, key,
        )
    }
}
