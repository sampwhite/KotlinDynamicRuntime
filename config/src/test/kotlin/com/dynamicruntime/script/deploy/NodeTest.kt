package com.dynamicruntime.script.deploy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * `kdr-node`'s decisions, made without a node (issue #1244): its arguments, what it runs on the node, and the status
 * report it makes of the answers. The run around them -- ssh, the service, the store-emptying run -- is exercised
 * against a real node.
 */
class NodeTest : StringSpec({

    val target = DeployTarget.parse(
        "node",
        """
        host=node.example.org
        user=kdr
        baseDir=/home/kdr/kdr
        service=kdr
        publicUrl=https://node.example.org
        env.KDR_PORT=7171
        """.trimIndent(),
    )

    "the arguments: a target and a subcommand, and its flags" {
        val logs = Node.parseArgs(listOf("node", "logs", "--lines", "20", "--follow")).shouldNotBeNull()
        logs.subcommand shouldBe Node.NDF.logs
        logs.lines shouldBe 20
        logs.follow shouldBe true
        Node.parseArgs(listOf("node", "reset-data", "--dry-run")).shouldNotBeNull().dryRun shouldBe true
        Node.parseArgs(listOf("node", "status")).shouldNotBeNull().lines shouldBe Node.defaultLogLines
        // No subcommand, an unknown one, an unknown flag, a count that is no count.
        Node.parseArgs(listOf("node")).shouldBeNull()
        Node.parseArgs(listOf("node", "reboot")).shouldBeNull()
        Node.parseArgs(listOf("node", "status", "--loud")).shouldBeNull()
        Node.parseArgs(listOf("node", "logs", "--lines", "many")).shouldBeNull()
    }

    "status asks the node once, each section on a line of its own, and reads the operator view as a direct request" {
        val command = Node.statusCommand(target)
        for (name in listOf("service", "releases", "memory", "disk", "health", "flavors", "boot", "system")) {
            command shouldContain "echo; echo ${Node.SEC.marker(name)};"
        }
        command shouldContain "systemctl --user show 'kdr'"
        command shouldContain "curl -s --max-time 10 'http://127.0.0.1:7171/kda/operator/flavors'"
        command shouldContain "'http://127.0.0.1:7171/kda/operator/boot/checks'"
    }

    // As the node answers: `curl` ends its output with no newline, so each marker follows an empty line.
    fun answer(vararg sections: Pair<String, String>): String =
        sections.joinToString("") { (name, text) -> "\n${Node.SEC.marker(name)}\n$text" }

    val healthyNode = answer(
        "service" to "ActiveState=active\nSubState=running\nActiveEnterTimestamp=Tue 2026-10-20 14:26:13 UTC\nNRestarts=0\nResult=success",
        "releases" to "20261019-1826-ef2d3819\n20261020-0926-c0c77d12\n--current--\n/home/kdr/kdr/releases/20261020-0926-c0c77d12",
        "memory" to "               total        used        free      shared  buff/cache   available\n" +
            "Mem:             956         503          70           4         430         452\nSwap:            511          41         470",
        "disk" to "Filesystem      Size  Used Avail Use% Mounted on\n/dev/sda         25G  8.4G   15G  37% /",
        "health" to "200",
        "flavors" to """{"results":{"active":["reference"],"declared":[],"values":[]}}""",
        "boot" to """{"items":[{"name":"fileStore","mode":"strict","findings":[]},{"name":"fragments","mode":"warn","findings":["home: a key the base dropped"]}]}""",
        "system" to """{"results":{"node":{"uptimeText":"21m 2s"},"memory":{"heapUsed":"0.06 GB","heapMax":"0.36 GB","nonHeapUsed":"0.06 GB"}}}""",
    )

    "the status report says what each section answered" {
        val report = Node.statusReport(target, Node.sections(healthyNode))
        report[0] shouldBe "Node kdr@node.example.org (https://node.example.org)"
        report shouldContain "  service:  active (running) since Tue 2026-10-20 14:26:13 UTC, 0 restart(s), last result success"
        report shouldContain "  release:  20261020-0926-c0c77d12   (kept: 20261019-1826-ef2d3819, 20261020-0926-c0c77d12)"
        report shouldContain "  health:   200"
        report shouldContain "  memory:   503 of 956 MB used, 452 MB available, swap 41 of 511 MB used"
        report shouldContain "  disk:     8.4G of 25G used, 15G free"
        report shouldContain "  jvm:      heap 0.06 GB of 0.36 GB, non-heap 0.06 GB, up 21m 2s"
        report shouldContain "  flavors:  reference"
        // A check that found something is named, finding by finding.
        report shouldContain "  boot:     2 check(s), 1 finding(s):"
        report shouldContain "    fragments: home: a key the base dropped"
    }

    "an operator view the node cannot give is said plainly, and the rest still reported" {
        val stopped = answer(
            "service" to "ActiveState=inactive\nSubState=dead\nActiveEnterTimestamp=\nNRestarts=0\nResult=success",
            "health" to "000",
            "flavors" to "",
            "boot" to """{"status":401,"errorMessage":"Request requires the 'operator' role and no user is logged in."}""",
            "system" to "",
        )
        val report = Node.statusReport(target, Node.sections(stopped))
        report shouldContain "  service:  inactive (dead) since -, 0 restart(s), last result success"
        report shouldContain "  flavors:  no answer (is the service running?)"
        report shouldContain "  boot:     unavailable (status 401)"
        report shouldContain "  release:  none   (kept: none)"
    }

    "reset-data runs the store-emptying script from the deployed release, under the node's own settings" {
        val report = Node.clearFileStoreCommand(target, remove = false)
        report shouldContain "set -a; . '/home/kdr/kdr/kdr.env'; set +a;"
        report shouldContain "KDR_LOG_LEVEL=warn"
        report shouldContain "-cp '/home/kdr/kdr/current/lib/*' ${Node.clearFileStoreMain}"
        report shouldNotContain "--remove"
        Node.clearFileStoreCommand(target, remove = true) shouldContain "${Node.clearFileStoreMain} --remove"
        // The database is the one directory under the base directory, quoted.
        Node.removeDatabaseCommand(target) shouldBe "rm -rf -- '/home/kdr/kdr/h2Database'"
        Node.logsCommand(target, 50, follow = true) shouldBe "journalctl --user -u 'kdr' -n 50 --no-pager -f"
    }
})
