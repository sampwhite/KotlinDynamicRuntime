package com.dynamicruntime.trials

import com.dynamicruntime.common.context.AppPaths
import java.io.File
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/**
 * A node a run started (issue #1859), as its run directory records it: enough to find it again, say what it holds, and
 * stop it, after the harness that started it has gone.
 */
class TrialNode(
    val runId: String,
    val port: Int,
    val pid: Long,
    val dbName: String,
    val log: File,
    /** The environment it was started with beyond what every trial node has: the model, its effort, the budget. */
    val settings: Map<String, String>,
) {
    val url: String get() = "http://localhost:$port"

    /** The process, while it runs. */
    val process: ProcessHandle? get() = ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive }

    val alive: Boolean get() = process != null

    fun record(runDir: File) {
        val props = Properties().apply {
            setProperty(runIdKey, runId)
            setProperty(portKey, port.toString())
            setProperty(pidKey, pid.toString())
            setProperty(dbKey, dbName)
            setProperty(logKey, log.path)
            settings.forEach { (k, v) -> setProperty(settingPrefix + k, v) }
        }
        File(runDir, fileName).outputStream().use {
            props.store(it, "A trial node (issue #1859): stop it with :assistantTrials:stopTrials.")
        }
    }

    /**
     * Stops it: gracefully first, so the node closes its database, then outright if it has not gone within [wait].
     * Answers whether it stopped gracefully.
     */
    fun stop(wait: Duration = Duration.ofSeconds(30)): Boolean {
        val p = process ?: return true
        p.destroy()
        val graceful = runCatching { p.onExit().get(wait.toMillis(), TimeUnit.MILLISECONDS) }.isSuccess
        if (!graceful) p.destroyForcibly()
        return graceful
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val fileName = "node.properties"
        private const val runIdKey = "runId"
        private const val portKey = "port"
        private const val pidKey = "pid"
        private const val dbKey = "db"
        private const val logKey = "log"
        private const val settingPrefix = "setting."

        /** The run directory's copy of the node's class path, and the file listing it, in order. */
        private const val libDir = "lib"
        private const val classPathFile = "classpath.txt"

        /** The ports trial nodes take (issue #1859): reserved for them, clear of every workspace's own. */
        val ports: IntRange = 7076..7079

        /** The node [runDir] records, or null when it records none. */
        fun read(runDir: File): TrialNode? {
            val file = File(runDir, fileName).takeIf { it.isFile } ?: return null
            val props = Properties().apply { file.inputStream().use { load(it) } }
            val settings = props.stringPropertyNames().filter { it.startsWith(settingPrefix) }
                .associate { it.removePrefix(settingPrefix) to props.getProperty(it) }
            return TrialNode(
                props.getProperty(runIdKey), props.getProperty(portKey).toInt(), props.getProperty(pidKey).toLong(),
                props.getProperty(dbKey), File(props.getProperty(logKey)), settings,
            )
        }

        /** Every node recorded under [runsDir] that is still running, newest first. */
        fun running(runsDir: File): List<TrialNode> =
            runsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.mapNotNull { read(it) }?.filter { it.alive }
                ?: emptyList()

        fun portInUse(port: Int): Boolean = runCatching { Socket("localhost", port).use { true } }.getOrDefault(false)

        /**
         * Starts a node for run [runId] on the first free port of [ports], its data in an H2 file of its own, with the
         * assistant on and offered to self-serve clients. [settings] are further environment variables (the model, its
         * effort, the budget). The node's output goes to a log in [runDir], and it outlives the harness.
         */
        fun start(runId: String, runDir: File, launchJar: File, settings: Map<String, String>): TrialNode {
            val port = ports.firstOrNull { !portInUse(it) } ?: throw IllegalStateException(
                "Every trial port (${ports.first}-${ports.last}) is in use. Stop a trial node you are done with: " +
                    "./gradlew :assistantTrials:stopTrials lists them.",
            )
            runDir.mkdirs()
            return launch(runId, runDir, classPath(runDir, launchJar), port, "trial" + runId.filter { it.isLetterOrDigit() }, settings)
        }

        /**
         * Starts the node [runDir] records again, on its port and its database, with its settings and its copy of the class
         * path, so it comes back as the run left it. One still running is stopped first. A run from before the copy was
         * taken gets one now, from [launchJar].
         */
        fun restart(runDir: File, launchJar: File): TrialNode {
            val was = read(runDir) ?: throw IllegalStateException("$runDir records no trial node.")
            if (was.alive) was.stop()
            if (portInUse(was.port)) {
                throw IllegalStateException("Port ${was.port} is in use by something else; the node cannot come back on it.")
            }
            return launch(was.runId, runDir, classPath(runDir, launchJar), was.port, was.dbName, was.settings)
        }

        /**
         * The node's class path, from the run's own copy, taken the first time it is asked for. The launch pathing jar names
         * the project's jars where the build writes them, and a node runs for hours while builds go on, reading those jars
         * as it serves: a build that rewrites one under it fails its pages with "invalid LOC header". So the jars under the
         * workspace are copied into [runDir]. The rest are Gradle's cached downloads, whose paths name their content, so a
         * build never rewrites one.
         */
        private fun classPath(runDir: File, launchJar: File): List<File> {
            val listed = File(runDir, classPathFile)
            if (listed.isFile) return listed.readLines().filter { it.isNotBlank() }.map { File(it) }
            if (!launchJar.isFile) throw IllegalStateException("No launch pathing jar at $launchJar; build :launch:pathingJar.")
            val entries = JarFile(launchJar).use { it.manifest.mainAttributes.getValue("Class-Path") }
                ?.split(' ')?.filter { it.isNotBlank() }?.map { File(URI(it)) }
                ?: throw IllegalStateException("$launchJar names no class path.")
            val workspace = AppPaths.workspaceDir().absoluteFile.canonicalPath + File.separator
            val lib = File(runDir, libDir).apply { mkdirs() }
            val copied = entries.mapIndexed { i, jar ->
                if (!jar.canonicalPath.startsWith(workspace)) {
                    jar
                } else {
                    // Numbered, so two jars of one name keep their order and both survive.
                    jar.copyTo(File(lib, "%02d-%s".format(i, jar.name)), overwrite = true)
                }
            }
            listed.writeText(copied.joinToString("\n", postfix = "\n") { it.path })
            return copied
        }

        private fun launch(
            runId: String,
            runDir: File,
            classPath: List<File>,
            port: Int,
            dbName: String,
            settings: Map<String, String>,
        ): TrialNode {
            val log = File(runDir, "node.log")
            val java = File(System.getProperty("java.home"), "bin/java").path
            val workspace = AppPaths.workspaceDir().absoluteFile
            val pb = ProcessBuilder(java, "-cp", classPath.joinToString(File.pathSeparator) { it.path }, "kdn.StartKt")
                .directory(workspace)
                .redirectErrorStream(true)
                // A restart adds to the log rather than losing what the run's node said.
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            val env = pb.environment()
            env["KDR_DEFAULTS_FILE"] = "none"
            env["KDR_PORT"] = port.toString()
            env["KDR_IN_MEMORY_ONLY"] = "false"
            env["KDR_DB_TYPE"] = "h2File"
            env["KDR_DB_NAME"] = dbName
            env["KDR_TEST_INSTANCE"] = "true"
            env["KDR_WORKSPACE_DIR"] = workspace.path
            env["KDR_ASSISTANT_SELF_SERVE"] = "true"
            // A trial sends several messages in a few minutes, and one person sends every one of a trial's.
            env["KDR_ASSISTANT_TURNS_PER_HOUR"] = "200"
            env.putAll(settings)
            val node = TrialNode(runId, port, pb.start().pid(), dbName, log, settings)
            node.record(runDir)
            return node
        }

        private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

        fun healthy(url: String): Boolean = runCatching {
            val req = HttpRequest.newBuilder(URI("$url/kda/health")).timeout(Duration.ofSeconds(2)).GET().build()
            http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        }.getOrDefault(false)

        /** Waits for [node] to answer its health check; throws, naming its log, when it exits or does not come up. */
        fun awaitHealthy(node: TrialNode, timeout: Duration = Duration.ofSeconds(180)) {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (!healthy(node.url)) {
                if (!node.alive) throw IllegalStateException("The node exited while starting; see ${node.log}.")
                if (System.nanoTime() > deadline) {
                    node.stop()
                    throw IllegalStateException("The node did not come up within $timeout, and was stopped; see ${node.log}.")
                }
                Thread.sleep(1000)
            }
        }
    }
}
