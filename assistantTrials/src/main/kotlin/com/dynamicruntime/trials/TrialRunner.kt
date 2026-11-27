package com.dynamicruntime.trials

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.AppPaths
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.sql.SecretsUtil
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.script.ProbeSession
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess
import kotlin.time.Clock

/** Every trial, by name. A new one is added here. */
object Trials {
    val all: List<Trial> = listOf(GrantsBasics.trial, GrantsPolish.trial)

    fun named(name: String): Trial = all.firstOrNull { it.name == name }
        ?: throw IllegalArgumentException("No trial '$name'. Trials: ${all.joinToString { it.name }}.")
}

/**
 * The harness's commands (issue #1859), run by `:assistantTrials:runTrials` and `:assistantTrials:stopTrials`:
 * - `run --trials <name>,<name> [--variant <name>|all] [--attach <url>] [--model m] [--effort e] [--budget tokens]
 *   [--provider fake]` runs the named trials, each as a fresh self-serve client, and writes the run's report. Unless it
 *   attaches to a node somebody started, it starts one and leaves it running. `--trials list` lists the trials.
 * - `stop [--port <port>|all]` lists the trial nodes still running, or stops the ones named.
 * - `restart --run <runId>` starts a run's node again, on its port and database, as the run left it.
 * - `recheck --run <runId>|all` holds saved runs to the checks as they are now.
 * - `walk --run <runId>` walks through what a run configured, on its node, which must be running.
 *
 * A run never fails on what the assistant did -- that is the report's to say. It exits non-zero only when the harness
 * itself could not run: no API key, no free port, a node that would not start.
 */
@Suppress("ConstPropertyName")
object TrialRunner {
    private val runsDir: File = File(System.getProperty("kdr.trials.runsDir") ?: "build/trialRuns")

    fun run(args: Map<String, String>) {
        val names = args["trials"] ?: throw IllegalArgumentException("Name the trials to run: -Ptrials=<name>,<name>, or -Ptrials=list.")
        if (names == "list") return list()
        val trials = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { Trials.named(it) }
        val runs = trials.flatMap { trial ->
            when (val v = args["variant"]) {
                "all" -> trial.variants.map { trial to it }
                else -> listOf(trial to trial.variant(v))
            }
        }
        val runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val runDir = File(runsDir, runId)
        val settings = buildMap {
            args["model"]?.let { put("KDR_ASSISTANT_MODEL", it) }
            args["effort"]?.let { put("KDR_ASSISTANT_EFFORT", it) }
            args["provider"]?.let { put("KDR_ASSISTANT_PROVIDER", it) }
            put("KDR_ASSISTANT_SELF_SERVE_BUDGET", args["budget"] ?: defaultBudget.toString())
        }
        val attach = args["attach"]?.trimEnd('/')
        val node = if (attach != null) {
            null
        } else {
            if (args["provider"] != "fake") requireKey()
            println("Starting a trial node for run $runId…")
            TrialNode.start(runId, runDir, launchJar(), settings).also {
                println("  on port ${it.port} (pid ${it.pid}); its log is ${it.log}")
                TrialNode.awaitHealthy(it)
            }
        }
        val url = attach ?: node!!.url
        val info = RunInfo(runId, url, node?.port, if (attach != null) emptyMap() else settings, commit(), Clock.System.now())
        println("Running ${runs.joinToString { "${it.first.name}/${it.second.name}" }} against $url")
        val (report, results) = runAll(runs, runDir, info) { trial, variant, email ->
            ProbeSession("trial-${trial.name}-${variant.name}", url).use { session ->
                session.becomeUser(email, client = CL.public)
                ProbePeople(url).use { TrialDriver(ProbeCaller(session), people = it).run(trial, variant, runId) }
            }
        }
        println()
        for ((r, _) in results) {
            val counts = r.outcomes().groupingBy { it.second.kind }.eachCount()
            println(
                "  ${r.trial.name}/${r.variant.name}: ${counts[OutcomeKind.met] ?: 0} met, ${counts[OutcomeKind.varied] ?: 0} varied, " +
                    "${counts[OutcomeKind.notMet] ?: 0} not met; ${r.weighted} weighted tokens, about " +
                        (TrialReport.cost(r.weighted, r.model) ?: "?") + if (r.problems.isEmpty()) "" else "; ${r.problems.size} problem(s)",
            )
        }
        println()
        println("Report: ${report.absolutePath}")
        node?.let {
            println("The node is still running at ${it.url}/wa. Stop it with ./gradlew :assistantTrials:stopTrials -Pport=${it.port}")
        }
    }

