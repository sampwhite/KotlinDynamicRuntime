package com.dynamicruntime.multinode

import com.dynamicruntime.common.context.AppPaths
import java.io.File
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * One backend the harness drives: a URL, and -- when the harness started it -- the process, which a scenario may
 * stop gracefully or kill outright.
 */
class HarnessNode(val url: String, private val process: Process? = null, val log: File? = null) {
    val label: String get() = url.removePrefix("http://")

    /** The port the node listens on, which is also how its own `ip:port` label is recognized in the trace. */
    val port: Int get() = url.substringAfterLast(':').toInt()

    /** Whether the harness owns this node's process, and so may stop or kill it. */
    val stoppable: Boolean get() = process != null

    val alive: Boolean get() = process?.isAlive ?: true

    /**
     * A graceful stop (SIGTERM): the JVM's shutdown hooks run, so a job in progress lets its rows go. Answers
     * whether it stopped gracefully within [wait]; a node that did not is killed, so it is gone either way and
     * never outlives the harness holding its port.
     */
    fun stop(wait: Duration = Duration.ofSeconds(20)): Boolean {
        val p = process ?: return false
        p.destroy()
        if (p.waitFor(wait.toMillis(), TimeUnit.MILLISECONDS)) return true
        kill()
        return false
    }

    /** A crash (SIGKILL): nothing runs, so a job's leases are left to lapse. */
    fun kill() {
        process?.destroyForcibly()?.waitFor(10, TimeUnit.SECONDS)
    }
}

/** What one scenario found: failures, or why it was skipped. */
class ScenarioResult(val name: String, val failures: List<String>, val skipped: String? = null) {
    val passed: Boolean get() = skipped == null && failures.isEmpty()
}

/**
 * The multi-node harness (issue #872): runs scenario suites against several backends sharing one Postgres
 * database, for behavior that only shows with several processes running at once.
 *
 * Two ways to run, both through `./gradlew :multiNodeTest:harness`:
 * - **Start** (the default): clear [MultiNodeDb.dbName], start three backends on [ports] with the multi-node
 *   component switched on, run the suites, and stop the backends. The first node starts alone, so one node
 *   creates the tables rather than three racing to. A port already in use stops the run before anything starts:
 *   the harness never takes over, or stops, a server it did not start.
 * - **Attach** (`-Pattach=<url>,<url>`): run against nodes somebody started by hand -- in IntelliJ, with breakpoints
 *   -- on the same database with the component on. The database is left alone, and a scenario that must stop or
 *   kill a node is skipped.
 */
object MultiNodeHarness {
    /** The ports the harness starts backends on: never 7070 (the developer's) or 7072 (kd3's agent server). */
    val ports = listOf(7071, 7073, 7074)

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun startNodes(launchJar: File, logDir: File): List<HarnessNode> {
        val busy = ports.filter { portInUse(it) }
        if (busy.isNotEmpty()) {
            throw IllegalStateException(
                "Port(s) ${busy.joinToString(", ")} already in use; stop whatever holds them, or use -Pattach.",
            )
        }
        if (!launchJar.isFile) throw IllegalStateException("No launch pathing jar at $launchJar; build :launch:pathingJar.")
        MultiNodeDb.reset()
        logDir.mkdirs()
        // Each node is kept as soon as its process exists, so a failure anywhere below stops every node started so
        // far rather than leaving it running -- holding its port -- after the harness has gone.
        val started = mutableListOf<HarnessNode>()
        try {
            started.add(startNode(ports.first(), launchJar, logDir))
            awaitHealthy(started.toList())
            for (port in ports.drop(1)) started.add(startNode(port, launchJar, logDir))
            awaitHealthy(started.drop(1))
        } catch (e: Exception) {
            started.forEach { it.stop() }
            throw e
        }
        return started
    }

    private fun startNode(port: Int, launchJar: File, logDir: File): HarnessNode {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val log = File(logDir, "node-$port.log")
        val pb = ProcessBuilder(java, "-cp", launchJar.path, "kdn.StartKt")
            .redirectErrorStream(true)
            .redirectOutput(log)
        val env = pb.environment()
        env["KDR_DEFAULTS_FILE"] = "none"
        env["KDR_PORT"] = port.toString()
        env["KDR_IN_MEMORY_ONLY"] = "false"
        env["KDR_DB_TYPE"] = "postgres"
        env["KDR_DB_NAME"] = MultiNodeDb.dbName
        env["KDR_TEST_INSTANCE"] = "true"
        env[MultiNodeTestComponent.enabledVar.name] = "true"
        // The workspace, for the secrets file the Postgres password is read from.
        env["KDR_WORKSPACE_DIR"] = AppPaths.workspaceDir().absolutePath
        return HarnessNode("http://localhost:$port", pb.start(), log)
    }

    private fun awaitHealthy(nodes: List<HarnessNode>, timeout: Duration = Duration.ofSeconds(120)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        for (node in nodes) {
            while (!healthy(node)) {
                if (!node.alive) throw IllegalStateException("Node ${node.label} exited while starting; see ${node.log}.")
                if (System.nanoTime() > deadline) throw IllegalStateException("Node ${node.label} did not come up; see ${node.log}.")
                Thread.sleep(500)
            }
        }
    }

    private fun healthy(node: HarnessNode): Boolean = runCatching {
        val req = HttpRequest.newBuilder(URI("${node.url}/kda/health")).timeout(Duration.ofSeconds(2)).GET().build()
        http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    private fun portInUse(port: Int): Boolean = runCatching { Socket("localhost", port).use { true } }.getOrDefault(false)

    /** Runs the named suite against [nodes]; answers the scenario results. */
    fun runSuite(suite: String, nodes: List<HarnessNode>): List<ScenarioResult> = when (suite) {
        "jobs" -> JobSuite(nodes).run()
        else -> throw IllegalArgumentException("No suite '$suite'. Suites: jobs.")
    }
}

/** `harness [--attach <url>,<url>] [--suite jobs]`. */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val attach = arg("--attach")?.split(',')?.map { it.trim().trimEnd('/') }?.filter { it.isNotEmpty() }
    val suite = arg("--suite") ?: "jobs"
    val started = mutableListOf<HarnessNode>()
    val results = try {
        val nodes = if (attach != null) {
            attach.map { HarnessNode(it) }
        } else {
            val jar = File(System.getProperty("kdr.multiNode.launchJar") ?: error("kdr.multiNode.launchJar is not set."))
            val logDir = File(System.getProperty("kdr.multiNode.logDir") ?: "build/multiNode")
            MultiNodeHarness.startNodes(jar, logDir).also { started.addAll(it) }
        }
        println("Multi-node harness: suite '$suite' against ${nodes.joinToString(", ") { it.label }}")
        MultiNodeHarness.runSuite(suite, nodes)
    } finally {
        started.filter { it.alive }.forEach { it.stop() }
    }
    for (r in results) {
        val verdict = when {
            r.skipped != null -> "SKIP (${r.skipped})"
            r.passed -> "PASS"
            else -> "FAIL"
        }
        println("  $verdict  ${r.name}")
        r.failures.forEach { println("        - $it") }
    }
    val failed = results.count { it.skipped == null && !it.passed }
    println(if (failed == 0) "Multi-node harness: all scenarios passed." else "Multi-node harness: $failed scenario(s) FAILED.")
    exitProcess(if (failed == 0) 0 else 1)
}
