package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

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
        client(name).sendJsonPostRequest("/gedra/entry/echo", mapOf(GS.entry to entry))
            .getValue(EP.results)!!.toJsonMap()

    fun status(name: String, entry: Map<String, Any?>): Int =
        client(name).sendEditRequest("/gedra/entry/echo", null, mapOf(GS.entry to entry), isPut = false)
            .rptStatusCode

    "an entry is validated against the branch its traitId names" {
        val results = echo("expense", mapOf(GS.traitId to GS.expenseReport, GS.year to 2024, GS.totalAmount to 4820.15))
        results[GS.branch] shouldBe GS.expenseReport
        results[GS.fieldCount] shouldBe 3
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

    // The default branch keeps an unknown trait readable rather than failing the request -- what a reader
    // holding only some of the trait definitions needs.
    "an unknown traitId falls to the default branch instead of failing" {
        val results = echo("unknown", mapOf(GS.traitId to "somethingNobodyDeclared", "anything" to 1))
        // The carried field survives: an opaque entry that arrives whole and is stored empty is worse than one
        // that is refused, because nothing says it happened.
        results[GS.fieldCount] shouldBe 2
        results[GS.branch] shouldBe GS.default
        results[GS.traitId] shouldBe "somethingNobodyDeclared"
    }

    "an entry with no traitId at all is refused" {
        status("noTrait", mapOf(GS.year to 2024)) shouldBe 400
    }
})
