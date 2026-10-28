package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.PORT
import com.dynamicruntime.common.gedra.fromPortable
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.confdemo.ConfDemo
import com.dynamicruntime.kdn.confdemo.provisionConfDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The portable form of a client's configuration, through the endpoints (issue #1435): read in portable form, every
 * bundle of the conference demo -- the client that shows every configurable feature -- loads into another client as
 * it is, and a single bundle written or patched in portable form lands in the writer's own namespace. Over the shared
 * instance, as the demo's documentation test provisions it.
 */
class PortableConfigCopyTest : StringSpec({
    val cxt = TestInstances.default("portableCopy1435")
    provisionConfDemo(cxt)
    val operator = TestUser.createFullAdmin(cxt, "portable@op1435.test")

    fun bundles(client: String): List<String> =
        operator.getItems(ACEP.bundles, mapOf(CFEP.client to client)).map { it[CFEP.name].toOptStr()!! }

    fun read(client: String, name: String, portable: Boolean): Map<String, Any?> =
        operator.getItem(ACEP.bundle, mapOf(CFEP.client to client, CFEP.name to name, PORT.portableField to portable))

    "every bundle reads back in portable form, naming no client but in its definition, and resolves to what is stored" {
        for (name in bundles(ConfDemo.client)) {
            val stored = read(ConfDemo.client, name, portable = false)
            val portable = read(ConfDemo.client, name, portable = true)
            portable[CFEP.namespaceField] shouldBe PORT.namespace
            val slots = portable[CFEP.slots].toJsonMapOrEmpty()
            slots.filterKeys { it != CCT.clientDef }.toString() shouldNotContain "client.${ConfDemo.client}"
            // The inverse gives back exactly what is stored.
            fromPortable(slots, ConfDemo.client) shouldBe stored[CFEP.slots]
        }
    }

    "the demo's bundles, read in portable form, load into another client as they are" {
        val copy = "confcopy1435"
        val portable = bundles(ConfDemo.client).map { read(ConfDemo.client, it, portable = true) }
        // The definition is the copy's own: the demo's, renamed. Everything else goes across untouched.
        val bundlesIn = portable.map { bundle ->
            val slots = bundle[CFEP.slots].toJsonMapOrEmpty().mapValues { (slot, entries) ->
                if (slot != CCT.clientDef) entries
                else entries.toJsonListOfMaps().map { it + (CLD.clientId to copy) + (CLD.name to "Conference copy") }
            }
            mapOf(
                CFEP.client to copy, CFEP.name to bundle[CFEP.name], CFEP.namespaceField to bundle[CFEP.namespaceField],
                CFEP.slots to slots,
            )
        }
        // All of them in one call, judged together: about 25 KB, which the import takes as an endpoint whose input is
        // a whole configuration (issue #1446).
        val imported = operator.postData(ACEP.import, mapOf(ACEP.bundlesField to bundlesIn))
        imported[ACEP.failures].toJsonListOfMaps().shouldBeEmpty()

        // It loads clean, with the demo's traits and workflows, in its own namespace.
        ClientService.get(cxt).present(copy).shouldNotBeNull()
        val demoTraits = SchemaService.get(cxt).gedraTraitsFor(ConfDemo.client).filter { it.typeName.startsWith("client.") }
        SchemaService.get(cxt).gedraTraitsFor(copy).map { it.traitId } shouldContainAll demoTraits.map { it.traitId }
        SchemaService.get(cxt).gedraTraitsFor(copy).filter { it.traitId in demoTraits.map { t -> t.traitId } }
            .forEach { it.typeName shouldContain "client.$copy." }
        operator.getItem(ACEP.bundle, mapOf(CFEP.client to copy, CFEP.name to bundlesIn.first()[CFEP.name]))[CFEP.namespaceField] shouldBe
            "client.$copy"
        operator.getItems(ACEP.bundles, mapOf(CFEP.client to copy)).flatMap { it[CFEP.issues].toJsonListOfMaps() }.shouldBeEmpty()
    }

    "a bundle written or patched in portable form lands in the writer's own namespace" {
        val client = "portwrite1435"
        val definition = read(ConfDemo.client, bundles(ConfDemo.client).first { name ->
            read(ConfDemo.client, name, portable = false)[CFEP.slots].toJsonMapOrEmpty().containsKey(CCT.clientDef)
        }, portable = false)[CFEP.slots].toJsonMapOrEmpty()[CCT.clientDef].toJsonListOfMaps().single()
        operator.postData(
            ACEP.bundleWrite,
            mapOf(
                CFEP.client to client, CFEP.name to "main", CFEP.namespaceField to PORT.namespace,
                CFEP.slots to mapOf(
                    CCT.clientDef to listOf(definition + (CLD.clientId to client) + (CLD.name to "Written")),
                    CCT.schemaDef to listOf(mapOf(CCT.typeName to "${PORT.namespace}.Address", CCT.schema to mapOf("type" to "string"))),
                ),
                CFEP.reloadField to true,
            ),
        )
        val written = read(client, "main", portable = false)
        written[CFEP.namespaceField] shouldBe "client.$client"
        written[CFEP.slots].toJsonMapOrEmpty()[CCT.schemaDef].toJsonListOfMaps().single()[CCT.typeName] shouldBe "client.$client.Address"

        // A patch addresses the entry in portable form too: its key is a type name.
        operator.postData(
            ACEP.bundlePatch,
            mapOf(
                CFEP.client to client,
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "main",
                        CFEP.edits to listOf(
                            mapOf(
                                CFEP.slot to CCT.schemaDef, GED.action to GedraEditAction.addOrReplace.name,
                                GE.data to mapOf(CCT.typeName to "${PORT.namespace}.Address", CCT.schema to mapOf("type" to "string", "maxLength" to 80L)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val patched = read(client, "main", portable = false)[CFEP.slots].toJsonMapOrEmpty()[CCT.schemaDef].toJsonListOfMaps()
        patched.map { it[CCT.typeName] } shouldBe listOf("client.$client.Address")
        patched.single()[CCT.schema].toJsonMapOrEmpty()["maxLength"] shouldBe 80L
    }
})
