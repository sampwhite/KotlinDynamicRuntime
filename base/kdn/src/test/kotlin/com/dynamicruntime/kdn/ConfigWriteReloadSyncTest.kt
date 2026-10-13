package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientSyncService
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A bundle write that asks for a reload (issue #1156) does what the reload endpoint does on the other nodes too: it
 * announces the reload, so a peer behind catches up on its next sync check. The single-node half -- live at once,
 * and not without asking -- is `GedraConfigEndpointTest`'s.
 */
class ConfigWriteReloadSyncTest : StringSpec({
    val db = mapOf("KDR_DB_NAME" to "cfgReloadSyncDb", "KDR_LOAD_STORED_CONFIG" to "true")
    // Its own instances (issue #1075): two nodes over one database of their own (`KDR_DB_NAME`), as a peer catching
    // up needs. Node B reads the database rather than a cache, so it sees what node A stored.
    val nodeA = Startup.mkTestBootCxt("cfgReloadSyncA", "cfgReloadSyncNodeA", db)
    val nodeB = Startup.mkTestBootCxt("cfgReloadSyncB", "cfgReloadSyncNodeB", db)
    GedraConfigService.get(nodeB).configCache = null

    "a write asking for a reload is announced, and a peer catches up" {
        val admin = TestUser.create(nodeA, "reloadsync@example.com", level = ROLE.admin)
        val client = admin.selfClient()!!
        val cfact = "syncReady"
        fun liveOn(node: KdrCxt) = cfact in SchemaService.get(node).cfactsFor(client).names

        admin.postData(
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to "synced",
                CFEP.namespaceField to clientNamespace(client),
                CFEP.slots to mapOf(
                    CCT.cfactDef to listOf(mapOf(CCT.name to cfact, CCT.group to "grp", CCT.description to "Ready.", CCT.toFrontend to false)),
                ),
                CFEP.reloadField to true,
            ),
        )
        liveOn(nodeA) shouldBe true
        liveOn(nodeB) shouldBe false

        ClientSyncService.get(nodeB).checkSync(nodeB)
        liveOn(nodeB) shouldBe true
    }
})
