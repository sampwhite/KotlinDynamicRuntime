package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.deriveEntryData
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * The derived **data** value computed on read (issue #712): the sample's `ExpenseTotalDeriver` fills an expense
 * report's `totalAmount` from the two amounts beside it whenever the form is read, and never stores it. This
 * pins the three read surfaces the two read-only form views are built from -- the single GET, the listing (the
 * raw read-only view is built from a listing row), and the survey workflow view -- plus the compute-on-read
 * contract itself: the value is present on a read and absent from what is stored.
 *
 * Its own clients on one shared in-memory database, as the neighbouring gedra sample tests explain. `acme`
 * collects `expenseReport` (its creation and survey workflows), so both the endpoints and the view resolve
 * against a real trait.
 */
class GedraDataDerivationTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "gedraDataDeriv", "gedraDataDerivTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    fun service(): GedraDataService = GedraDataService.get(cxt)
    fun asUser(client: String, userId: Long): KdrCxt = cxt.mkSubContext("dderiv", client).also { it.userId = userId }

    /** An expenseReport data entry -- the caller supplies the two amounts, never the total. */
    fun expense(year: Int, perItem: Double, count: Int): Map<String, Any?> =
        mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to year, ST.perItemAmount to perItem, ST.itemCount to count))

    fun entryData(entries: List<Map<String, Any?>>, traitId: String): Map<String, Any?> =
        entries.first { it[GE.traitId].toOptStr() == traitId }[GE.data].toJsonMapOrEmpty()

    "compute on read fills the total and does not store it" {
        val acme = asUser(SC.acme, 91201L)
        val gid = service().createGedra(acme, GedraDataType.formDoc, listOf(expense(2024, 12.5, 4))).gedraId

        // What is stored carries no total -- creation derives nothing, exactly as the trait's g-derived note says.
        val stored = service().queryGedra(acme, gid.fullId, GedraDataType.formDoc, ReadScope.ofClient(SC.acme))!!
        entryData(stored.entries, ST.expenseReport)[ST.totalAmount].shouldBeNull()

        // Computed on read, the total follows from the two amounts: 12.5 * 4 = 50.
        val derived = deriveEntryData(acme, GedraDataType.formDoc, stored.entries, SC.acme)
        entryData(derived, ST.expenseReport)[ST.totalAmount] shouldBe 50.0
    }

    "an entry missing a supplied amount is left untouched" {
        val acme = asUser(SC.acme, 91202L)
        // Only the per-item amount, no count: the deriver has nothing to compute, so the entry passes through
        // with no total rather than a wrong or partial one.
        val entries = listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2024, ST.perItemAmount to 9.0)))
        val derived = deriveEntryData(acme, GedraDataType.formDoc, entries, SC.acme)
        entryData(derived, ST.expenseReport)[ST.totalAmount].shouldBeNull()
    }

    "a trait with no deriver passes through unchanged" {
        val acme = asUser(SC.acme, 91203L)
        val entries = listOf(mapOf(GE.traitId to "name", GE.data to mapOf("name" to "No total here")))
        deriveEntryData(acme, GedraDataType.formDoc, entries, SC.acme) shouldBe entries
    }

    // --- the read surfaces the two read-only form views are built from, over real HTTP ---

    val acmeCreate = clientPath(GEP.formDocCreate, SC.acme)
    val acmeGet = clientPath(GEP.formDoc, SC.acme)
    val acmeList = clientPath(GEP.formDocs, SC.acme)
    val acmeView = clientPath(GEP.workflowView, SC.acme)

    fun createExpense(user: TestUser): String {
        val item = user.postItem(acmeCreate, mapOf(GDF.entries to listOf(expense(2024, 12.5, 4))))
        return item[GDF.gedraId].toOptStr()!!
    }

    "the single GET derives the total on read" {
        val user = TestUser.create(cxt, "dd-get@acme.test", userClient = SC.acme)
        val gid = createExpense(user)
        val item = user.getItem(acmeGet, mapOf(GDF.gedraId to gid))
        entryData(item[GDF.entries].toJsonListOfMaps(), ST.expenseReport)[ST.totalAmount] shouldBe 50.0
    }

    "the listing derives the total on each row (the raw read-only view's source)" {
        val user = TestUser.create(cxt, "dd-list@acme.test", userClient = SC.acme)
        val gid = createExpense(user)
        val row = user.getItems(acmeList).first { it[GDF.gedraId].toOptStr() == gid }
        entryData(row[GDF.entries].toJsonListOfMaps(), ST.expenseReport)[ST.totalAmount] shouldBe 50.0
    }

    "the survey workflow view derives the total in a task's presented entries" {
        val user = TestUser.create(cxt, "dd-view@acme.test", userClient = SC.acme)
        val gid = createExpense(user)
        val view = user.getData(acmeView, mapOf(GDF.gedraId to gid))
        val presented = view[WFD.tasks].toJsonListOfMaps()
            .flatMap { it[WVF.entries].toJsonListOfMaps() }
        entryData(presented, ST.expenseReport)[ST.totalAmount] shouldBe 50.0
    }
})
