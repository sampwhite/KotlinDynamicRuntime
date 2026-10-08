package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client's own schema is held to the shapes of the standard keywords it uses (issue #1053), by the two readings
 * of one list: **a write is refused** for a keyword of the wrong shape -- the trial reload finds it, as it finds any
 * new problem -- and **a definition already stored still loads**, the keyword dropped and the fault recorded on the
 * client, validating as it did when it was read leniently.
 *
 * One shared instance with the stored-config check at `warn`, the forgiving path a unit test opts into: the stored
 * definitions here are flawed on purpose.
 */
class StrictSchemaKeywordsTest : StringSpec({
    val cxt: KdrCxt = TestInstances.storedConfigWarn("strictKeywords")
    val admin = TestUser.createFullAdmin(cxt, "chief@strict1053.test")
    val client = "strict1053"
    val ns = clientNamespace(client)
    val writer = cxt.mkSubContext("strictWrite", client).also { it.userId = 10530L }

    GedraConfigService.get(cxt).writeConfig(
        writer,
        gedraConfig(cxt, "main", ns, client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Strict", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        },
    )
    GedraConfigReload.reloadClient(cxt, client)

    fun typeBundle(name: String, typeName: String, schema: Map<String, Any?>): Map<String, Any?> = mapOf(
        CFEP.client to client, CFEP.name to name, CFEP.namespaceField to ns,
        CFEP.slots to mapOf(CCT.schemaDef to listOf(mapOf(CCT.typeName to "$ns.$typeName", CCT.schema to schema))),
    )

    fun card(vararg onType: Pair<String, Any?>, name: Map<String, Any?> = mapOf(SCH.type to SCT.string)) =
        mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("name" to name)) + onType

    "a write of a type whose keyword has the wrong shape is refused, naming the type, the property and the keyword" {
        ClientService.get(cxt).known(client).shouldNotBeNull()
        fun refused(schema: Map<String, Any?>): String =
            admin.expectError(EXC.badInput, ACEP.bundleWrite, typeBundle("types", "Card", schema))[EP.errorMessage].toString()

        // A type that is no type used to be stored, and then constrain nothing. The write refuses it by the path
        // of the keyword (issue #1056) -- the slot, the type, the property, the keyword -- before any trial.
        refused(card(name = mapOf(SCH.type to "strng"))).let {
            it shouldContain "${CCT.schemaDef}[$ns.Card].${CCT.schema}.properties.name.type"
            it shouldContain "sets 'type' to 'strng'"
        }
        refused(card(SCH.required to "name")) shouldContain "sets 'required' to 'name'"
        refused(card(SCH.additionalProperties to "no")) shouldContain "'additionalProperties'"
        refused(card(name = mapOf(SCH.type to SCT.array, SCH.items to "string"))) shouldContain "'items'"
        refused(mapOf(SCH.type to SCT.kObject, SCH.properties to listOf("name"))) shouldContain "'properties'"
        refused(card(name = mapOf(SCH.type to listOf(SCT.string, SCT.kNull)))) shouldContain "a list of types is not supported"
        // Nothing of it was stored.
        admin.expectError(EXC.notFound, ACEP.bundle, args = mapOf(CFEP.client to client, CFEP.name to "types"))

        // The same type well-shaped is written -- and a keyword this layer does not read is the document's own.
        admin.postData(
            ACEP.bundleWrite,
            typeBundle("types", "Card", card(SCH.required to listOf("name"), "examples" to "none", name = mapOf(SCH.type to SCT.string, "x-note" to 5L))),
        )[CFEP.version] shouldBe 1
    }

    "a trait's data schema is held to the same shapes" {
        val trait = mapOf(
            CCT.traitId to "note", CCT.typeName to "$ns.NoteEntry", CCT.appliesTo to listOf(GedraDataType.formDoc.name),
            CCT.dataSchema to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("text" to mapOf(SCH.type to "text"))),
        )
        admin.expectError(
            EXC.badInput, ACEP.bundleWrite,
            mapOf(CFEP.client to client, CFEP.name to "traits", CFEP.namespaceField to ns, CFEP.slots to mapOf(CCT.traitDef to listOf(trait))),
        )[EP.errorMessage].toString() shouldContain "sets 'type' to 'text'"
    }

    "a definition already stored still loads: the keyword is dropped, the fault recorded, the meaning unchanged" {
        // Stored by a service write, which runs no trial -- how a row from before the rule looks.
        GedraConfigService.get(cxt).writeConfig(
            writer,
            gedraConfig(cxt, "legacy", ns, client) {
                type("Legacy") {
                    data.putAll(
                        mapOf(
                            SCH.type to SCT.kObject, SCH.required to "title",
                            SCH.properties to mapOf(
                                "title" to mapOf(SCH.type to "strng", SCH.description to "Its title."),
                                "pages" to mapOf(SCH.type to SCT.integer, SCH.description to "How long."),
                            ),
                        ),
                    )
                }
            },
        )
        val result = GedraConfigReload.reloadClient(cxt, client)

        // Both faults are on the client's issue list, each saying what it is and what was done.
        val issues = result.issues.filter { "Legacy" in it.message }
        issues.size shouldBe 2
        issues.single { "'required'" in it.message }.let {
            it.message shouldContain "Type '$ns.Legacy'"
            it.degradedTo shouldContain "Dropping 'required'"
        }
        issues.single { "'type'" in it.message }.message shouldContain "property 'title' sets 'type' to 'strng'"
        ClientConfigIssues.get(cxt).issuesFor(client).filter { "Legacy" in it.message }.size shouldBe 2

        // And the type the client runs is the one the lenient reading made: nothing required, `title` untyped, and
        // the sound property as it was.
        val legacy = SchemaService.get(cxt).storeFor(client).types.getValue("$ns.Legacy")
        legacy.required.shouldBeEmpty()
        legacy.properties.getValue("title").valueType.jsonType shouldBe null
        legacy.properties.getValue("title").valueType.description shouldBe "Its title."
        legacy.properties.getValue("pages").valueType.jsonType shouldBe SCT.integer

        // The fault being one the client already has, an unrelated write is not refused over it.
        admin.postData(ACEP.bundleWrite, typeBundle("more", "More", card()))[CFEP.version] shouldBe 1
    }
})
