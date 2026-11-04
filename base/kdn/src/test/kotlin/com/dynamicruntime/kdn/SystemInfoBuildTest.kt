package com.dynamicruntime.kdn

import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.operator.BLD
import com.dynamicruntime.common.operator.OPS
import com.dynamicruntime.common.operator.OSI
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

/**
 * `/operator/system/info` names the commit this node was built from (issue #1620), as `base/common`'s
 * `embedBuildInfo` task recorded it. A Gradle test run has the resource, so the commit is a full hash, or `unknown`
 * where the build had no git. Read as a direct request from the node itself, which needs no login (issue #1255).
 */
class SystemInfoBuildTest : StringSpec({

    val cxt = TestInstances.default("buildInfo")

    "system info reports the build's commit, its time, and its age" {
        val client = TestHttpClient(cxt.instanceConfig)
        client.peerAddress = "127.0.0.1"
        val info = TestUser(client, cxt, emptyMap()).getData(OPS.systemInfoPath)

        // The build section follows the node section.
        info.keys.toList().take(2) shouldBe listOf(OSI.node, OSI.build)
        val build = info[OSI.build].toJsonMapOrEmpty()
        val commit = build[OSI.commit] as String
        commit shouldMatch Regex("[0-9a-f]{40}|${BLD.unknown}")
        if (commit != BLD.unknown) {
            build[OSI.commitShort] shouldBe commit.take(12)
            (build[OSI.commitTime] as String).isNotEmpty() shouldBe true
            // Whole minutes, written as a Duration is: `0s` within the minute, else `43m`, `2d 5h 12m`, ...
            build[OSI.commitAge] as String shouldMatch Regex("0s|\\d+[dhm]( \\d+[hm])*")
        }
        (build[OSI.branch] as String).isNotEmpty() shouldBe true
    }
})
