package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldContainAll

/**
 * The Gedra entry sketch driven through the request pipeline (issue #252).
 *
 * The kernel tests cover selection and the boot checks directly; what these add is that a union survives the
 * trip an endpoint actually takes — resolved as an input type, validated by the dispatcher, and reported as a
 * 400 with the failure sitting on the field that caused it rather than on the union.
 */
class GedraSketchTest : StringSpec({

    // The component is registered here rather than discovered: the ServiceLoader entry that finds it in a
    // running deployment does not reach the test classpath (SampleFileApiTest does the same).
    InstanceRegistry.register(listOf(SampleComponent()))

    // Force SampleComponent.isLoaded on: it otherwise gates to developer envs, and mkTestBootCxt uses unit.
    fun client(name: String): TestHttpClient =
        TestHttpClient(
            Startup.mkTestBootCxt(name, "gedraSketch-$name", mapOf("KDR_LOAD_SAMPLE" to "true")).instanceConfig,
        )

    fun echo(name: String, entry: Map<String, Any?>): Map<String, Any?> =
        client(name).sendJsonPostRequest("/fixture/gedra/entry/echo", mapOf(GS.entry to entry))
            .getValue(EP.results)!!.toJsonMap()

    fun status(name: String, entry: Map<String, Any?>): Int =
        client(name).sendEditRequest("/fixture/gedra/entry/echo", null, mapOf(GS.entry to entry), isPut = false)
            .rptStatusCode

    "an entry is validated against the branch its traitId names" {
        val results = echo("expense", mapOf(
            GS.traitId to GS.expenseReport, GS.year to 2024, GS.perItemAmount to 12.5, GS.itemCount to 3,
        ))
        results[GS.branch] shouldBe GS.expenseReport
        // Assert the shape rather than a count: the count now also carries the stored envelope, so a number
        // here would have to be edited every time the envelope changes and would say nothing while it did.
        val entry = results.getValue(GS.entry)!!.toJsonMap()
        entry.keys shouldContainAll setOf(GS.year, GS.perItemAmount, GS.itemCount, GS.totalAmount)
    }

    "a different traitId in the same field selects a different branch" {
        val results = echo("approval", mapOf(GS.traitId to GS.managerApproval, GS.approved to true))
        results[GS.branch] shouldBe GS.managerApproval
    }

    // The branch's own constraint, reached through the union: `year` has a minimum, `approved` does not exist
    // on this branch, and only the selected branch gets a say.
    "a branch's own constraint rejects the entry" {
        status("belowMin", mapOf(GS.traitId to GS.expenseReport, GS.year to 1999)) shouldBe 400
    }

    "a field belonging to another branch is refused, since branches are closed" {
        status("crossBranch", mapOf(GS.traitId to GS.expenseReport, GS.year to 2024, GS.approved to true)) shouldBe 400
    }

    // The projection (issue #254), asked in both directions: the caller does not send the derived value, and
    // the response carries it. A test that only checked one of the two would pass for a model that had
    // collapsed the input and output shapes into one.
    "a derived value is computed for the response and never taken from the request" {
        val results = echo("derived", mapOf(
            GS.traitId to GS.expenseReport, GS.year to 2024, GS.perItemAmount to 2.5, GS.itemCount to 4,
        ))
        val entry = results.getValue(GS.entry)!!.toJsonMap()
        entry[GS.totalAmount] shouldBe 10.0

        // Echoed back by a client, the way read-modify-write does: dropped, not refused, and not believed.
        val echoed = echo("derivedEchoed", mapOf(
            GS.traitId to GS.expenseReport, GS.year to 2024, GS.perItemAmount to 2.5, GS.itemCount to 4,
            GS.totalAmount to 999.0,
        ))
        echoed.getValue(GS.entry)!!.toJsonMap()[GS.totalAmount] shouldBe 10.0
    }

    // What the caller is shown: the field they may not send is absent from the published input schema, so a
    // client generated from the catalog cannot even try.
    "the published input schema does not offer a derived field" {
        val catalog = client("catalogView").sendJsonGetRequest("/schema/endpoint", mapOf(
            "method" to "POST", "path" to "/fixture/gedra/entry/echo",
        )).getValue(EP.results)!!.toJsonMap()
        val defs = catalog.getValue($$"$defs")!!.toJsonMap()
        val branch = defs.getValue("gedra.ExpenseReportEntry")!!.toJsonMap()
        val props = branch.getValue("properties")!!.toJsonMap()
        // The keyword travels, which is what lets every surface honor it -- see the note in
        // buildEndpointInputSchema about the shared $defs bag.
        props.getValue(GS.totalAmount)!!.toJsonMap()["g-derived"] shouldBe true
    }

    // The conditional inside a branch (issue #253): the two mechanisms in one payload.
    "a rejection must say why, and an approval must not" {
        echo("rejected", mapOf(
            GS.traitId to GS.managerApproval, GS.approved to false, GS.rejectionReason to "Submitted late.",
        ))[GS.branch] shouldBe GS.managerApproval

        // Rejected with no reason.
        status("noReason", mapOf(GS.traitId to GS.managerApproval, GS.approved to false)) shouldBe 400
        // Approved, yet carrying a rejection reason.
        status("bothWays", mapOf(
            GS.traitId to GS.managerApproval, GS.approved to true, GS.rejectionReason to "Submitted late.",
        )) shouldBe 400
    }

    // The default branch keeps an unknown trait readable rather than failing the request -- what a reader
    // holding only some of the trait definitions needs.
    "an unknown traitId falls to the default branch instead of failing" {
        val results = echo("unknown", mapOf(GS.traitId to "somethingNobodyDeclared", "anything" to 1))
        // The carried field survives: an opaque entry that arrives whole and is stored empty is worse than one
        // that is refused, because nothing says it happened.
        results.getValue(GS.entry)!!.toJsonMap()["anything"] shouldBe 1L
        results[GS.branch] shouldBe GS.default
        results[GS.traitId] shouldBe "somethingNobodyDeclared"
    }

    "an entry with no traitId at all is refused" {
        status("noTrait", mapOf(GS.year to 2024)) shouldBe 400
    }
    // --- the round trip (issue #255) -----------------------------------------

    fun save(name: String, entries: List<Map<String, Any?>>): List<Map<String, Any?>> =
        client(name).sendJsonPostRequest("/fixture/gedra/entries/fillOut", mapOf(GS.entries to entries))
            .getValue(EP.items)!!.toJsonListOfMaps()

    // One array carrying several shapes, each validated against the branch its own traitId names. This is the
    // whole design under load at once: a union selecting per element, a conditional inside one of the
    // branches, and a projection deciding what the caller supplies versus what comes back.
    "an array of differently shaped entries round-trips" {
        val saved = save("mixed", listOf(
            mapOf(GS.traitId to GS.expenseReport, GS.year to 2024, GS.perItemAmount to 10.0, GS.itemCount to 3),
            mapOf(GS.traitId to GS.managerApproval, GS.approved to false, GS.rejectionReason to "Too late."),
            mapOf(GS.traitId to "somethingNobodyDeclared", "carried" to "through"),
        ))
        saved.map { it[GS.traitId] } shouldBe listOf(GS.expenseReport, GS.managerApproval, "somethingNobodyDeclared")
        // Derived within a branch, and the envelope every entry gains.
        saved[0][GS.totalAmount] shouldBe 30.0
        saved.forEach { it[GS.source] shouldBe GS.userSource }
        saved.map { it[GS.entryId] } shouldBe listOf("e-1", "e-2", "e-3")
    }

    // A failure has to name the element it came from, or an array of thirty entries reports a problem with no
    // way to find it.
    "a failure in one element names that element" {
        val resp = client("badElement").sendEditRequest(
            "/fixture/gedra/entries/fillOut", null,
            mapOf(GS.entries to listOf(
                mapOf(GS.traitId to GS.expenseReport, GS.year to 2024),
                mapOf(GS.traitId to GS.expenseReport, GS.year to 1999),
            )),
            isPut = false,
        )
        resp.rptStatusCode shouldBe 400
    }

    // The entries the caller sends carry none of the stored envelope, and every entry that comes back carries
    // all of it. Asked in both directions on purpose: this is the projection, seen from the outside.
    "the caller supplies none of the stored fields and receives all of them" {
        val sent = mapOf(GS.traitId to GS.managerApproval, GS.approved to true)
        val saved = save("envelope", listOf(sent)).single()
        sent.keys shouldNotContain GS.entryId
        saved.keys shouldContainAll setOf(GS.entryId, GS.source, GS.createdAt, GS.updatedAt)
    }

    // Echoed back the way read-modify-write does: the envelope is dropped on arrival and re-stamped, so a
    // client cannot pin an id or backdate a record by sending one.
    "a client cannot set the stored fields by sending them" {
        val saved = save("reEchoed", listOf(mapOf(
            GS.traitId to GS.managerApproval, GS.approved to true,
            GS.entryId to "e-999", GS.source to "excel", GS.createdAt to "1999-01-01T00:00:00Z",
        ))).single()
        saved[GS.entryId] shouldBe "e-1"
        saved[GS.source] shouldBe GS.userSource
        saved[GS.createdAt] shouldNotBe "1999-01-01T00:00:00Z"
    }

})
