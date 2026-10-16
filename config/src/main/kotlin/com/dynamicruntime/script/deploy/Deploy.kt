package com.dynamicruntime.script.deploy

import com.dynamicruntime.script.CmdResult
import com.dynamicruntime.script.Subprocess.capture
import com.dynamicruntime.script.Subprocess.inherit
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Deploys a build of KotlinDynamicRuntime to a lightweight node, from the developer's machine (issue #1243):
 *
 * ```sh
 * kdr-deploy <target> [--dry-run] [--yes] [--skip-build]
 * kdr-deploy <target> --rollback [--dry-run] [--yes]
 * ```
 *
 * The node is one `docs/deploy/lightweight-node.md` set up, and [DeployTarget] describes it. A deploy:
 * 1. builds the distribution (`:launch:installDist`), which is the release;
 * 2. copies it to a new `releases/<release>/` on the node;
 * 3. writes the node's `kdr.env` from the target, when it differs, keeping the old one as `kdr.env.prev`;
 * 4. points `current` at the new release and restarts the service;
 * 5. waits for `/kda/health` on the node itself, then through the public address;
 * 6. deletes releases beyond the target's `keepReleases`, never the running one.
 *
 * It never touches the node's data (`h2Database/`, `fileStore/`) or its secrets (`private/`), which are outside
 * every release. `--rollback` points `current` back at the release before it, and restarts. A failed health check
 * leaves the new release running and says how to roll back, rather than rolling back on its own: whoever deployed
 * should see why it failed first.
 *
 * **Clever Kotlin, dumb shell** (the code guide): `bin/kdr-deploy` only hands off. The node is reached with the
 * system `ssh` and `rsync`, so your own SSH configuration, agent and known hosts are used, and no key passes
 * through this code. The commands run on the node are short POSIX ones built here, and nothing is installed there
 * but the release and `kdr.env`. Nothing here needs the runtime, so it boots none of it.
 */
object Deploy {
    /** Flags the command accepts. */
    @Suppress("ConstPropertyName")
    object DPF {
        const val dryRun = "--dry-run"
        const val yes = "--yes"
        const val skipBuild = "--skip-build"
        const val rollback = "--rollback"
        const val repo = "--repo"
        const val cwd = "--cwd"
    }

    const val failureExit = 1

    /** A release's name: when it was built and from which commit, `-dirty` when the checkout had changes. */
    private val releasePattern = Regex("""\d{8}-\d{4}-[0-9a-f]{7,40}(-dirty)?""")

    /** Everything a run needs, once the arguments have been read. */
    data class Options(
        val target: String,
        val repoDir: File,
        /** Where the command was run from, which a relative target path is read against. */
        val callerDir: File,
        val dryRun: Boolean,
        val assumeYes: Boolean,
        val skipBuild: Boolean,
        val rollback: Boolean,
    )

    // --- the pure parts: what a deploy or a rollback does, decided without a node -------------------------------

    /** The name of a release built at [builtAt] from commit [shortSha], `-dirty` when the checkout had changes. */
    fun releaseName(builtAt: LocalDateTime, shortSha: String, dirty: Boolean): String =
        builtAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + "-" + shortSha + if (dirty) "-dirty" else ""

    fun isReleaseName(name: String): Boolean = releasePattern.matches(name)

    /** [text] quoted for a POSIX shell, so a value reaches the node's command line as written. */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** The `kdr.env` a target gives its node: a header saying where it came from, then one line per setting. */
    fun envFileText(target: DeployTarget): String = buildString {
        append("# Written by kdr-deploy from the target file ${target.name}.properties. Change the target, not this file:\n")
        append("# the next deploy writes it again.\n")
        for ((key, value) in target.env) append(key).append('=').append(value).append('\n')
    }

