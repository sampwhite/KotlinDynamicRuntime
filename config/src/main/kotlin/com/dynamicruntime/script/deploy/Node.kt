package com.dynamicruntime.script.deploy

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.operator.OSI
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.util.jsonMapOrNull
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.script.deploy.Deploy.shellQuote
import com.dynamicruntime.script.deploy.NodeShell.confirm
import com.dynamicruntime.script.deploy.NodeShell.healthy
import com.dynamicruntime.script.deploy.NodeShell.ssh
import com.dynamicruntime.script.deploy.NodeShell.sshToTerminal
import java.io.File
import kotlin.system.exitProcess

/**
 * Looks at a lightweight node and does the few operational things it needs, from the developer's machine (issue
 * #1244):
 *
 * ```sh
 * kdr-node <target> status
 * kdr-node <target> logs [--lines N] [--follow]
 * kdr-node <target> restart [--yes]
 * kdr-node <target> reset-data [--dry-run] [--yes]
 * ```
 *
 * The node is one `docs/deploy/lightweight-node.md` set up, and the same [DeployTarget] as `kdr-deploy` describes it.
 * Everything is done over the operator's own SSH ([NodeShell]), as the node's user, with no `sudo`.
 *
 * **`status` reads the node's own view with no session.** Its operator endpoints are read with `curl` on the node,
 * which makes each a direct request (issue #1255): the operator section's GETs need no login there, under the default
 * `KDR_DIRECT_ACCESS=loopback`.
 *
 * **`reset-data` is the periodic clearing of a reference deployment's data**, and destructive, so it shows what it
 * would remove and asks first. It stops the service, empties the file store ([ClearFileStore][com.dynamicruntime.script.ClearFileStore],
 * run on the node from the deployed release, so nothing is installed there), removes the database, and starts the
 * service, which a `reference` node provisions the conference demo in again as it starts.
 */
@Suppress("ConstPropertyName")
object Node {
    /** Flags and subcommands the command accepts. */
    @Suppress("ConstPropertyName")
    object NDF {
        const val status = "status"
        const val logs = "logs"
        const val restart = "restart"
        const val resetData = "reset-data"
        val subcommands = listOf(status, logs, restart, resetData)

        const val dryRun = "--dry-run"
        const val yes = "--yes"
        const val lines = "--lines"
        const val follow = "--follow"
        const val repo = "--repo"
        const val cwd = "--cwd"
    }

    const val failureExit = 1
    const val defaultLogLines = 100

    /** The node's database directory under its base directory, as the guide sets it up (`KDR_DB_TYPE=h2File`). */
    const val databaseDir = "h2Database"

    /** The class `reset-data` runs on the node to empty the file store. */
    const val clearFileStoreMain = "com.dynamicruntime.script.ClearFileStoreKt"

    /** The heap the store-emptying run is given, small beside the node's own server on a node of about 1 GB. */
    const val clearFileStoreHeap = "-Xmx160m"

    /** Everything a run needs, once the arguments have been read. */
    data class Options(
        val target: String,
        val subcommand: String,
        val repoDir: File,
        val callerDir: File,
        val dryRun: Boolean,
        val assumeYes: Boolean,
        val lines: Int,
        val follow: Boolean,
    )

    // --- the pure parts: what is run on the node, and what is made of its answers -------------------------------

    /** Section markers in the one SSH command `status` runs, so its output divides by what produced it. */
    @Suppress("ConstPropertyName")
    object SEC {
        const val service = "service"
        const val releases = "releases"
        const val memory = "memory"
        const val disk = "disk"
        const val health = "health"
        const val flavors = "flavors"
        const val boot = "boot"
        const val system = "system"
        fun marker(name: String): String = "--kdr:$name--"
    }

    /** A `curl` of one of the application's own GET paths on the node, as a direct request. */
    fun nodeGet(target: DeployTarget, path: String): String =
        "curl -s --max-time 10 ${shellQuote("http://127.0.0.1:${target.port}/kda$path")}"

