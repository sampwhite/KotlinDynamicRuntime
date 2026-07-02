package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.parseDate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant

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
                "count" to "not-an-int", // Count is coercible integer, string inspected -> badValue at "count"
                "tags" to listOf("ok", 5), // element 1 not a string, strict -> wrongType at "tags[1]"
            ),
        )
        failures.map { it.path to it.code } shouldContainExactlyInAnyOrder listOf(
            "name" to SchFailCode.missingRequired,
            "count" to SchFailCode.badValue,
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
        validate(person, mapOf("count" to "x")).map { it.code } shouldBe listOf(SchFailCode.badValue)
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

        // Strict string fails for a non-string (plain type check -> wrongType).
        validate(rec, mapOf("n" to 1, "s" to 7, "s2" to "ok", "nums" to listOf(1)))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("s" to SchFailCode.wrongType)
        // Inspected-content coercion failures are badValue; a bad csv element is reported element-wise.
        validate(rec, mapOf("n" to "abc", "s" to "ok", "s2" to "ok", "nums" to "1, x"))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder
            listOf("n" to SchFailCode.badValue, "nums[1]" to SchFailCode.badValue)
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

    "default: a required property with a default does not fail when missing" {
        val rec = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "Name", required = true) { type = SCT.string }
                    property("active", "Active", required = true) { type = SCT.boolean; default = true }
                }
            },
        )["core.Rec"].shouldNotBeNull()

        // "active" is missing but has a default -> no failure; "name" present -> ok.
        validate(rec, mapOf("name" to "Bob")).shouldBeEmpty()
        // Both missing -> only "name" fails (active is covered by its default).
        validate(rec, emptyMap<String, Any?>())
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("name" to SchFailCode.missingRequired)
    }

    "coerceAndValidate injects defaults and coerces, leaving the input untouched" {
        val rec = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Rec") {
                    type = SCT.kObject
                    property("count", "Count") { type = SCT.integer }                       // numeric -> coercible
                    property("active", "Active", required = true) { type = SCT.boolean; default = true }
                    property("tags", "Tags") { type = SCT.array; items { type = SCT.string }; allowCoerce = true }
                }
            },
        )["core.Rec"].shouldNotBeNull()

        val input = mapOf("count" to "42", "tags" to "a, b, c") // "active" omitted
        val result = coerceAndValidate(rec, input)

        result.failures.shouldBeEmpty()
        result.value shouldBe mapOf("count" to 42L, "tags" to listOf("a", "b", "c"), "active" to true)
        // The original input is not mutated.
        input shouldBe mapOf("count" to "42", "tags" to "a, b, c")
    }

    // --- date coercion (issue #10) ------------------------------------------

    fun dateRec(): SchType = parseSchemaTypes(
        schemaDefs(cxt, "core") {
            type("Rec") {
                type = SCT.kObject
                property("birth", "Birth day") { dayOnlyDate() }                 // allowCoerce defaults true
                property("created", "Created at") { dateTime() }
                property("raw", "Kept as string") { dayOnlyDate(); allowCoerce = false }
            }
        },
    )["core.Rec"]!!

    "date format validates parseability regardless of allowCoerce" {
        val rec = dateRec()
        validate(rec, mapOf("birth" to "2021-06-01", "created" to "2021-06-01T08:00:00.000Z", "raw" to "2021-06-01"))
            .shouldBeEmpty()
        validate(rec, mapOf("birth" to "not-a-date", "raw" to "also-bad"))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder
            listOf("birth" to SchFailCode.badValue, "raw" to SchFailCode.badValue)
        // The badValue carries the underlying parse exception as its cause.
        validate(rec, mapOf("birth" to "not-a-date")).single().cause.shouldNotBeNull()
    }

    "date coercion replaces the string with an Instant only when allowCoerce is on" {
        val rec = dateRec()
        val result = coerceAndValidate(
            rec,
            mapOf("birth" to "2021-06-01", "created" to "2021-06-01T08:00:00.000Z", "raw" to "2021-06-01"),
        )
        result.failures.shouldBeEmpty()
        val out = result.value as Map<*, *>
        out["birth"].shouldBeInstanceOf<Instant>()
        out["created"].shouldBeInstanceOf<Instant>()
        out["raw"] shouldBe "2021-06-01" // allowCoerce off -> kept as the original string
        // The parsed Instant round-trips to the same instant DateUtil would produce.
        out["created"] shouldBe "2021-06-01T08:00:00.000Z".parseDate()
    }

    "a non-string, non-date value for a date field is a plain wrongType" {
        validate(dateRec(), mapOf("birth" to 12345))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("birth" to SchFailCode.wrongType)
    }

    // --- boolean coercion (issue #10) ---------------------------------------

    fun boolRec(): SchType = parseSchemaTypes(
        schemaDefs(cxt, "core") {
            type("Rec") {
                type = SCT.kObject
                property("active", "Active") { type = SCT.boolean; allowCoerce = true }
                property("strict", "Strict bool") { type = SCT.boolean } // allowCoerce defaults false
            }
        },
    )["core.Rec"]!!

    "boolean coercion reads loose spellings when allowCoerce is on" {
        val rec = boolRec()
        coerceAndValidate(rec, mapOf("active" to "yes")).let {
            it.failures.shouldBeEmpty()
            (it.value as Map<*, *>)["active"] shouldBe true
        }
        coerceAndValidate(rec, mapOf("active" to "0")).let {
            (it.value as Map<*, *>)["active"] shouldBe false
        }
    }

    "boolean coercion treats a blank string as an absent value, not a failure" {
        coerceAndValidate(boolRec(), mapOf("active" to "   ")).let {
            it.failures.shouldBeEmpty()
            (it.value as Map<*, *>)["active"] shouldBe null
        }
    }

    "an unrecognized non-blank boolean string is a badValue" {
        validate(boolRec(), mapOf("active" to "purple"))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("active" to SchFailCode.badValue)
    }

    "without allowCoerce a boolean string is a plain wrongType" {
        validate(boolRec(), mapOf("strict" to "true"))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("strict" to SchFailCode.wrongType)
    }

    // --- JSON coercion for lists and maps (issue #10) -----------------------

    "a bracketed string coerces via the JSON parser and validates element-wise" {
        val rec = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Rec") {
                    type = SCT.kObject
                    property("nums", "Numbers") { type = SCT.array; items { type = SCT.integer }; allowCoerce = true }
                }
            },
        )["core.Rec"]!!

        coerceAndValidate(rec, mapOf("nums" to "[1, 2, 3]")).let {
            it.failures.shouldBeEmpty()
            (it.value as Map<*, *>)["nums"] shouldBe listOf(1L, 2L, 3L)
        }
        // CSV fallback (no leading bracket).
        coerceAndValidate(rec, mapOf("nums" to "4, 5")).let {
            (it.value as Map<*, *>)["nums"] shouldBe listOf(4L, 5L)
        }
        // A bad element inside bracketed JSON is reported element-wise.
        validate(rec, mapOf("nums" to """[1, "x"]"""))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("nums[1]" to SchFailCode.badValue)
    }

    "a string coerces to a map and the object schema is applied to the parsed value" {
        val rec = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Rec") {
                    type = SCT.kObject
                    property("addr", "Address") {
                        type = SCT.kObject
                        allowCoerce = true
                        property("zip", "Zip") { type = SCT.integer }
                    }
                }
            },
        )["core.Rec"]!!

        coerceAndValidate(rec, mapOf("addr" to """{"zip": "90210"}""")).let {
            it.failures.shouldBeEmpty()
            (it.value as Map<*, *>)["addr"] shouldBe mapOf("zip" to 90210L) // nested string coerced too
        }
        // Malformed JSON is a badValue at the field, carrying the parser exception (with its position).
        val failure = validate(rec, mapOf("addr" to "{not json")).single()
        failure.path shouldBe "addr"
        failure.code shouldBe SchFailCode.badValue
        val cause = failure.cause.shouldNotBeNull()
        cause.extraData.containsKey("offset") shouldBe true
    }
})
