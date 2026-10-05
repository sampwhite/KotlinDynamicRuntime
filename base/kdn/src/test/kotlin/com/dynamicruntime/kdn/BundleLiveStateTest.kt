package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * What the client detail's configuration table reads to say whether a bundle's latest revision is live (issue #1001):
 * the bundles summary's latest *published* version beside the latest version, and the overview row's tier.
 */
class BundleLiveStateTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("bundleLive1001", "bundleLive1001")
    val svc = GedraConfigService.get(cxt)

    "the summary names the published version under a draft, and the overview row the client's tier" {
        val client = "bundlelive"
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 10010L }
        fun write(name: String) = svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = name, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        write("First")
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        write("Second")
        GedraConfigReload.reloadClient(cxt, client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

        val main = admin.getItems(CFEP.bundles).single { it[CFEP.name] == "main" }
        main[CFEP.version] shouldBe 2
        main[CFEP.published] shouldBe false
        main[CFEP.publishedVersion] shouldBe 1

        fun row() = admin.getItems(UADEP.clientsOverview).single { it[CLD.clientId] == client }
        row()[CLD.publishedOnly] shouldBe false
        row()[CLD.staticHere] shouldBe false
        svc.setPublishedOnly(setup, client, true)
        row()[CLD.publishedOnly] shouldBe true

        // Published, the latest is its own published version.
        admin.postData(CFEP.bundlePublish, mapOf(CFEP.name to "main"))[CFEP.publishedVersion] shouldBe 2
    }
})
