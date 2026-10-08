package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Managing a client's Shadow Sandbox from the clients page (issue #932): adding or removing it sets the `sandbox` flag
 * of the client's stored definition, published and reloaded, so the sandbox appears or is withdrawn at once and the
 * client's tier follows (a client with a sandbox runs only published configuration). The listing shows each sandbox
 * beside its parent. One booted instance; a client per case.
 */
class SandboxManageTest : StringSpec({
    val cxt = TestInstances.default("sandboxManage932")
    val svc = GedraConfigService.get(cxt)

    /** Defines [client] -- no sandbox -- and publishes and reloads it. */
    fun defineClient(client: String) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 9320L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun present(client: String) = ClientService.get(cxt).isPresent(client)

    "adding a sandbox publishes the flag, loads the sandbox, and puts the client on published-only; removing withdraws it" {
        val client = "sbxmanage"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        svc.publishedOnly(cxt, client) shouldBe false

        val added = admin.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        added[CLD.client] shouldBe client
        added[CLD.sandbox] shouldBe true
        added[CLD.publishedOnly] shouldBe true
        present(sandboxOf(client)) shouldBe true
        // Asked again, nothing changes and nothing is refused.
        admin.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))[CLD.sandbox] shouldBe true

        val removed = admin.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to false))
        removed[CLD.sandbox] shouldBe false
        removed[CLD.publishedOnly] shouldBe false
        present(sandboxOf(client)) shouldBe false
        present(client) shouldBe true
    }

    "the listing shows a sandbox beside its parent, each saying which it is" {
        val client = "sbxlisted"
        defineClient(client)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        full.postData(UADEP.clientSandbox, mapOf(CLD.client to client, CLD.sandbox to true))
        val rows = full.getItems(UADEP.clientsOverview).associateBy { it[CLD.clientId] }
        rows.getValue(client)[CLD.hasSandbox] shouldBe true
        rows.getValue(client).containsKey(CLD.sandboxOf) shouldBe false
        val sandboxRow = rows.getValue(sandboxOf(client))
        sandboxRow[CLD.sandboxOf] shouldBe client
        sandboxRow[CLD.hasSandbox] shouldBe false
        // A sandbox holds no stored configuration of its own; the parent's source configs it carries are not counted.
        sandboxRow[CLD.storedConfigs] shouldBe 1
    }

    "a sandbox, a source-defined client, and a definition with unpublished changes are refused" {
        val client = "sbxrefused"
        defineClient(client)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        full.postData(UADEP.clientSandbox, mapOf(CLD.client to client, CLD.sandbox to true))
        full.expectError(EXC.badInput, UADEP.clientSandbox, mapOf(CLD.client to sandboxOf(client), CLD.sandbox to true))
        full.expectError(EXC.badInput, UADEP.clientSandbox, mapOf(CLD.client to full.selfClient(), CLD.sandbox to true))

        // An unpublished change to the definition's configuration would go live with the flag: refused.
        val other = "sbxdraft932"
        defineClient(other)
        val chief = TestUser.create(cxt, "chief@$other.test", level = ROLE.admin, userClient = other)
        svc.writeConfig(
            cxt.mkSubContext("draft", other).also { it.userId = 9321L },
            gedraConfig(cxt, "main", clientNamespace(other), other) {
                defineClient(
                    ClientDef(
                        clientId = other, name = "Renamed $other", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        chief.expectError(EXC.badInput, UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        present(sandboxOf(other)) shouldBe false
        // A client administrator names only their own client.
        chief.expectError(EXC.notAuthorized, UADEP.clientSandbox, mapOf(CLD.client to client, CLD.sandbox to false))
    }
})