    /** The settings that differ between the node's [current] `kdr.env` and the [wanted] one: `-` gone, `+` new. */
    fun envDiff(current: String?, wanted: String): List<String> {
        fun settings(text: String?) = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val before = settings(current)
        val after = settings(wanted)
        return before.filterNot { it in after }.map { "- $it" } + after.filterNot { it in before }.map { "+ $it" }
    }

    /**
     * The releases to delete from [releases], keeping the newest [keep] -- names sort by when they were built -- and
     * always [current] and [keepAlso] (the release a deploy just copied), whatever their age. Only names that are
     * releases are ever considered, so nothing else in the directory is touched.
     */
    fun releasesToPrune(releases: List<String>, current: String?, keep: Int, keepAlso: String? = null): List<String> {
        val ordered = releases.filter { isReleaseName(it) }.sortedDescending()
        val kept = ordered.take(keep).toSet() + setOfNotNull(current, keepAlso)
        return ordered.filterNot { it in kept }.sorted()
    }

    /** The release to roll back to from [current]: the newest one older than it, or null when there is none. */
    fun rollbackRelease(releases: List<String>, current: String?): String? {
        val ordered = releases.filter { isReleaseName(it) }.sorted()
        val at = ordered.indexOf(current)
        return if (at > 0) ordered[at - 1] else null
    }

    /** The release a `current` link points at, from what `readlink` printed: its last path segment. */
    fun releaseOfLink(readlink: String?): String? =
        readlink?.trim()?.trimEnd('/')?.substringAfterLast('/')?.takeIf { isReleaseName(it) }

    /** The command that points `current` at [release] and restarts the service. */
    fun switchCommand(target: DeployTarget, release: String): String =
        "ln -sfn ${shellQuote("${target.releasesDir}/$release")} ${shellQuote(target.currentLink)} && " +
            "systemctl --user restart ${shellQuote(target.service)}"

    /** The command that answers the HTTP status of `/kda/health` on the node itself. */
    fun nodeHealthCommand(target: DeployTarget): String =
        "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:${target.port}/kda/health"

    /** The command that lists the node's releases and says which one `current` points at. */
    fun listCommand(target: DeployTarget): String =
        "ls -1 ${shellQuote(target.releasesDir)}; echo ${shellQuote(listSeparator)}; readlink ${shellQuote(target.currentLink)} || true"

    private const val listSeparator = "--current--"

    /** What [listCommand] printed, as the releases and the one `current` points at. */
    fun parseListing(output: String): Pair<List<String>, String?> {
        val lines = output.lines().map { it.trim() }
        val split = lines.indexOf(listSeparator)
        val releases = (if (split < 0) lines else lines.subList(0, split)).filter { isReleaseName(it) }
        val current = if (split < 0) null else releaseOfLink(lines.drop(split + 1).firstOrNull { it.isNotEmpty() })
        return releases to current
    }

    // --- running it ---------------------------------------------------------------------------------------------

    fun run(args: List<String>): Int {
        val options = parseArgs(args) ?: return usage()
        val target = try {
            DeployTarget.load(options.repoDir, options.target, options.callerDir)
        } catch (e: IllegalArgumentException) {
            return fail(e.message.orEmpty())
        }
        return if (options.rollback) rollback(options, target) else deploy(options, target)
    }

