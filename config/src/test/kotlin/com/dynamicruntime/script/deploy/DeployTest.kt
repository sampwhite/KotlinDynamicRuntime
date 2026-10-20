package com.dynamicruntime.script.deploy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/**
 * `kdr-deploy`'s decisions, made without a node (issue #1243): reading a target, the release's name, the node's
 * `kdr.env`, which releases go, what a rollback goes back to, and the commands sent over SSH. The run around them
 * -- ssh, rsync, the build -- is exercised against a real node, as the guide does.
 */
class DeployTest : StringSpec({

    val targetText = """
        # A test node.
        host=node.example.org
        user=kdr
        baseDir=/home/kdr/kdr/
        service=kdr
        publicUrl=https://node.example.org/
        keepReleases=3
        env.KDR_ENV=dev
        env.KDR_PORT=7171
        env.KDR_MAIL_DOMAIN=mg.example.org
    """.trimIndent()

    val target = DeployTarget.parse("node", targetText)

    "a target file reads as written, its settings in order" {
        target.sshDestination shouldBe "kdr@node.example.org"
        target.baseDir shouldBe "/home/kdr/kdr"
        target.publicUrl shouldBe "https://node.example.org"
        target.env.keys.toList() shouldBe listOf("KDR_ENV", "KDR_PORT", "KDR_MAIL_DOMAIN")
        target.port shouldBe 7171
        target.envFile shouldBe "/home/kdr/kdr/kdr.env"
    }

    "a target file says what is wrong with it" {
        fun problem(text: String) = shouldThrow<IllegalArgumentException> { DeployTarget.parse("bad", text) }.message.orEmpty()
        problem("host=a") shouldContain "missing user, baseDir, service, publicUrl"
        problem("$targetText\nhosst=b") shouldContain "unknown key 'hosst'"
        problem("$targetText\nenv.KDR_ENV=prod") shouldContain "KDR_ENV is set twice"
        problem(targetText.replace("baseDir=/home/kdr/kdr/", "baseDir=kdr")) shouldContain "absolute path"
        problem(targetText.replace("keepReleases=3", "keepReleases=1")) shouldContain "at least 2"
        problem("$targetText\njust words") shouldContain "expected key=value"
        // It goes onto a Gradle command line, so only a plain name.
        problem("$targetText\nbuildProfile=ref erence") shouldContain "plain name"
        problem("$targetText\nbuildProfile=-Pother=x") shouldContain "plain name"
        // ASCII only (issue #1275): a lookalike letter is no letter to a command line.
        problem("$targetText\nbuildProfile=référence") shouldContain "plain name"
    }

    "a target's build profile reaches Gradle as -PkdrBuild, and none builds as a developer does (issue #1273)" {
        target.buildProfile shouldBe null
        Deploy.buildArgs(target) shouldBe listOf(":launch:installDist")
        val profiled = DeployTarget.parse("node", "$targetText\nbuildProfile=reference")
        Deploy.buildArgs(profiled) shouldBe listOf("-PkdrBuild=reference", ":launch:installDist")
    }

    "the repository's own target is a valid one" {
        // Run from the module directory under Gradle; the repository is its parent.
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, DPL.targetsDir).isDirectory }
        val reference = DeployTarget.load(repo, "dynamicruntime.org")
        reference.host shouldBe "dynamicruntime.org"
        reference.env["KDR_DEPLOY_FLAVORS"] shouldBe "reference"
        // Built without the workspace's own projects (issue #1273).
        reference.buildProfile shouldBe "reference"
        // A target never carries a secret; the node keeps its own.
        reference.env.keys.none { it.contains("KEY") || it.contains("SECRET") || it.contains("PASSWORD") } shouldBe true
    }

    "a target file outside the repository is read by its path, a relative one from where the command ran" {
        val repo = Files.createTempDirectory("kdr-deploy-repo").toFile()
        val caller = Files.createTempDirectory("kdr-deploy-caller").toFile()
        try {
            File(caller, "nodes").mkdirs()
            File(caller, "nodes/mynode.properties").writeText(targetText)
            DeployTarget.load(repo, "nodes/mynode.properties", caller).name shouldBe "mynode"
            DeployTarget.load(repo, File(caller, "nodes/mynode.properties").absolutePath).name shouldBe "mynode"
            // Not found from any other directory, and the miss says what it looked for.
            shouldThrow<IllegalArgumentException> { DeployTarget.load(repo, "nodes/mynode.properties", repo) }
                .message shouldContain "No target 'nodes/mynode.properties'"
        } finally {
            repo.deleteRecursively()
            caller.deleteRecursively()
        }
    }

    "a release is named for when it was built and from which commit" {
        val at = LocalDateTime.of(2026, 10, 16, 9, 5)
        Deploy.releaseName(at, "076cf2de", dirty = false) shouldBe "20261016-0905-076cf2de"
        Deploy.releaseName(at, "076cf2de", dirty = true) shouldBe "20261016-0905-076cf2de-dirty"
        Deploy.isReleaseName("20261016-0905-076cf2de-dirty") shouldBe true
        Deploy.isReleaseName("lost+found") shouldBe false
    }

    "kdr.env is the target's settings under a header, and a change shows as what goes and what comes" {
        val text = Deploy.envFileText(target)
        text shouldContain "target file node.properties"
        text.lines().filter { it.isNotEmpty() && !it.startsWith("#") } shouldBe
            listOf("KDR_ENV=dev", "KDR_PORT=7171", "KDR_MAIL_DOMAIN=mg.example.org")
        Deploy.envDiff("KDR_ENV=dev\nKDR_PORT=7171\nKDR_OLD=1\n", text) shouldBe
            listOf("- KDR_OLD=1", "+ KDR_MAIL_DOMAIN=mg.example.org")
        Deploy.envDiff(text, text) shouldBe emptyList()
    }

    "old releases go, but never the running one or the one just copied" {
        val releases = listOf("20261001-0100-aaaaaaa", "20261002-0100-bbbbbbb", "20261003-0100-ccccccc", "20261004-0100-ddddddd", "notes")
        Deploy.releasesToPrune(releases, current = "20261004-0100-ddddddd", keep = 3) shouldBe listOf("20261001-0100-aaaaaaa")
        // Running an old one (after a rollback) keeps it, whatever its age.
        Deploy.releasesToPrune(releases, current = "20261001-0100-aaaaaaa", keep = 2) shouldBe listOf("20261002-0100-bbbbbbb")
        Deploy.releasesToPrune(releases, current = null, keep = 2, keepAlso = "20261001-0100-aaaaaaa") shouldBe
            listOf("20261002-0100-bbbbbbb")
    }

    "a rollback goes to the release before the running one" {
        val releases = listOf("20261003-0100-ccccccc", "20261001-0100-aaaaaaa", "20261002-0100-bbbbbbb")
        Deploy.rollbackRelease(releases, "20261003-0100-ccccccc") shouldBe "20261002-0100-bbbbbbb"
        Deploy.rollbackRelease(releases, "20261001-0100-aaaaaaa") shouldBe null
        Deploy.rollbackRelease(releases, null) shouldBe null
    }

    "the node's listing reads back as its releases and the running one" {
        val output = "20261001-0100-aaaaaaa\n20261002-0100-bbbbbbb\n--current--\n/home/kdr/kdr/releases/20261002-0100-bbbbbbb\n"
        Deploy.parseListing(output) shouldBe (listOf("20261001-0100-aaaaaaa", "20261002-0100-bbbbbbb") to "20261002-0100-bbbbbbb")
        Deploy.parseListing("--current--\n") shouldBe (emptyList<String>() to null)
    }

    "the commands sent to the node quote what they are given" {
        Deploy.shellQuote("it's") shouldBe "'it'\\''s'"
        Deploy.switchCommand(target, "20261016-0905-076cf2de") shouldBe
            "ln -sfn '/home/kdr/kdr/releases/20261016-0905-076cf2de' '/home/kdr/kdr/current' && systemctl --user restart 'kdr'"
        Deploy.nodeHealthCommand(target) shouldContain "http://127.0.0.1:7171/kda/health"
    }
})
