package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Several listing columns reading one trait (issue #1490): each column has a name of its own -- its `columnId`, else
 * the trait id -- which its values on a row, its search parameters, its sort, and its value suggestions go by, so the
 * two show and search independently. Over the shared instance, with a client of its own.
 */
class UsageColumnsTest : StringSpec({
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
})