    /**
     * Runs each of [runs] with [runOne], writing the report again as each ends, so what one run was paid for is kept
     * whatever happens to the next (issue #1914). A run that fails to start -- its node gone, say -- is a result with that
     * as its problem, and the rest go on. Answers the report and the results.
     */
    fun runAll(
        runs: List<Pair<Trial, TrialVariant>>,
        runDir: File,
        info: RunInfo,
        runOne: (trial: Trial, variant: TrialVariant, email: String) -> TrialResult,
    ): Pair<File, List<Pair<TrialResult, String>>> {
        val results = mutableListOf<Pair<TrialResult, String>>()
        for ((trial, variant) in runs) {
            val email = TrialDriver.addressOf(trial, variant, info.runId)
            val startedAt = Clock.System.now()
            val result = try {
                runOne(trial, variant, email)
            } catch (e: Exception) {
                val problem = "The run failed: ${e.message ?: e::class.simpleName}"
                println("  ${trial.name}/${variant.name}: $problem")
                TrialResult(trial, variant, null, null, emptyList(), emptyMap(), listOf(problem), startedAt, Clock.System.now())
            }
            results.add(result to email)
            TrialReport.write(runDir, info, results)
        }
        return TrialReport.write(runDir, info, results) to results
    }

    /**
     * Holds saved runs to the checks as they are now (issue #1914), from each run's transcripts and configuration: what an
     * old report said is only what the checks of its day could tell. Costs nothing, and leaves the run's report as it was,
     * writing `recheck.md` beside it. `--run all` takes every run that has a transcript.
     */
    fun recheck(args: Map<String, String>) {
        val which = args["run"] ?: throw IllegalArgumentException("Name the run to recheck: -Prun=<runId>, or -Prun=all.")
        val dirs = if (which == "all") {
            runsDir.listFiles()?.filter { File(it, TrialReport.transcriptsDir).isDirectory }?.sortedBy { it.name }.orEmpty()
        } else {
            listOf(File(runsDir, which))
        }
        for (dir in dirs) {
            val results = TrialReport.read(dir, Trials.all)
            if (results.isEmpty()) println("${dir.name}: no transcript of a trial there is now.")
            for (result in results) {
                println("${dir.name} ${result.trial.name}/${result.variant.name}")
                for ((expectation, outcome) in result.outcomes()) {
                    if (outcome.kind == OutcomeKind.varied || outcome.kind == OutcomeKind.notMet) {
                        println("  ${outcome.kind.name}: ${expectation.name} -- ${outcome.detail.take(200)}")
                    }
                }
            }
            if (results.isNotEmpty()) File(dir, "recheck.md").writeText(TrialReport.renderRecheck(dir.name, results))
        }
    }

    /**
     * Walks through what a run configured (issue #1915), on its node, as its client is now: what the run's own walk did, for
     * a run from before there was one, or after a person changed the client by hand. Costs nothing. The run's report is left
     * as it was; `walk.md` is written beside it, and a recheck reads this walk in place of the one before.
     */
    fun walk(args: Map<String, String>) {
        val runId = args["run"] ?: throw IllegalArgumentException("Name the run to walk through: -Prun=<runId>.")
        val node = TrialNode.running(runsDir).firstOrNull { it.runId == runId } ?: throw IllegalArgumentException(
            "Run '$runId' has no node running. Start it again with ./gradlew :assistantTrials:restartTrials -Prun=$runId",
        )
        val runDir = File(runsDir, runId)
        val walked = TrialReport.read(runDir, Trials.all).filter { it.trial.walk.isNotEmpty() }.map { result ->
            val email = TrialDriver.addressOf(result.trial, result.variant, runId)
            println("Walking through ${result.trial.name}/${result.variant.name} on ${node.url}")
            val record = ProbeSession("walk-${result.variant.name}", node.url).use { session ->
                session.becomeUser(email)
                val admin = ProbeCaller(session)
                if (!isSandboxClient(clientOf(admin))) admin.postData(AEP.openSandbox, emptyMap())
                ProbePeople(node.url).use { people ->
                    TrialWalk(admin, people, clientOf(admin), ConfigView(TrialDriver.readConfig(admin)), result.trial.walk, email).walk()
                }
            }
            for ((step, outcome) in record.outcomes) println("  ${outcome.kind.name}: ${step.title} -- ${outcome.detail}")
            TrialReport.writeWalk(runDir, result, record)
            result to record
        }
        if (walked.isEmpty()) println("$runId: no transcript of a trial that is walked through.")
        if (walked.isNotEmpty()) File(runDir, "walk.md").writeText(TrialReport.renderWalks(runId, walked))
    }

