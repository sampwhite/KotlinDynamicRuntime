package com.dynamicruntime.webapp

import com.dynamicruntime.common.operator.TCI
import com.dynamicruntime.common.operator.TCS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for [cacheStateView], the join the cache-state operator page leads with
 * (issue #540): each of this node's caches paired with the shared row's last-changed date, the shared-row
 * tables this node holds no cache for, and the (deliberately light) attention count.
 */
class CacheStateViewTest {

    private fun cache(name: String, pending: Boolean = false, lastSeen: String? = "2026-01-01T00:00:00Z") =
        mapOf(
            TCI.tableName to name, TCI.topic to "auth", TCI.numRows to 3, TCI.highCounter to 7,
            TCI.lastSeen to lastSeen, TCI.pendingReload to pending,
        )

    @Test
    fun joinsCachesWithSharedRowAndCountsAttention() {
        val report = mapOf(
            TCS.nodeId to "10.0.0.1:7070",
            TCS.isDisabled to false,
            TCS.caches to listOf(cache("authUsers"), cache("gedra", pending = true)),
            TCS.sharedState to mapOf(
                "authUsers" to "2026-01-02T00:00:00Z",
                "otherNodeTable" to "2026-01-03T00:00:00Z",
            ),
        )
        val v = cacheStateView(report)
        assertEquals("10.0.0.1:7070", v.nodeId)
        assertEquals(false, v.isDisabled)
        assertEquals(2, v.rows.size)

        // The join is by table name: authUsers carries the shared date and is not flagged.
        val au = v.rows.single { it.tableName == "authUsers" }
        assertEquals("2026-01-02T00:00:00Z", au.sharedChanged)
        assertEquals(false, au.needsAttention)

        // gedra has a local write pending reload -> flagged; the shared row does not mention it.
        val g = v.rows.single { it.tableName == "gedra" }
        assertEquals(true, g.needsAttention)
        assertEquals(null, g.sharedChanged)

        // otherNodeTable is in the shared row but this node holds no cache for it -> unheld.
        assertEquals(listOf("otherNodeTable" to "2026-01-03T00:00:00Z"), v.unheldShared)

        // Attention is the light count: one pending row plus one unheld shared table.
        assertEquals(2, v.attentionCount)
    }

    @Test
    fun reportsCurrentWhenNothingPendingAndEverySharedTableIsHeld() {
        val report = mapOf(
            TCS.nodeId to "n1",
            TCS.isDisabled to false,
            TCS.caches to listOf(cache("authUsers")),
            TCS.sharedState to mapOf("authUsers" to "2026-01-02T00:00:00Z"),
        )
        val v = cacheStateView(report)
        assertEquals(0, v.attentionCount)
        assertTrue(v.unheldShared.isEmpty())
    }

    @Test
    fun toleratesAMissingOrDisabledReport() {
        val v = cacheStateView(mapOf(TCS.isDisabled to true))
        assertEquals("", v.nodeId)
        assertEquals(true, v.isDisabled)
        assertTrue(v.rows.isEmpty())
        assertEquals(0, v.attentionCount)
    }
}
