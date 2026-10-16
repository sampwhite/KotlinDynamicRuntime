package com.dynamicruntime.script

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/** The command-line tools' one way of running another program (`kdr-prune-branches`, `kdr-deploy`). */
class SubprocessTest : StringSpec({

    "captured output is trimmed, and stderr comes with it" {
        val result = Subprocess.capture(null, "sh", "-c", "echo out; echo err >&2")
        result.failed shouldBe false
        result.out shouldBe "out\nerr"
    }

    // What `kdr-deploy` writes the node's kdr.env through.
    "given input reaches the program, and none means an empty input rather than a wait" {
        Subprocess.capture(null, "cat", stdin = "KDR_ENV=dev\n").out shouldBe "KDR_ENV=dev"
        Subprocess.capture(null, "cat").out shouldBe ""
    }

    "a failing program and one that cannot start are both failures, not exceptions" {
        Subprocess.capture(File("."), "sh", "-c", "exit 3").code shouldBe 3
        val missing = Subprocess.capture(null, "no-such-program-kdr")
        missing.code shouldBe Subprocess.couldNotRunExit
        missing.out shouldContain "could not run no-such-program-kdr"
        Subprocess.inherit(File("."), "no-such-program-kdr") shouldBe false
    }
})
