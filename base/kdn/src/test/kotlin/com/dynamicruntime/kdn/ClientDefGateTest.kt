package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientDefSchema
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.configSlotFailures
import com.dynamicruntime.common.gedra.coreConfigTraits
import com.dynamicruntime.common.gedra.readClientDef
import com.dynamicruntime.common.gedra.reassembleForWrite
import com.dynamicruntime.common.gedra.reassembleGedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The client definition gate (issue #1051): a `kdr:clientDef` arriving in a configuration **write** is validated
 * against its schema (`ClientInfo`), and a fault is a 400 naming each path -- where it used to be read past, read
 * as something else, or thrown as a server error. What is already stored is still read leniently.
 */
class ClientDefGateTest : StringSpec({
    val cxt: KdrCxt = Startup.mkTestBootCxt("clientDefGate", "clientDefGateTest")

    fun fullAdmin(): TestUser = TestUser.createFullAdmin(cxt, "gate1051@example.com")

    fun info(clientId: String): Map<String, Any?> = ClientDef(
        clientId = clientId, name = "Gate $clientId", usageType = ClientUsageType.dev,
        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
    ).toInfo()

    fun writeBody(clientId: String, def: Map<String, Any?>): Map<String, Any?> = mapOf(
        CFEP.client to clientId, CFEP.name to "main", CFEP.namespaceField to clientNamespace(clientId),
        CFEP.slots to mapOf(CCT.clientDef to listOf(def)),
    )

    /** The failures an error envelope carries, as path to code. */
    fun failuresOf(envelope: Map<String, Any?>): Map<String, String> =
        envelope[EP.extraData].toJsonMapOrEmpty()[EP.failures].toJsonListOfMaps()
            .associate { it[EP.failurePath].toString() to it[EP.failureCode].toString() }

    "a definition is read strictly: its keys and no others, each of its type, each choice one that exists" {
        val good = info("gateread")
        readClientDef(cxt, good).def.shouldNotBeNull().toInfo() shouldBe good
        readClientDef(cxt, good).failures.shouldBeEmpty()

        fun codes(def: Map<String, Any?>) = readClientDef(cxt, def).failures.associate { it.path to it.code }
        // A misspelled key is not read past.
        codes(good + ("enabledEnviroments" to listOf(ENV.unit))) shouldBe mapOf("enabledEnviroments" to SchFailCode.additionalProperty)
        // An absent list is a missing field, not a retired client.
        codes(good - CLD.enabledEnvironments) shouldBe mapOf(CLD.enabledEnvironments to SchFailCode.missingRequired)
        codes(good - CLD.name - CLD.audience).keys shouldBe setOf(CLD.name, CLD.audience)
        // A flag is a boolean: not text that reads like one, and not a number.
        codes(good + (CLD.preload to "yes")).keys shouldBe setOf(CLD.preload)
        codes(good + (CLD.sandbox to 1)).keys shouldBe setOf(CLD.sandbox)
        codes(good + (CLD.staticConfig to "true")).keys shouldBe setOf(CLD.staticConfig)
        // Each environment, and each of the two choices, is one there is.
        codes(good + (CLD.enabledEnvironments to listOf(ENV.unit, "staging"))) shouldBe
            mapOf("${CLD.enabledEnvironments}[1]" to SchFailCode.invalidOption)
        codes(good + (CLD.usageType to "prodution")) shouldBe mapOf(CLD.usageType to SchFailCode.invalidOption)
        // A list is a list, and text is text.
        codes(good + (CLD.enabledEnvironments to "unit,local")).keys shouldBe setOf(CLD.enabledEnvironments)
        codes(good + (CLD.name to 5)).keys shouldBe setOf(CLD.name)
        // The class's own rule about suggested labels is reported the same way, where it applies.
        codes(good + (CLD.userLabels to listOf("reviewer", " reviewer "))) shouldBe mapOf(CLD.userLabels to SchFailCode.badValue)
        // Every failure is reported, not the first.
        codes(good - CLD.name + ("extra" to 1) + (CLD.preload to "yes")).keys shouldBe setOf(CLD.name, "extra", CLD.preload)
        // A key a request may carry off-contract is not one a definition may: it would be accepted and then gone.
        codes(good + ("_enabledEnvironments" to listOf(ENV.unit)) + ("\$note" to "x")) shouldBe
            mapOf("_enabledEnvironments" to SchFailCode.additionalProperty, "\$note" to SchFailCode.additionalProperty)
    }

    "the written form is a type of its own beside the answered one, and only it closes the environments" {
        val types = cxt.getSchema().types
        fun envItems(typeName: String) = types.getValue(typeName).properties.getValue(CLD.enabledEnvironments).valueType.itemType.shouldNotBeNull()
        // One declaration makes both, so they hold the same fields and offer the same environments...
        types.getValue(CLD.writtenInfoTypeQualified).properties.keys shouldBe types.getValue(CLD.infoTypeQualified).properties.keys
        envItems(CLD.writtenInfoTypeQualified).options.shouldNotBeNull().map { it.value } shouldBe ENV.names
        envItems(CLD.infoTypeQualified).options.shouldNotBeNull().map { it.value } shouldBe ENV.names
        // ...and differ in whether a name that is none of them may stand: an answer has to be able to carry a
        // stored client's mistake, and a write is refused for it.
        envItems(CLD.writtenInfoTypeQualified).openOptions shouldBe false
        envItems(CLD.infoTypeQualified).openOptions shouldBe true
        // The gate judges by the written one, and the slot's declaration names it.
        ClientDefSchema.defType(cxt).name shouldBe CLD.writtenInfoTypeQualified
        coreConfigTraits(cxt).defs.getValue("kdr.core.ClientDefEntry").toString() shouldContain CLD.writtenInfoTypeQualified
    }

    "what is already stored is still read leniently: the load does not pass the gate" {
        // A row no write would accept now -- an unknown key, a flag that is not one. The load's reader takes it as
        // it always has, so a deployment's stored clients are not refused by the deploy that added the gate.
        val stored = info("gatestored") + ("legacyKey" to "x") + (CLD.preload to "yes")
        val config = reassembleGedraConfig(cxt, "main", clientNamespace("gatestored"), "gatestored", mapOf(CCT.clientDef to listOf(stored)))
        config.client.shouldNotBeNull().preload shouldBe false
        // The gate, asked of the same slots, names both -- under the slot.
        configSlotFailures(cxt, mapOf(CCT.clientDef to listOf(stored))).map { it.path } shouldBe
            listOf("${CCT.clientDef}.${CLD.preload}", "${CCT.clientDef}.legacyKey")
        // One client a configuration: a second entry would be silently lost.
        configSlotFailures(cxt, mapOf(CCT.clientDef to listOf(info("a"), info("b")))).single().path shouldBe CCT.clientDef
        // No definition, nothing to refuse.
        configSlotFailures(cxt, mapOf(CCT.cfactDef to listOf(mapOf(CCT.name to "x")))).shouldBeEmpty()
    }

    "a bundle write of a malformed definition is a 400 naming each path, and stores nothing" {
        val admin = fullAdmin()
        val client = "gatewrite"
        val bad = info(client) - CLD.enabledEnvironments + ("enabledEnviroments" to listOf(ENV.unit)) + (CLD.preload to "yes")
        val refused = admin.expectError(EXC.badInput, ACEP.bundleWrite, writeBody(client, bad))
        failuresOf(refused) shouldBe mapOf(
            "${CCT.clientDef}.${CLD.enabledEnvironments}" to SchFailCode.missingRequired.name,
            "${CCT.clientDef}.${CLD.preload}" to SchFailCode.wrongType.name,
            "${CCT.clientDef}.enabledEnviroments" to SchFailCode.additionalProperty.name,
        )
        // The message says it without the structured part: each path, and what is wrong there.
        val message = refused[EP.errorMessage].toString()
        message shouldContain "Configuration 'main' cannot be written"
        message shouldContain "${CCT.clientDef}.enabledEnviroments"
        // Nothing was stored, so there is nothing to read back and no client to reload into being.
        admin.expectError(EXC.notFound, ACEP.bundle, args = mapOf(CFEP.client to client, CFEP.name to "main"))

        // An unknown environment and a bad choice, each by name.
        failuresOf(
            admin.expectError(
                EXC.badInput, ACEP.bundleWrite,
                writeBody(client, info(client) + (CLD.enabledEnvironments to listOf(ENV.unit, ENV.local, "staging")) + (CLD.usageType to "prodution")),
            ),
        ) shouldBe mapOf(
            "${CCT.clientDef}.${CLD.enabledEnvironments}[2]" to SchFailCode.invalidOption.name,
            "${CCT.clientDef}.${CLD.usageType}" to SchFailCode.invalidOption.name,
        )
        // A missing name was a server error (a conversion fault thrown from the reader); it is the writer's.
        failuresOf(admin.expectError(EXC.badInput, ACEP.bundleWrite, writeBody(client, info(client) - CLD.name))).keys shouldBe
            setOf("${CCT.clientDef}.${CLD.name}")
        // So is a fault the reassembly itself finds -- here a sandbox's id, which only the system makes: a
        // conversion fault in source, and from a writer bad input, with what is wrong kept.
        val colon = admin.expectError(EXC.badInput, ACEP.bundleWrite, writeBody(client, info(client) + (CLD.clientId to "$client:sandbox")))
        colon[EP.errorMessage].toString() shouldContain "Configuration 'main' cannot be written."
        colon[EP.errorMessage].toString() shouldContain "holds a colon"
        // The fault is kept as the cause -- its stack for the log, its extra data carried up -- not flattened to text.
        val refusal = shouldThrow<KdrException> {
            reassembleForWrite(cxt, "main", clientNamespace(client), client, mapOf(CCT.clientDef to listOf(info(client) + (CLD.clientId to "$client:sandbox"))))
        }
        refusal.code shouldBe EXC.badInput
        refusal.cause.shouldBeInstanceOf<KdrException>().code shouldBe EXC.internalError
        refusal.activity shouldBe ACT.conversion

        // The definition as it should be is written, and the client it defines comes to be.
        admin.postData(ACEP.bundleWrite, writeBody(client, info(client)))[CFEP.version] shouldBe 1
        admin.postData(ACEP.reload, mapOf(CFEP.client to client))
        ClientService.get(cxt).known(client).shouldNotBeNull().name shouldBe "Gate $client"
    }

    "a patch that leaves the definition malformed is refused, and one that keeps it sound is not" {
        val admin = fullAdmin()
        val client = "gatepatch"
        admin.postData(ACEP.bundleWrite, writeBody(client, info(client)))

        fun patch(data: Map<String, Any?>): Map<String, Any?> = mapOf(
            CFEP.client to client, CFEP.name to "main",
            CFEP.edits to listOf(mapOf(CFEP.slot to CCT.clientDef, GED.action to GedraEditAction.addOrMerge.name, GE.data to data)),
        )
        failuresOf(admin.expectError(EXC.badInput, ACEP.bundlePatch, patch(mapOf(CLD.sandbox to "yes", "nickname" to "G")))) shouldBe mapOf(
            "${CCT.clientDef}.${CLD.sandbox}" to SchFailCode.wrongType.name,
            "${CCT.clientDef}.nickname" to SchFailCode.additionalProperty.name,
        )
        // A replacement that drops a required field is refused as that -- before anything reads it as a definition.
        failuresOf(
            admin.expectError(
                EXC.badInput, ACEP.bundlePatch,
                mapOf(
                    CFEP.client to client, CFEP.name to "main",
                    CFEP.edits to listOf(
                        mapOf(CFEP.slot to CCT.clientDef, GED.action to GedraEditAction.addOrReplace.name, GE.data to (info(client) - CLD.name)),
                    ),
                ),
            ),
        ).keys shouldBe setOf("${CCT.clientDef}.${CLD.name}")
        // The stored definition is as it was.
        val stored = admin.getItem(ACEP.bundle, mapOf(CFEP.client to client, CFEP.name to "main"))[CFEP.slots]
            .toJsonMapOrEmpty()[CCT.clientDef].toJsonListOfMaps().single()
        stored.keys shouldNotContain "nickname"
        stored[CLD.name] shouldBe "Gate $client"

        // A sound patch of the same slot goes through, and of another slot too: the whole config is gated, and passes.
        admin.postData(ACEP.bundlePatch, patch(mapOf(CLD.description to "Patched")))
        admin.postData(
            ACEP.bundlePatch,
            mapOf(
                CFEP.client to client, CFEP.name to "main",
                CFEP.edits to listOf(
                    mapOf(
                        CFEP.slot to CCT.cfactDef, GED.action to GedraEditAction.addOrReplace.name,
                        GE.data to mapOf(CCT.name to "ready", CCT.group to "grp", CCT.description to "Ready"),
                    ),
                ),
            ),
        )[CFEP.slots].toJsonMapOrEmpty().keys shouldBe setOf(CCT.clientDef, CCT.cfactDef)
    }

    "an import reports a malformed definition by path and writes none of that client, while another client's goes in" {
        val admin = fullAdmin()
        val bad = "gateimportbad"
        val good = "gateimportgood"
        val result = admin.postData(
            ACEP.import,
            mapOf(
                ACEP.bundlesField to listOf(
                    writeBody(bad, info(bad) + ("owner" to "nobody")),
                    writeBody(good, info(good)),
                    // A fault the reassembly finds: the import's text says what it is, not only whose.
                    writeBody("gateimportcolon", info("gateimportcolon") + (CLD.clientId to "gateimportcolon:sandbox")),
                ),
            ),
        )
        val failures = result[ACEP.failures].toJsonListOfMaps().associate { it[CFEP.client].toString() to it[ACEP.message].toString() }
        failures.keys shouldBe setOf(bad, "gateimportcolon")
        failures.getValue(bad) shouldContain "${CCT.clientDef}.owner"
        failures.getValue("gateimportcolon") shouldContain "holds a colon"
        result[ACEP.written].toJsonListOfMaps().mapNotNull { it[CFEP.client].toOptStr() } shouldBe listOf(good)
        ClientService.get(cxt).known(bad) shouldBe null
        ClientService.get(cxt).known(good).shouldNotBeNull()
        result[ACEP.reloaded].toJsonListOfStrings() shouldBe listOf(good)
    }
})
