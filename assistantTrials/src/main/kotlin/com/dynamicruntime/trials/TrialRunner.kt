package com.dynamicruntime.trials

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.context.AppPaths
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.sql.SecretsUtil
import com.dynamicruntime.script.ProbeSession
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess
import kotlin.time.Clock

/** Every trial, by name. A new one is added here. */
object Trials {
    val all: List<Trial> = listOf(GrantsBasics.trial)

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
 *
 * A run never fails on what the assistant did -- that is the report's to say. It exits non-zero only when the harness
 * itself could not run: no API key, no free port, a node that would not start.
 */
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
        val results = runs.map { (trial, variant) ->
            val email = "${trial.name}-${variant.name}-$runId@trials.test".lowercase()
            ProbeSession("trial-${trial.name}-${variant.name}", url).use { session ->
                session.becomeUser(email, client = CL.public)
                TrialDriver(ProbeCaller(session)).run(trial, variant, runId) to email
            }
        }
        val report = TrialReport.write(runDir, info, results)
        println()
        for ((r, _) in results) {
            val counts = r.outcomes().groupingBy { it.second.kind }.eachCount()
            println(
                "  ${r.trial.name}/${r.variant.name}: ${counts[OutcomeKind.met] ?: 0} met, ${counts[OutcomeKind.varied] ?: 0} varied, " +
                    "${counts[OutcomeKind.notMet] ?: 0} not met; ${r.weighted} weighted tokens, about " +
                    "${TrialReport.cost(r.weighted, r.model) ?: "?"}" + if (r.problems.isEmpty()) "" else "; ${r.problems.size} problem(s)",
            )
        }
        println()
        println("Report: ${report.absolutePath}")
        node?.let {
            println("The node is still running at ${it.url}/wa. Stop it with ./gradlew :assistantTrials:stopTrials -Pport=${it.port}")
        }
    }

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

/** `run …`, `stop …`, or `restart …`; see [TrialRunner]. */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val options = args.drop(1).chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
    try {
        when (command) {
            "run" -> TrialRunner.run(options)
            "stop" -> TrialRunner.stop(options)
            "restart" -> TrialRunner.restart(options)
            else -> throw IllegalArgumentException("Usage: run --trials <names> | stop [--port <port>|all] | restart --run <runId>")
        }
    } catch (e: Exception) {
        System.err.println("Assistant trials: ${e.message ?: e::class.simpleName}")
        exitProcess(1)
    }
    exitProcess(0)
}
