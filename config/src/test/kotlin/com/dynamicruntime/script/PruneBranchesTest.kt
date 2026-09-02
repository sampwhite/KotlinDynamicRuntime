package com.dynamicruntime.script

import com.dynamicruntime.common.util.addDays
import com.dynamicruntime.script.PruneBranches.MergeState
import com.dynamicruntime.script.PruneBranches.RemoteBranch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Covers the decisions `kdr-prune-branches` makes before it deletes anything: what the arguments mean, which
 * branches are candidates, and how git's output is read. Every one of these is pure, which is the point of
 * splitting them out — the command deletes remote branches, so the parts that decide *which* have to be
 * checkable without a remote to delete from.
 */
class PruneBranchesTest : StringSpec({

    val me = "swhite@gyassa.com"
    val identities = setOf(me, "sampwhite@users.noreply.github.com")
    val now = Clock.System.now()
    val cutoff = now.addDays(-14)

    fun branch(
        name: String,
        daysOld: Int,
        email: String = me,
        mergeState: PruneBranches.MergeState = MergeState.reachable,
    ) = RemoteBranch(
        name = name,
        sha = "0123456789abcdef",
        lastCommit = now.addDays(-daysOld),
        authorName = "Samuel White",
        authorEmail = email,
        mergeState = mergeState,
    )

    // --- the age argument -------------------------------------------------------------------

    "weeksProblem enforces the one-week floor" {
        PruneBranches.weeksProblem(0).shouldNotBeNull()
        PruneBranches.weeksProblem(-3).shouldNotBeNull()
        PruneBranches.weeksProblem(1) shouldBe null
        PruneBranches.weeksProblem(52) shouldBe null
    }

    "parseArgs reads the week count and the flags" {
        val options = PruneBranches.parseArgs(
            listOf("3", "--dry-run", "--include-unmerged", "--skip-pr-check", "--keep-local"),
        ).shouldNotBeNull()
        options.weeks shouldBe 3
        options.dryRun shouldBe true
        options.includeUnmerged shouldBe true
        options.skipPrCheck shouldBe true
        options.keepLocal shouldBe true
        options.assumeYes shouldBe false
        options.remote shouldBe "origin"
    }

    "parseArgs leaves the safety checks on by default" {
        val options = PruneBranches.parseArgs(listOf("3")).shouldNotBeNull()
        options.skipPrCheck shouldBe false
        options.keepLocal shouldBe false
        options.includeUnmerged shouldBe false
    }

    "parseArgs collects repeatable values and takes the last --repo" {
        val options = PruneBranches.parseArgs(
            listOf("--repo", "/wrapper/checkout", "2", "--email", "OLD@example.com", "--protect", "release",
                "--protect", "staging", "--repo", "/mine", "--remote", "upstream", "-y"),
        ).shouldNotBeNull()
        options.repoDir.path shouldBe "/mine"
        options.remote shouldBe "upstream"
        options.assumeYes shouldBe true
        options.extraEmails shouldBe setOf("old@example.com")
        options.extraProtected shouldBe setOf("release", "staging")
    }

    "parseArgs rejects what it cannot act on" {
        PruneBranches.parseArgs(listOf()) shouldBe null
        PruneBranches.parseArgs(listOf("--dry-run")) shouldBe null
        PruneBranches.parseArgs(listOf("soon")) shouldBe null
        PruneBranches.parseArgs(listOf("2", "3")) shouldBe null
        PruneBranches.parseArgs(listOf("2", "--wat")) shouldBe null
        PruneBranches.parseArgs(listOf("2", "--remote")) shouldBe null
    }

    // --- choosing the branches --------------------------------------------------------------

    "plan deletes only old, merged branches of the caller's own" {
        val branches = listOf(
            branch("old-merged", daysOld = 30),
            branch("old-unmerged", daysOld = 30, mergeState = MergeState.unmerged),
            branch("fresh", daysOld = 2),
            branch("evas", daysOld = 30, email = "ecordes@gyassa.com"),
            branch("main", daysOld = 1),
        )
        val plan = PruneBranches.plan(branches, identities, cutoff, setOf("main"), includeUnmerged = false)
        plan.doomed.map { it.name } shouldBe listOf("old-merged")
        plan.kept.associate { it.branch.name to it.reason } shouldBe mapOf(
            "old-unmerged" to "not merged into the default branch",
            "fresh" to "worked on since the cutoff",
            "evas" to "not yours (ecordes@gyassa.com)",
            "main" to "protected",
        )
    }

    "plan takes unmerged branches only when asked" {
        val branches = listOf(branch("old-unmerged", daysOld = 30, mergeState = MergeState.unmerged))
        PruneBranches.plan(branches, identities, cutoff, setOf(), includeUnmerged = true)
            .doomed.map { it.name } shouldBe listOf("old-unmerged")
    }

    // --- merged by patch-id, not by reachability --------------------------------------------

    "plan deletes a branch whose commits were rebased in, which reachability alone calls unmerged" {
        val branches = listOf(branch("rebased-in", daysOld = 30, mergeState = MergeState.appliedUpstream))
        PruneBranches.plan(branches, identities, cutoff, setOf(), includeUnmerged = false)
            .doomed.map { it.name } shouldBe listOf("rebased-in")
    }

    "merged is true by either route into the default branch" {
        branch("a", 1, mergeState = MergeState.reachable).merged shouldBe true
        branch("a", 1, mergeState = MergeState.appliedUpstream).merged shouldBe true
        branch("a", 1, mergeState = MergeState.unmerged).merged shouldBe false
    }

    "everyCommitApplied is true only when git cherry has nothing outstanding" {
        PruneBranches.everyCommitApplied("- abc123\n- def456") shouldBe true
        // No lines at all is a branch whose only commits are merges, which git cherry does not consider.
        PruneBranches.everyCommitApplied("") shouldBe true
        PruneBranches.everyCommitApplied("+ abc123") shouldBe false
        PruneBranches.everyCommitApplied("- abc123\n+ def456") shouldBe false
    }

    "parseBranchLine reports unmerged for anything the cheap check did not list" {
        val line = "abc\t1754000000\t<swhite@gyassa.com>\tSamuel White\torigin/x"
        PruneBranches.parseBranchLine(line, "origin", setOf("x"))!!.mergeState shouldBe MergeState.reachable
        PruneBranches.parseBranchLine(line, "origin", setOf())!!.mergeState shouldBe MergeState.unmerged
    }

    "plan protects a branch even when it is otherwise a candidate" {
        val branches = listOf(branch("release", daysOld = 300))
        val plan = PruneBranches.plan(branches, identities, cutoff, setOf("release"), includeUnmerged = true)
        plan.doomed shouldBe listOf()
        plan.kept.single().reason shouldBe "protected"
    }

    "plan lists the oldest branch first" {
        val branches = listOf(branch("newer", daysOld = 20), branch("older", daysOld = 90))
        PruneBranches.plan(branches, identities, cutoff, setOf(), includeUnmerged = false)
            .doomed.map { it.name } shouldBe listOf("older", "newer")
    }

    "a branch exactly at the cutoff is kept, not deleted" {
        // The boundary decides whether "two weeks" includes the commit made two weeks ago to the second; it
        // does not, because the safe side of an off-by-one here is the one that keeps a branch.
        val exactly = RemoteBranch("edge", "abc", cutoff, "Samuel White", me, MergeState.reachable)
        val plan = PruneBranches.plan(listOf(exactly), identities, cutoff, setOf(), includeUnmerged = false)
        plan.doomed shouldBe listOf()
        plan.kept.single().reason shouldBe "worked on since the cutoff"
    }

    // --- open pull requests -----------------------------------------------------------------

    "plan keeps a branch with an open PR, however stale and merged it looks" {
        val branches = listOf(branch("under-review", daysOld = 400))
        val plan = PruneBranches.plan(
            branches, identities, cutoff, setOf(), includeUnmerged = true, openPrs = mapOf("under-review" to 344),
        )
        plan.doomed shouldBe listOf()
        plan.kept.single().reason shouldBe "open PR #344"
    }

    "an open PR on someone else's branch reports the PR, which is the more useful reason" {
        val branches = listOf(branch("evas", daysOld = 400, email = "ecordes@gyassa.com"))
        val plan = PruneBranches.plan(
            branches, identities, cutoff, setOf(), includeUnmerged = true, openPrs = mapOf("evas" to 12),
        )
        plan.kept.single().reason shouldBe "open PR #12"
    }

    "an open PR does not save a branch that was explicitly protected from deletion anyway" {
        // Protection is the caller's own instruction, so it outranks everything, including this.
        val branches = listOf(branch("release", daysOld = 400))
        val plan = PruneBranches.plan(
            branches, identities, cutoff, setOf("release"), includeUnmerged = true, openPrs = mapOf("release" to 7),
        )
        plan.kept.single().reason shouldBe "protected"
    }

    "parsePrLines reads gh's number/branch pairs and keeps the oldest PR per branch" {
        PruneBranches.parsePrLines("344\tfix-skill-example-path\n546\tissue-533-task-model") shouldBe
            mapOf("fix-skill-example-path" to 344, "issue-533-task-model" to 546)
        PruneBranches.parsePrLines("99\tsame-branch\n12\tsame-branch") shouldBe mapOf("same-branch" to 12)
    }

    "parsePrLines survives empty and malformed output" {
        PruneBranches.parsePrLines("") shouldBe mapOf()
        PruneBranches.parsePrLines("not a pr line\n\t\nxyz\tbranch") shouldBe mapOf()
    }

    // --- the local branches left behind -----------------------------------------------------

    "localBranchKeepReason deletes only a local branch sitting on the deleted commit" {
        PruneBranches.localBranchKeepReason("old", "abc123", "abc123", "main") shouldBe null
    }

    "localBranchKeepReason keeps a local branch that moved on, or is checked out" {
        PruneBranches.localBranchKeepReason("old", "def456", "abc123", "main").shouldNotBeNull()
        PruneBranches.localBranchKeepReason("old", "abc123", "abc123", "old").shouldNotBeNull()
    }

    "localBranchKeepReason copes with a detached HEAD" {
        PruneBranches.localBranchKeepReason("old", "abc123", "abc123", null) shouldBe null
    }

    "owns compares addresses without regard to case" {
        PruneBranches.owns(identities, branch("b", 1, email = "SWhite@Gyassa.COM")) shouldBe true
        PruneBranches.owns(identities, branch("b", 1, email = "someone@else.com")) shouldBe false
    }

    // --- reading git's output ---------------------------------------------------------------

    "parseBranchLine reads a for-each-ref line and strips the angle brackets" {
        val line = "abc123\t1754000000\t<swhite@gyassa.com>\tSamuel White\torigin/feat/some-work"
        val parsed = PruneBranches.parseBranchLine(line, "origin", setOf("feat/some-work")).shouldNotBeNull()
        parsed.name shouldBe "feat/some-work"
        parsed.sha shouldBe "abc123"
        parsed.authorEmail shouldBe "swhite@gyassa.com"
        parsed.authorName shouldBe "Samuel White"
        parsed.lastCommit shouldBe Instant.fromEpochSeconds(1754000000)
        parsed.merged shouldBe true
    }

    "parseBranchLine skips the remote's own HEAD and anything malformed" {
        // refs/remotes/origin/HEAD shortens to a bare "origin" — a symref, not a branch, and deleting it
        // would be deleting the default branch.
        PruneBranches.parseBranchLine(
            "abc\t1754000000\t<swhite@gyassa.com>\tSamuel White\torigin", "origin", setOf(),
        ) shouldBe null
        PruneBranches.parseBranchLine("warning: something git wanted to say", "origin", setOf()) shouldBe null
        PruneBranches.parseBranchLine(
            "abc\tnot-a-date\t<swhite@gyassa.com>\tSamuel White\torigin/x", "origin", setOf(),
        ) shouldBe null
    }

    "parseMergedNames drops the HEAD symref line and the remote prefix" {
        val output = """
              origin/HEAD -> origin/main
              origin/main
              origin/issue-13-logging
              origin/feat/nested/name
        """.trimIndent()
        PruneBranches.parseMergedNames(output, "origin") shouldBe
            setOf("main", "issue-13-logging", "feat/nested/name")
    }
})
