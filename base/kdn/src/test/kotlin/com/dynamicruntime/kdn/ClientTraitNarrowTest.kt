package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.gedra.TraitNarrowing
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.traitDesignFromInfo
import com.dynamicruntime.common.gedra.traitNarrowingFromInfo
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SLM
import com.dynamicruntime.common.startup.ComponentDefinition
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Narrowing and extending a global trait (issue #1119): a narrowing hides fields in the layout -- the data still
 * accepts them -- and requires fields and restricts choices in the data, written as the client's alteration of the
 * trait's data type; the impact report for a narrowing that strands stored data, live or as a sandbox's draft; and an
 * extension, a trait of the client's own that is a global trait plus the fields it adds. Over an instance with a global
 * trait to narrow ([TraitBaseComponent]); a client per case.
 */
class ClientTraitNarrowTest : StringSpec({
    val cxt = TestInstances.traitBases("traitNarrow1119")
    val survey = TraitBaseComponent.survey

    fun defineClient(client: String, sandbox: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11190L }
        val svc = GedraConfigService.get(cxt)
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox, includedTraits = listOf(survey),
                    ),
                )
            },
        )
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun narrow(user: TestUser, narrowing: TraitNarrowing, acknowledge: Boolean = false): Map<String, Any?> =
        user.postData(
            UADEP.clientTraitNarrow,
            buildMap {
                put(CIT.narrowing, narrowing.toInfo())
                if (acknowledge) put(IMP.acknowledgeImpact, true)
            },
        )

    fun form(user: TestUser, data: Map<String, Any?>): String =
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to survey, GE.data to data))))[GDF.gedraId].toOptStr()!!

    fun refusalOf(user: TestUser, data: Map<String, Any?>) =
        user.expectError(EXC.badInput, GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to survey, GE.data to data))))

    "a narrowing hides in the layout, and requires and restricts in the data" {
        val client = "nrw1119"
        defineClient(client)
        val chief = admin(client)
        val saved = narrow(chief, TraitNarrowing(survey, hidden = setOf("notes"), required = setOf("topic"), choices = mapOf("topic" to listOf("b"))))
        saved[CPY.mode] shouldBe EDM.live
        saved[CIT.configName] shouldBe "narrow_fixture_survey"
        // The layout leaves the hidden field out; the data still accepts it.
        val layout = SchemaService.get(cxt).storeFor(client).layouts.getValue(TraitBaseComponent.dataType)
        layout.mode.name shouldBe SLM.authoritative
        layout.fieldNames shouldNotContain "notes"
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        form(user, mapOf("name" to "x", "topic" to "b", "notes" to "still accepted")).shouldNotBeNull()
        // Required and restricted in the data.
        refusalOf(user, mapOf("name" to "x")).toString() shouldContain "topic"
        refusalOf(user, mapOf("name" to "x", "topic" to "a")).toString() shouldContain "topic"
        // The catalog reads it back.
        val trait = chief.getItem(UADEP.clientTraits)[CIT.traits].toJsonListOfMaps().single { it[CCT.traitId] == survey }
        traitNarrowingFromInfo(trait[CIT.narrowing].toJsonMapOrEmpty()).hidden shouldBe setOf("notes")
        trait[CIT.narrowingConfig] shouldBe "narrow_fixture_survey"
        // A field the trait requires cannot be hidden.
        chief.expectError(
            EXC.badInput, UADEP.clientTraitNarrow, mapOf(CIT.narrowing to TraitNarrowing(survey, hidden = setOf("name")).toInfo()),
        ).toString() shouldContain "required by the trait"
        // And an empty narrowing takes the client back to the trait as declared.
        narrow(chief, TraitNarrowing(survey))
        SchemaService.get(cxt).overlaidTypesFor(client) shouldNotContain TraitBaseComponent.dataType
        form(user, mapOf("name" to "x", "topic" to "a")).shouldNotBeNull()
        // Narrowing again writes into the configuration the first made, which taking it back left empty.
        narrow(chief, TraitNarrowing(survey, required = setOf("topic")))[CIT.configName] shouldBe "narrow_fixture_survey"
        SchemaService.get(cxt).overlaidTypesFor(client) shouldContain TraitBaseComponent.dataType
        refusalOf(user, mapOf("name" to "x")).toString() shouldContain "topic"
        chief.getItem(UADEP.clientTraits)[CIT.traits].toJsonListOfMaps().single { it[CCT.traitId] == survey }[CIT.narrowingConfig] shouldBe
            "narrow_fixture_survey"
    }

    "a narrowing that strands stored data is refused with the impact report until acknowledged" {
        val client = "nrwimpact1119"
        defineClient(client)
        val chief = admin(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val held = form(user, mapOf("name" to "x", "topic" to "a"))
        val refused = chief.expectError(
            EXC.badInput, UADEP.clientTraitNarrow, mapOf(CIT.narrowing to TraitNarrowing(survey, choices = mapOf("topic" to listOf("b"))).toInfo()),
        )
        refused[EP.errorCode] shouldBe IMP.refusedCode
        val finding = refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().single()
        finding[IMP.kind] shouldBe ImpactKind.dataInvalid.name
        finding[IMP.sampleIds].toJsonListOfStrings() shouldContain held
        // Hiding changes no data, so it judges none.
        narrow(chief, TraitNarrowing(survey, hidden = setOf("notes")))[CPY.mode] shouldBe EDM.live
        narrow(chief, TraitNarrowing(survey, hidden = setOf("notes"), choices = mapOf("topic" to listOf("b"))), acknowledge = true)[CPY.mode] shouldBe EDM.live
    }

    "a sandbox's narrowing is a draft, and its impact on the live client is said at the save" {
        val client = "nrwsbx1119"
        defineClient(client, sandbox = true)
        val chief = admin(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val held = form(user, mapOf("name" to "x", "topic" to "a"))
        val saved = narrow(chief, TraitNarrowing(survey, required = setOf("notes")))
        saved[CPY.mode] shouldBe EDM.draft
        val impact = saved[CIT.draftImpact].toJsonMapOrEmpty()
        impact[IMP.findings].toJsonListOfMaps().single()[IMP.sampleIds].toJsonListOfStrings() shouldContain held
        // The client runs what it published; its sandbox the draft.
        SchemaService.get(cxt).overlaidTypesFor(client) shouldNotContain TraitBaseComponent.dataType
        SchemaService.get(cxt).overlaidTypesFor(sandboxOf(client)) shouldContain TraitBaseComponent.dataType
    }

    "an extension is the global trait plus the fields it adds" {
        val client = "ext1119"
        defineClient(client)
        val chief = admin(client)
        val design = TraitDesign(
            null, "Survey plus", null,
            listOf(TraitFieldDesign(null, "Name", TraitFieldKind.text), TraitFieldDesign(null, "Score", TraitFieldKind.integer, required = true)),
            extendsTrait = survey,
        )
        val saved = chief.postData(UADEP.clientTraitDesign, mapOf(CIT.design to design.toInfo()))
        saved[CIT.configName] shouldBe "trait_surveyPlus"
        val made = traitDesignFromInfo(saved[CIT.design].toJsonMapOrEmpty())
        made.extendsTrait shouldBe survey
        // The base's own "name" is taken, so the added field gets the next key.
        made.fields.map { it.key } shouldBe listOf("name2", "score")
        // A form holds the base's fields and the added ones, and both requirements hold.
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val entry = mapOf(GE.traitId to "surveyPlus", GE.data to mapOf("name" to "x", "topic" to "a", "name2" to "y", "score" to 3))
        user.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry)))[GDF.gedraId].toOptStr().shouldNotBeNull()
        user.expectError(
            EXC.badInput, GEP.formDocCreate,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to "surveyPlus", GE.data to mapOf("score" to 3)))),
        ).toString() shouldContain "name"
        // Listed with its design, and edited by adding a field; what it extends cannot change.
        val own = chief.getItem(UADEP.clientTraits)[CIT.ownTraits].toJsonListOfMaps().single { it[CIT.traitId] == "surveyPlus" }
        traitDesignFromInfo(own[CIT.design].toJsonMapOrEmpty()).extendsTrait shouldBe survey
        val more = TraitDesign(made.traitId, made.label, null, made.fields + TraitFieldDesign(null, "Notes", TraitFieldKind.longText), survey)
        traitDesignFromInfo(chief.postData(UADEP.clientTraitDesign, mapOf(CIT.design to more.toInfo()))[CIT.design].toJsonMapOrEmpty())
            .fields.map { it.key } shouldBe listOf("name2", "score", "notes2")
        chief.expectError(
            EXC.badInput, UADEP.clientTraitDesign, mapOf(CIT.design to TraitDesign(made.traitId, made.label, null, made.fields, null).toInfo()),
        ).toString() shouldContain "extends never changes"
    }

    "a trait with no such global trait to extend or narrow is refused" {
        val client = "extbad1119"
        defineClient(client)
        val chief = admin(client)
        chief.expectError(
            EXC.notFound, UADEP.clientTraitNarrow, mapOf(CIT.narrowing to TraitNarrowing("fixture:nosuch").toInfo()),
        )
        chief.expectError(
            EXC.badInput, UADEP.clientTraitDesign,
            mapOf(CIT.design to TraitDesign(null, "X", null, listOf(TraitFieldDesign(null, "A", TraitFieldKind.text)), "fixture:nosuch").toInfo()),
        ).toString() shouldContain "no global trait"
        // Nothing was narrowed.
        chief.getItem(UADEP.clientTraits)[CIT.traits].toJsonListOfMaps().single { it[CCT.traitId] == survey }[CIT.narrowingConfig].shouldBeNull()
    }
})

/** A global trait to narrow and extend (issue #1119): a required name, an open choice, several choices, and a note. */
class TraitBaseComponent : ComponentDefinition {
    override val providerName: String = "traitBaseFixture"
    override val ownerRoot: String = "fixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "traitBases", "fixture.traits") {
            trait("SurveyEntry", survey, setOf(GedraDataType.formDoc), "A survey to narrow.") {
                property("name", "Who answers.", required = true)
                property("topic", "What it is about.") {
                    option("a", "A")
                    option("b", "B")
                    openOptions()
                }
                property("tags", "Tags.") {
                    type = SCT.array
                    items {
                        option("x")
                        option("y")
                    }
                }
                property("notes", "Anything else.")
            }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val survey = "fixture:survey"
        const val dataType = "fixture.traits.SurveyData"
    }
}
