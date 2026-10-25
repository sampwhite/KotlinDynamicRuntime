package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.gedra.workflow.WorkflowParamsService
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A workflow's parameters reach every node (issue #1356): saved on one, they are in force on another as soon as it
 * next reads the shared cache state, with no reload and no publish. The single-node behavior is
 * `ClientWorkflowWindowsTest`'s.
 */
class WorkflowParamsSyncTest : StringSpec({
    // Its own instances (issue #1075): two nodes over one database of their own (`KDR_DB_NAME`), and node B's clock
    // is moved past the state-read throttle.
    val db = mapOf("KDR_DB_NAME" to "wfParamsSyncDb")
    val nodeA = Startup.mkTestBootCxt("wfParamsSyncA", "wfParamsSyncNodeA", db)
    val nodeB = Startup.mkTestBootCxt("wfParamsSyncB", "wfParamsSyncNodeB", db)

    "windows saved on one node are in force on another at its next read" {
        val client = "wfparamsync1356"
        // A fresh context per read: a context refreshes the caches once, and remembers it did.
        fun windowsOn(node: KdrCxt): WfWindows =
            WorkflowParamsService.get(node).windowsFor(node.mkSubContext("read", client), client, "review")

        // Node B has read the table, so what follows is news to a loaded cache, not its first load.
        windowsOn(nodeB) shouldBe WfWindows.none
        val windows = WfWindows(lifetime = WfWindow(end = Instant.fromEpochSeconds(nodeA.instanceNow().epochSeconds) + 30.days))
        // Outside a request, the write is announced to the other nodes by monitoring it, as a request's end would.
        SqlTableCacheService.get(nodeA).withMonitoring(nodeA) {
            WorkflowParamsService.get(nodeA).save(nodeA.mkSubContext("set", client).also { it.userId = 13560L }, client, "review", WfParams(windows))
        }
        windowsOn(nodeA) shouldBe windows

        nodeB.instanceConfig.clock.advanceBy(1.seconds)
        windowsOn(nodeB) shouldBe windows
    }
})
