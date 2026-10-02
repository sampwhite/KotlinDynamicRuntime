package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.CursorToken
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.CursorKeys
import com.dynamicruntime.common.endpoint.cursorPage
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Cursor paging over real HTTP (issue #976), against a fixture listing: `after` in, `next` out, through the
 * dispatcher's input validation and the response-schema validation `mkTestBootCxt` turns on -- so a page with a
 * `next` and the last page without one both have to conform to the declared output.
 */
class CursorPagingEndpointTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("cursorPaging", "cursorPagingTest", emptyMap(), listOf(CursorFixtureComponent()))
    val http = TestHttpClient(cxt.instanceConfig)

    fun page(path: String, args: Map<String, Any?>) = http.sendJsonGetRequest(path, args)
    fun ids(resp: Map<String, Any?>) = resp[EP.items].toJsonListOfMaps().map { it["id"].toOptStr() }

    "walking next returns every row once, and the last page has no next" {
        val seen = mutableListOf<String?>()
        var after: String? = null
        var pages = 0
        while (true) {
            val resp = page(CursorFixtureComponent.walk, mapOf(EP.limit to 3) + (after?.let { mapOf(EP.after to it) } ?: emptyMap()))
            resp[EP.status] shouldBe null
            seen += ids(resp)
            resp[EP.numAvailable] shouldBe CursorFixtureComponent.rows.size
            pages++
            val next = resp[EP.next].toOptStr()
            // hasMore and next are one fact.
            resp[EP.hasMore] shouldBe (next != null)
            if (next == null) {
                resp.containsKey(EP.next) shouldBe false
                break
            }
            after = next
        }
        seen shouldContainExactly CursorFixtureComponent.rows
        pages shouldBe 3
    }

    "an empty after is the first page" {
        ids(page(CursorFixtureComponent.walk, mapOf(EP.limit to 2, EP.after to ""))) shouldContainExactly
            CursorFixtureComponent.rows.take(2)
    }

    "a cursor of another query, or a malformed one, is a 400" {
        val foreign = CursorToken.encode("someOtherListing", listOf("r3"))
        val resp = page(CursorFixtureComponent.walk, mapOf(EP.after to foreign))
        resp[EP.status] shouldBe EXC.badInput
        resp[EP.errorMessage].toOptStr().shouldNotBeNull() shouldContain "different query"
        page(CursorFixtureComponent.walk, mapOf(EP.after to "garbage"))[EP.status] shouldBe EXC.badInput
    }

    // A token of this very query whose key is not this listing's shape: refused, never read as "no cursor", which
    // would answer page one and send an automated walk round again.
    "a cursor whose key is the wrong shape is a 400, not the first page" {
        for (key in listOf(emptyList(), listOf(5), listOf("r1", "extra"))) {
            val resp = page(CursorFixtureComponent.walk, mapOf(EP.after to CursorToken.encode(CursorFixtureComponent.queryId, key)))
            resp[EP.status] shouldBe EXC.badInput
        }
    }

    "a limit below one is a 400" {
        page(CursorFixtureComponent.walk, mapOf(EP.limit to 0))[EP.status] shouldBe EXC.badInput
    }

    "a cursor-paged handler that returns a plain list is a server fault, not a truncated page" {
        page(CursorFixtureComponent.broken, emptyMap())[EP.status] shouldBe EXC.internalError
    }
})

/** A fixture component with one cursor-paged listing over a fixed set of rows, and one that breaks the contract. */
class CursorFixtureComponent : ComponentDefinition {
    override val providerName: String = "cursorFixture"
    override val ownerRoot: String = namespace

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        collector.addModule(
            schemaModule(cxt, namespace) {
                type("Row") { type = SCT.kObject; property("id", "The row's id.", required = true) }
                listEndpoint(walk, "A cursor-paged walk over fixed rows.", outputRef = "Row", cursorPaged = true) { _, request ->
                    cursorPage(request, queryId, rows, { it }, naturalOrder(), CursorKeys.string) { mapOf("id" to it) }
                }
                listEndpoint(broken, "Returns a plain list from a cursor-paged listing.", outputRef = "Row", cursorPaged = true) { _, _ ->
                    rows.map { mapOf("id" to it) }
                }
            },
        )
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val namespace = "cursorfixture"
        const val walk = "/fixture/cursor/walk"
        const val broken = "/fixture/cursor/broken"
        const val queryId = "cursorFixture"
        val rows = (1..8).map { "r$it" }
    }
}
