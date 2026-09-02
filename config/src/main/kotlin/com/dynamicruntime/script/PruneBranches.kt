package com.dynamicruntime.script

import com.dynamicruntime.common.util.addDays
import com.dynamicruntime.common.util.formatCompactId
import com.dynamicruntime.common.util.formatDayPart
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Deletes your own stale branches from a remote, from the command line:
 *
 * ```sh
 * kdr-prune-branches <weeks> [--include-unmerged] [--dry-run] [--yes]
 * ```
 *
 * "Yours" is decided by the **author email on each branch's tip commit**, matched against the identities this
 * machine can prove: `git config user.email`, and -- when the GitHub CLI is installed and logged in -- the
 * account's own address plus both of GitHub's `users.noreply.github.com` forms. Git records no owner for a
 * branch, and the push that created it leaves no trace in the ref, so the tip author is the honest proxy;
 * `--email` adds an address the sniff cannot know about (an old employer's, say). "Stale" is the tip's
 * *committer* date being at least [minWeeks] week(s) old, so a branch is judged by its last activity rather
 * than by when it was created, which git does not record either.
 *
 * Three things are refused rather than deleted, because each destroys work or attention that exists nowhere
 * else. A branch with an **open pull request** is kept whatever its age says, since deleting it closes the PR
 * and takes the review conversation's diff with it; that check needs the GitHub CLI, and rather than guess
 * when `gh` cannot answer, the command stops and offers `--skip-pr-check`. A branch not merged into the
 * remote's default branch is kept unless `--include-unmerged` says otherwise -- and "merged" here means the
 * *work* is in the default branch, not merely that the branch tip is reachable from it, since a rebased or
 * cherry-picked branch keeps its own shas and would otherwise look unmerged forever. The default branch itself
 * is never a candidate. Everything actually deleted is written to a restore file first -- one `git push` per
 * branch, resurrecting it at the exact commit -- since a deleted remote branch is otherwise recoverable only
 * from someone's local clone.
 *
 * Deleting is local as well as remote: `git push --delete` drops the remote-tracking ref by itself, and this
 * follows it with a prune and with `git branch -D` for each local branch still sitting on the deleted commit,
 * so a prune does not leave a checkout full of branches tracking things that are gone. A local branch that has
 * moved on from the remote's commit, or that is checked out, is kept and reported.
 *
 * It is a **Kotlin** command per the code guide's "clever Kotlin, dumb shell": `bin/kdr-prune-branches` only
 * locates the checkout and hands off. Nothing here needs the runtime, so it boots none of it -- this talks to
 * `git` and `gh` and nothing else.
 */
@Suppress("ConstPropertyName")
object PruneBranches {
    /** Flags the command accepts. */
    @Suppress("ConstPropertyName")
    object PBF {
        const val dryRun = "--dry-run"
        const val yes = "--yes"
        const val includeUnmerged = "--include-unmerged"
        const val skipPrCheck = "--skip-pr-check"
        const val keepLocal = "--keep-local"
        const val remote = "--remote"
        const val repo = "--repo"
        const val email = "--email"
        const val protect = "--protect"
    }

    /** Flags that consume the argument after them. */
    val valueFlags = setOf(PBF.remote, PBF.repo, PBF.email, PBF.protect)

    /** Exit codes: 0 success, 1 usage or failure. */
    const val failureExit = 1

    /**
     * The smallest age the command accepts. One week is the floor because the argument is the *whole* safety
     * mechanism: `0` would sweep up the branch pushed a minute ago, which is invariably the one being worked
     * on.
     */
    const val minWeeks = 1

    /** How many branches go into one `git push --delete`, keeping the command line and the blast radius sane. */
    const val deleteBatchSize = 20

    /** Branches never deleted, whatever their age. The remote's default branch joins these at run time. */
    val alwaysProtected = setOf("main", "master")

    /** Fields [refFormat] emits, tab separated. */
    const val refFieldCount = 5

    /** Tip sha, committer date, author email, author name, short ref -- one branch per line. */
    const val refFormat =
        "%(objectname)%09%(committerdate:unix)%09%(authoremail)%09%(authorname)%09%(refname:short)"

    const val daysPerWeek = 7

    /** How much of a sha the listing shows. */
    const val shaDisplayLength = 8

    /** Fields [prListArgs] emits, tab separated. */
    const val prFieldCount = 2

    /**
     * Asks the GitHub CLI for every open pull request raised from a branch *in this repository*, as
     * `<number>\t<branch>` lines. Cross-repository pull requests are dropped: their head branch lives in a
     * fork, so a same-named branch here is a different branch and protecting it would be a false positive.
     */
    val prListArgs = listOf(
        "gh", "pr", "list", "--state", "open", "--limit", "1000",
        "--json", "number,headRefName,isCrossRepository",
        "--jq", ".[] | select(.isCrossRepository|not) | \"\\(.number)\\t\\(.headRefName)\"",
    )

    /**
     * How a branch's work reached the default branch, if it did.
     *
     * The state that earns its keep is [MergeState.appliedUpstream]. A branch whose commits were rebased or
     * cherry-picked in keeps its *own* shas, so nothing on it is reachable from the default branch and
     * `git branch --merged` calls it unmerged forever -- although every line of it landed. Reachability alone
     * is what leaves a repository accumulating old branches nobody dares delete, each looking like the one
     * that might still hold something.
     */
    enum class MergeState { reachable, appliedUpstream, unmerged }

    /** A remote branch, as `git for-each-ref` reports it. */
    data class RemoteBranch(
        /** Short name with no remote prefix, e.g. `issue-13-logging` (it may itself contain slashes). */
        val name: String,
        val sha: String,
        /** Committer date of the tip: when the branch was last worked on. */
        val lastCommit: Instant,
        val authorName: String,
        val authorEmail: String,
        val mergeState: MergeState,
    ) {
        /** Whether its work is in the default branch at all, by whichever route. */
        val merged: Boolean get() = mergeState != MergeState.unmerged
    }

    /** A branch the plan leaves alone, with the reason to show. */
    data class KeptBranch(val branch: RemoteBranch, val reason: String)

    /** What a run would do: [doomed] gets deleted, [kept] is reported and left. */
    data class PrunePlan(val doomed: List<RemoteBranch>, val kept: List<KeptBranch>)

    /** Everything a run needs, once the arguments have been read. */
    data class Options(
        val weeks: Int,
        val repoDir: File,
        val remote: String,
        val dryRun: Boolean,
        val assumeYes: Boolean,
        val includeUnmerged: Boolean,
        val skipPrCheck: Boolean,
        val keepLocal: Boolean,
        val extraEmails: Set<String>,
        val extraProtected: Set<String>,
    )

    fun run(args: List<String>): Int {
        val options = parseArgs(args) ?: return usage()
        weeksProblem(options.weeks)?.let { return usage(it) }
        val repoDir = topLevel(options.repoDir) ?: return fail("${options.repoDir} is not a git repository.")

        val remoteUrl = capture(repoDir, "git", "remote", "get-url", options.remote)
        if (remoteUrl.failed) {
            return fail("No remote named '${options.remote}' in $repoDir.")
        }
        println("Repository: $repoDir")
        println("Remote:     ${options.remote} (${remoteUrl.out})")

        // Refresh first. Acting on stale refs is how this would offer to delete a branch somebody else removed
        // last week, or miss one pushed this morning -- and the prune keeps the local view honest afterward.
        println("Fetching ${options.remote} ...")
        if (!inherit(repoDir, "git", "fetch", "--prune", options.remote)) {
            return fail("git fetch failed; nothing was deleted.")
        }

        val defaultBranch = defaultBranch(repoDir, options.remote)
            ?: return fail(
                "Could not work out ${options.remote}'s default branch. Set it with:\n" +
                    "  git remote set-head ${options.remote} --auto",
            )
        val identities = identities(repoDir, options.extraEmails)
        if (identities.isEmpty()) {
            return fail(
                "Could not work out who you are: `git config user.email` is unset and the GitHub CLI is not " +
                    "logged in. Set one, or name yourself with ${PBF.email} <address>.",
            )
        }
        val openPrs = if (options.skipPrCheck) {
            mapOf()
        } else {
            openPrBranches(repoDir) ?: return fail(
                "Could not ask GitHub which pull requests are open -- is `gh` installed and logged in? Try " +
                    "`gh auth status`.\nNot deleting anything: a branch under review is exactly what this " +
                    "check exists to save. Re-run with ${PBF.skipPrCheck} to prune without it.",
            )
        }
        val cutoff = Clock.System.now().addDays(-daysPerWeek * options.weeks)
        println("Identity:   ${identities.joinToString(", ")}")
        println("Cutoff:     ${cutoff.formatDayPart()} (${options.weeks} week(s) ago)")
        println(
            "Open PRs:   " +
                if (options.skipPrCheck) "not checked (${PBF.skipPrCheck})" else "${openPrs.size} in this repo",
        )
        println()

        val branches = readBranches(repoDir, options.remote, defaultBranch)
        val protectedNames = alwaysProtected + defaultBranch + options.extraProtected
        val plan = plan(branches, identities, cutoff, protectedNames, options.includeUnmerged, openPrs)

        report(plan, options)
        if (plan.doomed.isEmpty()) {
            println("Nothing to delete.")
            return 0
        }
        if (options.dryRun) {
            println("${PBF.dryRun}: stopping here; nothing was deleted.")
            return 0
        }
        val restoreFile = writeRestoreFile(plan.doomed, options.remote)
        println("Restore commands written to $restoreFile")
        if (!options.assumeYes && !confirm(plan.doomed.size, options.remote)) {
            println("Aborted; nothing was deleted.")
            return 0
        }
        return delete(repoDir, options.remote, plan.doomed, options.keepLocal)
    }

    // --- the decisions, kept pure so a test can drive them ------------------------------------------------

    /** What is wrong with [weeks], or null when it is acceptable. */
    fun weeksProblem(weeks: Int): String? =
        if (weeks < minWeeks) {
            "<weeks> must be at least $minWeeks -- a smaller window would sweep up live work."
        } else {
            null
        }

    /**
     * Sorts [branches] into the ones to delete and the ones to keep. A branch survives if it is protected, is
     * the head of an open pull request in [openPrs], is not the caller's, is newer than [cutoff], or carries
     * unmerged work while [includeUnmerged] is false -- tested in that order, so the reason reported is the
     * most important one rather than the first accident of iteration.
     *
     * An open pull request outranks every reason but an explicit protection: a review in flight is the one
     * state where deleting the branch breaks something a person is actively looking at, whatever the branch's
     * age or merge status says.
     */
    fun plan(
        branches: List<RemoteBranch>,
        identities: Set<String>,
        cutoff: Instant,
        protectedNames: Set<String>,
        includeUnmerged: Boolean,
        openPrs: Map<String, Int> = mapOf(),
    ): PrunePlan {
        val doomed = mutableListOf<RemoteBranch>()
        val kept = mutableListOf<KeptBranch>()
        for (branch in branches.sortedBy { it.lastCommit }) {
            val reason = when {
                branch.name in protectedNames -> "protected"
                openPrs.containsKey(branch.name) -> "open PR #${openPrs[branch.name]}"
                !owns(identities, branch) -> "not yours (${branch.authorEmail})"
                branch.lastCommit >= cutoff -> "worked on since the cutoff"
                !branch.merged && !includeUnmerged -> "not merged into the default branch"
                else -> null
            }
            if (reason == null) doomed.add(branch) else kept.add(KeptBranch(branch, reason))
        }
        return PrunePlan(doomed, kept)
    }

    /** How a [MergeState] reads in the listing, wide enough that the column lines up. */
    fun describe(state: MergeState): String = when (state) {
        MergeState.reachable -> "merged   "
        MergeState.appliedUpstream -> "applied  "
        MergeState.unmerged -> "UNMERGED "
    }

    /** Whether [branch]'s tip was authored by one of [identities] (addresses compared case-insensitively). */
    fun owns(identities: Set<String>, branch: RemoteBranch): Boolean =
        branch.authorEmail.lowercase() in identities

    /**
     * Why the existing local branch [name] must be kept after its remote counterpart was deleted, or null when
     * it is safe to delete. [currentBranch] is the checked-out branch, if any.
     *
     * The rule is deliberately strict: the local branch goes only when it sits on the *exact* commit the
     * remote did, which is the one case where deleting it can lose nothing -- that commit is in the restore
     * file, and anything else a local branch might hold is by definition not on the remote at all.
     */
    fun localBranchKeepReason(
        name: String,
        localSha: String,
        remoteSha: String,
        currentBranch: String?,
    ): String? = when {
        name == currentBranch -> "it is checked out"
        localSha != remoteSha -> "it holds a commit the deleted remote branch did not"
        else -> null
    }

    /**
     * Reads the arguments into [Options], or null when they do not parse. The wrapper passes its own `--repo`
     * first and a caller's own copy arrives later, so the last one given wins.
     */
    fun parseArgs(args: List<String>): Options? {
        var weeks: Int? = null
        var repoDir: File? = null
        var remote = "origin"
        var dryRun = false
        var assumeYes = false
        var includeUnmerged = false
        var skipPrCheck = false
        var keepLocal = false
        val emails = mutableSetOf<String>()
        val protectedNames = mutableSetOf<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg in valueFlags) {
                val value = args.getOrNull(i + 1) ?: return null
                when (arg) {
                    PBF.remote -> remote = value
                    PBF.repo -> repoDir = File(value)
                    PBF.email -> emails.add(value.lowercase())
                    PBF.protect -> protectedNames.add(value)
                }
                i += 2
                continue
            }
            when {
                arg == PBF.dryRun -> dryRun = true
                arg == PBF.yes || arg == "-y" -> assumeYes = true
                arg == PBF.includeUnmerged -> includeUnmerged = true
                arg == PBF.skipPrCheck -> skipPrCheck = true
                arg == PBF.keepLocal -> keepLocal = true
                arg.startsWith("-") -> return null
                weeks != null -> return null
                else -> weeks = arg.toIntOrNull() ?: return null
            }
            i++
        }
        return Options(
            weeks = weeks ?: return null,
            repoDir = repoDir ?: File(System.getProperty("user.dir")),
            remote = remote,
            dryRun = dryRun,
            assumeYes = assumeYes,
            includeUnmerged = includeUnmerged,
            skipPrCheck = skipPrCheck,
            keepLocal = keepLocal,
            extraEmails = emails,
            extraProtected = protectedNames,
        )
    }

    /**
     * Parses one `git for-each-ref` line in the shape [refFormat] asks for, or null when the line is not one
     * (git prints the odd warning to the same stream) or names the remote's own HEAD rather than a branch.
     */
    fun parseBranchLine(line: String, remote: String, mergedNames: Set<String>): RemoteBranch? {
        val fields = line.split("\t")
        if (fields.size != refFieldCount) {
            return null
        }
        val (sha, unixDate, rawEmail, authorName, refName) = fields
        val name = refName.removePrefix("$remote/")
        // refs/remotes/<remote>/HEAD shortens to the bare remote name; it is a symref, not a branch.
        if (name.isEmpty() || name == refName) {
            return null
        }
        val seconds = unixDate.toLongOrNull() ?: return null
        return RemoteBranch(
            name = name,
            sha = sha,
            lastCommit = Instant.fromEpochSeconds(seconds),
            authorName = authorName,
            authorEmail = rawEmail.removePrefix("<").removeSuffix(">"),
            // Only the cheap, whole-repo answer is available here. A branch that lands as `unmerged` is asked
            // about individually afterwards, since that is the answer that can still be wrong.
            mergeState = if (name in mergedNames) MergeState.reachable else MergeState.unmerged,
        )
    }

    /**
     * Whether `git cherry <default> <branch>` reports nothing outstanding: every line it prints is `-`, meaning
     * git found a commit upstream with the same patch-id. No lines at all counts too -- that is a branch whose
     * only commits are merges, which `git cherry` does not consider and which carry no work of their own.
     *
     * A `+` line is a commit whose change exists nowhere upstream, and one is enough to keep the branch.
     */
    fun everyCommitApplied(output: String): Boolean =
        output.lineSequence().none { it.trimStart().startsWith("+") }

    /**
     * Parses the `<number>\t<branch>` lines [prListArgs] produces into branch name -> PR number. A branch with
     * more than one open PR keeps the lowest number, which is the oldest and so the one a reader is most
     * likely to be looking at.
     */
    fun parsePrLines(output: String): Map<String, Int> {
        val prs = mutableMapOf<String, Int>()
        for (line in output.lineSequence()) {
            val fields = line.split("\t")
            if (fields.size != prFieldCount) {
                continue
            }
            val number = fields[0].trim().toIntOrNull() ?: continue
            val branch = fields[1].trim()
            if (branch.isEmpty()) {
                continue
            }
            val existing = prs[branch]
            if (existing == null || number < existing) {
                prs[branch] = number
            }
        }
        return prs
    }

    /** Parses `git branch -r --merged`, dropping the `origin/HEAD -> origin/main` symref line. */
    fun parseMergedNames(output: String, remote: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains(" -> ") }
            .map { it.removePrefix("$remote/") }
            .toSet()

    // --- talking to git and gh ----------------------------------------------------------------------------

    private fun readBranches(repoDir: File, remote: String, defaultBranch: String): List<RemoteBranch> {
        val merged = capture(repoDir, "git", "branch", "-r", "--merged", "$remote/$defaultBranch")
        val mergedNames = parseMergedNames(merged.out, remote)
        val refs = capture(repoDir, "git", "for-each-ref", "--format=$refFormat", "refs/remotes/$remote/")
        val branches = refs.out.lineSequence().mapNotNull { parseBranchLine(it, remote, mergedNames) }.toList()
        return branches.map {
            if (it.mergeState == MergeState.unmerged) askPatchIds(repoDir, remote, defaultBranch, it) else it
        }
    }

    /**
     * Re-asks whether [branch] is merged, by patch-id rather than by reachability, and upgrades it to
     * [MergeState.appliedUpstream] when every commit on it already exists in the default branch under some
     * other sha. This is the second opinion for a rebased or cherry-picked branch, which the cheap
     * whole-repository check can only ever call unmerged.
     *
     * It costs one `git cherry` per branch, so it runs *only* for the branches that failed the cheap check --
     * in a healthy repository a handful, not the hundred-odd that are plainly merged. A failure to ask leaves
     * the branch unmerged, which is the answer that keeps it.
     */
    private fun askPatchIds(
        repoDir: File,
        remote: String,
        defaultBranch: String,
        branch: RemoteBranch,
    ): RemoteBranch {
        val cherry = capture(repoDir, "git", "cherry", "$remote/$defaultBranch", "$remote/${branch.name}")
        return if (!cherry.failed && everyCommitApplied(cherry.out)) {
            branch.copy(mergeState = MergeState.appliedUpstream)
        } else {
            branch
        }
    }

    /** The remote's default branch (`main`, usually), from its HEAD symref. */
    private fun defaultBranch(repoDir: File, remote: String): String? {
        val head = capture(repoDir, "git", "symbolic-ref", "--quiet", "--short", "refs/remotes/$remote/HEAD")
        if (!head.failed && head.out.isNotEmpty()) {
            return head.out.removePrefix("$remote/")
        }
        // No symref (a clone that never ran `set-head`): fall back to whichever conventional name exists.
        for (name in alwaysProtected) {
            val exists = capture(repoDir, "git", "rev-parse", "--verify", "--quiet", "refs/remotes/$remote/$name")
            if (!exists.failed && exists.out.isNotEmpty()) {
                return name
            }
        }
        return null
    }

    /**
     * The addresses that count as the caller: `git config user.email`, the GitHub account's own address and
     * both `users.noreply.github.com` forms when `gh` can say who it is, plus any [extra] given on the command
     * line. Lowercased, because git preserves the case a commit was made with and nobody means it to matter.
     */
    private fun identities(repoDir: File, extra: Set<String>): Set<String> {
        val found = mutableSetOf<String>()
        found.addAll(extra)
        val gitEmail = capture(repoDir, "git", "config", "user.email")
        if (!gitEmail.failed && gitEmail.out.isNotEmpty()) {
            found.add(gitEmail.out.lowercase())
        }
        // `gh` is optional: without it the git identity alone is usually right, and saying so beats failing.
        val gh = capture(repoDir, "gh", "api", "user", "--jq", "[.login, .id, .email] | @tsv")
        if (gh.failed) {
            println("(the GitHub CLI could not answer, so only your git identity is used)")
            return found
        }
        val fields = gh.out.split("\t")
        val login = fields.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val id = fields.getOrNull(1)?.takeIf { it.isNotEmpty() }
        val email = fields.getOrNull(2)?.takeIf { it.isNotEmpty() && it != "null" }
        if (email != null) {
            found.add(email.lowercase())
        }
        if (login != null) {
            found.add("$login@users.noreply.github.com".lowercase())
            if (id != null) {
                found.add("$id+$login@users.noreply.github.com".lowercase())
            }
        }
        return found
    }

    /**
     * Branch name -> open PR number for this repository, or null when the GitHub CLI could not answer at all.
     * Null is not "there are no open pull requests": it is "we do not know", which the caller has to treat as
     * a reason to stop rather than as a green light.
     */
    private fun openPrBranches(repoDir: File): Map<String, Int>? {
        val result = capture(repoDir, *prListArgs.toTypedArray())
        return if (result.failed) null else parsePrLines(result.out)
    }

    private fun delete(repoDir: File, remote: String, doomed: List<RemoteBranch>, keepLocal: Boolean): Int {
        var deleted = 0
        for (batch in doomed.chunked(deleteBatchSize)) {
            val cmd = listOf("git", "push", remote, "--delete") + batch.map { it.name }
            if (!inherit(repoDir, *cmd.toTypedArray())) {
                System.err.println(
                    "git push failed after deleting $deleted branch(es); the rest were left alone. " +
                        "Re-run to continue.",
                )
                cleanUpLocal(repoDir, remote, doomed.take(deleted), keepLocal)
                return failureExit
            }
            deleted += batch.size
        }
        println()
        println("Deleted $deleted branch(es) from $remote.")
        cleanUpLocal(repoDir, remote, doomed, keepLocal)
        return 0
    }

    /**
     * Clears what the deletion leaves behind locally. `git push --delete` already drops the
     * `refs/remotes/<remote>/<branch>` tracking ref, so the prune is belt and braces -- though it does also
     * clear refs for branches somebody else deleted meanwhile. The local *branches* are the real leftovers: a
     * push never touches `refs/heads`, so without this they linger, tracking a remote branch that is gone.
     */
    private fun cleanUpLocal(repoDir: File, remote: String, deleted: List<RemoteBranch>, keepLocal: Boolean) {
        if (deleted.isEmpty()) {
            return
        }
        val pruned = capture(repoDir, "git", "remote", "prune", remote)
        if (pruned.failed) {
            System.err.println("Could not prune ${remote}'s remote-tracking refs: ${pruned.out}")
        } else {
            println("Pruned ${remote}'s remote-tracking refs.")
        }
        if (keepLocal) {
            println("${PBF.keepLocal}: local branches left alone.")
            return
        }
        val current = currentBranch(repoDir)
        var removed = 0
        for (branch in deleted) {
            val localSha = revParse(repoDir, "refs/heads/${branch.name}") ?: continue
            val keep = localBranchKeepReason(branch.name, localSha, branch.sha, current)
            if (keep != null) {
                println("  kept local branch ${branch.name} -- $keep")
                continue
            }
            val result = capture(repoDir, "git", "branch", "-D", branch.name)
            if (result.failed) {
                System.err.println("  could not delete local branch ${branch.name}: ${result.out}")
            } else {
                removed++
            }
        }
        println("Deleted $removed local branch(es) that tracked them.")
    }

    // --- output -------------------------------------------------------------------------------------------

    private fun report(plan: PrunePlan, options: Options) {
        if (plan.kept.isNotEmpty()) {
            println("Keeping ${plan.kept.size} branch(es):")
            for ((branch, reason) in plan.kept) {
                println("  ${branch.lastCommit.formatDayPart()}  ${branch.name}  -- $reason")
            }
            println()
        }
        if (plan.doomed.isEmpty()) {
            return
        }
        val unmergedNote = if (options.includeUnmerged) " (including unmerged work)" else ""
        println("Deleting ${plan.doomed.size} branch(es) from ${options.remote}$unmergedNote:")
        for (branch in plan.doomed) {
            val sha = branch.sha.take(shaDisplayLength)
            println("  ${branch.lastCommit.formatDayPart()}  $sha  ${describe(branch.mergeState)}  ${branch.name}")
        }
        println()
    }

    /**
     * Writes the `git push` lines that would put [doomed] back, and returns the file. Written before the
     * confirmation rather than after the deletes, so an interrupted run still leaves the way back.
     */
    private fun writeRestoreFile(doomed: List<RemoteBranch>, remote: String): File {
        val now = Clock.System.now()
        val file = File(System.getProperty("java.io.tmpdir"), "kdr-prune-branches-${now.formatCompactId()}.txt")
        val text = buildString {
            append("# Branches deleted from '$remote' by kdr-prune-branches on $now.\n")
            append("# Run a line to restore that branch at exactly the commit it pointed at.\n")
            for (branch in doomed) {
                append("git push $remote ${branch.sha}:refs/heads/${branch.name}\n")
            }
        }
        file.writeText(text)
        return file
    }

    /** Asks before deleting. A non-interactive stdin reads as "no", so a piped run cannot delete by surprise. */
    private fun confirm(count: Int, remote: String): Boolean {
        print("Delete these $count branch(es) from $remote? [y/N] ")
        System.out.flush()
        val answer = readlnOrNull()?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }

    private fun fail(message: String): Int {
        System.err.println(message)
        return failureExit
    }

    private fun usage(problem: String? = null): Int {
        if (problem != null) {
            System.err.println(problem)
            System.err.println()
        }
        System.err.println(
            """
            Usage: kdr-prune-branches <weeks> [options]

            Deletes remote branches whose tip commit you authored and which have not been touched for at
            least <weeks> weeks (minimum $minWeeks). Branches with an open pull request, unmerged branches,
            and the default branch are kept. The local branches that tracked the deleted ones go too.

              ${PBF.includeUnmerged}  also delete branches not merged into the default branch
              ${PBF.dryRun}           show what would be deleted and stop
              ${PBF.yes}, -y          do not ask for confirmation
              ${PBF.skipPrCheck}    prune without asking GitHub about open pull requests
              ${PBF.keepLocal}        delete only on the remote; leave local branches alone
              ${PBF.remote} <name>    the remote to prune (default 'origin')
              ${PBF.repo} <dir>       the checkout to work in (default: the one holding this script)
              ${PBF.email} <addr>     also treat this address as yours (repeatable)
              ${PBF.protect} <name>   never delete this branch (repeatable)
            """.trimIndent(),
        )
        return failureExit
    }

    // --- subprocesses -------------------------------------------------------------------------------------

    /** A finished subprocess: its exit code and its combined output. */
    data class CmdResult(val code: Int, val out: String) {
        val failed: Boolean get() = code != 0
    }

    /**
     * Runs [cmd] in [dir] and captures its output. Stderr is merged into stdout: these commands say little,
     * and one stream cannot then fill its pipe while we are reading the other.
     */
    private fun capture(dir: File, vararg cmd: String): CmdResult =
        try {
            val process = ProcessBuilder(*cmd)
                .directory(dir)
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            CmdResult(process.waitFor(), out.trim())
        } catch (e: Exception) {
            CmdResult(failureExit, "could not run ${cmd.firstOrNull()}: ${e.message}")
        }

    /** Runs [cmd] in [dir] with its output going straight to the terminal. True on a zero exit. */
    private fun inherit(dir: File, vararg cmd: String): Boolean =
        try {
            ProcessBuilder(*cmd)
                .directory(dir)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
                .waitFor() == 0
        } catch (e: Exception) {
            System.err.println("could not run ${cmd.firstOrNull()}: ${e.message}")
            false
        }

    /** The checked-out branch, or null on a detached HEAD. */
    private fun currentBranch(repoDir: File): String? {
        val result = capture(repoDir, "git", "symbolic-ref", "--quiet", "--short", "HEAD")
        return if (result.failed || result.out.isEmpty()) null else result.out
    }

    /** The commit [ref] resolves to, or null when there is no such ref. */
    private fun revParse(repoDir: File, ref: String): String? {
        val result = capture(repoDir, "git", "rev-parse", "--verify", "--quiet", ref)
        return if (result.failed || result.out.isEmpty()) null else result.out
    }

    /** The root of the working tree holding [dir], or null when it is not in a repository. */
    private fun topLevel(dir: File): File? {
        val result = capture(dir, "git", "rev-parse", "--show-toplevel")
        return if (result.failed || result.out.isEmpty()) null else File(result.out)
    }
}

fun main(args: Array<String>) {
    exitProcess(PruneBranches.run(args.toList()))
}
