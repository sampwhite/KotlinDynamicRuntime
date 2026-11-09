package com.dynamicruntime.script.deploy

import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.startup.BootStage
import com.dynamicruntime.script.ClearFileStore
import com.dynamicruntime.script.Subprocess.capture
import com.dynamicruntime.script.bootScriptCxt
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

/**
 * Resets a lightweight node's data, **run on the node itself** with the deployed release's own Java (issue #1722), as
 * [ClearFileStore] already is:
 *
 * ```sh
 * java -cp <baseDir>/current/lib/'*' com.dynamicruntime.script.deploy.NodeResetKt <check|now> \
 *     --base-dir <baseDir> --service <service> --port <port>
 * ```
 *
 * A reset stops the service, empties the file store (keeping code-defined clients' web assets), removes the database,
 * records when it ran in a marker ([NRST.markerFile] under the base directory), starts the service, and waits until it
 * answers on the node. A failure after the stop starts the service again rather than leaving the site down.
 *
 * **`now`** resets at once: what `kdr-node reset-data` and `kdr-deploy --reset-data` run over ssh, after asking.
 *
 * **`check`** resets only when the data is a calendar month old ([decide]): what the daily timer a deploy installs on a
 * reference node runs (`resetDataMonthly`, [ResetUnits]). It is how dynamicruntime.org keeps its privacy policy's
 * promise to wipe the site every month. The age is the marker's, never a file's timestamps, which Linux does not keep
 * reliably; every reset, however it was made, writes the marker, so a deploy's reset restarts the clock and a site is
 * never wiped twice in a few days. With no marker yet, a check records one and wipes nothing, so installing the timer
 * cannot wipe a site at once. Checking boots nothing; only a reset does.
 *
 * Everything runs as the node's user, with no `sudo`: the service is a systemd user service, stopped and started with
 * `systemctl --user`.
 */
@Suppress("ConstPropertyName")
object NodeReset {
    /** The modes and flags the program accepts. */
    @Suppress("ConstPropertyName")
    object NRST {
        const val check = "check"
        const val now = "now"
        const val baseDir = "--base-dir"
        const val service = "--service"
        const val port = "--port"

        /** The marker a reset writes under the base directory: when it ran, as an ISO-8601 instant on one line. */
        const val markerFile = "data-reset"

        /** The class the node runs. */
        const val mainClass = "com.dynamicruntime.script.deploy.NodeResetKt"

        /** The heap the program is given, small beside the node's own server on a node of about 1 GB. */
        const val heap = "-Xmx160m"
    }

    const val failureExit = 1

    /** How long a reset waits for the service to answer after starting it. */
    const val healthSeconds = 120

    /** What a check decided, from the last reset and the time it ran. */
    sealed interface Decision {
        /** No reset is recorded yet (or the marker does not read): record one now, and wipe nothing. */
        data object Record : Decision

        /** The data is a calendar month old: reset it. */
        data class Due(val since: Instant) : Decision

        /** Not yet: the next reset is due at [at]. */
        data class NotDue(val at: Instant) : Decision
    }

    /** Everything a run needs, once the arguments have been read. */
    data class Options(val mode: String, val baseDir: File, val service: String, val port: Int)

    // --- the pure parts --------------------------------------------------------------------------------------------

    /**
     * When the reset after one made at [last] is due: a calendar month later, in UTC, so January 31 is followed by the
     * last day of February rather than by early March.
     */
    fun nextReset(last: Instant): Instant = last.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()

    /** What a check at [now] decides, given the [last] reset recorded, or null for none. */
    fun decide(last: Instant?, now: Instant): Decision = when {
        last == null -> Decision.Record
        now >= nextReset(last) -> Decision.Due(last)
        else -> Decision.NotDue(nextReset(last))
    }

    /** The instant a marker's [text] records, or null when it records none that reads. */
    fun parseMarker(text: String?): Instant? = text?.trim()?.takeIf { it.isNotEmpty() }?.let {
        try {
            Instant.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** The options [args] give, or null when they are not ones the program accepts. */
    fun parseArgs(args: List<String>): Options? {
        var mode: String? = null
        var baseDir: String? = null
        var service: String? = null
        var port: Int? = null
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                NRST.baseDir -> baseDir = args.getOrNull(++i) ?: return null
                NRST.service -> service = args.getOrNull(++i) ?: return null
                NRST.port -> port = args.getOrNull(++i)?.toIntOrNull() ?: return null
                NRST.check, NRST.now -> if (mode != null) return null else mode = arg
                else -> return null
            }
            i++
        }
        if (baseDir == null || !baseDir.startsWith("/")) return null
        return Options(mode ?: return null, File(baseDir), service ?: return null, port ?: return null)
    }

    // --- the reset, over what it acts on ---------------------------------------------------------------------------

    /** What a reset acts on, apart from the files under the base directory: a real node's ([SystemOps]), or a test's. */
    interface Ops {
        fun stop(): Boolean
        fun start(): Boolean