    /** The one command `status` runs: each section's marker, then what the node says of it. */
    fun statusCommand(target: DeployTarget): String = listOf(
        SEC.service to "systemctl --user show ${shellQuote(target.service)} -p ActiveState -p SubState " +
            "-p ActiveEnterTimestamp -p NRestarts -p Result",
        SEC.releases to "test -d ${shellQuote(target.releasesDir)} && { ${Deploy.listCommand(target)}; }",
        SEC.memory to "free -m",
        SEC.disk to "df -h ${shellQuote(target.baseDir)}",
        SEC.health to Deploy.nodeHealthCommand(target),
        SEC.flavors to nodeGet(target, FLV.operatorPath),
        SEC.boot to nodeGet(target, OPS.bootChecksPath),
        SEC.system to nodeGet(target, OPS.systemInfoPath),
    // Each marker on a line of its own: what came before may end without a newline (`curl` prints none), and a
    // marker run on to the end of it would be read as part of it.
    ).joinToString("; ") { (name, command) -> "echo; echo ${SEC.marker(name)}; $command" }

    /** [output] of [statusCommand], divided into its sections by name. */
    fun sections(output: String): Map<String, String> {
        val found = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in output.lines()) {
            val name = Regex("^--kdr:([a-z]+)--$").find(line.trim())?.groupValues?.get(1)
            if (name != null) {
                current = StringBuilder().also { found[name] = it }
            } else {
                current?.append(line)?.append('\n')
            }
        }
        return found.mapValues { it.value.toString().trim() }
    }

    /** The status report, made of the [sections] the node answered: one line per thing to know. */
    fun statusReport(target: DeployTarget, sections: Map<String, String>): List<String> {
        val out = mutableListOf("Node ${target.sshDestination} (${target.publicUrl})")
        val service = sections[SEC.service].orEmpty().lines().mapNotNull { line ->
            line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
        }.toMap()
        out += "  service:  " + if (service.isEmpty()) "unknown" else {
            "${service["ActiveState"]} (${service["SubState"]}) since ${service["ActiveEnterTimestamp"].orEmpty().ifEmpty { "-" }}, " +
                "${service["NRestarts"] ?: "?"} restart(s), last result ${service["Result"] ?: "?"}"
        }
        val (releases, current) = Deploy.parseListing(sections[SEC.releases].orEmpty())
        out += "  release:  ${current ?: "none"}   (kept: ${releases.joinToString(", ").ifEmpty { "none" }})"
        out += "  health:   ${sections[SEC.health].orEmpty().ifEmpty { "no answer" }}"
        out += "  memory:   " + memoryLine(sections[SEC.memory].orEmpty())
        out += "  disk:     " + diskLine(sections[SEC.disk].orEmpty())
        out += "  jvm:      " + jvmLine(sections[SEC.system].orEmpty())
        out += "  flavors:  " + flavorsLine(sections[SEC.flavors].orEmpty())
        out += bootLines(sections[SEC.boot].orEmpty())
        return out
    }

    /** `free -m`'s memory and swap rows, as used of total. */
    private fun memoryLine(free: String): String {
        fun row(label: String) = free.lines().firstOrNull { it.startsWith(label) }?.trim()?.split(Regex("\\s+"))
        val mem = row("Mem:") ?: return "unknown"
        val swap = row("Swap:")
        val avail = mem.getOrNull(6)?.let { ", $it MB available" }.orEmpty()
        val swapText = swap?.let { ", swap ${it.getOrNull(2)} of ${it.getOrNull(1)} MB used" }.orEmpty()
        return "${mem.getOrNull(2)} of ${mem.getOrNull(1)} MB used$avail$swapText"
    }

    /** `df -h`'s row for the base directory's filesystem. */
    private fun diskLine(df: String): String {
        val row = df.lines().drop(1).firstOrNull { it.isNotBlank() }?.trim()?.split(Regex("\\s+")) ?: return "unknown"
        return "${row.getOrNull(2)} of ${row.getOrNull(1)} used, ${row.getOrNull(3)} free"
    }

    /** The JVM's memory and uptime, from `/operator/system/info`. */
    private fun jvmLine(json: String): String {
        val results = json.jsonMapOrNull()?.get(EP.results)?.toJsonMapOrEmpty() ?: return unreadable(json)
        val memory = results[OSI.memory].toJsonMapOrEmpty()
        val node = results[OSI.node].toJsonMapOrEmpty()
        return "heap ${memory[OSI.heapUsed].toOptStr()} of ${memory[OSI.heapMax].toOptStr()}, non-heap " +
            "${memory[OSI.nonHeapUsed].toOptStr()}, up ${node[OSI.uptimeText].toOptStr()}"
    }

    /** The active deploy flavors, from `/operator/flavors`. */
    private fun flavorsLine(json: String): String {
        val results = json.jsonMapOrNull()?.get(EP.results)?.toJsonMapOrEmpty() ?: return unreadable(json)
        val active = (results[FLV.active] as? List<*>).orEmpty().map { it.toString() }
        return active.joinToString(", ").ifEmpty { "none" }
    }

    /** The boot checks, from `/operator/boot/checks`: one line, and a line per finding of any check that found one. */
    private fun bootLines(json: String): List<String> {
        val items = json.jsonMapOrNull()?.get(EP.items)?.toJsonListOfMaps() ?: return listOf("  boot:     ${unreadable(json)}")
        val findings = items.flatMap { check ->
            (check[BCHK.findings] as? List<*>).orEmpty().map { "    ${check[BCHK.name]}: $it" }
        }
        val summary = "  boot:     ${items.size} check(s), " +
            if (findings.isEmpty()) "nothing found" else "${findings.size} finding(s):"
        return listOf(summary) + findings
    }

    /** What to say of an operator answer that is not the JSON expected: its status, when it is an error envelope. */
    private fun unreadable(json: String): String {
        val status = json.jsonMapOrNull()?.get(EP.status)?.toString()
        return when {
            json.isBlank() -> "no answer (is the service running?)"
            status != null -> "unavailable (status $status)"
            else -> "unreadable"
        }
    }

    /** The journal's last [lines] lines for the service, or a follow of it. */
    fun logsCommand(target: DeployTarget, lines: Int, follow: Boolean): String =
        "journalctl --user -u ${shellQuote(target.service)} -n $lines --no-pager" + if (follow) " -f" else ""

    /** Restarts the service. */
    fun restartCommand(target: DeployTarget): String = "systemctl --user restart ${shellQuote(target.service)}"

    fun stopCommand(target: DeployTarget): String = "systemctl --user stop ${shellQuote(target.service)}"

    fun startCommand(target: DeployTarget): String = "systemctl --user start ${shellQuote(target.service)}"

    /**
     * Runs [ClearFileStore][com.dynamicruntime.script.ClearFileStore] on the node from the deployed release, under the
     * node's own settings (`kdr.env`, and its `JAVA_HOME`), reporting only unless [remove]. Logged at `warn`, so its
     * answer is not lost among startup lines.
     */
    fun clearFileStoreCommand(target: DeployTarget, remove: Boolean): String =
        "set -a; . ${shellQuote(target.envFile)}; set +a; KDR_LOG_LEVEL=warn " +
                $$"\"${JAVA_HOME:+$JAVA_HOME/bin/}java\" $$clearFileStoreHeap -cp $${shellQuote("${target.currentLink}/lib/*")} " +
            clearFileStoreMain + if (remove) " --remove" else ""

    /** The node's database directory. */
    fun databasePath(target: DeployTarget): String = "${target.baseDir}/$databaseDir"

    /** The size of the database directory, or a word for its absence. */
    fun databaseSizeCommand(target: DeployTarget): String =
        "du -sh ${shellQuote(databasePath(target))} 2>/dev/null | cut -f1 || true"

    fun removeDatabaseCommand(target: DeployTarget): String = "rm -rf -- ${shellQuote(databasePath(target))}"

    // --- running it ---------------------------------------------------------------------------------------------

    fun run(args: List<String>): Int {
        val options = parseArgs(args) ?: return usage()
        val target = try {
            DeployTarget.load(options.repoDir, options.target, options.callerDir)
        } catch (e: IllegalArgumentException) {
            return fail(e.message.orEmpty())
        }
        return when (options.subcommand) {
            NDF.status -> status(target)
            NDF.logs -> if (sshToTerminal(target, logsCommand(target, options.lines, options.follow))) 0 else fail("Reading the log failed.")
            NDF.restart -> restart(options, target)
            NDF.resetData -> resetData(options, target)
            else -> usage()
        }
    }

    private fun status(target: DeployTarget): Int {
        val result = ssh(target, statusCommand(target))
        if (result.out.isBlank()) return fail("The node did not answer: ${result.out}")
        statusReport(target, sections(result.out)).forEach { println(it) }
        return 0
    }

    private fun restart(options: Options, target: DeployTarget): Int {
        if (!options.assumeYes && !confirm("Restart ${target.service} on ${target.host}?")) return fail("Not restarted.")
        if (ssh(target, restartCommand(target)).failed) return fail("The restart failed.")
        return if (healthy(target)) 0 else fail("The service restarted but did not answer as healthy; see kdr-node ${options.target} logs.")
    }

    private fun resetData(options: Options, target: DeployTarget): Int {
        // What there is to remove, asked of the node while it still runs: the store-emptying run reaches no database
        // (issue #1289), so it does not disturb the server.
        val report = ssh(target, clearFileStoreCommand(target, remove = false))
        val dbSize = ssh(target, databaseSizeCommand(target)).out.ifEmpty { "absent" }
        println("Reset the data of ${target.sshDestination} (${target.publicUrl})")
        println("  stop:      ${stopCommand(target)}")
        println("  files:     empty every declared bucket, keeping the web assets of clients defined in source:")
        report.out.lines().filter { it.isNotBlank() }.forEach { println("               $it") }
        if (report.failed) return fail("The file store could not be read; nothing was changed.")
        println("  database:  remove ${databasePath(target)} ($dbSize)")
        println("  start:     ${startCommand(target)}, then /kda/health on the node and ${target.publicUrl}")
        if (options.dryRun) {
            println("Dry run: nothing was changed.")
            return 0
        }
        if (!options.assumeYes && !confirm("This deletes the data of ${target.host}. Reset it?")) return fail("Not reset.")

        if (ssh(target, stopCommand(target)).failed) return fail("Stopping the service failed; nothing was removed.")
        val cleared = ssh(target, clearFileStoreCommand(target, remove = true))
        cleared.out.lines().filter { it.isNotBlank() }.forEach { println("  $it") }
        if (cleared.failed) {
            ssh(target, startCommand(target))
            return fail("Emptying the file store failed; the database was kept, and the service started again.")
        }
        if (ssh(target, removeDatabaseCommand(target)).failed) {
            ssh(target, startCommand(target))
            return fail("Removing the database failed; the service started again.")
        }
        println("Removed the database. Starting...")
        if (ssh(target, startCommand(target)).failed) return fail("Starting the service failed; see kdr-node ${options.target} logs.")
        return if (healthy(target)) {
            println("Reset ${target.host}.")
            0
        } else {
            fail("The service started but did not answer as healthy; see kdr-node ${options.target} logs.")
        }
    }

    fun parseArgs(args: List<String>): Options? {
        val positional = mutableListOf<String>()
        var repo: File? = null
        var cwd: File? = null
        var dryRun = false
        var yes = false
        var lines = defaultLogLines
        var follow = false
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                NDF.dryRun -> dryRun = true
                NDF.yes, "-y" -> yes = true
                NDF.follow, "-f" -> follow = true
                NDF.lines, "-n" -> lines = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                NDF.repo -> repo = File(args.getOrNull(++i) ?: return null)
                NDF.cwd -> cwd = File(args.getOrNull(++i) ?: return null)
                else -> if (arg.startsWith("-")) return null else positional += arg
            }
            i++
        }
        val (target, subcommand) = positional.takeIf { it.size == 2 } ?: return null
        if (subcommand !in NDF.subcommands) return null
        return Options(target, subcommand, repo ?: File("."), cwd ?: File("."), dryRun, yes, lines, follow)
    }

    private fun usage(): Int {
        System.err.println(
            """
            Usage: kdr-node <target> ${NDF.status}
                   kdr-node <target> ${NDF.logs} [${NDF.lines} N] [${NDF.follow}]
                   kdr-node <target> ${NDF.restart} [${NDF.yes}]
                   kdr-node <target> ${NDF.resetData} [${NDF.dryRun}] [${NDF.yes}]

            Looks at and operates the lightweight node <target> describes -- a name in ${DPL.targetsDir}/, or a path
            to a target file -- set up by docs/deploy/lightweight-node.md.

              ${NDF.status}       the service, the release, memory and disk, health, and the node's own view
              ${NDF.logs}         the service's journal (${NDF.lines} N, default $defaultLogLines; ${NDF.follow} to follow)
              ${NDF.restart}      restart the service and wait for health
              ${NDF.resetData}   stop, empty the file store (keeping source clients' web assets), remove the
                           database, start, and wait for health; ${NDF.dryRun} shows what it would remove
            """.trimIndent(),
        )
        return failureExit
    }

    private fun fail(message: String): Int {
        System.err.println("kdr-node: $message")
        return failureExit
    }
}

fun main(args: Array<String>) {
    exitProcess(Node.run(args.toList()))
}