    private fun clientOf(caller: TrialCaller): String = caller.getData(AEP.selfInfo)[UPF.client].toOptStr().orEmpty()

    private fun list() {
        for (trial in Trials.all) {
            println("${trial.name}: ${trial.goal}")
            trial.variants.forEach { println("  ${it.name}: ${it.description}") }
        }
    }

    fun stop(args: Map<String, String>) {
        val running = TrialNode.running(runsDir)
        val which = args["port"]
        if (which == null) {
            if (running.isEmpty()) println("No trial node is running.")
            running.forEach { println("  port ${it.port}: run ${it.runId}, pid ${it.pid}, ${it.url}/wa") }
            if (running.isNotEmpty()) println("Stop one with -Pport=<port>, or all of them with -Pport=all.")
            return
        }
        val chosen = if (which == "all") running else running.filter { it.port.toString() == which }
        if (chosen.isEmpty()) println("No trial node is running on port $which.")
        for (node in chosen) {
            val graceful = node.stop()
            println("  stopped port ${node.port} (run ${node.runId})" + if (graceful) "" else ", forcibly")
        }
    }

    fun restart(args: Map<String, String>) {
        val runId = args["run"] ?: throw IllegalArgumentException(
            "Name the run whose node to restart: -Prun=<runId>, the name of its directory under $runsDir.",
        )
        val runDir = File(runsDir, runId).takeIf { it.isDirectory } ?: throw IllegalArgumentException("No run '$runId' under $runsDir.")
        println("Restarting run $runId's node…")
        val node = TrialNode.restart(runDir, launchJar())
        TrialNode.awaitHealthy(node)
        println("The node is running again at ${node.url}/wa (pid ${node.pid}).")
        println("Stop it with ./gradlew :assistantTrials:stopTrials -Pport=${node.port}")
    }

    /** The monthly budget each trial's client switches the assistant on with, in weighted tokens: a few dollars. */
    private const val defaultBudget = 3_000_000L

    private fun launchJar(): File = File(System.getProperty("kdr.trials.launchJar") ?: error("kdr.trials.launchJar is not set."))

    /** Refuses the run, before anything starts, when the workspace holds no API key for the node to use. */
    private fun requireKey() {
        val name = System.getenv(ASST.keySecretEnvVar)?.ifBlank { null } ?: "anthropicApiKey"
        if (SecretsUtil.getSecret(name).isNullOrBlank()) {
            throw IllegalStateException(
                "The workspace holds no Anthropic API key: add '$name' to ${File(AppPaths.workspaceDir(), SecretsUtil.secretsPath)}, " +
                    "or run with -Pprovider=fake to try the harness without the model.",
            )
        }
    }

    /** The repository's commit, and whether it has uncommitted changes, for the report; null when git cannot say. */
    private fun commit(): String? = runCatching {
        val repo = File(AppPaths.workspaceDir(), "KotlinDynamicRuntime")
        fun git(vararg a: String) = ProcessBuilder(listOf("git", "-C", repo.path) + a).redirectErrorStream(true).start()
            .let { p -> p.inputStream.bufferedReader().readText().trim().also { p.waitFor() } }
        val head = git("rev-parse", "--short", "HEAD")
        head + if (git("status", "--porcelain").isNotEmpty()) " with uncommitted changes" else ""
    }.getOrNull()
}

/** `run …`, `stop …`, `restart …`, `recheck …`, or `walk …`; see [TrialRunner]. */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val options = args.drop(1).chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
    try {
        when (command) {
            "run" -> TrialRunner.run(options)
            "stop" -> TrialRunner.stop(options)
            "restart" -> TrialRunner.restart(options)
            "recheck" -> TrialRunner.recheck(options)
            "walk" -> TrialRunner.walk(options)
            else -> throw IllegalArgumentException(
                "Usage: run --trials <names> | stop [--port <port>|all] | restart --run <runId> | recheck --run <runId>|all | " +
                    "walk --run <runId>",
            )
        }
    } catch (e: Exception) {
        System.err.println("Assistant trials: ${e.message ?: e::class.simpleName}")
        exitProcess(1)
    }
    exitProcess(0)
}