    private fun deploy(options: Options, target: DeployTarget): Int {
        val sha = capture(options.repoDir, "git", "rev-parse", "--short=8", "HEAD").takeIf { !it.failed }?.out
            ?: return fail("${options.repoDir} is not a git checkout.")
        val dirty = capture(options.repoDir, "git", "status", "--porcelain").out.isNotEmpty()
        val release = releaseName(LocalDateTime.now(), sha, dirty)
        val (releases, current) = listing(target) ?: return fail("Could not reach ${target.sshDestination}, or ${target.releasesDir} is not there. Has the node been set up (docs/deploy/lightweight-node.md)?")
        val remoteEnv = ssh(target, "cat ${shellQuote(target.envFile)} 2>/dev/null || true").out
        val wantedEnv = envFileText(target)
        val envChanges = if (target.env.isEmpty()) emptyList() else envDiff(remoteEnv, wantedEnv)
        val prune = releasesToPrune(releases + release, current = release, keep = target.keepReleases, keepAlso = current)

        println("Deploy to ${target.sshDestination} (${target.publicUrl})")
        println("  release:   $release${if (dirty) "   (the checkout has uncommitted changes)" else ""}")
        println("  running:   ${current ?: "nothing"}")
        if (!options.skipBuild) println("  build:     ./gradlew :launch:installDist")
        println("  copy:      ${DPL.distributionDir}/ -> ${target.releasesDir}/$release/")
        when {
            target.env.isEmpty() -> println("  kdr.env:   left as it is (the target sets nothing)")
            envChanges.isEmpty() -> println("  kdr.env:   unchanged")
            else -> {
                println("  kdr.env:   rewritten (the old one kept as kdr.env.prev):")
                envChanges.forEach { println("               $it") }
            }
        }
        println("  switch:    ${switchCommand(target, release)}")
        println("  check:     /kda/health on the node, then ${target.publicUrl}/kda/health")
        println("  remove:    ${prune.joinToString(", ").ifEmpty { "nothing" }}")
        if (options.dryRun) {
            println("Dry run: nothing was changed.")
            return 0
        }
        if (!options.assumeYes && !confirm("Deploy $release to ${target.host}?")) return fail("Not deployed.")

        if (!options.skipBuild) {
            val workspace = options.repoDir.parentFile
            if (!inherit(workspace, "./gradlew", ":launch:installDist", "-q")) return fail("The build failed.")
        }
        val dist = File(options.repoDir, DPL.distributionDir)
        if (!File(dist, "bin/launch").isFile) return fail("No distribution at $dist; build it with ./gradlew :launch:installDist.")
        println("Copying the release...")
        if (!inherit(options.repoDir, "rsync", "-a", "--delete", "$dist/", "${target.sshDestination}:${target.releasesDir}/$release/")) {
            return fail("Copying the release failed; nothing on the node was switched.")
        }
        if (envChanges.isNotEmpty()) {
            val write = "cp ${shellQuote(target.envFile)} ${shellQuote("${target.envFile}.prev")} 2>/dev/null; " +
                "cat > ${shellQuote("${target.envFile}.new")} && mv ${shellQuote("${target.envFile}.new")} ${shellQuote(target.envFile)}"
            if (ssh(target, write, stdin = wantedEnv).failed) return fail("Writing kdr.env failed; nothing was switched.")
            println("Wrote kdr.env.")
        }
        println("Switching to $release and restarting...")
        if (ssh(target, switchCommand(target, release)).failed) return fail("Switching or restarting failed.")
        if (!healthy(target)) {
            val back = current?.let { "kdr-deploy ${options.target} ${DPF.rollback} (back to $it)" } ?: "there is no earlier release to go back to"
            return fail("$release is running but did not answer as healthy. Look at its log (journalctl --user -u ${target.service} on the node); to go back: $back.")
        }
        if (prune.isNotEmpty()) {
            val remove = "cd ${shellQuote(target.releasesDir)} && rm -rf -- " + prune.joinToString(" ") { shellQuote(it) }
            if (ssh(target, remove).failed) println("Could not remove the old releases ${prune.joinToString(", ")}; they are still there.")
            else println("Removed ${prune.joinToString(", ")}.")
        }
        println("Deployed $release to ${target.publicUrl}.")
        return 0
    }

    private fun rollback(options: Options, target: DeployTarget): Int {
        val (releases, current) = listing(target) ?: return fail("Could not reach ${target.sshDestination}.")
        val back = rollbackRelease(releases, current)
            ?: return fail("Nothing to roll back to: ${current ?: "no release"} is running, and no release is older.")
        println("Roll back ${target.sshDestination} from $current to $back")
        println("  switch:    ${switchCommand(target, back)}")
        if (options.dryRun) {
            println("Dry run: nothing was changed.")
            return 0
        }
        if (!options.assumeYes && !confirm("Roll back to $back on ${target.host}?")) return fail("Not rolled back.")
        if (ssh(target, switchCommand(target, back)).failed) return fail("Switching or restarting failed.")
        if (!healthy(target)) return fail("$back is running but did not answer as healthy.")
        println("Rolled back to $back. $current is still on the node; the next deploy keeps or removes it as usual.")
        return 0
    }

