package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Each client keeps its configuration issues, and the client's own reads return them (issue #840): the
 * definition, the cross-client summary, a stored config's summary and bundle, and a reload's result. A client a
 * check dropped still answers its definition read, with the issues that say why.
 *
 * One shared instance with the stored-config check at `warn` -- the forgiving path, which a unit test opts into
 * (issue #839) -- and a new client per scenario. The clients' definitions are deliberately flawed, which is why
 * the check is at warn rather than the unit default.
 */
class ClientConfigIssuesTest : StringSpec({

    val cxt = Startup.mkTestBootCxt(
        "cfgIssues", "clientConfigIssuesTest", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
    )
    val admin = TestUser.createFullAdmin(cxt, "chief@iss840.test")

    fun writer(client: String): KdrCxt = cxt.mkSubContext("issuesWrite", client).also { it.userId = 8400L }

    fun clientDef(client: String, includedTraits: List<String> = emptyList()) = ClientDef(
        clientId = client, name = "Client $client", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = includedTraits,
    )

    /**
     * A config defining [client] with one presented trait -- and, when [reservedTrait] is given, a second trait of
     * that id presented too. A reserved forms-listing field name (`limit`, `sortDir`) as a trait id mints a search
     * parameter that collides with the listing's own: the write accepts it, and the usage-rule check forgives it by
     * dropping the parameter while keeping the client -- a forgiven problem on a client that stays present.
     */
    fun config(client: String, reservedTrait: String? = null, includedTraits: List<String> = emptyList()): GedraConfig =
        gedraConfig(cxt, "${client}cfg", "${client}config", client) {
            defineClient(clientDef(client, includedTraits))
            trait("${client}Entry", "${client}Trait", setOf(GedraDataType.formDoc), "A trait of $client.") {
                property("text", "A value.")
            }
            traitUsage("${client}Trait", "Text", $$"${text}")
            if (reservedTrait != null) {
                val kinds = setOf(GedraDataType.formDoc)
                trait("${client}Reserved", reservedTrait, kinds, "A trait named like a listing field.") {
                    property("text", "A value.")
                }
                traitUsage(reservedTrait, "Reserved", $$"${text}")
            }
        }

    fun storeAndReload(config: GedraConfig) = run {
        GedraConfigService.get(cxt).writeConfig(writer(config.gedraId.client), config)
        GedraConfigReload.reloadClient(cxt, config.gedraId.client)
    }

    "a kept client's forgiven problem is on its definition, its summary row, its stored config and the reload" {
        val client = "iss840kept"
        val result = storeAndReload(config(client, reservedTrait = "sortDir"))

        // The reload reports it, attributed to the stored config that holds the rules.
        val issue = result.issues.single()
        issue.message shouldContain "collide with a reserved forms-listing field"
        issue.origin shouldBe GedraConfigOrigin.stored
        issue.elementKind shouldBe GCEL.usage
        val storedId = issue.storedConfigId.shouldNotBeNull()
        ClientService.get(cxt).present(client).shouldNotBeNull()

        val def = admin.getItem(ADEP.clientDefinition, mapOf(CLD.client to client))
        def[CLD.present] shouldBe true
        val defIssue = def[CLD.issues].toJsonListOfMaps().single()
        defIssue[GCI.message].toOptStr().shouldNotBeNull() shouldContain "collide with a reserved forms-listing field"
        defIssue[GCI.storedConfigId] shouldBe storedId
        defIssue[GCI.origin] shouldBe GedraConfigOrigin.stored.name

        val row = admin.getItems(ADEP.clientSummaries).first { it[CLD.clientId] == client }
        row[CLD.issues].toJsonListOfMaps().single()[GCI.elementKind] shouldBe GCEL.usage

        val summary = admin.getItems(ACEP.bundles, mapOf(CFEP.client to client)).single()
        summary[CFEP.issues].toJsonListOfMaps().single()[GCI.storedConfigId] shouldBe storedId
        val bundle = admin.getItem(ACEP.bundle, mapOf(CFEP.client to client, CFEP.name to "${client}cfg"))
        bundle[CFEP.issues].toJsonListOfMaps().single()[GCI.storedConfigId] shouldBe storedId

        // The reload endpoint returns the same structured issue.
        val reloaded = admin.postData(ACEP.reload, mapOf(CFEP.client to client))
        reloaded[CFEP.issues].toJsonListOfMaps().single()[GCI.message].toOptStr().shouldNotBeNull() shouldContain
            "collide with a reserved forms-listing field"
    }

    "a reload replaces the client's list: fixing the config clears it" {
        val client = "iss840fixed"
        storeAndReload(config(client, reservedTrait = "limit")).issues.size shouldBe 1

        storeAndReload(config(client)).issues.shouldBeEmpty()
        ClientConfigIssues.get(cxt).issuesFor(client).shouldBeEmpty()
        admin.getItem(ADEP.clientDefinition, mapOf(CLD.client to client))[CLD.issues].toJsonListOfMaps().shouldBeEmpty()
    }

    // The B4 warn case, now reported: the re-check dropped the client, and both the reload result and the
    // client's definition read say so, rather than a clean reload of a client that is no longer there.
    "a client a check dropped still answers its definition read, with the issue that says why" {
        val client = "iss840dropped"
        val result = storeAndReload(config(client, includedTraits = listOf("noSuchTrait840")))

        ClientService.get(cxt).known(client) shouldBe null
        val issue = result.issues.single { it.elementKind == GCEL.client }
        issue.message shouldContain "noSuchTrait840"
        issue.elementId shouldBe client

        val def = admin.getItem(ADEP.clientDefinition, mapOf(CLD.client to client))
        def[CLD.present] shouldBe false
        def[CLD.client].toJsonMapOrEmpty()[CLD.clientId] shouldBe client
        def[CLD.traits].toJsonListOfMaps().shouldBeEmpty()
        val defIssue = def[CLD.issues].toJsonListOfMaps().single()
        defIssue[GCI.message].toOptStr().shouldNotBeNull() shouldContain "noSuchTrait840"
    }

    "a client nobody declared is still a 404" {
        admin.expectError(EXC.notFound, ADEP.clientDefinition, args = mapOf(CLD.client to "iss840nobody"))
    }
})
