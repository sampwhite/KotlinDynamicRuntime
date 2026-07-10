package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.typeRefPath
import com.dynamicruntime.common.startup.CX
import com.dynamicruntime.common.startup.SS
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * The "exercise everything at once" test for the schema layer, driven through the in-process client against
 * the `PUT /schema/complex` endpoint. It covers, in one place: deep `$ref` validation (a chain of referenced
 * object types), recursive validation and the cyclic `$defs` walk (the self-referential `TreeNode`), scalar
 * coercion, `option` choices, dates, and the list envelope's `limit`. As the schema layer grows (allOf /
 * anyOf / if / else), extend `ComplexInput` in SchemaService and add cases here.
 *
 * Runs under [Startup.mkTestBootCxt] (which sets `validateResponseSchema`), so responses are output-validated too.
 */
class SchemaComplexEndpointTest : StringSpec({

    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "schemaComplexTest").instanceConfig)

    fun items(resp: Map<String, Any?>): List<Map<String, Any?>> =
        (resp[EP.items] as? List<*>).orEmpty().map { it!!.toJsonMap() }

    // Navigate to a nested, mutable sub-map (the builders below produce LinkedHashMaps all the way down, so a
    // test can corrupt one leaf to prove validation without rebuilding the whole structure).
    @Suppress("UNCHECKED_CAST")
    fun sub(map: Map<String, Any?>, key: String): LinkedHashMap<String, Any?> =
        map[key] as LinkedHashMap<String, Any?>

    // A fully valid, deeply nested, recursive input. Each call rebuilds it fresh so mutations don't leak.
    fun geo(): LinkedHashMap<String, Any?> = linkedMapOf(CX.lat to 40.7, CX.lon to -74.0)
    fun address(): LinkedHashMap<String, Any?> =
        linkedMapOf(CX.street to "1 Main St", CX.zip to "12345", CX.location to geo())
    fun tree(): LinkedHashMap<String, Any?> = linkedMapOf(
        CX.label to "root", CX.weight to 1.0,
        CX.parent to linkedMapOf<String, Any?>(
            CX.label to "mid",
            CX.parent to linkedMapOf<String, Any?>(CX.label to "leaf"),
        ),
    )
    fun complexInput(): LinkedHashMap<String, Any?> = linkedMapOf(
        CX.name to "widget",
        CX.priority to CX.high,
        CX.createdOn to "2021-06-01T10:00:00Z",
        CX.score to 3.5,
        CX.active to true,
        CX.aliases to listOf("w1", "w2"),
        CX.address to address(),
        CX.tree to tree(),
    )
    fun validQuery(): LinkedHashMap<String, Any?> = linkedMapOf(
        CX.input to complexInput(),
        CX.mode to CX.lenient,
        CX.sinceDate to "2020-01-01",
    )

    fun putStatus(cxtName: String, body: Map<String, Any?>): Int =
        client(cxtName).sendEditRequest("/schema/complex", null, body, isPut = true).rptStatusCode

    "PUT /schema/complex validates a deep, recursive input and expands the parent chain into items" {
        val resp = client("complexOk").sendJsonPutRequest("/schema/complex", validQuery())
        val list = items(resp)
        // The recursive `parent` chain (root -> mid -> leaf) proves the nested/recursive input validated and
        // is navigable; one result per node, deepest last.
        list.map { it[CX.name] } shouldBe listOf("root", "mid", "leaf")
        list.map { (it[CX.depth] as Number).toInt() } shouldBe listOf(0, 1, 2)
        list.first()[CX.hasLocation] shouldBe true
        list.first()[CX.priority] shouldBe CX.high
        list.first()[CX.mode] shouldBe CX.lenient
    }

    "PUT /schema/complex coerces string-encoded scalars anywhere in the input" {
        val q = validQuery()
        val input = sub(q, CX.input)
        input[CX.score] = "3.5" // number from a string
        input[CX.active] = "true" // boolean from a string
        sub(sub(input, CX.address), CX.location)[CX.lat] = "40.7" // number two refs deep, from a string
        // Coercion succeeds, so the request is processed (an item list, not a 400-error envelope).
        items(client("complexCoerce").sendJsonPutRequest("/schema/complex", q)).size shouldBe 3
    }

    "PUT /schema/complex truncates the expanded items by limit" {
        val q = validQuery()
        q[EP.limit] = 2
        val resp = client("complexLimit").sendJsonPutRequest("/schema/complex", q)
        items(resp).size shouldBe 2
        (resp[EP.numItems] as Number).toInt() shouldBe 2
    }

    "a missing required top-level field fails validation" {
        val q = validQuery()
        sub(q, CX.input).remove(CX.name)
        putStatus("complexNoName", q) shouldBe 400
    }

    "an invalid option value fails validation" {
        val q = validQuery()
        sub(q, CX.input)[CX.priority] = "urgent" // not one of low/medium/high
        putStatus("complexBadOption", q) shouldBe 400
    }

    "an unparseable date fails validation" {
        val q = validQuery()
        sub(q, CX.input)[CX.createdOn] = "not-a-timestamp"
        putStatus("complexBadDate", q) shouldBe 400
    }

    $$"a missing required field one $ref deep fails validation" {
        val q = validQuery()
        sub(sub(q, CX.input), CX.address).remove(CX.street) // Address.street is required
        putStatus("complexDeep1", q) shouldBe 400
    }

    $$"a missing required field two $refs deep fails validation" {
        val q = validQuery()
        sub(sub(sub(q, CX.input), CX.address), CX.location).remove(CX.lat) // GeoPoint.lat is required
        putStatus("complexDeep2", q) shouldBe 400
    }

    "a missing required field in the recursive parent chain fails validation" {
        val q = validQuery()
        sub(sub(sub(q, CX.input), CX.tree), CX.parent).remove(CX.label) // TreeNode.label is required, recursively
        putStatus("complexRecursive", q) shouldBe 400
    }

    $$"the /schema/endpoint lookup keeps the input $refs intact and closes over the recursive $defs" {
        // Use the single-endpoint lookup (not the full listing) to fetch just this endpoint's definition.
        val resp = client("complexCatalog")
            .sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "PUT", EI.path to "/schema/complex"))
        val results = resp[EP.results]!!.toJsonMap()
        val eps = (results[SS.endpoints] as List<*>).map { it!!.toJsonMap() }
        eps.size shouldBe 1 // the lookup returns exactly the one requested endpoint
        val complex = eps.single()
        complex[EI.path] shouldBe "/schema/complex"
        complex[EI.method] shouldBe "PUT"

        // The flattened input keeps `input` as a $ref (client resolves it), plus mode/sinceDate and the
        // appended limit.
        val inputProps = complex[EI.inputSchema]!!.toJsonMap()[SCH.properties]!!.toJsonMap()
        inputProps.keys shouldContainAll listOf(CX.input, CX.mode, CX.sinceDate, EP.limit)
        inputProps[CX.input]!!.toJsonMap()[SCH.dRef] shouldBe typeRefPath("ComplexInput", "schema")

        // The shared $defs closes over the whole reference graph -- including the self-referential TreeNode,
        // returned once (the cyclic walk terminated).
        val defs = results[SCH.dDefs]!!.toJsonMap()
        defs.keys shouldContainAll listOf("schema.ComplexInput", "schema.Address", "schema.GeoPoint", "schema.TreeNode")
    }
})