        /** Empties the file store, keeping code-defined clients' web assets; whether it did. */
        fun clearFileStore(): Boolean

        /** Waits for the service to answer as healthy on the node; whether it did. */
        fun healthy(): Boolean
    }

    /**
     * Resets the data under [baseDir] with [ops] at [now], saying each step to [out]; whether the service came back
     * healthy on emptied data. A failure after the stop starts the service again, on what was left, and says so.
     */
    fun reset(baseDir: File, ops: Ops, now: Instant, out: (String) -> Unit): Boolean {
        out("Stopping the service.")
        if (!ops.stop()) {
            out("Stopping the service failed; nothing was removed.")
            return false
        }
        fun restartAfter(problem: String): Boolean {
            out("$problem; starting the service again on what is left.")
            ops.start()
            return false
        }
        out("Emptying the file store.")
        if (!ops.clearFileStore()) return restartAfter("Emptying the file store failed, and the database was kept")
        val database = File(baseDir, Node.databaseDir)
        if (database.exists() && !database.deleteRecursively()) return restartAfter("Removing $database failed")
        out("Removed the database.")
        writeMarker(baseDir, now)
        out("Recorded the reset at $now.")
        out("Starting the service.")
        if (!ops.start()) {
            out("Starting the service failed.")
            return false
        }
        if (!ops.healthy()) {
            out("The service started but did not answer as healthy within $healthSeconds seconds.")
            return false
        }
        out("Reset; the next is due ${nextReset(now)}.")
        return true
    }

    /** The marker under [baseDir], recording a reset at [at], written whole or not at all. */
    fun writeMarker(baseDir: File, at: Instant) {
        val marker = File(baseDir, NRST.markerFile)
        val next = File(baseDir, "${NRST.markerFile}.new")
        next.writeText("$at\n")
        if (!next.renameTo(marker)) {
            marker.writeText("$at\n")
            next.delete()
        }
    }

    /** The last reset recorded under [baseDir], or null for none. */
    fun lastReset(baseDir: File): Instant? = File(baseDir, NRST.markerFile).takeIf { it.isFile }?.let { parseMarker(it.readText()) }

    /** A check under [baseDir] at [now]: what it decided, said to [out], and the reset when one is due; whether all went. */
    fun check(baseDir: File, ops: Ops, now: Instant, out: (String) -> Unit): Boolean =
        when (val decision = decide(lastReset(baseDir), now)) {
            Decision.Record -> {
                writeMarker(baseDir, now)
                out("No reset was recorded; recorded one now, wiping nothing. The first reset is due ${nextReset(now)}.")
                true
            }
            is Decision.NotDue -> {
                out("Not due until ${decision.at} (the last reset was ${lastReset(baseDir)}).")
                true
            }
            is Decision.Due -> {
                out("Due: the last reset was ${decision.since}, a month ago or more.")
                reset(baseDir, ops, now, out)
            }
        }

    // --- running it on the node ------------------------------------------------------------------------------------

    /** A node's [Ops]: its systemd user service, its own file store, and its health endpoint. */
    class SystemOps(private val options: Options) : Ops {
        private fun systemctl(verb: String): Boolean {
            val result = capture(null, "systemctl", "--user", verb, options.service)
            if (result.failed) println("systemctl --user $verb ${options.service}: ${result.out}")
            return !result.failed
        }

        override fun stop(): Boolean = systemctl("stop")

        override fun start(): Boolean = systemctl("start")

        override fun clearFileStore(): Boolean {
            val cxt = bootScriptCxt("nodeReset", BootStage.collected, setOf(FileStoreService.serviceName))
            return ClearFileStore.runOn(cxt, remove = true) { println("  $it") } == 0
        }

        override fun healthy(): Boolean {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${options.port}/kda/health"))
                .timeout(Duration.ofSeconds(5)).GET().build()
            val until = System.nanoTime() + healthSeconds * 1_000_000_000L
            while (System.nanoTime() < until) {
                val up = runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 }
                    .getOrDefault(false)
                if (up) return true
                Thread.sleep(2000)
            }
            return false
        }
    }

    fun run(args: List<String>): Int {
        val options = parseArgs(args) ?: return usage()
        if (!options.baseDir.isDirectory) return fail("No base directory ${options.baseDir}.")
        val ops = SystemOps(options)
        // To the second: the marker and what is said of it are read by people.
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val ok = when (options.mode) {
            NRST.now -> reset(options.baseDir, ops, now) { println(it) }
            else -> check(options.baseDir, ops, now) { println(it) }
        }
        return if (ok) 0 else failureExit
    }

    private fun usage(): Int = fail(
        "Usage: ${NRST.mainClass} <${NRST.check}|${NRST.now}> ${NRST.baseDir} <absolute dir> ${NRST.service} <name> ${NRST.port} <port>",
    )

    private fun fail(message: String): Int {
        System.err.println("NodeReset: $message")
        return failureExit
    }
}

fun main(args: Array<String>) {
    exitProcess(NodeReset.run(args.toList()))
}
