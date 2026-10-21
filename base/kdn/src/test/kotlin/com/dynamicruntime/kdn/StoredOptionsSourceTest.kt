package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Which options sources a client's **stored** schema may name (issue #1193). An id is global, so without a rule a
 * client's administrator could put any registered source on a field of their own, and the catalog would resolve it
 * for whoever is shown that client's forms -- handing over a list written for another surface and scoped by that
 * surface's gate, such as every customer's id and name. Only a source registered with `storedConfigMayName`, which
 * answers per caller, may be named: any other is refused when written and dropped, with an issue, when loaded.
 *
 * On the shared instance whose stored-config check is `warn`, so a config written through the service -- which does
 * not trial -- loads and reports rather than refusing; the admin endpoint's write is where the trial refuses.
 */
class StoredOptionsSourceTest : StringSpec({

    val cxt = TestInstances.storedConfigWarn("storedOptions")
    val admin = TestUser.createFullAdmin(cxt, "chief@options1193.test")

    fun writer(on: KdrCxt, client: String): KdrCxt = on.mkSubContext("optionsWrite", client).also { it.userId = 11930L }

    fun clientDef(client: String) = ClientDef(
        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local),
    )

    /** [client]'s definition and a trait whose one field sources its choices from [source]. */
    fun config(client: String, source: String): GedraConfig =
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(clientDef(client))
            trait("Profile", "profile", setOf(GedraDataType.formDoc), "Who the form is about.") {
                property("pick", "A choice from a sourced list.") { optionsSource(source) }
                property("note", "A note.")
            }
        }

    fun writeBody(config: GedraConfig): Map<String, Any?> = mapOf(
        CFEP.client to config.gedraId.client, CFEP.name to config.name, CFEP.namespaceField to config.namespace,
        CFEP.slots to gedraConfigToEntries(config),
    )

    fun profileType(client: String) = "${clientNamespace(client)}.ProfileData"

    /** The `pick` field of [client]'s compiled trait type, as this node holds it. */
    fun storedPick(client: String): Map<String, Any?> =
        SchemaService.get(cxt).storeFor(client).defs[profileType(client)].toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()["pick"].toJsonMapOrEmpty()

    /** The values [user] is offered for `pick` on [client]'s form-create surface, as the catalog serves it. */
    fun offered(user: TestUser, client: String): List<String> {
        val args = mapOf(EI.method to "POST", EI.path to GEP.formDocCreate, EI.resolveClient to true, EI.client to client)
        return user.getData("/schema/endpoint", args)[SCH.dDefs].toJsonMapOrEmpty()[profileType(client)]
            .toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()["pick"].toJsonMapOrEmpty()[SCH.options]
            .toJsonListOfMaps().mapNotNull { it[SCH.value].toOptStr() }
    }

    "a stored field naming a source stored config may not name is refused at write" {
        val client = "opt1193w"
        val refused = admin.expectError(EXC.badInput, ACEP.bundleWrite, writeBody(config(client, CUST.customerOptions)))
        refused[EP.errorMessage].toString() shouldContain
            "sources its options from '${CUST.customerOptions}', which stored configuration may not name"
        // Nothing stored: the client has no stored configuration at all.
        admin.expectError(EXC.notFound, ACEP.bundles, args = mapOf(CFEP.client to client))
    }

    "and one already stored is dropped at load, with the issue said" {
        val client = "opt1193l"
        // Stored through the service, which does not trial: a config written before the rule, as far as the load
        // can tell.
        GedraConfigService.get(cxt).writeConfig(writer(cxt, client), config(client, CUST.customerOptions))
        val result = GedraConfigReload.reloadClient(cxt, client)
        result.loaded shouldBe 1
        val issue = result.issues.single()
        issue.message shouldContain "which stored configuration may not name"
        issue.elementKind shouldBe GCEL.type
        issue.elementId shouldBe profileType(client)
        issue.origin shouldBe GedraConfigOrigin.stored
        // The field stands, taking free input; the rest of the type with it.
        storedPick(client).containsKey(SCH.optionsSource) shouldBe false
        storedPick(client)[SCH.type] shouldBe "string"
    }

    "a source that answers per caller is kept, and each caller is offered their own answer" {
        val client = "opt1193ok"
        admin.postData(ACEP.bundleWrite, writeBody(config(client, CLD.clientOptions)))
        GedraConfigReload.reloadClient(cxt, client).issues.size shouldBe 0
        storedPick(client)[SCH.optionsSource] shouldBe CLD.clientOptions

        // A pair, as ClientOptionsTest's are: the client's own member is offered only their client, while an
        // administrator of every client reading the same surface is offered more.
        val member = TestUser.create(cxt, "member@$client.test", userClient = client)
        offered(member, client) shouldContainExactly listOf(client)
        offered(admin, client) shouldContain CL.hub
    }

    "a stored extension may restate a source its base names, on that field and no other" {
        val client = "opt1193ext"
        // The client-create form, a global type offering the customer list on its customer field.
        val createType = SchemaService.get(cxt).schemaStore.defs.keys.single { it.endsWith(".${CLD.createTypeName}") }
        val config = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(clientDef(client))
            type("Made") {
                extends(createType)
                property(CLD.customerId, "Who owns it, worded our way.") { optionsSource(CUST.customerOptions) }
                property("shade", "The same list on a field the base does not offer it on.") {
                    optionsSource(CUST.customerOptions)
                }
            }
        }
        GedraConfigService.get(cxt).writeConfig(writer(cxt, client), config)
        val issue = GedraConfigReload.reloadClient(cxt, client).issues.single()
        issue.message shouldContain "property 'shade'"
        issue.message shouldContain "which stored configuration may not name"
        val made = SchemaService.get(cxt).storeFor(client).defs["${clientNamespace(client)}.Made"].toJsonMapOrEmpty()
        made[SCH.properties].toJsonMapOrEmpty()[CLD.customerId].toJsonMapOrEmpty()[SCH.optionsSource] shouldBe
            CUST.customerOptions
    }
})
