package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.UsageKind
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The sort a listing applies before paging (issue #666): `GedraDataService.listGedras` orders the whole
 * scope-matched set by a chosen column in place of its default order, then pages -- so the page and its
 * `numAvailable` are over the sorted set. Exercised over the cache path (a client scope); the SQL fallback runs
 * the same `orderBySort`. The comparator itself (number-by-value, blanks-last, direction) is unit-tested in
 * `GedraSearchTest`; this pins that the service applies it, keeps the total order, and does not disturb paging.
 *
 * Its own client -- every spec shares one in-memory database, and another asserts exhaustively over its own.
 */
class GedraSortTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraSort", "gedraSortTest")
    val client = "gsortclient"
    val kind = GedraDataType.formDoc
    val scope = ReadScope.ofClient(client)

    fun svc(): GedraDataService = GedraDataService.get(cxt)
    fun asOwner(): KdrCxt = cxt.mkSubContext("gsort", client).also { it.userId = 90201L }

    /** Creates a form carrying a `name`, or none when [name] is null (a row missing the sorted trait). */
    fun create(name: String?) {
        val entries = if (name == null) emptyList() else listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name)))
        svc().createGedra(asOwner(), kind, entries)
    }

    /** The `name` value of a row, as the sort key reads it; "" when the row has none. */
    fun nameOf(row: GedraDataRow): String =
        row.entries.firstOrNull { it[GE.traitId].toOptStr() == GT.name }?.get(GE.data).toJsonMapOrEmpty()[GT.name].toOptStr() ?: ""

    fun sortBy(kindOf: UsageKind, descending: Boolean) =
        GedraDataService.GedraSort(kindOf, descending) { nameOf(it) }

    fun namesSorted(kindOf: UsageKind, descending: Boolean, limit: Int = 50): List<String> =
        svc().listGedras(cxt, kind, scope, limit, 0, null, sortBy(kindOf, descending)).rows.map { nameOf(it) }

    // "10", "2", "1" so a numeric sort differs from a lexical one; a blank so blanks-last is exercised.
    create("10"); create("2"); create("1"); create(null)

    "a number sort orders by value, ascending, blanks last" {
        namesSorted(UsageKind.number, descending = false) shouldBe listOf("1", "2", "10", "")
    }

    "a number sort descending reverses the values but keeps blanks last" {
        namesSorted(UsageKind.number, descending = true) shouldBe listOf("10", "2", "1", "")
    }

    "a string sort is lexical over the same values" {
        // "1", "10", "2" lexically -- the wrong order for numbers, the right one for text.
        namesSorted(UsageKind.string, descending = false) shouldBe listOf("1", "10", "2", "")
    }

    "the sort orders the whole set before paging, and numAvailable is the whole set" {
        val page = svc().listGedras(cxt, kind, scope, limit = 2, offset = 0, null, sortBy(UsageKind.number, false))
        page.rows.map { nameOf(it) } shouldBe listOf("1", "2")
        page.numAvailable shouldBe 4
        val next = svc().listGedras(cxt, kind, scope, limit = 2, offset = 2, null, sortBy(UsageKind.number, false))
        next.rows.map { nameOf(it) } shouldBe listOf("10", "")
        next.numAvailable shouldBe 4
    }
})
