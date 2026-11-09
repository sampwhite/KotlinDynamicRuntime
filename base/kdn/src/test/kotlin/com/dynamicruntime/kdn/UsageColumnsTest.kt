package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
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
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Several listing columns reading one trait (issue #1490): each column has a name of its own -- its `columnId`, else
 * the trait id -- which its values on a row, its search parameters, its sort, and its value suggestions go by, so the
 * two show and search independently. Over the shared instance, with a client of its own.
 *
 * Also, since issue #1739, the tests that were `ListingPresentationTest`, each kept whole below.
 */
class UsageColumnsTest : StringSpec({
    include(usageColumnsTests())
    include(listingPresentationTests())
})

/** This file's own tests, as the class describes them. */
private fun usageColumnsTests() = stringSpec {
    val cxt = TestInstances.default("usageColumns1490")
    val client = "cols1490"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("ExpenseEntry", "expense", setOf(GedraDataType.formDoc), "An expense.") {
            property("amount", "How much.") { type = SCT.number }
            property("note", "What it was for.")
        }
        // The first column keeps the trait's name; the second, of the same trait, has one of its own.
        traitUsage("expense", "Amount", $$"${amount}", UsageKind.number)
        traitUsage("expense", "Note", $$"${note}", UsageKind.string, substring = true, columnId = "expenseNote")
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("cols", client).also { it.userId = 14900L }, config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "u@$client.test", userClient = client)
    fun create(amount: Number, note: String) = user.postItem(
        GEP.formDocCreate,
        mapOf(GDF.entries to listOf(mapOf(GE.traitId to "expense", GE.data to mapOf("amount" to amount, "note" to note)))),
    )
    create(900, "Airfare")
    create(40, "Lunch with the client")
    create(600, "Hotel")

    // The client's own copies of the listing and its value suggestions, which carry its search fields.
    val formDocs = clientPath(GEP.formDocs, client)
    val formDocValues = clientPath(GEP.formDocValues, client)

    /** Each row's values, by column. */
    fun values(query: Map<String, Any?> = emptyMap()): List<Map<Any?, Any?>> = user.getItems(formDocs, query)
        .map { row -> row[GDF.displayValues].toJsonListOfMaps().associate { it[UF.columnId] to it[UF.value] } }

    "two columns of one trait are two columns, each under its own name" {
        val listing = user.client.sendJsonGetRequest(formDocs, emptyMap())
        val columns = ListingColumn.listFrom(listing[EP.summary].toJsonMapOrEmpty()[GDF.columns])
        columns.map { listOf(it.columnId, it.traitId, it.label) } shouldBe
            listOf(listOf("expense", "expense", "Amount"), listOf("expenseNote", "expense", "Note"))
        values().first { it["expenseNote"] == "Hotel" }["expense"] shouldBe "600"
    }

    "each column searches by its own parameters, and the first keeps the names it had" {
        values(mapOf("expenseMin" to 500)).map { it["expenseNote"] }.toSet() shouldBe setOf("Airfare", "Hotel")
        values(mapOf("expenseNoteContains" to "lunch")).map { it["expense"] } shouldBe listOf("40")
        values(mapOf("expenseMin" to 500, "expenseNoteContains" to "air")).map { it["expense"] } shouldBe listOf("900")
        // The free-text term reads the text column.
        values(mapOf(EI.q to "hotel")).map { it["expense"] } shouldBe listOf("600")
    }

    "each column sorts by its own value" {
        fun sorted(column: String) = values(mapOf(GSORT.sort to "${GSORT.displayColumnPrefix}$column", GSORT.sortDir to GSORT.asc))
        sorted("expense").map { it["expense"] } shouldBe listOf("40", "600", "900")
        sorted("expenseNote").map { it["expenseNote"] } shouldBe listOf("Airfare", "Hotel", "Lunch with the client")
    }

    "value suggestions are asked for by column, and only a text column has them" {
        user.getItems(formDocValues, mapOf(UF.columnId to "expenseNote", EI.q to "h")).map { it[UF.value] } shouldBe
            listOf("Hotel", "Lunch with the client")
        user.expectError(EXC.badInput, formDocValues, args = mapOf(UF.columnId to "expense"))
    }

    "a write is refused when two columns would share a name, or a column's name is not one" {
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        fun write(vararg columns: Map<String, Any?>) = mapOf(
            CFEP.name to "more", CFEP.namespaceField to clientNamespace(client), CFEP.slots to mapOf(CCT.usageDef to columns.toList()),
        )
        fun column(label: String, columnId: String? = null) = buildMap {
            put(CCT.traitId, "expense")
            put(CCT.label, label)
            put(CCT.display, $$"${note}")
            columnId?.let { put(CCT.columnId, it) }
        }
        // A third column of the trait with no name of its own takes the first's.
        admin.expectError(EXC.badInput, CFEP.bundleWrite, write(column("Again")))[EP.errorMessage].toString() shouldContain "expense"
        // A name that is not an identifier would make a malformed query parameter.
        admin.expectError(EXC.badInput, CFEP.bundleWrite, write(column("Again", "expense note")))[EP.errorMessage].toString() shouldContain
            CCT.columnId
        // Named, the third column is accepted -- the same value as the second's, under another heading, is fine.
        admin.postData(CFEP.bundleWrite, write(column("Note again", "noteAgain")) + (CFEP.reloadField to true))
        values().first()["noteAgain"] shouldBe values().first()["expenseNote"]
    }
}

/**
 * A listing column's value and its presentation (issue #1323): the column sorts and searches by its value, which each
 * row carries, and the presentation its cells read through comes once, with the listing's columns. Over the shared
 * instance, with a client of its own.
 */
private fun listingPresentationTests() = stringSpec {
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
}
