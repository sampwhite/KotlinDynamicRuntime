package com.dynamicruntime.multinode

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.job.JOB
import com.dynamicruntime.common.job.JOBEP
import com.dynamicruntime.common.job.JOBF
import com.dynamicruntime.common.job.JOBT
import com.dynamicruntime.common.job.JobAttemptEnd
import com.dynamicruntime.common.job.JobLaunchKind
import com.dynamicruntime.common.job.JobLaunchOutcome
import com.dynamicruntime.common.job.JobRunStatus
import com.dynamicruntime.common.job.JobTraceEvent
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.script.ProbeSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The batch-jobs suite (issue #872): the job framework's promises, checked across several real processes on one
 * database -- which launch wins a race, how an abort crosses nodes, what a graceful stop and a crash leave behind,
 * and that no task is ever run by two executions at once.
 *
 * Every scenario reads its verdict from the nodes' own operator surface: the job status rows and, from the
 * fixture, every task execution the fake jobs recorded. Each launch takes a name unique to this run, so a scenario
 * reads only its own work.
 */
class JobSuite(private val nodes: List<HarnessNode>) {
    private val runId = System.currentTimeMillis().toString(36)
    private val sessions: List<ProbeSession> = nodes.map { node ->
        ProbeSession("mn-${node.label}", node.url).also {
            it.becomeUser("mn-operator@example.com", ROLE.operator, listOf(ROLE.allClients))
        }
    }

    fun run(): List<ScenarioResult> = listOf(
        scenario("simultaneous launches: one starts, the rest are locked out, nothing runs twice", ::contention),
        scenario("an abort requested on one node stops the launch running on another", ::abortAcrossNodes),
        scenario("every node's scheduler contends for each slot; each slot runs once", ::scheduledContention),
        scenario("a graceful stop releases the launch, which another node adopts at once", needsStop = 2, body = ::releaseOnStop),
        scenario("a crashed node's launch is adopted by another once its lease lapses", needsStop = 3, body = ::failover),
    )

    // --- scenarios -------------------------------------------------------------------------------------------

    private fun contention(): List<String> {
        val name = "contend-$runId"
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(sessions.size)
        val outcomes = try {
            sessions.map { s -> pool.submit<String> { start.await(); launch(s, name, tasks = 12) } }
                .also { start.countDown() }
                .map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        val out = mutableListOf<String>()
        val started = outcomes.count { it == JobLaunchOutcome.started.name }
        if (started != 1) out.add("Expected exactly one launch to start; outcomes were $outcomes.")
        val others = outcomes.filter { it != JobLaunchOutcome.started.name }
        if (others.any { it != JobLaunchOutcome.lockedOut.name && it != JobLaunchOutcome.alreadyComplete.name }) {
            out.add("The launches that did not start should be locked out; outcomes were $outcomes.")
        }
        out.addAll(awaitEnd(name, JobRunStatus.complete))
        out.addAll(checkWork(name, expectEnded = 12, oncePerTask = true))
        // The trace, written by all three nodes into one stream: the winner's single claim, each loser's lock-out.
        val trace = traceOf(name)
        val claims = trace.filter { it[JOBT.event] == JobTraceEvent.launchClaimed.name }
        if (claims.size != 1) out.add("Expected one launchClaimed in the trace, found ${claims.size}.")
        val winner = nodes[outcomes.indexOf(JobLaunchOutcome.started.name).coerceAtLeast(0)]
        if (claims.singleOrNull()?.let { heldBy(it, winner) } == false) {
            out.add("The claim was traced by ${claims.single()[JOB.holder]}, not the node that started it (${winner.label}).")
        }
        val lockOuts = trace.count { it[JOBT.event] == JobTraceEvent.launchLockedOut.name }
        if (lockOuts != outcomes.count { it == JobLaunchOutcome.lockedOut.name }) {
            out.add("The trace has $lockOuts lock-outs for ${outcomes.count { it == JobLaunchOutcome.lockedOut.name }} locked-out launches.")
        }
        val holders = trace.mapNotNull { it[JOB.holder].toOptStr() }.toSet()
        if (nodes.any { node -> holders.none { it.endsWith(":${node.port}") } }) {
            out.add("Not every node's entries reached the trace; holders were $holders.")
        }
        out.addAll(checkOrdered(name, trace))
        return out
    }

    private fun abortAcrossNodes(): List<String> {
        val name = "abort-$runId"
        val out = mutableListOf<String>()
        val first = launch(sessions[0], name, tasks = 40, taskMs = 300)
        if (first != JobLaunchOutcome.started.name) return listOf("The launch did not start: $first.")
        Thread.sleep(1_000)
        val requested = sessions[1].sendJsonPostRequest(JOBEP.abort, mapOf(JOBF.jobType to MNJ.workJob))
            .get(EP.results).toJsonMapOrEmpty()[JOBF.requested]
        if (requested != true) out.add("The abort request on ${nodes[1].label} found no active launch.")
        out.addAll(awaitEnd(name, JobRunStatus.aborted))
        val reason = launchRow()?.get(JOB.history).toJsonListOfMaps().lastOrNull()?.get(JOB.reason).toOptStr()
        if (reason != "Abort requested.") out.add("The launch ended for another reason: $reason.")
        out.addAll(checkWork(name, oncePerTask = true))
        // Asked of one node, noticed by the other: the stop is traced by the node running the launch.
        val stopping = traceOf(name).filter { it[JOBT.event] == JobTraceEvent.stopping.name }
        if (stopping.none { it[JOBT.message] == "Abort requested." && heldBy(it, nodes[0]) }) {
            out.add("No 'Abort requested.' stop traced by ${nodes[0].label}; stops were ${stopping.map { it[JOB.holder] to it[JOBT.message] }}.")
        }
        return out
    }

    private fun scheduledContention(): List<String> {
        // Slots come every fifteen seconds; wait for two to finish, then check each ran every task exactly once.
        val out = mutableListOf<String>()
        var slots = emptyList<String>()
        val finished = waitFor(50_000) {
            slots = scheduledRow()?.get(JOB.history).toJsonListOfMaps()
                .filter { it[JOB.end] == JobAttemptEnd.complete.name }
                .mapNotNull { it[JOB.launchName].toOptStr() }.distinct()
            slots.size >= 2
        }
        if (!finished) return listOf("Fewer than two scheduled slots completed in time: $slots.")
        for (slot in slots) {
            out.addAll(checkWork(slot, jobType = MNJ.scheduledJob, oncePerTask = true))
            // Three schedulers looked; one claimed.
            val claims = traceOf(slot, MNJ.scheduledJob, JobLaunchKind.scheduled).count { it[JOBT.event] == JobTraceEvent.launchClaimed.name }
            if (claims != 1) out.add("$slot: expected one launchClaimed in the trace, found $claims.")
        }
        return out
    }

    private fun releaseOnStop(): List<String> {
        val name = "release-$runId"
        val out = mutableListOf<String>()
        val first = launch(sessions[1], name, tasks = 40, taskMs = 300)
        if (first != JobLaunchOutcome.started.name) return listOf("The launch did not start: $first.")
        Thread.sleep(1_200)
        if (!nodes[1].stop()) return listOf("${nodes[1].label} did not stop gracefully in time.")
        // Released on the way down, so another node can adopt it at once, without waiting out the lease.
        val again = launch(sessions[0], name, tasks = 40, taskMs = 300)
        if (again != JobLaunchOutcome.started.name) out.add("The relaunch after a graceful stop was $again, not started.")
        out.addAll(awaitEnd(name, JobRunStatus.complete, via = 0))
        val ends = launchRow(via = 0)?.get(JOB.history).toJsonListOfMaps().map { it[JOB.end] }
        if (JobAttemptEnd.released.name !in ends) out.add("The stopped node's attempt is not recorded as released: $ends.")
        out.addAll(checkWork(name, via = 0))
        // The stopping node traced its own shutdown on the way down; the next node's claim adopted its work.
        val trace = traceOf(name)
        if (trace.none { it[JOBT.event] == JobTraceEvent.stopping.name && heldBy(it, nodes[1]) }) {
            out.add("${nodes[1].label} traced no stop as it shut down.")
        }
        out.addAll(checkAdopted(trace, nodes[0]))
        return out
    }

    private fun failover(): List<String> {
        val name = "failover-$runId"
        val out = mutableListOf<String>()
        val first = launch(sessions[2], name, tasks = 40, taskMs = 300)
        if (first != JobLaunchOutcome.started.name) return listOf("The launch did not start: $first.")
        Thread.sleep(1_200)
        nodes[2].kill()
        // Its lease is still live, so the first relaunch is locked out; once it lapses, a relaunch adopts it.
        val early = launch(sessions[0], name, tasks = 40, taskMs = 300)
        if (early != JobLaunchOutcome.lockedOut.name) out.add("A relaunch before the lease lapsed was $early, not locked out.")
        var outcome = early
        val adopted = waitFor(15_000, pollMs = 500) {
            outcome = launch(sessions[0], name, tasks = 40, taskMs = 300)
            outcome == JobLaunchOutcome.started.name
        }
        if (!adopted) return out + "No relaunch was admitted after the crashed node's lease lapsed; last was $outcome."
        out.addAll(awaitEnd(name, JobRunStatus.complete, via = 0))
        val ends = launchRow(via = 0)?.get(JOB.history).toJsonListOfMaps().map { it[JOB.end] }
        if (JobAttemptEnd.lapsed.name !in ends) out.add("The crashed node's attempt is not recorded as lapsed: $ends.")
        out.addAll(checkWork(name, via = 0))
        // What the crashed node flushed at its heartbeats survived it; the adopter's claim continued its work.
        val trace = traceOf(name)
        if (trace.none { it[JOBT.event] == JobTraceEvent.taskDone.name && heldBy(it, nodes[2]) }) {
            out.add("None of ${nodes[2].label}'s task entries reached the trace before it was killed.")
        }
        out.addAll(checkAdopted(trace, nodes[0]))
        out.addAll(checkOrdered(name, trace))
        return out
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun scenario(name: String, body: () -> List<String>, needsStop: Int = 0): ScenarioResult {
        if (needsStop > 0 && !nodes.take(needsStop).all { it.stoppable }) {
            return ScenarioResult(name, emptyList(), "needs nodes the harness started")
        }
        if (nodes.size < 3) return ScenarioResult(name, emptyList(), "needs three nodes")
        return try {
            ScenarioResult(name, body())
        } catch (e: Exception) {
            ScenarioResult(name, listOf("Threw: ${e.message}"))
        }
    }

    /** [name]'s trace, as any node reads it: the one stream every node's entries went into. */
    private fun traceOf(
        name: String,
        jobType: String = MNJ.workJob,
        kind: JobLaunchKind = JobLaunchKind.endpoint,
        via: Int = 0,
    ): List<Map<String, Any?>> =
        sessions[via].sendJsonGetRequest(
            JOBEP.trace,
            // Past the endpoint's default page of 100: a task-level trace of a launch runs longer.
            mapOf(JOBF.jobType to jobType, JOBF.name to name, JOBF.kind to kind.name, EP.limit to 10_000),
        )[EP.items].toJsonListOfMaps()

    /** Whether [entry] was written by [node], which labels itself `ip:port`. */
    private fun heldBy(entry: Map<String, Any?>, node: HarnessNode): Boolean =
        entry[JOB.holder].toOptStr()?.endsWith(":${node.port}") == true

    /** That [trace] holds a claim by [adopter] that adopted the earlier work rather than resetting it. */
    private fun checkAdopted(trace: List<Map<String, Any?>>, adopter: HarnessNode): List<String> {
        val claims = trace.filter { it[JOBT.event] == JobTraceEvent.launchClaimed.name && heldBy(it, adopter) }
        return if (claims.any { it[JOB.data].toJsonMapOrEmpty()[JOB.adopted] == true }) {
            emptyList()
        } else {
            listOf("No claim by ${adopter.label} adopted the earlier work; its claims were ${claims.map { it[JOB.data] }}.")
        }
    }

    /** That [trace] reads back in the order the database numbered it, each entry once. */
    private fun checkOrdered(name: String, trace: List<Map<String, Any?>>): List<String> {
        val seqs = trace.mapNotNull { it[JOBT.traceSeq].toOptLong() }
        return if (seqs.zipWithNext().all { (a, b) -> a < b }) emptyList() else listOf("$name: the trace is not in sequence order.")
    }

    /** Launches the fake work job on [session]'s node as [name], answering the outcome. */
    private fun launch(session: ProbeSession, name: String, tasks: Int, taskMs: Int = 150): String? {
        val resp = session.sendPostRequest(
            JOBEP.launch,
            mapOf(
                JOBF.jobType to MNJ.workJob, JOBF.name to name, JOBF.clients to listOf(CL.hub),
                JOBF.workAreas to listOf("${MNJ.tasksArea}$tasks", "${MNJ.taskMsArea}$taskMs"),
            ),
        )
        if (!resp.isSuccess) throw IllegalStateException("Launch on ${session.baseUrl} failed (${resp.statusCode}): ${resp.errorMessage}")
        return resp.results[JOBF.outcome].toOptStr()
    }

    private fun statusRows(jobType: String, via: Int): List<Map<String, Any?>> =
        sessions[via].sendJsonGetRequest(JOBEP.status, mapOf(JOBF.jobType to jobType))[EP.items]
            .toJsonListOfMaps().firstOrNull()?.get(JOBF.launches).toJsonListOfMaps()

    private fun launchRow(via: Int = 0): Map<String, Any?>? =
        statusRows(MNJ.workJob, via).firstOrNull { it[JOB.launchKind] == JobLaunchKind.endpoint.name }

    private fun scheduledRow(via: Int = 0): Map<String, Any?>? =
        statusRows(MNJ.scheduledJob, via).firstOrNull { it[JOB.launchKind] == JobLaunchKind.scheduled.name }

    /** Waits until the endpoint launch named [name] has ended as [status]. */
    private fun awaitEnd(name: String, status: JobRunStatus, via: Int = 0, timeoutMs: Long = 30_000): List<String> {
        var row: Map<String, Any?>? = null
        val ended = waitFor(timeoutMs) {
            row = launchRow(via)
            row?.get(JOB.launchName) == name && row?.get(JOB.runStatus) == status.name
        }
        return if (ended) emptyList() else listOf("Launch $name did not end as ${status.name}; last seen: ${row?.get(JOB.runStatus)}.")
    }

    /** Checks what [name]'s executions recorded: no two of one task at once, and optionally each task once. */
    private fun checkWork(
        name: String,
        jobType: String = MNJ.workJob,
        via: Int = 0,
        expectEnded: Int? = null,
        oncePerTask: Boolean = false,
    ): List<String> {
        val report = sessions[via].sendJsonGetRequest(MNJ.workPath, mapOf(MNJ.jobType to jobType, MNJ.launchName to name))[EP.results]
            .toJsonMapOrEmpty()
        val out = mutableListOf<String>()
        val overlaps = report[MNJ.overlaps].toJsonListOfMaps()
        if (overlaps.isNotEmpty()) out.add("$name: tasks ran twice at the same time: $overlaps.")
        val ended = report[MNJ.ended].toOptLong()
        if (expectEnded != null && ended != expectEnded.toLong()) {
            out.add("$name: expected $expectEnded ended executions, found $ended.")
        }
        if (oncePerTask && (report[MNJ.maxPerTask].toOptLong() ?: 0) > 1) {
            out.add("$name: a task ran more than once (at most ${report[MNJ.maxPerTask]}).")
        }
        return out
    }

    private fun waitFor(timeoutMs: Long, pollMs: Long = 250, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(pollMs)
        }
        return condition()
    }
}
