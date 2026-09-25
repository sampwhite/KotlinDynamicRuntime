package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client's `audience` and `usageType` carry authority over it, so a client may not set them for itself (issue
 * #820): a client administrator's write must keep them as they are, while a platform operator -- an `allClients`
 * administrator, or a service write -- may set them. Before, a data-defined client's own administrator could write
 * `audience: internal` or `usageType: dev` and lift the functional-group restriction on its own client.
 */
class ClientOperatorFieldsTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("opFields", "clientOperatorFieldsTest")
    val client = "op820"

    fun def(
        name: String = client,
        usageType: ClientUsageType = ClientUsageType.dev,
        audience: ClientAudience = ClientAudience.internal,
    ) = ClientDef(
        clientId = client, name = name, usageType = usageType, audience = audience,
        enabledEnvironments = setOf(ENV.unit, ENV.local),
    )

    fun config(clientDef: ClientDef): GedraConfig =
        gedraConfig(cxt, "main", "${client}config", client) { defineClient(clientDef) }

    fun writeBody(clientDef: ClientDef): Map<String, Any?> = mapOf(
        CFEP.name to "main", CFEP.namespaceField to "${client}config",
        CFEP.slots to gedraConfigToEntries(config(clientDef)),
    )

    // The client is created by a service write -- an operator's -- as internal and dev.
    val creator = cxt.mkSubContext("opCreate", client).also { it.userId = 8200L }
    GedraConfigService.get(cxt).writeConfig(creator, config(def()))
    GedraConfigReload.reloadClient(cxt, client)
    val clientAdmin = TestUser.create(cxt, "admin@op820.test", level = ROLE.admin, userClient = client)

    "a client administrator may rewrite its definition, keeping the operator's fields" {
        clientAdmin.postData(CFEP.bundleWrite, writeBody(def(name = "Op 820")))
        GedraConfigReload.reloadClient(cxt, client)
        ClientService.get(cxt).known(client).shouldNotBeNull().name shouldBe "Op 820"
    }

    "a client administrator's write changing audience or usageType is refused" {
        val raised = clientAdmin.expectError(
            EXC.notAuthorized, CFEP.bundleWrite, writeBody(def(audience = ClientAudience.customer)),
        )
        raised[EP.errorMessage].toString() shouldContain CLD.audience
        clientAdmin.expectError(
            EXC.notAuthorized, CFEP.bundleWrite, writeBody(def(usageType = ClientUsageType.production)),
        )[EP.errorMessage].toString() shouldContain "${CLD.usageType} '${ClientUsageType.dev.name}'"
    }

    "a client administrator's patch changing usageType is refused" {
        val patched = def().toInfo() + (CLD.usageType to ClientUsageType.demo.name)
        clientAdmin.expectError(
            EXC.notAuthorized, CFEP.bundlePatch,
            mapOf(
                CFEP.name to "main",
                CFEP.edits to listOf(
                    mapOf(
                        CFEP.slot to CCT.clientDef, GED.action to GedraEditAction.addOrReplace.name, GE.data to patched,
                    ),
                ),
            ),
        )[EP.errorMessage].toString() shouldContain CLD.usageType
    }

    "a platform operator may set them" {
        val operator = TestUser.createFullAdmin(cxt, "chief@op820.test")
        operator.postData(
            ACEP.bundleWrite, writeBody(def(audience = ClientAudience.customer)) + (CFEP.client to client),
        )
        GedraConfigReload.reloadClient(cxt, client)
        ClientService.get(cxt).known(client).shouldNotBeNull().audience shouldBe ClientAudience.customer
    }
})
