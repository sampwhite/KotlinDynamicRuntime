package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
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
 * The expense report's total, computed on every write by the trait's own `g-onWrite` function (issue #1501) and stored:
 * `computeValue` over the per-item amount and the item count. This pins what is stored -- the total present, a
 * caller's own total ignored, a missing amount leaving none -- and the three read surfaces the two read-only form views
 * are built from: the single GET, the listing (the raw read-only view is built from a listing row), and the survey
 * workflow view, which now read the stored value as they read any other.
 *
 * Its own clients, as the neighboring gedra sample tests do. `acme` collects `expenseReport` (its creation and survey
 * workflows), so both the endpoints and the view resolve against a real trait.
 */
class ExpenseTotalOnWriteTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "expenseTotalOnWrite", "expenseTotalOnWriteTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    fun service(): GedraDataService = GedraDataService.get(cxt)
    fun asUser(client: String, userId: Long): KdrCxt = cxt.mkSubContext("onWriteTotal", client).also { it.userId = userId }

    /** An expenseReport data entry -- the caller supplies the two amounts, never the total. */
    fun expense(year: Int, perItem: Double, count: Int): Map<String, Any?> =
        mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to year, ST.perItemAmount to perItem, ST.itemCount to count))

    fun entryData(entries: List<Map<String, Any?>>, traitId: String): Map<String, Any?> =
        entries.first { it[GE.traitId].toOptStr() == traitId }[GE.data].toJsonMapOrEmpty()

    fun storedTotal(acme: KdrCxt, entries: List<Map<String, Any?>>): Any? {
        val gid = service().createGedra(acme, GedraDataType.formDoc, entries).gedraId
        val stored = service().queryGedra(acme, gid.fullId, GedraDataType.formDoc, ReadScope.ofClient(SC.acme))!!
        return entryData(stored.entries, ST.expenseReport)[ST.totalAmount]
    }

    "the total is stored when the form is created" {
        // 12.5 * 4 = 50, computed as the entry is written and kept with it.
        storedTotal(asUser(SC.acme, 91201L), listOf(expense(2024, 12.5, 4))) shouldBe 50.0
    }

    "an entry missing a supplied amount stores no total" {
        val entries = listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2024, ST.perItemAmount to 9.0)))
        storedTotal(asUser(SC.acme, 91202L), entries).shouldBeNull()
    }

    "a total the caller supplies is replaced by the computed one" {
        val data = mapOf(ST.year to 2024, ST.perItemAmount to 2.0, ST.itemCount to 3, ST.totalAmount to 999.0)
        storedTotal(asUser(SC.acme, 91203L), listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to data))) shouldBe 6.0
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

    "the single GET reads the stored total" {
        val user = TestUser.create(cxt, "dd-get@acme.test", userClient = SC.acme)
        val gid = createExpense(user)
        val item = user.getItem(acmeGet, mapOf(GDF.gedraId to gid))
        entryData(item[GDF.entries].toJsonListOfMaps(), ST.expenseReport)[ST.totalAmount] shouldBe 50.0
    }

    "the listing carries the stored total on each row (the raw read-only view's source)" {
        val user = TestUser.create(cxt, "dd-list@acme.test", userClient = SC.acme)
        val gid = createExpense(user)
        val row = user.getItems(acmeList).first { it[GDF.gedraId].toOptStr() == gid }
        entryData(row[GDF.entries].toJsonListOfMaps(), ST.expenseReport)[ST.totalAmount] shouldBe 50.0
    }

    "the survey workflow view presents the stored total in a task's entries" {
        val user = TestUser.create(cxt, "dd-view@acme.test", userClient = SC.acme)
        val gid = createExpense(user)
        val view = user.getData(acmeView, mapOf(GDF.gedraId to gid))
        val presented = view[WFD.tasks].toJsonListOfMaps()
            .flatMap { it[WVF.entries].toJsonListOfMaps() }
        entryData(presented, ST.expenseReport)[ST.totalAmount] shouldBe 50.0
    }
})
