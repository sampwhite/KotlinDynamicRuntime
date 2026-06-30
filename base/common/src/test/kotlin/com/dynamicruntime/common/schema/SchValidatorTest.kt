package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class SchValidatorTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    // Build a schema with the DSL, then parse it into resolved types.
    fun personTypes(): Map<String, SchType> = parseSchemaTypes(
        schemaDefs(cxt, "core") {
            type("Count") { type = SCT.integer; description = "A count" }
            type("Person") {
                type = SCT.kObject
                property("name", "The name", required = true) { type = SCT.string }
                property("count", "How many") { ref("Count") }            // $ref -> core.Count
                property("tags", "Tags") {
                    type = SCT.array
                    items { type = SCT.string }
                }
            }
        },
    )

    "parsed types and bound refs are declared attributes (no map lookups)" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        person.jsonType shouldBe SCT.kObject
        person.required shouldBe setOf("name")
        // The $ref field is bound to the resolved Count type.
        val count = person.properties["count"].shouldNotBeNull()
        count.refName shouldBe "core.Count"
        count.valueType.jsonType shouldBe SCT.integer
    }

    "valid data produces no failures" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        validate(person, mapOf("name" to "Bob", "count" to 3, "tags" to listOf("a", "b"))).shouldBeEmpty()
    }

    "collects every failure with its path and code" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        val failures = validate(
            person,
            mapOf(
                // "name" omitted -> missingRequired at "name"
                "count" to "not-an-int", // ref Count is integer -> wrongType at "count"
                "tags" to listOf("ok", 5), // element 1 not a string -> wrongType at "tags[1]"
            ),
        )
        failures.map { it.path to it.code } shouldContainExactlyInAnyOrder listOf(
            "name" to SchFailCode.missingRequired,
            "count" to SchFailCode.wrongType,
            "tags[1]" to SchFailCode.wrongType,
        )
    }

    $$"a $ref to an unknown type throws KdrException" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "core") {
                    type("Person") {
                        type = SCT.kObject
                        property("count", "How many") { ref("Missing") }
                    }
                },
            )
        }
    }

    "refs resolve against provided existing types" {
        val countTypes = parseSchemaTypes(
            schemaDefs(cxt, "core") { type("Count") { type = SCT.integer; description = "A count" } },
        )
        val personTypes = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Person") {
                    type = SCT.kObject
                    property("count", "How many") { ref("Count") }
                }
            },
            existingTypes = countTypes,
        )
        val person = personTypes["core.Person"].shouldNotBeNull()
        validate(person, mapOf("count" to 7)).shouldBeEmpty()
        validate(person, mapOf("count" to "x")).map { it.code } shouldBe listOf(SchFailCode.wrongType)
    }

    "allowCoerce: numeric defaults to coercible, string defaults strict" {
        val rec = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Rec") {
                    type = SCT.kObject
                    property("n", "a number") { type = SCT.integer }                 // numeric -> coercible by default
                    property("s", "a string") { type = SCT.string }                  // string -> strict by default
                    property("s2", "coercible string") { type = SCT.string; allowCoerce = true }
                    property("nums", "numbers from csv") {
                        type = SCT.array
                        items { type = SCT.integer }
                        allowCoerce = true
                    }
                }
            },
        )["core.Rec"].shouldNotBeNull()

        // Coercions succeed: "5" -> int, csv string -> int list, int -> string for s2.
        validate(rec, mapOf("n" to "5", "s" to "ok", "s2" to "ok", "nums" to "1, 2, 3")).shouldBeEmpty()
        validate(rec, mapOf("n" to 5, "s" to "ok", "s2" to 7, "nums" to listOf(1, 2))).shouldBeEmpty()

        // Strict string fails for a non-string; bad numeric/csv coercions fail.
        validate(rec, mapOf("n" to 1, "s" to 7, "s2" to "ok", "nums" to listOf(1)))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("s" to SchFailCode.wrongType)
        validate(rec, mapOf("n" to "abc", "s" to "ok", "s2" to "ok", "nums" to "1, x"))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder
            listOf("n" to SchFailCode.wrongType, "nums" to SchFailCode.wrongType)
    }

    "options: an invalid choice fails with the full options list; label defaults to value" {
        val rec = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Rec") {
                    type = SCT.kObject
                    property("dept", "Department") {
                        option("sec", "Security (Navy Blue)")
                        option("ops", "Operations (Dark Gray)")
                        option("mgt") // label defaults to value
                    }
                }
            },
        )["core.Rec"].shouldNotBeNull()

        validate(rec, mapOf("dept" to "ops")).shouldBeEmpty()
        validate(rec, mapOf("dept" to "mgt")).shouldBeEmpty() // label-defaulted option still valid

        val failures = validate(rec, mapOf("dept" to "xyz"))
        failures shouldHaveSize 1
        failures[0].path shouldBe "dept"
        failures[0].code shouldBe SchFailCode.invalidOption
        failures[0].options shouldBe listOf(
            SchOption("sec", "Security (Navy Blue)"),
            SchOption("ops", "Operations (Dark Gray)"),
            SchOption("mgt", "mgt"), // label defaulted to value
        )
    }
})
