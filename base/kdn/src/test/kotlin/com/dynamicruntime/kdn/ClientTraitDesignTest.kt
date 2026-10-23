package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.TraitChoiceDesign
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.traitDesignFromInfo
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.ADEP
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
 * Designing a client's own traits (issue #1118): a new trait from a design, in a configuration of its own, with keys
 * made from its labels and its labels in the layout; an edit of one the client stores, keys kept and kinds held; the
 * impact report for an edit that strands stored data; the plan's limits; and a sandbox's draft. One shared instance; a
 * client per case.
 */
class ClientTraitDesignTest : StringSpec({
    val cxt = TestInstances.default("traitDesign1118")
    val svc = GedraConfigService.get(cxt)

    /** Defines [client] in stored configuration with a hand-written trait of its own (`note`), published and reloaded. */
    fun defineClient(client: String, sandbox: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11180L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                    ),
                )
                trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") { property("text", "The note.") }
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun field(label: String, kind: TraitFieldKind, vararg choices: String, required: Boolean = false) =
        TraitFieldDesign(null, label, kind, required = required, choices = choices.map { TraitChoiceDesign(null, it) })

    fun save(user: TestUser, design: TraitDesign, acknowledge: Boolean = false, client: String? = null): Map<String, Any?> =
        user.postData(
            UADEP.clientTraitDesign,
            buildMap {
                client?.let { put(CLD.client, it) }
                put(CIT.design, design.toInfo())
                if (acknowledge) put(IMP.acknowledgeImpact, true)
            },
        )

    fun designOf(saved: Map<String, Any?>): TraitDesign = traitDesignFromInfo(saved[CIT.design].toJsonMapOrEmpty())

    fun supported(client: String): List<String> = SchemaService.get(cxt).gedraTraitsFor(client).map { it.traitId }

    val safety = TraitDesign(
        null, "Site safety", "How a site is kept safe.",
        listOf(
            field("Do you inspect weekly?", TraitFieldKind.yesNo),
            field("Notes", TraitFieldKind.longText),
            field("Hazards", TraitFieldKind.choices, "Falls", "Fire"),
        ),
    )

    "a new trait gets a configuration of its own, keys from its labels, and its labels in the layout" {
        val client = "tdnew1118"
        defineClient(client)
        val chief = admin(client)
        val saved = save(chief, safety)
        saved[CPY.mode] shouldBe EDM.live
        saved[CIT.configName] shouldBe "trait_siteSafety"
        val design = designOf(saved)
        design.traitId shouldBe "siteSafety"
        design.fields.map { it.key } shouldBe listOf("doYouInspectWeekly", "notes", "hazards")
        design.fields[2].choices.map { it.value } shouldBe listOf("falls", "fire")
        // Live, and supported: its own trait needs no including.
        supported(client) shouldContain "siteSafety"
        val layout = SchemaService.get(cxt).storeFor(client).layouts.values.single { l -> l.fieldNames.contains("doYouInspectWeekly") }
        layout.label shouldBe "Site safety"
        layout.fields.map { it.label } shouldBe listOf("Do you inspect weekly?", "Notes", "Hazards")
        layout.fields.map { it.multiline } shouldBe listOf(false, true, false)
        // The catalog lists it with its design, beside the hand-written trait the editor can edit too.
        val own = chief.getItem(UADEP.clientTraits)[CIT.ownTraits].toJsonListOfMaps().associateBy { it[CIT.traitId] }
        own["siteSafety"]?.get(CIT.configName) shouldBe "trait_siteSafety"
        own["note"]?.get(CIT.design).shouldNotBeNull()
        // A form can hold it.
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "siteSafety", GE.data to mapOf("hazards" to listOf("fire"), "notes" to "Line one\nLine two")))),
        )[GDF.gedraId].toOptStr().shouldNotBeNull()
    }

    "an edit keeps keys, relabels freely, and holds a kept field to its kind" {
        val client = "tdedit1118"
        defineClient(client)
        val chief = admin(client)
        val made = designOf(save(chief, safety))
        val relabeled = TraitDesign(
            made.traitId, "Safety on site", made.description,
            listOf(
                TraitFieldDesign("doYouInspectWeekly", "Weekly inspections?", TraitFieldKind.yesNo),
                // Long text to text keeps the data.
                TraitFieldDesign("notes", "Remarks", TraitFieldKind.text),
                made.fields[2],
                field("Notes", TraitFieldKind.text),
            ),
        )
        val edited = designOf(save(chief, relabeled))
        // The new field's key is made from its label, beside the kept "notes".
        edited.fields.map { it.key } shouldBe listOf("doYouInspectWeekly", "notes", "hazards", "notes2")
        edited.label shouldBe "Safety on site"
        chief.expectError(
            EXC.badInput, UADEP.clientTraitDesign,
            mapOf(CIT.design to TraitDesign(made.traitId, "S", null, listOf(TraitFieldDesign("notes", "N", TraitFieldKind.number))).toInfo()),
        ).toString() shouldContain "never changes"
        // A hand-written trait the editor can read is edited where it is stored.
        val note = TraitDesign("note", "Note", "A note.", listOf(TraitFieldDesign("text", "The text", TraitFieldKind.longText)))
        save(chief, note)[CIT.configName] shouldBe "main"
        chief.expectError(EXC.notFound, UADEP.clientTraitDesign, mapOf(CIT.design to TraitDesign("nosuch", "N", null, safety.fields).toInfo()))
    }

    "an edit that strands stored data is refused with the impact report until acknowledged" {
        val client = "tdimpact1118"
        defineClient(client)
        val chief = admin(client)
        val made = designOf(save(chief, safety))
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val form = user.postItem(
            GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "siteSafety", GE.data to mapOf("hazards" to listOf("fire"))))),
        )[GDF.gedraId].toOptStr()!!
        // Required in the data, which the stored entry does not hold.
        val requiring = TraitDesign(
            made.traitId, made.label, made.description,
            made.fields.map { if (it.key == "doYouInspectWeekly") TraitFieldDesign(it.key, it.label, it.kind, required = true) else it },
        )
        val refused = chief.expectError(EXC.badInput, UADEP.clientTraitDesign, mapOf(CIT.design to requiring.toInfo()))
        refused[EP.errorCode] shouldBe IMP.refusedCode
        val finding = refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().single()
        finding[IMP.kind] shouldBe ImpactKind.dataInvalid.name
        finding[IMP.sampleIds].toJsonListOfStrings() shouldContain form
        save(chief, requiring, acknowledge = true)[CPY.mode] shouldBe EDM.live
    }

    "a free client's trait is held to its plan's attributes, and the catalog says its limits" {
        val operator = TestUser.createFullAdmin(cxt, "operator@traitDesign1118.test")
        val client = "${CUST.free}_td1118"
        operator.postData(
            ADEP.clientCreate,
            mapOf(
                CLD.clientId to client, CLD.name to "Free", CLD.usageType to ClientUsageType.dev.name,
                CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            ),
        )
        val chief = admin(client)
        val quotas = chief.getItem(UADEP.clientTraits)[CIT.quotas].toJsonMapOrEmpty()
        quotas[CIT.attributesLimit] shouldBe 10
        quotas[CIT.schemasLimit] shouldBe 10
        val tooMany = TraitDesign(null, "Big", null, (1..11).map { field("Field $it", TraitFieldKind.text) })
        chief.expectError(EXC.badInput, UADEP.clientTraitDesign, mapOf(CIT.design to tooMany.toInfo())).toString() shouldContain
            "with 11 attributes (the plan allows 10)"
        save(chief, TraitDesign(null, "Big", null, (1..10).map { field("Field $it", TraitFieldKind.text) }))[CPY.mode] shouldBe EDM.live
    }

    "for a client with a sandbox a new trait is a draft its sandbox runs" {
        val client = "tdsbx1118"
        defineClient(client, sandbox = true)
        val saved = save(admin(client), safety)
        saved[CPY.mode] shouldBe EDM.draft
        supported(client) shouldNotContain "siteSafety"
        supported(sandboxOf(client)) shouldContain "siteSafety"
    }

    "another client's administrator may not design its traits" {
        val client = "tdmine1118"
        val other = "tdtheirs1118"
        defineClient(client)
        defineClient(other)
        admin(client).expectError(EXC.notAuthorized, UADEP.clientTraitDesign, mapOf(CLD.client to other, CIT.design to safety.toInfo()))
        supported(other) shouldNotContain "siteSafety"
    }
})
