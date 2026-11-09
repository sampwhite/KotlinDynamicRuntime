package com.dynamicruntime.script.deploy

import com.dynamicruntime.script.deploy.NodeReset.Decision
import com.dynamicruntime.script.deploy.NodeReset.NRST
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * The node's data reset (issue #1722), made without a node: when a reset is due, what the marker says, the reset's
 * steps and what a failure among them does, and the units a deploy installs. Its run on the node itself -- systemd, the
 * file store, the service's health -- is proven there, as the issue's testing steps say.
 */
class NodeResetTest : StringSpec({

    fun at(text: String): Instant = Instant.parse(text)

    "a reset is due a calendar month after the last, month ends included" {
        NodeReset.nextReset(at("2026-11-09T09:00:00Z")) shouldBe at("2026-12-09T09:00:00Z")
        // A month after January 31 is the last day of February, in a leap year too: never early March.
        NodeReset.nextReset(at("2027-01-31T09:00:00Z")) shouldBe at("2027-02-28T09:00:00Z")
        NodeReset.nextReset(at("2028-01-31T09:00:00Z")) shouldBe at("2028-02-29T09:00:00Z")
        NodeReset.nextReset(at("2026-12-15T23:30:00Z")) shouldBe at("2027-01-15T23:30:00Z")
    }

    "a check resets only once the data is a month old, and with no marker records one and wipes nothing" {
        val last = at("2026-11-09T09:00:00Z")
        NodeReset.decide(null, at("2026-11-10T09:00:00Z")) shouldBe Decision.Record
        NodeReset.decide(last, at("2026-12-09T08:59:59Z")) shouldBe Decision.NotDue(at("2026-12-09T09:00:00Z"))
        NodeReset.decide(last, at("2026-12-09T09:00:00Z")) shouldBe Decision.Due(last)
        // A node down for weeks resets at its first check back.
        NodeReset.decide(last, at("2027-03-01T09:00:00Z")) shouldBe Decision.Due(last)
    }

    "the marker is an instant on a line; anything else reads as none" {
        NodeReset.parseMarker("2026-11-09T09:00:00Z\n") shouldBe at("2026-11-09T09:00:00Z")
        NodeReset.parseMarker(null).shouldBeNull()
        NodeReset.parseMarker("  ").shouldBeNull()
        NodeReset.parseMarker("last Tuesday").shouldBeNull()
    }

    "the arguments: a mode, an absolute base directory, the service, and its port" {
        val options = NodeReset.parseArgs(listOf("check", "--base-dir", "/home/kdr/kdr", "--service", "kdr", "--port", "7070"))
            .shouldNotBeNull()
        options.mode shouldBe NRST.check
        options.baseDir shouldBe File("/home/kdr/kdr")
        options.port shouldBe 7070
        NodeReset.parseArgs(listOf("now", "--base-dir", "/b", "--service", "kdr", "--port", "1")).shouldNotBeNull().mode shouldBe NRST.now
        // No mode, two, a relative directory, a missing service, a port that is no port, an unknown flag.
        NodeReset.parseArgs(listOf("--base-dir", "/b", "--service", "kdr", "--port", "1")).shouldBeNull()
        NodeReset.parseArgs(listOf("now", "check", "--base-dir", "/b", "--service", "kdr", "--port", "1")).shouldBeNull()
        NodeReset.parseArgs(listOf("now", "--base-dir", "kdr", "--service", "kdr", "--port", "1")).shouldBeNull()
        NodeReset.parseArgs(listOf("now", "--base-dir", "/b", "--port", "1")).shouldBeNull()
        NodeReset.parseArgs(listOf("now", "--base-dir", "/b", "--service", "kdr", "--port", "x")).shouldBeNull()
        NodeReset.parseArgs(listOf("now", "--base-dir", "/b", "--service", "kdr", "--port", "1", "--force")).shouldBeNull()
    }

    /** A node's ops, each answering as told, and recording the order they were asked in. */
    class FakeOps(
        val stops: Boolean = true,
        val clears: Boolean = true,
        val starts: Boolean = true,
        val healthy: Boolean = true,
    ) : NodeReset.Ops {
        val calls = mutableListOf<String>()
        override fun stop(): Boolean = stops.also { calls += "stop" }
        override fun start(): Boolean = starts.also { calls += "start" }
        override fun clearFileStore(): Boolean = clears.also { calls += "clear" }
        override fun healthy(): Boolean = healthy.also { calls += "healthy" }
    }

    /** A base directory with a database in it, as a node's is. */
    fun baseDir(): File = Files.createTempDirectory("nodeReset").toFile().also {
        File(it, "${Node.databaseDir}/kdr.mv.db").apply { parentFile.mkdirs(); writeText("data") }
    }

    "a reset stops, empties the store, removes the database, records itself, and starts the service" {
        val dir = baseDir()
        val ops = FakeOps()
        val said = mutableListOf<String>()
        val now = at("2026-11-09T09:00:00Z")
        NodeReset.reset(dir, ops, now) { said += it } shouldBe true
        ops.calls shouldContainExactly listOf("stop", "clear", "start", "healthy")
        File(dir, Node.databaseDir).exists() shouldBe false
        NodeReset.lastReset(dir) shouldBe now
        said.last() shouldContain "next is due 2026-12-09T09:00:00Z"
    }

    "a reset that fails after the stop starts the service again on what was left, and records nothing" {
        val dir = baseDir()
        val ops = FakeOps(clears = false)
        val said = mutableListOf<String>()
        NodeReset.reset(dir, ops, at("2026-11-09T09:00:00Z")) { said += it } shouldBe false
        ops.calls shouldContainExactly listOf("stop", "clear", "start")
        // The database stays when the store could not be emptied, and no reset is recorded.
        File(dir, "${Node.databaseDir}/kdr.mv.db").exists() shouldBe true
        NodeReset.lastReset(dir).shouldBeNull()
        said.joinToString("\n") shouldContain "starting the service again on what is left"

        // A stop that fails changes nothing at all.
        val stopped = FakeOps(stops = false)
        NodeReset.reset(baseDir(), stopped, at("2026-11-09T09:00:00Z")) {} shouldBe false
        stopped.calls shouldContainExactly listOf("stop")
        // A service that will not come back is a failure, though the data was reset.
        NodeReset.reset(baseDir(), FakeOps(healthy = false), at("2026-11-09T09:00:00Z")) {} shouldBe false
    }

    "a check records a first marker, waits a month, then resets" {
        val dir = baseDir()
        val ops = FakeOps()
        val said = mutableListOf<String>()
        NodeReset.check(dir, ops, at("2026-11-09T09:00:00Z")) { said += it } shouldBe true
        ops.calls shouldBe emptyList()
        File(dir, Node.databaseDir).exists() shouldBe true
        said.single() shouldContain "wiping nothing"

        NodeReset.check(dir, ops, at("2026-12-01T09:00:00Z")) { said += it } shouldBe true
        ops.calls shouldBe emptyList()
        said.last() shouldContain "Not due until 2026-12-09T09:00:00Z"

        NodeReset.check(dir, ops, at("2026-12-09T09:00:00Z")) { said += it } shouldBe true
        ops.calls shouldContainExactly listOf("stop", "clear", "start", "healthy")
        NodeReset.lastReset(dir) shouldBe at("2026-12-09T09:00:00Z")
    }

    val target = DeployTarget.parse(
        "node",
        """
        host=node.example.org
        user=kdr
        baseDir=/home/kdr/kdr
        service=kdr
        publicUrl=https://node.example.org
        resetDataMonthly=true
        env.JAVA_HOME=/usr/lib/jvm/java-25/
        env.KDR_PORT=7171
        """.trimIndent(),
    )

    "a deploy's units run the daily check from current, with Java by its path and no shell" {
        val service = ResetUnits.serviceText(target)
        service shouldContain "Type=oneshot"
        service shouldContain "EnvironmentFile=/home/kdr/kdr/kdr.env"
        service shouldContain "ExecStart=\"/usr/lib/jvm/java-25/bin/java\" \"-Xmx160m\" \"-cp\" \"/home/kdr/kdr/current/lib/*\" " +
            "\"${NRST.mainClass}\" \"check\" \"--base-dir\" \"/home/kdr/kdr\" \"--service\" \"kdr\" \"--port\" \"7171\"\n"
        // systemd expands these on a command line; a unit written out needs none.
        service.substringAfter("[Service]") shouldNotContain "$"
        service.substringAfter("[Service]") shouldNotContain "%"
        val timer = ResetUnits.timerText(target)
        timer shouldContain "OnCalendar=${ResetUnits.calendar}"
        timer shouldContain "Persistent=true"
        timer shouldContain "Unit=kdr-reset.service"
        timer shouldContain "WantedBy=timers.target"
        // With no JAVA_HOME, the system's Java.
        ResetUnits.javaPath(target.copy(env = emptyMap())) shouldBe "/usr/bin/java"
    }

    "a reset over ssh runs the program now, under the node's own settings" {
        val command = ResetUnits.resetNowCommand(target)
        command shouldContain "set -a; . '/home/kdr/kdr/kdr.env'; set +a;"
        command shouldContain "-cp '/home/kdr/kdr/current/lib/*' ${NRST.mainClass} " +
            "'now' '--base-dir' '/home/kdr/kdr' '--service' 'kdr' '--port' '7171'"
    }

    "a deploy installs, updates, keeps, or removes the units to match the target" {
        val service = ResetUnits.serviceText(target)
        val timer = ResetUnits.timerText(target)
        ResetUnits.change(target, null, null) shouldBe ResetUnits.Change.Install
        ResetUnits.change(target, service, timer) shouldBe ResetUnits.Change.Unchanged
        ResetUnits.change(target, service.replace("7171", "7070"), timer) shouldBe ResetUnits.Change.Update
        val off = target.copy(resetDataMonthly = false)
        ResetUnits.change(off, service, timer) shouldBe ResetUnits.Change.Remove
        ResetUnits.change(off, null, null) shouldBe ResetUnits.Change.None
        // What the node printed reads back as the two files, each null when absent.
        ResetUnits.parseRead("${service}\n${ResetUnits.separator}\n${timer}\n${ResetUnits.separator}\n") shouldBe (service to timer)
        ResetUnits.parseRead("\n${ResetUnits.separator}\n\n${ResetUnits.separator}\n") shouldBe (null to null)
    }
})
