package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GSORT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.ListingColumn
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A listing column's value and its presentation (issue #1323): the column sorts and searches by its value, which each
 * row carries, and the presentation its cells read through comes once, with the listing's columns. Over the shared
 * instance, with a client of its own.
 */
class ListingPresentationTest : StringSpec({
    val cxt = TestInstances.default("presentation1323")
    val client = "present1323"
    val amountPresentation = $$"""${currency(value, "USD")}"""

    fun asClient(): KdrCxt = cxt.mkSubContext("present", client).also { it.userId = 13230L }

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("AmountEntry", "amount", setOf(GedraDataType.formDoc), "How much.") {
            property("amount", "The amount.") { type = SCT.number }
        }
        trait("DueEntry", "due", setOf(GedraDataType.formDoc), "When it is due.") { property("at", "The moment.") }
        traitUsage("amount", "Amount", $$"${amount}", UsageKind.number, presentation = amountPresentation)
        traitUsage("due", "Due", $$"${at}", UsageKind.date)
    }
    GedraConfigService.get(cxt).writeConfig(asClient(), config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)
    fun create(amount: Number) = user.postItem(
        GEP.formDocCreate,
        mapOf(
            GDF.entries to listOf(
                mapOf(GE.traitId to "amount", GE.data to mapOf("amount" to amount)),
                mapOf(GE.traitId to "due", GE.data to mapOf("at" to "2026-10-18T07:30:00.000Z")),
            ),
        ),
    )
    create(1000)
    create(200)

    "the listing says its columns once, each with its presentation, and each row carries its values" {
        val listing = user.client.sendJsonGetRequest(GEP.formDocs, emptyMap())
        val columns = ListingColumn.listFrom(listing[EP.summary].toJsonMapOrEmpty()[GDF.columns])
        columns.map { listOf(it.traitId, it.label, it.kind, it.presentation) } shouldBe listOf(
            listOf("amount", "Amount", UsageKind.number, amountPresentation),
            listOf("due", "Due", UsageKind.date, null),
        )
        // A row carries the value, not the presented text, and no presentation of its own.
        val row = listing[EP.items].toJsonListOfMaps().first()[GDF.displayValues].toJsonListOfMaps()
        row.associate { it[UF.traitId] to it[UF.value] }["due"] shouldBe "2026-10-18T07:30:00.000Z"
        row.none { UF.presentation in it } shouldBe true
    }

    "the column sorts by its value, never by what its cells show" {
        // As text, "$1,000.00" would sort before "$200.00".
        fun amounts(dir: String) = user.getItems(GEP.formDocs, mapOf(GSORT.sort to "${GSORT.displayColumnPrefix}amount", GSORT.sortDir to dir))
            .map { r -> r[GDF.displayValues].toJsonListOfMaps().first { it[UF.traitId] == "amount" }[UF.value] }
        amounts(GSORT.asc) shouldBe listOf("200", "1000")
        amounts(GSORT.desc) shouldBe listOf("1000", "200")
    }

})
