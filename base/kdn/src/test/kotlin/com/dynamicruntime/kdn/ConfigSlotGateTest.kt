package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.configSlotFailures
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.requireWritableSlots
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import com.dynamicruntime.common.schema.MSCH
import com.dynamicruntime.common.schema.SchMetaSchema
import com.dynamicruntime.common.schema.parseSchemaTypes
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The config slot gate (issue #1052): every slot entry a configuration **write** brings is held to the shape
 * `coreConfigTraits` declares for its slot -- where it used to be read leniently, a missing field becoming an empty
 * one and a wrong one a default. A write is judged for what it changes, so a fault a stored configuration already
 * has does not refuse an unrelated write to it.
 */
class ConfigSlotGateTest : StringSpec({
    val cxt: KdrCxt = Startup.mkTestBootCxt("slotGate", "configSlotGateTest")

    fun fullAdmin(): TestUser = TestUser.createFullAdmin(cxt, "gate1052@example.com")

    /** The gate's failures for one slot's [entries], as path to code. */
    fun codes(slot: String, vararg entries: Map<String, Any?>): Map<String, SchFailCode> =
        configSlotFailures(cxt, mapOf(slot to entries.toList())).associate { it.path to it.code }

    fun usage(trait: String = "a", display: Any? = "\${name}") =
        mapOf(CCT.traitId to trait, CCT.label to "A", CCT.display to display, CCT.kind to UsageKind.string.name, CCT.substring to false)

    fun cfact(name: String = "ready", group: Any? = "grp") =
        mapOf(CCT.name to name, CCT.group to group, CCT.description to "Ready.", CCT.toFrontend to false)

    "a usage rule is held to its shape, and its display to being a template that parses" {
        codes(CCT.usageDef, usage()).shouldBeEmpty()
        codes(CCT.usageDef, usage() - CCT.label) shouldBe mapOf("${CCT.usageDef}[a].${CCT.label}" to SchFailCode.missingRequired)
        codes(CCT.usageDef, usage() + (CCT.kind to "text")) shouldBe mapOf("${CCT.usageDef}[a].${CCT.kind}" to SchFailCode.invalidOption)
        // A flag is a boolean: the reassembly reads anything else as false.
        codes(CCT.usageDef, usage() + (CCT.substring to "yes")) shouldBe mapOf("${CCT.usageDef}[a].${CCT.substring}" to SchFailCode.wrongType)
        codes(CCT.usageDef, usage() + ("lable" to "A")) shouldBe mapOf("${CCT.usageDef}[a].lable" to SchFailCode.additionalProperty)
        // A template that does not parse blanks its column for every row and says nothing; here it is said.
        val malformed = configSlotFailures(cxt, mapOf(CCT.usageDef to listOf(usage(display = "\${name"))))
        malformed.single().path shouldBe "${CCT.usageDef}[a].${CCT.display}"
        malformed.single().code shouldBe SchFailCode.badValue
        malformed.single().message shouldContain "not a template that parses"
        // An entry with no key is named by its place, and its missing key is among its failures.
        // By place, counted from 0 as an array's items are: `[#1]` is the second entry.
        codes(CCT.usageDef, usage(), usage() - CCT.traitId) shouldBe mapOf("${CCT.usageDef}[#1].${CCT.traitId}" to SchFailCode.missingRequired)
    }

    "a cfact, a fragment overlay and a UiBlock overlay are each held to their shapes" {
        codes(CCT.cfactDef, cfact()).shouldBeEmpty()
        // A cfact with only a name used to be stored with an empty group and description.
        codes(CCT.cfactDef, mapOf(CCT.name to "ready")).keys shouldBe
            setOf("${CCT.cfactDef}[ready].${CCT.group}", "${CCT.cfactDef}[ready].${CCT.description}")
        codes(CCT.cfactDef, cfact() + (CCT.toFrontend to "yes")) shouldBe mapOf("${CCT.cfactDef}[ready].${CCT.toFrontend}" to SchFailCode.wrongType)
        // A key a request may carry off-contract is not one a stored entry may.
        codes(CCT.cfactDef, cfact() + ("_note" to "x")) shouldBe mapOf("${CCT.cfactDef}[ready]._note" to SchFailCode.additionalProperty)

        fun fragment(content: Any?) = mapOf(CCT.fileId to "home", CCT.content to content)
        codes(CCT.fragmentDef, fragment(mapOf("home" to mapOf("title" to "Welcome")))).shouldBeEmpty()
        // Namespace to key to text: neither tier may be anything else.
        codes(CCT.fragmentDef, fragment(mapOf("home" to "Welcome", "nav" to mapOf("title" to 5, "back" to "Back")))) shouldBe mapOf(
            "${CCT.fragmentDef}[home].${CCT.content}.home" to SchFailCode.wrongType,
            "${CCT.fragmentDef}[home].${CCT.content}.nav.title" to SchFailCode.wrongType,
        )
        codes(CCT.fragmentDef, fragment(listOf("x"))).keys shouldBe setOf("${CCT.fragmentDef}[home].${CCT.content}")

        codes(CCT.uiBlockDef, mapOf(CCT.blockId to "kdr:app", CCT.content to mapOf("menu" to emptyList<Any>()))).shouldBeEmpty()
        codes(CCT.uiBlockDef, mapOf(CCT.blockId to "kdr:app", CCT.content to "menu")).keys shouldBe setOf("${CCT.uiBlockDef}[kdr:app].${CCT.content}")
        codes(CCT.uiBlockDef, mapOf(CCT.content to emptyMap<String, Any?>())).keys shouldBe setOf("${CCT.uiBlockDef}[#0].${CCT.blockId}")
        // Two overlays of one file, or of one block, are two layers: a repeat there is legal, and the serializer
        // writes one entry a layer.
        val layer = fragment(mapOf("home" to mapOf("title" to "Welcome")))
        codes(CCT.fragmentDef, layer, fragment(mapOf("home" to mapOf("lede" to "Hello")))).shouldBeEmpty()
        codes(CCT.cfactDef, cfact(), cfact()).shouldBeEmpty()
    }

    "a trait declaration and a schema type are held to their envelopes, and their bodies to their shape" {
        fun trait(vararg change: Pair<String, Any?>) = mapOf(
            CCT.traitId to "note", CCT.typeName to "client.x.NoteEntry", CCT.appliesTo to listOf(GedraDataType.formDoc.name),
            CCT.dataSchema to mapOf(SCH.type to SCT.kObject),
        ) + change
        codes(CCT.traitDef, trait()).shouldBeEmpty()
        // A kind that is no kind used to be dropped from the trait, silently.
        codes(CCT.traitDef, trait(CCT.appliesTo to listOf(GedraDataType.formDoc.name, "formDocs"))) shouldBe
            mapOf("${CCT.traitDef}[note].${CCT.appliesTo}[1]" to SchFailCode.invalidOption)
        codes(CCT.traitDef, trait(CCT.primaryKey to "year")).keys shouldBe setOf("${CCT.traitDef}[note].${CCT.primaryKey}")
        codes(CCT.traitDef, trait(CCT.dataSchema to "an object")).keys shouldBe setOf("${CCT.traitDef}[note].${CCT.dataSchema}")
        // A body naming a type of the same client, or one that would not compile, is the trial's to judge: it
        // compiles the client's whole document, which the gate does not have.
        val sibling = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("who" to mapOf(SCH.dRef to "#/\$defs/client.x.Person")))
        codes(CCT.traitDef, trait(CCT.dataSchema to sibling)).shouldBeEmpty()

        fun type(schema: Any?) = mapOf(CCT.typeName to "client.x.Person", CCT.schema to schema)
        codes(CCT.schemaDef, type(sibling)).shouldBeEmpty()
        codes(CCT.schemaDef, type(listOf("x"))).keys shouldBe setOf("${CCT.schemaDef}[client.x.Person].${CCT.schema}")
        codes(CCT.schemaDef, mapOf(CCT.schema to emptyMap<String, Any?>())).keys shouldBe setOf("${CCT.schemaDef}[#0].${CCT.typeName}")
        // A body is not parsed here at all: one no parser would take is still the trial's to refuse.
        codes(CCT.schemaDef, type(mapOf(SCH.oneOf to listOf(mapOf(SCH.type to SCT.string))))).shouldBeEmpty()
        // But it is held to its shape (issue #1056): every keyword at fault in it, at once, each at its own path --
        // where the trial would name the first, in a sentence.
        val misshapen = mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                "name" to mapOf(SCH.type to "strng", SCH.allowCoerce to "yes"),
                "tags" to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.type to SCT.string, SCH.enum to listOf("a"))),
            ),
            SCH.required to "name",
        )
        val body = "${CCT.schemaDef}[client.x.Person].${CCT.schema}"
        codes(CCT.schemaDef, type(misshapen)).keys shouldBe setOf(
            "$body.properties.name.type", "$body.properties.name.${SCH.allowCoerce}", "$body.properties.tags.items.enum", "$body.required",
        )
        codes(CCT.traitDef, trait(CCT.dataSchema to misshapen)).keys.map { it.removePrefix("${CCT.traitDef}[note].${CCT.dataSchema}.") } shouldBe
            listOf("properties.name.type", "properties.name.${SCH.allowCoerce}", "properties.tags.items.enum", "required")
        // A stored body's own directive stands at its top -- whether it may is the assembly's to say -- and a
        // property an alteration sets to null is one it removes.
        codes(CCT.schemaDef, type(mapOf(SCH.extends to "client.x.Base", SCH.properties to mapOf("gone" to null)))).shouldBeEmpty()
        codes(CCT.schemaDef, type(mapOf(SCH.extends to 5L))).keys shouldBe setOf("$body.${SCH.extends}")
        // A second type under one name would silently replace the first.
        codes(CCT.schemaDef, type(mapOf(SCH.type to SCT.kObject)), type(mapOf(SCH.type to SCT.string))) shouldBe
            mapOf("${CCT.schemaDef}[client.x.Person]" to SchFailCode.badValue)
    }

    "a workflow's definition is held to its schema, and a report's left to the trial" {
        // A workflow that does not read refuses the reassembly anyway; the gate says every reason, by path.
        val workflow = codes(CCT.workflowDef, mapOf(CCT.workflowId to "review", CCT.definition to mapOf("workflowId" to "review", "bogus" to 1)))
        workflow.getValue("${CCT.workflowDef}[review].${CCT.definition}.bogus") shouldBe SchFailCode.additionalProperty
        workflow.keys.all { it.startsWith("${CCT.workflowDef}[review].${CCT.definition}.") } shouldBe true
        // A report that does not read is kept as stored and reported, so a configuration may already hold one: what
        // is inside the definition is the trial's, which refuses a new one. The envelope is the gate's.
        codes(CCT.reportDef, mapOf(CCT.reportId to "odd", CCT.definition to mapOf("columns" to "none", "bogus" to 1))).shouldBeEmpty()
        codes(CCT.reportDef, mapOf(CCT.reportId to "odd", CCT.definition to "text")).keys shouldBe setOf("${CCT.reportDef}[odd].${CCT.definition}")
        codes(CCT.reportDef, mapOf(CCT.reportId to "odd")).keys shouldBe setOf("${CCT.reportDef}[odd].${CCT.definition}")
        // And its second under one id is kept the same way, not lost.
        codes(
            CCT.reportDef,
            mapOf(CCT.reportId to "odd", CCT.definition to emptyMap<String, Any?>()),
            mapOf(CCT.reportId to "odd", CCT.definition to emptyMap<String, Any?>()),
        ).shouldBeEmpty()
        // A slot nothing declares is not the gate's: the bundle write refuses it by name.
        codes("kdr:nothing", mapOf("x" to 1)).shouldBeEmpty()
    }

    "only what a write changes is judged: an entry carried unchanged is not judged again" {
        val bad = mapOf(CCT.name to "old")
        val stored = mapOf(CCT.cfactDef to listOf(bad, cfact("fine")))
        fun paths(vararg entries: Map<String, Any?>) =
            configSlotFailures(cxt, mapOf(CCT.cfactDef to entries.toList()), stored).map { it.path }.toSet()

        // The stored fault, written back as it is, beside a new entry: nothing to refuse.
        paths(bad, cfact("fine"), cfact("new")).shouldBeEmpty()
        // A new entry at fault is refused, and only it.
        paths(bad, cfact("fine"), mapOf(CCT.name to "new")) shouldBe
            setOf("${CCT.cfactDef}[new].${CCT.group}", "${CCT.cfactDef}[new].${CCT.description}")
        // The faulty entry changed, and still at fault, is the write's own now.
        paths(bad + (CCT.description to "Old."), cfact("fine")) shouldBe setOf("${CCT.cfactDef}[old].${CCT.group}")
        // Without the stored revision -- a first write -- everything is judged.
        configSlotFailures(cxt, stored).map { it.path }.toSet() shouldBe
            setOf("${CCT.cfactDef}[old].${CCT.group}", "${CCT.cfactDef}[old].${CCT.description}")
        // A type the stored revision already declares twice is not refused for it; a new repeat is.
        fun person(type: String) = mapOf(CCT.typeName to "client.x.Person", CCT.schema to mapOf(SCH.type to type))
        val twice = mapOf(CCT.schemaDef to listOf(person(SCT.kObject), person(SCT.string)))
        configSlotFailures(cxt, twice, twice).shouldBeEmpty()
        configSlotFailures(cxt, twice, stored).map { it.path } shouldBe listOf("${CCT.schemaDef}[client.x.Person]")
        // The message names the first few failures and counts the rest; all of them travel structured.
        val many = mapOf(CCT.cfactDef to (1..12).map { mapOf(CCT.name to "c$it", CCT.group to "g") })
        val refusal = shouldThrow<KdrException> { requireWritableSlots(cxt, "big", many) }
        refusal.message.shouldNotBeNull() shouldContain "${CCT.cfactDef}[c10].${CCT.description}"
        refusal.message.shouldNotBeNull() shouldNotContain "${CCT.cfactDef}[c11]"
        refusal.message.shouldNotBeNull() shouldContain "And 2 more."
        refusal.extraData[EP.failures].toJsonListOfMaps().size shouldBe 12
    }

    "every configuration this node declares in source passes the gate in its stored form" {
        // The gate judges whole configurations on a patch, so what the serializer writes must be what it accepts:
        // a configuration promoted from source, or written back unchanged, is never refused.
        val configs = SchemaCollector.get(cxt).shouldNotBeNull().gedraConfigs.configs
            // What a client's configuration may hold: the serializer refuses a config declaring config or state traits.
            .filter { it.configTraits.isEmpty() && it.stateTraits.isEmpty() }
        configs.isNotEmpty() shouldBe true
        for (config in configs) {
            configSlotFailures(cxt, gedraConfigToEntries(config)).map { "${config.gedraId}: ${it.path}: ${it.message}" }.shouldBeEmpty()
        }
    }

    // --- over HTTP ---------------------------------------------------------------------------------------------------

    val client = "slotgate"
    val ns = clientNamespace(client)

    fun bundle(name: String, slots: Map<String, Any?>): Map<String, Any?> =
        mapOf(CFEP.client to client, CFEP.name to name, CFEP.namespaceField to ns, CFEP.slots to slots)

    fun failuresOf(envelope: Map<String, Any?>): Map<String, String> =
        envelope[EP.extraData].toJsonMapOrEmpty()[EP.failures].toJsonListOfMaps()
            .associate { it[EP.failurePath].toString() to it[EP.failureCode].toString() }

    fun patch(name: String, slot: String, action: GedraEditAction, data: Map<String, Any?>): Map<String, Any?> = mapOf(
        CFEP.client to client, CFEP.name to name,
        CFEP.edits to listOf(mapOf(CFEP.slot to slot, GED.action to action.name, GE.data to data)),
    )

    "a bundle write, a patch and an import each refuse a malformed entry by path, and store nothing of it" {
        val admin = fullAdmin()
        val def = ClientDef(
            clientId = client, name = "Slot gate", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
            enabledEnvironments = setOf(ENV.unit, ENV.local),
        ).toInfo()
        // `postData` returns the results of a call and does not assert it succeeded, so each one here is checked.
        admin.postData(ACEP.bundleWrite, bundle("main", mapOf(CCT.clientDef to listOf(def))))[CFEP.version] shouldBe 1
        admin.postData(ACEP.reload, mapOf(CFEP.client to client))
        ClientService.get(cxt).known(client).shouldNotBeNull()

        // A bundle write: every failure, across slots, by slot, entry and path.
        val refused = admin.expectError(
            EXC.badInput, ACEP.bundleWrite,
            bundle(
                "listing",
                mapOf(
                    CCT.usageDef to listOf(usage(GT.name, display = "\${name"), usage(GT.name) + (CCT.kind to "text")),
                    CCT.cfactDef to listOf(mapOf(CCT.name to "ready", CCT.toFrontend to "yes")),
                ),
            ),
        )
        failuresOf(refused).keys shouldBe setOf(
            "${CCT.usageDef}[${GT.name}].${CCT.display}", "${CCT.usageDef}[${GT.name}].${CCT.kind}",
            "${CCT.cfactDef}[ready].${CCT.group}", "${CCT.cfactDef}[ready].${CCT.description}", "${CCT.cfactDef}[ready].${CCT.toFrontend}",
        )
        refused[EP.errorMessage].toString() shouldContain "Configuration 'listing' cannot be written"
        admin.expectError(EXC.notFound, ACEP.bundle, args = mapOf(CFEP.client to client, CFEP.name to "listing"))

        // The same bundle as it should be is written.
        admin.postData(ACEP.bundleWrite, bundle("listing", mapOf(CCT.cfactDef to listOf(cfact("ready")))))[CFEP.version] shouldBe 1

        // A patch: an added entry at fault is refused, and the stored slot is as it was.
        failuresOf(
            admin.expectError(EXC.badInput, ACEP.bundlePatch, patch("listing", CCT.cfactDef, GedraEditAction.addOrReplace, mapOf(CCT.name to "late"))),
        ).keys shouldBe setOf("${CCT.cfactDef}[late].${CCT.group}", "${CCT.cfactDef}[late].${CCT.description}")
        admin.getItem(ACEP.bundle, mapOf(CFEP.client to client, CFEP.name to "listing"))[CFEP.slots]
            .toJsonMapOrEmpty()[CCT.cfactDef].toJsonListOfMaps().map { it[CCT.name] } shouldBe listOf("ready")

        // An import: the bundle at fault is reported by path, and that client's set is not written.
        val imported = admin.postData(
            ACEP.import,
            mapOf(ACEP.bundlesField to listOf(bundle("extra", mapOf(CCT.cfactDef to listOf(cfact("extra") + ("grup" to "x")))))),
        )
        imported[ACEP.failures].toJsonListOfMaps().single()[ACEP.message].toString() shouldContain "${CCT.cfactDef}[extra].grup"
        imported[ACEP.written].toJsonListOfMaps().shouldBeEmpty()

        // A schema body's faults of shape (issue #1056): refused by the write itself, all of them, by path.
        val person = "${CCT.schemaDef}[$ns.Person].${CCT.schema}"
        val misshapen = admin.expectError(
            EXC.badInput, ACEP.bundleWrite,
            bundle(
                "types",
                mapOf(
                    CCT.schemaDef to listOf(
                        mapOf(
                            CCT.typeName to "$ns.Person",
                            CCT.schema to mapOf(
                                SCH.type to SCT.kObject,
                                SCH.properties to mapOf("name" to mapOf(SCH.type to "strng"), "age" to mapOf(SCH.type to SCT.integer, SCH.minimum to "young")),
                                SCH.additionalProperties to "no",
                            ),
                        ),
                    ),
                ),
            ),
        )
        failuresOf(misshapen).keys shouldBe setOf("$person.properties.name.type", "$person.properties.age.minimum", "$person.additionalProperties")
        misshapen[EP.errorMessage].toString() shouldContain "$person.properties.name.type: This schema sets 'type' to 'strng'"
        admin.expectError(EXC.notFound, ACEP.bundle, args = mapOf(CFEP.client to client, CFEP.name to "types"))
    }

    "the schema for schema is served, generated from the tables the gate reads" {
        val served = fullAdmin().getData(MSCH.path)
        served[MSCH.nodeType] shouldBe MSCH.nodeTypeName
        // What is served is the document itself, and it compiles to the type a body is validated against.
        val types = parseSchemaTypes(served[SCH.dDefs].toJsonMapOrEmpty())
        types.getValue(MSCH.nodeTypeName).properties.keys shouldBe SchMetaSchema.nodeType.properties.keys
        served[MSCH.notStated].toJsonListOfMaps().map { it[MSCH.rule] }.toSet() shouldBe
            setOf(MSCH.closedPrefix, MSCH.refusedKeyword, MSCH.eitherOf, MSCH.unsetEntry, MSCH.placement)
        (served[MSCH.notChecked] as List<*>).shouldNotBeEmpty()
    }

    "a fault a stored configuration already has does not refuse an unrelated write to it" {
        val admin = fullAdmin()
        // Stored by a service write, which takes a built config and gates nothing -- how a row from before the gate
        // looks: a usage rule whose display does not parse.
        val bound = cxt.mkSubContext("slotGateSeed", client).also { it.userId = 10520L }
        GedraConfigService.get(cxt).writeConfig(
            bound, gedraConfig(cxt, "legacy", ns, client) { traitUsage(GT.name, "Name", "\${name") },
        )
        GedraConfigReload.reloadClient(cxt, client)

        // A patch of another slot goes through: the rule is carried unchanged, so it is not judged again.
        admin.postData(ACEP.bundlePatch, patch("legacy", CCT.cfactDef, GedraEditAction.addOrReplace, cfact("legacyReady")))[CFEP.slots]
            .toJsonMapOrEmpty().keys shouldBe setOf(CCT.usageDef, CCT.cfactDef)
        // So does the bundle read and written back as it is.
        val read = admin.getItem(ACEP.bundle, mapOf(CFEP.client to client, CFEP.name to "legacy"))
        admin.postData(ACEP.bundleWrite, bundle("legacy", read[CFEP.slots].toJsonMapOrEmpty()))[CFEP.slots]
            .toJsonMapOrEmpty().keys shouldBe setOf(CCT.usageDef, CCT.cfactDef)
        // And imported as it is: an import of a client's own bundles back into it is a restore, not a new write.
        val restored = admin.postData(ACEP.import, mapOf(ACEP.bundlesField to listOf(bundle("legacy", read[CFEP.slots].toJsonMapOrEmpty()))))
        restored[ACEP.failures].toJsonListOfMaps().shouldBeEmpty()
        restored[ACEP.written].toJsonListOfMaps().map { it[CFEP.name] } shouldBe listOf("legacy")
        // Changing the rule and leaving it at fault is refused: it is the write's own now.
        failuresOf(
            admin.expectError(
                EXC.badInput, ACEP.bundlePatch,
                patch("legacy", CCT.usageDef, GedraEditAction.addOrMerge, mapOf(CCT.traitId to GT.name, CCT.label to "Full name")),
            ),
        ).keys shouldBe setOf("${CCT.usageDef}[${GT.name}].${CCT.display}")
        // And mending it is how it stops being one.
        admin.postData(
            ACEP.bundlePatch,
            patch("legacy", CCT.usageDef, GedraEditAction.addOrMerge, mapOf(CCT.traitId to GT.name, CCT.display to "\${name}")),
        )[CFEP.slots].toJsonMapOrEmpty()[CCT.usageDef].toJsonListOfMaps().single()[CCT.display] shouldBe "\${name}"
    }
})
