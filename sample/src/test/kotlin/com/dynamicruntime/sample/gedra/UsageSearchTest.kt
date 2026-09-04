package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.GDBG
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GDX
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Searching the forms list by a trait a client declared a usage rule for (issue #538). The parameters come from
 * the usage rules (issue #537): every client's list can be sliced by a search field, and because a search
 * parameter is advertised on the client's own variant of the listing's named input type, a request carrying one
 * only validates when the client actually declared it -- so a passing search here is also proof the per-client
 * variant carries the field.
 *
 * `globex` inherits the global `name` usage (searchable exact and by substring); `acme` overrides it with an
 * `Auditor` string (exact and substring) and a `Year` number (a `>=`/`<=` range) -- between them, every kind.
 * Each case uses a fresh user, so the rows it searches are its own (an ordinary caller's read scope is their own
 * rows), which also means these run through the SQL fall-back path; the cache path filters the same predicate.
 */
class UsageSearchTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "usageSearch", "usageSearchTest", mapOf("KDR_LOAD_SAMPLE" to "true"), additionalComponents = listOf(SampleComponent()),
    )

    // The search parameter names, derived exactly as the backend derives them from a usage's trait id.
    fun exact(traitId: String) = traitId
    fun contains(traitId: String) = "${traitId}Contains"
    fun min(traitId: String) = "${traitId}Min"
    fun max(traitId: String) = "${traitId}Max"

    fun displayValue(row: Map<String, Any?>, traitId: String): Any? =
        row[GDF.displayValues].toJsonListOfMaps().first { it[UF.traitId] == traitId }[UF.value]

    fun postName(user: TestUser, name: String) = user.postItem(
        clientPath(GEP.formDocCreate, SC.globex),
        mapOf(GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name)))),
    )

    fun postAudit(user: TestUser, auditor: String) = user.postItem(
        clientPath(GEP.formDocCreate, SC.acme),
        mapOf(GDF.entries to listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to auditor, SC.findings to "ok")))),
    )

    fun postExpense(user: TestUser, year: Int) = user.postItem(
        clientPath(GEP.formDocCreate, SC.acme),
        mapOf(GDF.entries to listOf(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to year)))),
    )

    "globex searches its Name column exact (case-insensitively) and by substring" {
        val user = TestUser.create(cxt, "name-search@globex.test", userClient = SC.globex)
        postName(user, "Quarterly plan")
        postName(user, "Annual budget")
        val path = clientPath(GEP.formDocs, SC.globex)

        // No search: the whole page, the named-type conversion having changed nothing about ordinary listing.
        user.getItems(path).size shouldBe 2
        // Exact, case-insensitive: the exact parameter is named for the trait (`name`).
        user.getItems(path, mapOf(exact(GT.name) to "quarterly plan"))
            .map { displayValue(it, GT.name) } shouldBe listOf("Quarterly plan")
        // Substring: the contains parameter globex inherits because the global `name` usage asked for it.
        user.getItems(path, mapOf(contains(GT.name) to "budg"))
            .map { displayValue(it, GT.name) } shouldBe listOf("Annual budget")
        // A substring that matches neither: an empty page, not an error.
        user.getItems(path, mapOf(contains(GT.name) to "zzz")).size shouldBe 0
    }

    "acme searches its Auditor column exact and by substring" {
        val user = TestUser.create(cxt, "auditor-search@acme.test", userClient = SC.acme)
        postAudit(user, "Dana Reyes")
        postAudit(user, "Sam Patel")
        val path = clientPath(GEP.formDocs, SC.acme)

        user.getItems(path, mapOf(exact(SC.siteAudit) to "dana reyes"))
            .map { displayValue(it, SC.siteAudit) } shouldBe listOf("Dana Reyes")
        user.getItems(path, mapOf(contains(SC.siteAudit) to "patel"))
            .map { displayValue(it, SC.siteAudit) } shouldBe listOf("Sam Patel")
    }

    "acme searches its Year column as a >= / <= range" {
        val user = TestUser.create(cxt, "year-search@acme.test", userClient = SC.acme)
        postExpense(user, 2024)
        postExpense(user, 2026)
        val path = clientPath(GEP.formDocs, SC.acme)

        // A lower bound keeps 2026; an upper bound keeps 2024; the two together keep both.
        user.getItems(path, mapOf(min(ST.expenseReport) to 2025))
            .map { displayValue(it, ST.expenseReport) } shouldBe listOf("2026")
        user.getItems(path, mapOf(max(ST.expenseReport) to 2025))
            .map { displayValue(it, ST.expenseReport) } shouldBe listOf("2024")
        user.getItems(path, mapOf(min(ST.expenseReport) to 2024, max(ST.expenseReport) to 2026))
            .map { displayValue(it, ST.expenseReport) } shouldContainExactlyInAnyOrder listOf("2024", "2026")
        // A range that excludes both: an empty page.
        user.getItems(path, mapOf(min(ST.expenseReport) to 2030)).size shouldBe 0
    }

    "two search parameters together narrow to their intersection" {
        val user = TestUser.create(cxt, "combined-search@acme.test", userClient = SC.acme)
        postAudit(user, "Dana Reyes")
        postExpense(user, 2026)
        val path = clientPath(GEP.formDocs, SC.acme)

        // The audit row has no year and the expense row has no auditor, so a search on both matches neither: the
        // parameters are ANDed, and a row missing either trait fails the one about it (a blank cell is a
        // non-match, not a fault).
        user.getItems(path, mapOf(exact(SC.siteAudit) to "Dana Reyes", min(ST.expenseReport) to 2000)).size shouldBe 0
    }

    "an admin's client-scoped search runs the predicate through the gedra cache" {
        // An admin is client-wide, so its read scope carries a client and the listing is served from the
        // gedra cache's client+kind index -- the path an ordinary user (scoped to their own rows) never takes.
        // The predicate must filter there too, which this asserts through the `explainScope` diagnostic, over a
        // uniquely-named row so a client-wide reader (which sees every acme audit this shared instance holds)
        // still matches exactly one.
        val admin = TestUser.create(cxt, "cache-admin@acme.test", level = ROLE.admin, userClient = SC.acme)
        val unique = "Zephyr Cachecheck"
        postAudit(admin, unique)
        val resp = admin.client.sendJsonGetRequest(
            clientPath(GEP.formDocs, SC.acme),
            mapOf(exact(SC.siteAudit) to unique, EP.debug to GDBG.explainScope),
        )
        // The search returned exactly the uniquely-named row...
        resp[EP.items].toJsonListOfMaps().map { displayValue(it, SC.siteAudit) } shouldBe listOf(unique)
        // ...filtered over the cache's client+kind index, not the SQL fall-back (the diagnostic names which ran).
        val explained = resp[EP.meta].toJsonMapOrEmpty()[GDBG.scopeExplained].toJsonMapOrEmpty()
        explained[GDBG.statement] shouldBe "cache:${GDX.clientKind}"
    }
    // The free-text term (issue #562): one box searched across every text field, ANDed with per-field filters.
    "the free-text term searches the text fields at once, and stacks with a per-field filter" {
        val user = TestUser.create(cxt, "q-search@globex.test", userClient = SC.globex)
        postName(user, "Quarterly plan")
        postName(user, "Annual budget")
        val path = clientPath(GEP.formDocs, SC.globex)

        // Substring, case-insensitive, without naming which field -- the point of the box.
        user.getItems(path, mapOf(EI.q to "BUDG")).map { displayValue(it, GT.name) } shouldBe listOf("Annual budget")
        // Matches neither: an empty page, not an error.
        user.getItems(path, mapOf(EI.q to "zzz")).size shouldBe 0
        // ANDed with a per-field filter: the term alone matches both, the filter narrows to one.
        user.getItems(path, mapOf(EI.q to "a", contains(GT.name) to "annual"))
            .map { displayValue(it, GT.name) } shouldBe listOf("Annual budget")
        // A blank term is no search: the whole page.
        user.getItems(path, mapOf(EI.q to "  ")).size shouldBe 2
    }

    // Field-value suggestions (issue #581): the distinct values a text trait takes across the caller's own
    // documents, for a filter box's type-ahead -- deduped, contains-filtered, sorted, and scoped to the caller.
    "formDocValues suggests a text trait's distinct values, deduped and filtered" {
        val user = TestUser.create(cxt, "values@globex.test", userClient = SC.globex)
        postName(user, "Quarterly plan")
        postName(user, "Quarterly plan")   // a duplicate collapses to one distinct value
        postName(user, "Annual budget")
        val path = clientPath(GEP.formDocValues, SC.globex)
        fun values(args: Map<String, Any?>) = user.getItems(path, args).map { it[UF.value] }

        // All distinct values, sorted, the duplicate collapsed to one.
        values(mapOf(GE.traitId to GT.name)) shouldBe listOf("Annual budget", "Quarterly plan")
        // A case-insensitive contains filter.
        values(mapOf(GE.traitId to GT.name, EI.q to "QUART")) shouldBe listOf("Quarterly plan")
        // A fragment matching nothing: an empty list, not an error.
        values(mapOf(GE.traitId to GT.name, EI.q to "zzz")).size shouldBe 0
        // A nonsensical negative limit is the harmless empty page, not a 500 (the limit is floored at 0).
        values(mapOf(GE.traitId to GT.name, EP.limit to -1)).size shouldBe 0
    }

    "formDocValues is scoped to the caller: one user's values are not another's" {
        val alice = TestUser.create(cxt, "vscope-alice@globex.test", userClient = SC.globex)
        val bob = TestUser.create(cxt, "vscope-bob@globex.test", userClient = SC.globex)
        postName(alice, "Alice plan zzscopemark")
        val path = clientPath(GEP.formDocValues, SC.globex)
        // Bob, scoped to his own rows, sees none of alice's distinct values.
        bob.getItems(path, mapOf(GE.traitId to GT.name, EI.q to "zzscopemark")).size shouldBe 0
        // Alice sees her own.
        alice.getItems(path, mapOf(GE.traitId to GT.name, EI.q to "zzscopemark")).map { it[UF.value] } shouldBe
            listOf("Alice plan zzscopemark")
    }

    "formDocValues refuses a trait that is not a text search field" {
        val user = TestUser.create(cxt, "values-bad@acme.test", userClient = SC.acme)
        val path = clientPath(GEP.formDocValues, SC.acme)
        // A number usage (Year) has no value list to suggest -- a 400, not an empty page.
        user.expectError(400, path, args = mapOf(GE.traitId to ST.expenseReport))
        // An unknown trait id.
        user.expectError(400, path, args = mapOf(GE.traitId to "nosuchtrait"))
        // A missing trait id.
        user.expectError(400, path)
    }
})
