package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.typeRefPath
import com.dynamicruntime.common.startup.CX
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonListOfMaps
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

    // assumeEnvAuth so the catalog lookup is unrestricted (issue #489): /fixture/schema/complex is not a
    // published endpoint, so a non-env-authed caller would be handed an empty result instead of its definition.
    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "schemaComplexTest", mapOf(ACFG.assumeEnvAuth to true)).instanceConfig)

    fun items(resp: Map<String, Any?>): List<Map<String, Any?>> =
        resp[EP.items].toJsonListOfMaps()

    // Navigate to a nested, mutable sub-map (the builders below produce LinkedHashMaps all the way down, so a
    // test can corrupt one leaf to prove validation without rebuilding the whole structure).
    @Suppress("UNCHECKED_CAST")
    fun sub(map: Map<String, Any?>, key: String): LinkedHashMap<String, Any?> =
        map[key] as LinkedHashMap<String, Any?>

    // The same for one element of a list-of-objects field -- so a test can corrupt a single element.
    @Suppress("UNCHECKED_CAST")
    fun elem(map: Map<String, Any?>, key: String, index: Int): LinkedHashMap<String, Any?> =
        (map[key] as List<*>)[index] as LinkedHashMap<String, Any?>

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
    // The list-of-objects field: an array whose element type is the referenced `Contact`. The second element
    // carries a further $ref (GeoPoint), so an element is itself a small object graph.
    fun contacts(): MutableList<Any?> = mutableListOf(
        linkedMapOf<String, Any?>(CX.kind to CX.email, CX.handle to "w@example.com"),
        linkedMapOf<String, Any?>(CX.kind to CX.phone, CX.handle to "555-1234", CX.location to geo()),
    )
    fun complexInput(): LinkedHashMap<String, Any?> = linkedMapOf(
        CX.name to "widget",
        CX.priority to CX.high,
        CX.createdOn to "2021-06-01T10:00:00Z",
        CX.score to 3.5,
        CX.active to true,
        CX.aliases to listOf("w1", "w2"),
        CX.contacts to contacts(),
        CX.address to address(),
        CX.tree to tree(),
    )
    fun validQuery(): LinkedHashMap<String, Any?> = linkedMapOf(
        CX.input to complexInput(),
        CX.mode to CX.lenient,
        CX.sinceDate to "2020-01-01",
    )

    fun putStatus(cxtName: String, body: Map<String, Any?>): Int =
        client(cxtName).sendEditRequest("/fixture/schema/complex", null, body, HttpMethod.PUT).rptStatusCode

    "PUT /schema/complex validates a deep, recursive input and expands the parent chain into items" {
        val resp = client("complexOk").sendJsonPutRequest("/fixture/schema/complex", validQuery())
        val list = items(resp)
        // The recursive `parent` chain (root -> mid -> leaf) proves the nested/recursive input validated and
        // is navigable; one result per node, deepest last.
        list.map { it[CX.name] } shouldBe listOf("root", "mid", "leaf")
        list.map { (it[CX.depth] as Number).toInt() } shouldBe listOf(0, 1, 2)
        list.first()[CX.hasLocation] shouldBe true
        list.first()[CX.priority] shouldBe CX.high
        list.first()[CX.mode] shouldBe CX.lenient
    }

    "the limit trims the page while numAvailable reports the whole set (issue #499)" {
        // This handler returns the full expanded chain (root, mid, leaf) as a plain list; the executor trims it
        // to `limit` and -- since a list endpoint with a limit now reports numAvailable by default -- fills the
        // total from the untrimmed size, with no change to the handler.
        val q = validQuery().apply { put(EP.limit, 2) }
        val resp = client("complexLimit").sendJsonPutRequest("/fixture/schema/complex", q)
        items(resp).map { it[CX.name] } shouldBe listOf("root", "mid")
        (resp[EP.numItems] as Number).toInt() shouldBe 2
        (resp[EP.numAvailable] as Number).toInt() shouldBe 3
    }

    "PUT /schema/complex coerces string-encoded scalars anywhere in the input" {
        val q = validQuery()
        val input = sub(q, CX.input)
        input[CX.score] = "3.5" // number from a string
        input[CX.active] = "true" // boolean from a string
        sub(sub(input, CX.address), CX.location)[CX.lat] = "40.7" // number two refs deep, from a string
        // Coercion succeeds, so the request is processed (an item list, not a 400-error envelope).
        items(client("complexCoerce").sendJsonPutRequest("/fixture/schema/complex", q)).size shouldBe 3
    }

    "a list of objects is validated element-wise and arrives intact" {
        val list = items(client("complexContacts").sendJsonPutRequest("/fixture/schema/complex", validQuery()))
        // Both elements survived, and a field from *inside* the first element came back -- so the array was
        // validated and carried through, not merely tolerated.
        list.first()[CX.contactCount] shouldBe 2
        list.first()[CX.primaryContact] shouldBe "w@example.com"
    }

    "a missing required field inside a list element fails validation" {
        val q = validQuery()
        elem(sub(q, CX.input), CX.contacts, 1).remove(CX.handle) // Contact.handle is required
        putStatus("complexElemMissing", q) shouldBe 400
    }

    "an invalid option inside a list element fails validation" {
        val q = validQuery()
        elem(sub(q, CX.input), CX.contacts, 0)[CX.kind] = "carrier-pigeon" // not email/phone
        putStatus("complexElemOption", q) shouldBe 400
    }

    $$"coercion reaches through a list element into its own $ref" {
        val q = validQuery()
        // contacts[1].location is a GeoPoint; a string latitude two levels inside an array element still coerces.
        sub(elem(sub(q, CX.input), CX.contacts, 1), CX.location)[CX.lat] = "40.7"
        items(client("complexElemCoerce").sendJsonPutRequest("/fixture/schema/complex", q)).size shouldBe 3
    }

    $$"a missing required field inside a list element's own $ref fails validation" {
        val q = validQuery()
        sub(elem(sub(q, CX.input), CX.contacts, 1), CX.location).remove(CX.lat) // GeoPoint.lat is required
        putStatus("complexElemDeep", q) shouldBe 400
    }

    // The free-form map (issue #251). Worth asserting through the *endpoint*, not just the validator: the
    // parent type is closed, so the interesting question is whether coercion prunes undeclared keys on the way
    // down into a property that is deliberately open. It must not -- an open map whose keys are dropped in
    // transit is indistinguishable from one nobody filled in.
    "an open map property carries undeclared keys through validation" {
        val q = validQuery()
        sub(q, CX.input)[CX.extras] = linkedMapOf<String, Any?>(
            "channel" to "excel",
            "nested" to linkedMapOf<String, Any?>("fileRef" to "///x.xlsx"),
        )
        val list = items(client("complexExtras").sendJsonPutRequest("/fixture/schema/complex", q))
        // The count is what makes this test able to fail: a request whose map was silently emptied in transit
        // would still be a valid request returning three items.
        list.first()[CX.extraKeys] shouldBe 2
    }

    "an absent open map property is not invented" {
        val list = items(client("complexNoExtras").sendJsonPutRequest("/fixture/schema/complex", validQuery()))
        list.first()[CX.extraKeys] shouldBe 0
    }

    "a non-object value for an open map property fails validation" {
        val q = validQuery()
        sub(q, CX.input)[CX.extras] = "not a map" // what the form emits when the typed JSON will not parse
        putStatus("complexExtrasBad", q) shouldBe 400
    }

    "PUT /schema/complex truncates the expanded items by limit" {
        val q = validQuery()
        q[EP.limit] = 2
        val resp = client("complexLimit").sendJsonPutRequest("/fixture/schema/complex", q)
        items(resp).size shouldBe 2
        (resp[EP.numItems] as Number).toInt() shouldBe 2
    }

    "a missing required top-level field fails validation" {
        val q = validQuery()
        sub(q, CX.input).remove(CX.name)
        putStatus("complexNoName", q) shouldBe 400
    }

    "an empty or null value for a required field is treated as missing, not as a value (issue #187)" {
        // Before emptyIsAbsent, "" was simply a valid string and satisfied `required` -- a field the user had
        // cleared was indistinguishable from one they had filled.
        val blank = validQuery()
        sub(blank, CX.input)[CX.name] = ""
        putStatus("complexBlankName", blank) shouldBe 400

        // And an explicit null, which used to fail as the wrong type, now reads the same way.
        val nulled = validQuery()
        sub(nulled, CX.input)[CX.name] = null
        putStatus("complexNullName", nulled) shouldBe 400
    }

    "an empty optional field is dropped rather than failing its type check (issue #187)" {
        val q = validQuery()
        // A blank number and a blank date would each have been a badValue failure; now they are simply absent.
        sub(q, CX.input)[CX.score] = ""
        sub(q, CX.input)[CX.createdOn] = ""
        // Both are required, so they now report as missing -- one failure kind, not a coercion error.
        putStatus("complexBlankScalars", q) shouldBe 400

        // The optional ones just disappear, leaving a valid request.
        val ok = validQuery()
        sub(ok, CX.input)[CX.active] = ""
        sub(sub(ok, CX.input), CX.address)[CX.zip] = ""
        items(client("complexBlankOptional").sendJsonPutRequest("/fixture/schema/complex", ok)).size shouldBe 3
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
            .sendJsonGetRequest("/schema/endpoint", mapOf(EI.method to "PUT", EI.path to "/fixture/schema/complex"))
        val results = resp[EP.results]!!.toJsonMap()
        val eps = results[EI.endpoints].toJsonListOfMaps()
        eps.size shouldBe 1 // the lookup returns exactly the one requested endpoint
        val complex = eps.single()
        complex[EI.path] shouldBe "/fixture/schema/complex"
        complex[EI.method] shouldBe "PUT"

        // The flattened input keeps `input` as a $ref (client resolves it), plus mode/sinceDate and the
        // appended limit.
        val inputProps = complex[EI.inputSchema]!!.toJsonMap()[SCH.properties]!!.toJsonMap()
        inputProps.keys shouldContainAll listOf(CX.input, CX.mode, CX.sinceDate, EP.limit)
        inputProps[CX.input]!!.toJsonMap()[SCH.dRef] shouldBe typeRefPath("ComplexInput", "schema")

        // The shared $defs closes over the whole reference graph -- including the self-referential TreeNode,
        // returned once (the cyclic walk terminated), and Contact, which is only ever reached *through an
        // array's items*.
        val defs = results[SCH.dDefs]!!.toJsonMap()
        defs.keys shouldContainAll
            listOf("schema.ComplexInput", "schema.Address", "schema.GeoPoint", "schema.TreeNode", "schema.Contact")

        // And the array field keeps its element `$ref` intact, which is what tells a client (the frontend form
        // engine included) that this is a list of objects rather than a list of scalars.
        val contactsProp = defs["schema.ComplexInput"]!!.toJsonMap()[SCH.properties]!!.toJsonMap()[CX.contacts]!!.toJsonMap()
        contactsProp[SCH.type] shouldBe SCT.array
        contactsProp[SCH.items]!!.toJsonMap()[SCH.dRef] shouldBe typeRefPath("Contact", "schema")
    }
})
