package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.job.JobDef
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobProfile
import com.dynamicruntime.common.job.JobRunCxt
import com.dynamicruntime.common.job.JobRunMode
import com.dynamicruntime.common.job.JobSchedule
import com.dynamicruntime.common.job.JobService
import com.dynamicruntime.common.job.JobTaskResult
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.mkUniqueId

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
 * - **The task total comes from the cache**, so the job may run synchronously for a small client: a client's own page
 *   does that ([recalculateFormStates], issue #1412), within the profile's synchronous cap.
 *
 * Asserted state is never touched: engagement and approvals survive every recompute, which is what already
 * keeps an engaged workflow's entry and drops an unengaged one past its relevancy (#787, #790, #794).
 */
fun stateRecomputeJob(): JobDef = JobDef(
    jobType = SRJ.jobType,
    description = "Recomputes the derived state of every gedra a deriver applies to, so stored state follows " +
        "configuration and time as well as data writes.",
    // A client's page recalculates its forms synchronously (issue #1412), so the synchronous caps are a client's
    // forms rather than the default's demo-sized run. A deployment tunes them under `jobProfiles.gedraState`.
    profile = JobProfile(SRJ.profile, syncTaskCap = 2000, syncHardCap = 2500),
    tasks = { run, client -> gedraIds(run.cxt, client) },
    countTasks = { run, client -> gedraIds(run.cxt, client).size },
    runTask = ::recomputeOne,
    schedule = JobSchedule.daily("03:00"),
    resourceOf = { it },
)

/**
 * The most forms a client's page may recalculate synchronously (issue #1412): the job profile's synchronous cap, as
 * the deployment tunes it.
 */
fun formStatesRecalcLimit(cxt: KdrCxt): Int = JobService.get(cxt).def(SRJ.jobType).profile.resolve(cxt).syncTaskCap

/**
 * How many gedras a recalculation of [client]'s form states would look at (issue #1412): its tasks, what
 * [formStatesRecalcLimit] is held against -- every live gedra of a kind some state deriver applies to, which today is
 * its forms.
 */
fun formStatesRecalcCount(cxt: KdrCxt, client: String): Int = gedraIds(cxt, client).size

/**
 * Recalculates the derived state of [client]'s forms now (issue #1412), as the nightly run would: a synchronous
 * **client launch** of this job, so a launch for another client runs beside it, and the operator's job page shows it.
 * Refused (400) for a client holding more than [formStatesRecalcLimit] of them, before anything runs. Answers in [FSR]'s
 * names: [FSR.busy] when another launch -- the nightly one, or another administrator's -- holds the client, in which
 * case nothing ran.
 */
fun recalculateFormStates(cxt: KdrCxt, client: String): Map<String, Any?> {
    val limit = formStatesRecalcLimit(cxt)
    val forms = formStatesRecalcCount(cxt, client)
    if (forms > limit) {
        throw KdrException.mkInput(
            "Client '$client' holds $forms forms, more than the $limit a recalculation from its page may take. The " +
                "nightly recalculation brings them up to date.",
        )
    }
    // A name of its own, so no click adopts an earlier one's finished work and does nothing. Counted once, above.
    val result = JobService.get(cxt).launch(
        cxt, SRJ.jobType, "page-${cxt.mkUniqueId()}", JobLaunchKind.client, JobRunMode.sync, clients = listOf(client),
        countedTasks = forms.toLong(),
    )
    val busy = result.outcome == JobLaunchOutcome.lockedOut || client in result.busyClients
    val counts = result.counts
    return buildMap {
        put(
            FSR.outcome,
            when {
                busy -> FSR.busy
                result.outcome == JobLaunchOutcome.completed -> FSR.completed
                else -> FSR.stopped
            },
        )
        if (busy) {
            put(FSR.reason, "Another recalculation of this client's forms is running. Try again once it has finished.")
        } else {
            result.reason?.let { put(FSR.reason, it) }
        }
        if (counts != null && !busy) {
            put(FSR.updated, counts.completed)
            put(FSR.current, counts.skipped)
            put(FSR.failed, counts.failed)
            counts.total?.let { put(FSR.total, it) }
        }
    }
}

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
    // Its computed values too (issue #1501): a changed `g-onWrite` function leaves them stale with the data unchanged.
    if (service.derivedStateCurrent(run.cxt, row) && service.computedDataCurrent(run.cxt, row)) return JobTaskResult.nothingToDo
    if (run.dryRun) {
        run.trace("Its derived state or computed values are out of date; a real run would recompute them.", key)
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