    /** The node's releases and the one running, or null when the node cannot be listed. */
    private fun listing(target: DeployTarget): Pair<List<String>, String?>? {
        val result = ssh(target, "test -d ${shellQuote(target.releasesDir)} && { ${listCommand(target)}; }")
        return if (result.failed) null else parseListing(result.out)
    }

    /** Waits for the application to answer on the node, then through the public address. */
    private fun healthy(target: DeployTarget): Boolean {
        print("Waiting for the application on the node")
        val nodeUp = waitFor(DPL.nodeHealthSeconds) { ssh(target, nodeHealthCommand(target)).out == "200" }
        println(if (nodeUp) " -- up." else " -- no answer.")
        if (!nodeUp) return false
        print("Checking ${target.publicUrl}")
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(URI.create("${target.publicUrl}/kda/health")).timeout(Duration.ofSeconds(10)).GET().build()
        val publicUp = waitFor(DPL.publicHealthSeconds) {
            runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200 }.getOrDefault(false)
        }
        println(if (publicUp) " -- up." else " -- no answer through the public address (the node itself answers).")
        return publicUp
    }

    private fun waitFor(seconds: Int, check: () -> Boolean): Boolean {
        val until = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < until) {
            if (check()) return true
            print(".")
            Thread.sleep(2000)
        }
        return false
    }

    private fun confirm(question: String): Boolean {
        print("$question [y/N] ")
        return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
    }

    private fun parseArgs(args: List<String>): Options? {
        var target: String? = null
        var repo: File? = null
        var cwd: File? = null
        var dryRun = false
        var yes = false
        var skipBuild = false
        var rollback = false
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                DPF.dryRun -> dryRun = true
                DPF.yes, "-y" -> yes = true
                DPF.skipBuild -> skipBuild = true
                DPF.rollback -> rollback = true
                DPF.repo -> repo = File(args.getOrNull(++i) ?: return null)
                DPF.cwd -> cwd = File(args.getOrNull(++i) ?: return null)
                else -> if (arg.startsWith("-") || target != null) return null else target = arg
            }
            i++
        }
        return Options(target ?: return null, repo ?: File("."), cwd ?: File("."), dryRun, yes, skipBuild, rollback)
    }

    private fun usage(): Int {
        System.err.println(
            """
            Usage: kdr-deploy <target> [options]
                   kdr-deploy <target> ${DPF.rollback} [options]

            Deploys a build to the lightweight node <target> describes -- a name in ${DPL.targetsDir}/, or a
            path to a target file -- set up by docs/deploy/lightweight-node.md: builds the distribution, copies it
            to a new release, writes the node's kdr.env from the target, switches to it, restarts, and checks it.

              ${DPF.dryRun}     show what would happen and stop
              ${DPF.yes}, -y      do not ask for confirmation
              ${DPF.skipBuild}  deploy the distribution already built
              ${DPF.rollback}   go back to the release before the running one
            """.trimIndent(),
        )
        return failureExit
    }

    private fun fail(message: String): Int {
        System.err.println("kdr-deploy: $message")
        return failureExit
    }

    // --- subprocesses -------------------------------------------------------------------------------------------

    /** Runs [command] on the node over SSH, with [stdin] as its input when given, and captures its output. */
    private fun ssh(target: DeployTarget, command: String, stdin: String? = null): CmdResult =
        capture(null, "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15", target.sshDestination, command, stdin = stdin)
}

fun main(args: Array<String>) {
    exitProcess(Deploy.run(args.toList()))
}
