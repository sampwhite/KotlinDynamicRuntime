package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Choosing the global traits a client includes (issue #1117): the catalog the picker draws -- every global trait as the
 * platform declares it -- and the save, written to the client's stored definition and made to take effect as a
 * definition edit is: live, or a draft for a client with a sandbox. A trait taken away from forms that hold it is
 * refused with the impact report until acknowledged. One shared instance; a client per case.
 */
class ClientTraitsEditTest : StringSpec({
    val cxt = TestInstances.default("clientTraits1117")
    val svc = GedraConfigService.get(cxt)

    /**
     * Defines [client] in stored configuration with a trait of its own (`note`), published and reloaded; including
     * [included], with a sandbox, or as a customer's production client when asked.
     */
    fun defineClient(
        client: String, included: List<String> = emptyList(), sandbox: Boolean = false, customerProduction: Boolean = false,
        on: KdrCxt = cxt,
    ) {
        val setup = on.mkSubContext("setup", client).also { it.userId = 11170L }
        val store = GedraConfigService.get(on)
        store.writeConfig(
            setup,
            gedraConfig(on, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client,
                        usageType = if (customerProduction) ClientUsageType.production else ClientUsageType.dev,
                        audience = if (customerProduction) ClientAudience.customer else ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = included, sandbox = sandbox,
                    ),
                )
                trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") { property("text", "The note.") }
            },
        )
        store.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(on, client)
    }

    fun present(client: String, on: KdrCxt = cxt): ClientDef = ClientService.get(on).present(client)!!

    fun supported(client: String): List<String> =
        SchemaService.get(cxt).supportedGedraTraitsFor(client, present(client)).map { it.traitId }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun include(user: TestUser, vararg entries: String, acknowledge: Boolean = false): Map<String, Any?> =
        user.postData(
            UADEP.clientTraitsSet,
            buildMap {
                put(CLD.includedTraits, entries.toList())
                if (acknowledge) put(IMP.acknowledgeImpact, true)
            },
        )

    "the catalog lists every global trait as the platform declares it, with its fields" {
        val client = "traitcat1117"
        defineClient(client)
        val catalog = admin(client).getItem(UADEP.clientTraits)
        catalog[CLD.client] shouldBe client
        val traits = catalog[CIT.traits].toJsonListOfMaps()
        val ids = traits.map { it[CCT.traitId] }
        ids shouldContain GT.name
        // Global traits only: the client's own trait is supported by being declared, so it is not the picker's.
        ids shouldNotContain "note"
        // Each trait's fields are in the catalog's own schema, so the page can draw them read-only.
        val name = traits.single { it[CCT.traitId] == GT.name }
        name[CCT.description].toOptStr().shouldNotBeNull()
        name[CIT.customized] shouldBe false
        val dataType = refName(name[CIT.dataRef].toOptStr()!!)!!
        catalog[SCH.dDefs].toJsonMapOrEmpty()[dataType].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()
            .keys shouldContain GT.nameField
        catalog.containsKey(CIT.groupsRefused) shouldBe false
    }

    "a client's administrator includes a global trait, and it is live and supported at once" {
        val client = "traitlive1117"
        defineClient(client)
        val chief = admin(client)
        val saved = include(chief, GT.name)
        saved[CLD.client] shouldBe client
        saved[COV.configName] shouldBe "main"
        saved[CPY.mode] shouldBe EDM.live
        saved[CLD.includedTraits].toJsonListOfStrings() shouldBe listOf(GT.name)
        present(client).includedTraits shouldBe listOf(GT.name)
        supported(client) shouldContain GT.name

        // Every global trait, now and to come.
        include(chief, GT.name, CLD.allGlobal)
        val everyGlobal = admin(client).getItem(UADEP.clientTraits)[CIT.traits].toJsonListOfMaps().map { it[CCT.traitId] }
        supported(client).toSet() shouldBe (everyGlobal + "note").toSet()

        // And none: what is left is the client's own.
        include(chief)
        present(client).includedTraits shouldBe emptyList()
        supported(client) shouldBe listOf("note")
    }

    "an entry the save adds must name a global trait, or the one group" {
        val client = "traitbad1117"
        defineClient(client, included = listOf(GT.name))
        val chief = admin(client)
        chief.expectError(EXC.badInput, UADEP.clientTraitsSet, mapOf(CLD.includedTraits to listOf(GT.name, "kdr:nosuch")))
            .toString() shouldContain "not a global trait"
        chief.expectError(EXC.badInput, UADEP.clientTraitsSet, mapOf(CLD.includedTraits to listOf(GT.name, "note")))
            .toString() shouldContain "own trait"
        chief.expectError(EXC.badInput, UADEP.clientTraitsSet, mapOf(CLD.includedTraits to listOf(GT.name, "#someGroup")))
            .toString() shouldContain "no group"
        // A list the client already has changes nothing.
        chief.expectError(EXC.badInput, UADEP.clientTraitsSet, mapOf(CLD.includedTraits to listOf(GT.name)))
            .toString() shouldContain "nothing to save"
        present(client).includedTraits shouldBe listOf(GT.name)
    }

    "an entry already there is kept as it is, though the load drops it" {
        // An entry naming nothing, which a load forgiving stored config -- as a deployment's is -- drops with an issue.
        val warn = TestInstances.storedConfigWarn("clientTraitsWarn1117")
        val client = "traitstale1117"
        defineClient(client, included = listOf("kdr:gone"), on = warn)
        present(client, warn).includedTraits shouldBe emptyList()
        val chief = TestUser.create(warn, "chief@$client.test", level = ROLE.admin, userClient = client)
        val saved = include(chief, "kdr:gone", GT.name)
        saved[CLD.includedTraits].toJsonListOfStrings() shouldBe listOf("kdr:gone", GT.name)
        present(client, warn).includedTraits shouldBe listOf(GT.name)
    }

    "a customer's production client names each trait: the group is refused, and the catalog says why" {
        val client = "traitcustprod1117"
        defineClient(client, customerProduction = true)
        val chief = admin(client)
        val why = chief.getItem(UADEP.clientTraits)[CIT.groupsRefused].toOptStr()
        why.shouldNotBeNull() shouldContain "names each trait"
        chief.expectError(EXC.badInput, UADEP.clientTraitsSet, mapOf(CLD.includedTraits to listOf(CLD.allGlobal)))
            .toString() shouldContain "names each trait"
        include(chief, GT.name)[CPY.mode] shouldBe EDM.live
    }

    "taking away a trait forms hold is refused with the impact report and undone, and saves acknowledged" {
        val client = "traitimpact1117"
        defineClient(client, included = listOf(GT.name))
        val chief = admin(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val form = user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "Kept")))),
        )[GDF.gedraId].toOptStr()!!

        val refused = chief.expectError(EXC.badInput, UADEP.clientTraitsSet, mapOf(CLD.includedTraits to emptyList<String>()))
        refused[EP.errorCode] shouldBe IMP.refusedCode
        val finding = refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().single()
        finding[IMP.kind] shouldBe ImpactKind.traitGone.name
        finding[IMP.traitId] shouldBe GT.name
        finding[IMP.sampleIds].toJsonListOfStrings() shouldContain form
        // Undone: still included, and the configuration left published rather than a draft.
        present(client).includedTraits shouldBe listOf(GT.name)
        svc.readLatest(cxt.mkSubContext("check", client), GedraId.of(GedraConfigType.configDoc, client, "main"))?.isPublished shouldBe true

        include(chief, acknowledge = true)[CPY.mode] shouldBe EDM.live
        supported(client) shouldNotContain GT.name
    }

    "for a client with a sandbox a change is a draft its sandbox runs, and the sandbox is not edited itself" {
        val client = "traitsbx1117"
        defineClient(client, sandbox = true)
        val chief = admin(client)
        val saved = include(chief, GT.name)
        saved[CPY.mode] shouldBe EDM.draft
        // The client runs what it published; its sandbox runs the draft.
        present(client).includedTraits shouldBe emptyList()
        present(sandboxOf(client)).includedTraits shouldBe listOf(GT.name)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        full.expectError(
            EXC.badInput, UADEP.clientTraitsSet, mapOf(CLD.client to sandboxOf(client), CLD.includedTraits to listOf(GT.name)),
        ).toString() shouldContain "is a sandbox"
    }

    "another client's administrator may neither read the catalog nor save for it" {
        val client = "traitmine1117"
        val other = "traittheirs1117"
        defineClient(client)
        defineClient(other)
        val chief = admin(client)
        chief.expectError(EXC.notAuthorized, UADEP.clientTraits, args = mapOf(CLD.client to other))
        chief.expectError(EXC.notAuthorized, UADEP.clientTraitsSet, mapOf(CLD.client to other, CLD.includedTraits to listOf(GT.name)))
        present(other).includedTraits shouldBe emptyList()
        // An administrator who sees every client names it.
        val full = TestUser.createFullAdmin(cxt, "full@$other.test")
        full.postData(UADEP.clientTraitsSet, mapOf(CLD.client to other, CLD.includedTraits to listOf(GT.name)))[CPY.mode] shouldBe EDM.live
        present(other).includedTraits shouldBe listOf(GT.name)
    }
})
