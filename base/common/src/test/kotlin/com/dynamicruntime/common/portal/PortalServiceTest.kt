package com.dynamicruntime.common.portal

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/** The fields feed's entry for [collationKey], indexed by field name. */
private fun fieldsByName(catalog: Map<String, Any?>, collationKey: String): Map<String, Map<String, Any?>> =
    (catalog[collationKey] as List<*>).associate { val f = it!!.toJsonMap(); f[PTL.name] as String to f }

class PortalServiceTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    // A module whose input type has a required string, an options field, and a plain integer, exercised
    // through a general endpoint (top-level $ref input) and a list endpoint (request nested under `request`).
    val module = schemaModule(cxt, "api") {
        type("Query") {
            type = SCT.kObject
            property("namePrefix", "Filter by name prefix", required = true)
            property("status", "Ticket status") { option("open"); option("closed", "Closed out") }
            property("age", "Age in years") { type = SCT.integer }
        }
        type("Out") { type = SCT.kObject; property("name", "The name") }
        generalEndpoint("/tickets/rename", "Rename a ticket", HttpMethod.POST, outputRef = "Out", inputRef = "Query") { _, _ ->
            emptyMap<String, Any?>()
        }
        listEndpoint("/tickets", "List tickets", outputRef = "Out", inputRef = "Query") { _, _ -> emptyList<Any?>() }
    }

    // Compile the module and publish a schema store on the context, keyed by collationKey as the runtime does.
    val store = KdrSchemaStore(
        types = parseSchemaTypes(module.defs),
        endpoints = module.endpoints.associateBy { it.collationKey },
    )
    cxt.instanceConfig.put(KdrSchemaStore.key, store)

    "buildFieldsCatalog keys each endpoint's fields by its collationKey" {
        val catalog = PortalService.buildFieldsCatalog(cxt)
        catalog.keys shouldContainExactlyInAnyOrder listOf("/tickets/rename:POST", "/tickets:GET")
    }

    "a general endpoint's referenced input type resolves to its fields, with required flags and options" {
        val fields = fieldsByName(PortalService.buildFieldsCatalog(cxt), "/tickets/rename:POST")
        fields.keys.toList() shouldContainExactly listOf("namePrefix", "status", "age")

        fields["namePrefix"]!![PTL.type] shouldBe SCT.string
        fields["namePrefix"]!![PTL.required] shouldBe true
        fields["namePrefix"]!![PTL.description] shouldBe "Filter by name prefix"

        fields["status"]!![PTL.required] shouldBe false
        (fields["status"]!![PTL.options] as List<*>).map { it!!.toJsonMap()[PTL.value] } shouldContainExactly listOf("open", "closed")
        (fields["status"]!![PTL.options] as List<*>).map { it!!.toJsonMap()[PTL.label] } shouldContainExactly listOf("open", "Closed out")

        fields["age"]!![PTL.type] shouldBe SCT.integer
    }

    "a list endpoint exposes the nested request object plus the limit sibling" {
        val fields = fieldsByName(PortalService.buildFieldsCatalog(cxt), "/tickets:GET")
        fields.keys.toList() shouldContainExactly listOf("request", "limit")

        // `request` is an object carrying the caller's query fields as nested descriptors.
        fields["request"]!![PTL.type] shouldBe SCT.kObject
        val nested = (fields["request"]!![PTL.fields] as List<*>).map { it!!.toJsonMap()[PTL.name] }
        nested shouldContainExactly listOf("namePrefix", "status", "age")

        // `limit` is an integer with the framework default injected.
        fields["limit"]!![PTL.type] shouldBe SCT.integer
        fields["limit"]!![PTL.default] shouldBe 100
    }
})
