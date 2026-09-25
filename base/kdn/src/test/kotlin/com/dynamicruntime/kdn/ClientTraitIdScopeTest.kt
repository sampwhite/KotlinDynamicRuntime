package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Trait ids are scoped to their client (issue #807): two clients may each declare `survey1`, and each gets its own.
 * What stays unique is a **global** trait's id -- no client may reuse one -- and a client's own ids within that
 * client. So a stored entry's bare trait id resolves unambiguously against the client of the form that holds it.
 */
class ClientTraitIdScopeTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("traitIdScope", "clientTraitIdScopeTest")
    val alpha = "idscopealpha"
    val beta = "idscopebeta"

    fun writer(client: String): KdrCxt = cxt.mkSubContext("idScope", client).also { it.userId = 8070L }

    fun store(client: String, name: String = "main", withDef: Boolean = true, build: GedraConfigBuilder.() -> Unit) {
        val config = gedraConfig(cxt, name, "${client}config", client) {
            if (withDef) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            }
            build()
        }
        GedraConfigService.get(cxt).writeConfig(writer(client), config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    // The same id, two shapes: alpha's `survey1` holds a required text topic, beta's a required count -- and a
    // `topic` of its own that means something else, which is what an alpha rule must not pick up on beta's rows.
    store(alpha) {
        trait("Survey1Entry", "survey1", setOf(GedraDataType.formDoc), "Alpha's survey.") {
            property("topic", "The topic.", required = true)
        }
        traitUsage("survey1", "Topic", $$"${topic}")
    }
    store(beta) {
        trait("Survey1Entry", "survey1", setOf(GedraDataType.formDoc), "Beta's survey.") {
            property("count", "How many.", required = true) { type = SCT.integer }
            property("topic", "Beta's own, unrelated topic.")
        }
    }

    fun entry(data: Map<String, Any?>) = mapOf(GDF.entries to listOf(mapOf(GE.traitId to "survey1", GE.data to data)))

    "two clients each declaring one trait id both load, each with its own definition" {
        val schema = SchemaService.get(cxt)
        schema.gedraTraitsFor(alpha).single { it.traitId == "survey1" }.typeName shouldBe "${alpha}config.Survey1Entry"
        schema.gedraTraitsFor(beta).single { it.traitId == "survey1" }.typeName shouldBe "${beta}config.Survey1Entry"
        schema.isGlobalTrait("survey1") shouldBe false
    }

    "each client's forms are held to its own definition, and read back as written" {
        val alphaUser = TestUser.create(cxt, "u@$alpha.test", userClient = alpha)
        val betaUser = TestUser.create(cxt, "u@$beta.test", userClient = beta)
        val alphaDoc = alphaUser.postItem(GEP.formDocCreate, entry(mapOf("topic" to "Roads")))
        alphaUser.expectError(EXC.badInput, GEP.formDocCreate, entry(mapOf("count" to 3)))
        val betaDoc = betaUser.postItem(GEP.formDocCreate, entry(mapOf("count" to 3)))
        betaUser.expectError(EXC.badInput, GEP.formDocCreate, entry(mapOf("topic" to "Roads")))

        fun dataOf(doc: Map<String, Any?>) =
            doc[GDF.entries].toJsonListOfMaps().single { it[GE.traitId] == "survey1" }[GE.data].toJsonMapOrEmpty()
        dataOf(alphaDoc)["topic"] shouldBe "Roads"
        dataOf(betaDoc)["count"] shouldBe 3L
    }

    // An `allClients` caller's listing spans clients but applies its own client's usage rules. A rule over the
    // caller's **own** `survey1` must not read beta's differently shaped `survey1`: on beta's row it is blank.
    "a usage over a client's own trait does not read another client's trait of the same id" {
        val operator = TestUser.createFullAdmin(cxt, "chief@$alpha.test", userClient = alpha)
        TestUser.create(cxt, "v@$beta.test", userClient = beta)
            .postItem(GEP.formDocCreate, entry(mapOf("count" to 7, "topic" to "Leak")))
        val rows = operator.getItems(GEP.formDocs, mapOf(EP.limit to 200))
        fun topicOf(row: Map<String, Any?>) =
            row[GDF.displayValues].toJsonListOfMaps().single { it[UF.traitId] == "survey1" }[UF.value]
        val betaRows = rows.filter { it[GDF.client] == beta }
        betaRows.isNotEmpty() shouldBe true
        betaRows.forEach { topicOf(it) shouldBe "" }
        // Alpha's own rows still show the rule's value.
        rows.filter { it[GDF.client] == alpha }.map { topicOf(it) }.contains("Roads") shouldBe true
    }

    "a client reusing a global trait's id is refused" {
        shouldThrow<KdrException> {
            store("idscopegamma") {
                trait("NameEntry", "name", setOf(GedraDataType.formDoc), "A second name.") { property("n", "N.") }
            }
        }.message.shouldNotBeNull() shouldContain "global trait's id"
    }

    "a client declaring one trait id in two of its configs is refused" {
        shouldThrow<KdrException> {
            store(alpha, name = "second", withDef = false) {
                trait("OtherSurveyEntry", "survey1", setOf(GedraDataType.formDoc), "Again.") { property("x", "X.") }
            }
        }.message.shouldNotBeNull() shouldContain "unique within a client"
    }
})
