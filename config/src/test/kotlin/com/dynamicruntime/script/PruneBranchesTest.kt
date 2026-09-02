package com.dynamicruntime.script

import com.dynamicruntime.common.util.addDays
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
        merged: Boolean = true,
    ) = RemoteBranch(
        name = name,
        sha = "0123456789abcdef",
        lastCommit = now.addDays(-daysOld),
        authorName = "Samuel White",
        authorEmail = email,
        merged = merged,
    )

    // --- the age argument -------------------------------------------------------------------

    "weeksProblem enforces the one-week floor" {
        PruneBranches.weeksProblem(0).shouldNotBeNull()
        PruneBranches.weeksProblem(-3).shouldNotBeNull()
        PruneBranches.weeksProblem(1) shouldBe null
        PruneBranches.weeksProblem(52) shouldBe null
    }

    "parseArgs reads the week count and the flags" {
        val options = PruneBranches.parseArgs(listOf("3", "--dry-run", "--include-unmerged")).shouldNotBeNull()
        options.weeks shouldBe 3
        options.dryRun shouldBe true
        options.includeUnmerged shouldBe true
        options.assumeYes shouldBe false
        options.remote shouldBe "origin"
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
            branch("old-unmerged", daysOld = 30, merged = false),
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
        val branches = listOf(branch("old-unmerged", daysOld = 30, merged = false))
        PruneBranches.plan(branches, identities, cutoff, setOf(), includeUnmerged = true)
            .doomed.map { it.name } shouldBe listOf("old-unmerged")
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
        val exactly = RemoteBranch("edge", "abc", cutoff, "Samuel White", me, merged = true)
        val plan = PruneBranches.plan(listOf(exactly), identities, cutoff, setOf(), includeUnmerged = false)
        plan.doomed shouldBe listOf()
        plan.kept.single().reason shouldBe "worked on since the cutoff"
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
