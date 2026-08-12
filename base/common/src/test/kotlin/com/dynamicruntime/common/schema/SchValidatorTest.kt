package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toStartOfDay
import kotlinx.datetime.LocalDate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
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

    // Authored options are not always authored as strings -- a code list lifted from an enum or a spreadsheet
    // carries numbers. Before issue #267 the parser read each `value` with the old string-only `toOptStr` and
    // dropped every entry it could not read, leaving an option list that was *empty rather than absent*: the
    // field then rejected every value on earth, itself included, with "is not a valid option".
    "options: a numeric option value is read rather than dropped" {
        val rec = parseSchemaTypes(
            mapOf(
                "core.Rec" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "level" to mapOf(
                            SCH.type to SCT.string,
                            SCH.options to listOf(
                                mapOf(SCH.value to 1, SCH.label to "One"),
                                mapOf(SCH.value to 2), // label defaults to the (now readable) value
                            ),
                        ),
                    ),
                ),
            ),
        )["core.Rec"].shouldNotBeNull()

        rec.properties.getValue("level").valueType.options shouldBe
            listOf(SchOption("1", "One"), SchOption("2", "2"))
        validate(rec, mapOf("level" to "2")).shouldBeEmpty()

        val failures = validate(rec, mapOf("level" to "3"))
        failures shouldHaveSize 1
        failures[0].code shouldBe SchFailCode.invalidOption
        failures[0].options shouldBe listOf(SchOption("1", "One"), SchOption("2", "2"))
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

    "date coercion follows the declared format, and only when allowCoerce is on (issue #189)" {
        val rec = dateRec()
        val result = coerceAndValidate(
            rec,
            mapOf("birth" to "2021-06-01", "created" to "2021-06-01T08:00:00.000Z", "raw" to "2021-06-01"),
        )
        result.failures.shouldBeEmpty()
        val out = result.value as Map<*, *>
        // A day-only field yields a day; a date-time field yields a moment. The schema already said which.
        out["birth"].shouldBeInstanceOf<LocalDate>()
        out["created"].shouldBeInstanceOf<Instant>()
        out["raw"] shouldBe "2021-06-01" // allowCoerce off -> kept as the original string
        // The parsed Instant round-trips to the same instant DateUtil would produce.
        out["created"] shouldBe "2021-06-01T08:00:00.000Z".parseDate()
    }

    // The regression this issue is really about: a day used to be pinned to midnight in the server zone and
    // then written back out in UTC, so it returned as a timestamp -- and from a zone east of UTC, as the wrong
    // day. Serializing the coerced value is what exercises that, since the old bug only appeared on the way out.
    "a day-only date round-trips as the day it is, not as a timestamp" {
        val result = coerceAndValidate(dateRec(), mapOf("birth" to "2021-06-01"))
        result.failures.shouldBeEmpty()
        (result.value as Map<*, *>)["birth"].fmt() shouldBe "2021-06-01"
        // And the whole coerced map serializes with the day intact.
        result.value.toJsonStr(compact = true) shouldBe """{"birth":"2021-06-01"}"""
    }

    "a value of the other date shape is reshaped only where allowCoerce permits it" {
        val rec = dateRec()
        // Lenient (allowCoerce defaults on for date formats): a full timestamp handed to a day-only field is
        // narrowed to its day rather than rejected...
        val narrowed = coerceAndValidate(rec, mapOf("birth" to "2021-06-01T08:00:00.000Z"))
        narrowed.failures.shouldBeEmpty()
        (narrowed.value as Map<*, *>)["birth"].fmt() shouldBe "2021-06-01"
        // ...and an already-parsed day handed to a date-time field is given its start of day.
        val widened = coerceAndValidate(rec, mapOf("created" to LocalDate.parse("2021-06-01")))
        widened.failures.shouldBeEmpty()
        (widened.value as Map<*, *>)["created"] shouldBe LocalDate.parse("2021-06-01").toStartOfDay()
    }

    // Reshaping discards information -- a timestamp becoming a day loses the time of day -- so it is a
    // coercion, and `raw` (allowCoerce off) is entitled to refuse it and take only what it declared.
    "a strict day-only field refuses a timestamp instead of silently truncating it" {
        val rec = dateRec()
        validate(rec, mapOf("raw" to "2021-06-01T08:00:00.000Z"))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("raw" to SchFailCode.badValue)
        // An already-parsed Instant is refused the same way and as a plain type mismatch.
        validate(rec, mapOf("raw" to "2021-06-01T08:00:00.000Z".parseDate()))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("raw" to SchFailCode.wrongType)
        // The day itself is still perfectly acceptable and still kept as its original string.
        val ok = coerceAndValidate(rec, mapOf("raw" to "2021-06-01"))
        ok.failures.shouldBeEmpty()
        (ok.value as Map<*, *>)["raw"] shouldBe "2021-06-01"
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

    // --- additionalProperties + off-contract keys (issue #18) ----------------

    "additionalProperties defaults false for a type that declares properties" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        person.additionalProperties shouldBe false
        validate(person, mapOf("name" to "Bob", "extra" to 1))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf("extra" to SchFailCode.additionalProperty)
    }

    "additionalProperties defaults true for a property-less generic map, so any key is kept" {
        val generic = parseSchemaTypes(
            schemaDefs(cxt, "core") { type("Bag") { type = SCT.kObject } },
        )["core.Bag"].shouldNotBeNull()
        generic.additionalProperties shouldBe true
        validate(generic, mapOf("anything" to 1, "else" to "x")).shouldBeEmpty()
    }

    "an explicit additionalProperties = true allows undeclared keys on a type with properties" {
        val rec = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Rec") {
                    type = SCT.kObject
                    additionalProperties = true
                    property("name", "Name") { type = SCT.string }
                }
            },
        )["core.Rec"].shouldNotBeNull()
        rec.additionalProperties shouldBe true
        validate(rec, mapOf("name" to "Bob", "extra" to 1)).shouldBeEmpty()
        // Coercion keeps the extra key too.
        coerceAndValidate(rec, mapOf("name" to "Bob", "extra" to 1)).value shouldBe
            mapOf("name" to "Bob", "extra" to 1)
    }

    "an underscore-prefixed key is always allowed and kept, even when additionalProperties is false" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        validate(person, mapOf("name" to "Bob", "_debug" to "x")).shouldBeEmpty()
        coerceAndValidate(person, mapOf("name" to "Bob", "_debug" to "x")).value shouldBe
            mapOf("name" to "Bob", "_debug" to "x")
    }

    $$"a non-reserved $-key ($note) is allowed on validate but dropped on coerce" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        validate(person, mapOf("name" to "Bob", $$"$note" to "a human note")).shouldBeEmpty()
        // Dropped from the coerced output.
        coerceAndValidate(person, mapOf("name" to "Bob", $$"$note" to "a human note")).value shouldBe
            mapOf("name" to "Bob")
    }

    // An editor has to keep showing the offending key: dropping it rewrites the text under the person who
    // typed it, leaving a failure about something no longer on screen (issue #191).
    "keepAdditionalProperties retains an undeclared key while still reporting it" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        val data = mapOf("name" to "Bob", "bogus" to 1L)

        // The default drops it -- the wire should not carry keys the schema never declared.
        val dropped = coerceAndValidate(person, data)
        dropped.failures.map { it.code } shouldBe listOf(SchFailCode.additionalProperty)
        (dropped.value as Map<*, *>).containsKey("bogus") shouldBe false

        // With the option, the same failure is raised, but the key survives into the output.
        val kept = coerceAndValidate(person, data, SchOpts(keepAdditionalProperties = true))
        kept.failures.map { it.code } shouldBe listOf(SchFailCode.additionalProperty)
        (kept.value as Map<*, *>)["bogus"] shouldBe 1L
    }

    "an option cannot turn a failure into a success" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        // Keeping the key is about the output, never about validity: it is still not allowed here.
        validate(person, mapOf("name" to "Bob", "bogus" to 1L), SchOpts(keepAdditionalProperties = true))
            .map { it.code } shouldBe listOf(SchFailCode.additionalProperty)
    }

    $$"a reserved $-keyword ($ref) is treated as a normal key, so additionalProperties applies" {
        val person = personTypes()["core.Person"].shouldNotBeNull()
        // $ref is reserved, so it is not exempt: on a type with "additionalProperties=false" it is rejected.
        validate(person, mapOf("name" to "Bob", $$"$ref" to "#/x"))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder listOf($$"$ref" to SchFailCode.additionalProperty)
    }

    // --- emptyIsAbsent (issue #187) -------------------------------------------------------------------------

    // Every shape the rule has to distinguish, in one type: scalars (on by default), containers (off by
    // default), the two opt-outs, and a scalar carrying a default.
    fun emptyTypes(): Map<String, SchType> = parseSchemaTypes(
        schemaDefs(cxt, "e") {
            type("Inner") { type = SCT.kObject; property("v", "A value") }
            type("Rec") {
                type = SCT.kObject
                property("name", "A name", required = true)
                property("count", "A count", required = true) { type = SCT.integer }
                property("flag", "A flag", required = true) { type = SCT.boolean }
                property("tags", "Tags", required = true) { type = SCT.array; items { type = SCT.string } }
                property("inner", "An object", required = true) { ref("Inner") }
                property("keepEmpty", "A string whose empty value is meaningful", required = true) {
                    emptyIsAbsent = false
                }
                property("dropEmptyList", "A list that opts IN to the rule", required = true) {
                    type = SCT.array; items { type = SCT.string }; emptyIsAbsent = true
                }
            }
        },
    )

    fun fullRec(): Map<String, Any?> = mapOf(
        "name" to "Bob", "count" to 1, "flag" to true, "tags" to listOf("a"),
        "inner" to mapOf("v" to "x"), "keepEmpty" to "set", "dropEmptyList" to listOf("a"),
    )

    /** The property names reported missing for [data] -- the whole point of the rule is what it does to "required". */
    fun missing(data: Map<String, Any?>): List<String> {
        val rec = emptyTypes()["e.Rec"].shouldNotBeNull()
        return validate(rec, data).filter { it.code == SchFailCode.missingRequired }.map { it.path }
    }

    "a blank or null scalar reads as absent, so it fails its required check" {
        missing(fullRec() + mapOf("name" to "")) shouldBe listOf("name")
        missing(fullRec() + mapOf("name" to "   ")) shouldBe listOf("name") // whitespace-only counts as blank
        missing(fullRec() + mapOf("name" to null)) shouldBe listOf("name")
        missing(fullRec() + mapOf("count" to null)) shouldBe listOf("count")
    }

    "a null no longer fails as the wrong type -- it reads as no value at all" {
        val rec = emptyTypes()["e.Rec"].shouldNotBeNull()
        // Before the rule, null matched no type and coerced to nothing, so this was a wrongType failure.
        validate(rec, fullRec() + mapOf("name" to null)).map { it.code } shouldBe listOf(SchFailCode.missingRequired)
    }

    "empty means zero-length, never zero-valued" {
        // 0 and false are ordinary values; a JS-flavored "falsy" reading would silently eat them.
        missing(fullRec() + mapOf("count" to 0)).shouldBeEmpty()
        missing(fullRec() + mapOf("flag" to false)).shouldBeEmpty()
        validate(emptyTypes()["e.Rec"].shouldNotBeNull(), fullRec() + mapOf("count" to 0, "flag" to false))
            .shouldBeEmpty()
    }

    "containers keep their empty value by default, so [] and {} still satisfy required" {
        // An empty list/map is often meaningful (on an update, "clear this" versus "leave it alone"), so
        // arrays and objects are opt-in rather than default.
        missing(fullRec() + mapOf("tags" to emptyList<String>(), "inner" to emptyMap<String, Any?>())).shouldBeEmpty()
    }

    "a container that opts in, and a string that opts out, both honor their declaration" {
        missing(fullRec() + mapOf("dropEmptyList" to emptyList<String>())) shouldBe listOf("dropEmptyList")
        missing(fullRec() + mapOf("keepEmpty" to "")).shouldBeEmpty() // "" is this field's value
    }

    "an absent value is dropped from the coerced output rather than nulled" {
        val rec = emptyTypes()["e.Rec"].shouldNotBeNull()
        val coerced = coerceAndValidate(rec, fullRec() + mapOf("name" to "")).value as Map<*, *>
        // Not present at all: a null would fail the plain type check on any re-validation.
        coerced.containsKey("name") shouldBe false
        coerced["keepEmpty"] shouldBe "set"
    }

    // The mode-consistency guarantee: knowing whether `required` is satisfied now depends on what happened to
    // each value during the property loop, and validate-only mode builds no output map to read that back from.
    // If the two modes ever disagree, a request would validate on one path and fail on the other.
    "validate and coerceAndValidate report the same failures for every empty shape" {
        val rec = emptyTypes()["e.Rec"].shouldNotBeNull()
        val cases = listOf(
            fullRec() + mapOf("name" to ""),
            fullRec() + mapOf("name" to null),
            fullRec() + mapOf("count" to null),
            fullRec() + mapOf("tags" to emptyList<String>()),
            fullRec() + mapOf("dropEmptyList" to emptyList<String>()),
            fullRec() + mapOf("keepEmpty" to ""),
            fullRec() + mapOf("count" to 0, "flag" to false),
        )
        for (data in cases) {
            validate(rec, data).map { it.path to it.code } shouldBe
                coerceAndValidate(rec, data).failures.map { it.path to it.code }
        }
    }

    "a default still fills a required property whose supplied value was empty" {
        val types = parseSchemaTypes(
            schemaDefs(cxt, "d") {
                type("WithDefault") {
                    type = SCT.kObject
                    property("size", "A size", required = true) { type = SCT.integer; default = 10 }
                }
            },
        )
        val t = types["d.WithDefault"].shouldNotBeNull()
        // "" reads as absent, and an absent required property with a default is supplied, not failed.
        val result = coerceAndValidate(t, mapOf("size" to ""))
        result.failures.shouldBeEmpty()
        (result.value as Map<*, *>)["size"] shouldBe 10
    }

    // --- bounds: the four min/max pairs, sharing two failure codes (issue #203) ---------------------------

    fun boundTypes(): Map<String, SchType> = parseSchemaTypes(
        schemaDefs(cxt, "b") {
            type("Bounded") {
                type = SCT.kObject
                property("score", "A score") { type = SCT.integer; minimum = 1; maximum = 10 }
                property("nick", "A nickname") { type = SCT.string; minLength = 2; maxLength = 5 }
                property("tags", "Tags") { type = SCT.array; items { type = SCT.string }; minItems = 1; maxItems = 2 }
                property("bag", "A bag") { type = SCT.kObject; minProperties = 1; maxProperties = 2 }
            }
        },
    )

    "each of the four pairs reports below its minimum" {
        val t = boundTypes()["b.Bounded"].shouldNotBeNull()
        val failures = validate(
            t,
            mapOf("score" to 0, "nick" to "a", "tags" to listOf<Any?>(), "bag" to mapOf<String, Any?>()),
        )
        failures.map { it.path to it.code } shouldContainExactlyInAnyOrder listOf(
            "score" to SchFailCode.belowMinimum,
            "nick" to SchFailCode.belowMinimum,
            "tags" to SchFailCode.belowMinimum,
            "bag" to SchFailCode.belowMinimum,
        )
        // One code, but wording that suits what each type measures.
        failures.single { it.path == "score" }.message shouldBe "This must be at least 1."
        failures.single { it.path == "nick" }.message shouldBe "This must be at least 2 characters."
        failures.single { it.path == "tags" }.message shouldBe "This must have at least 1 item."
        failures.single { it.path == "bag" }.message shouldBe "This must have at least 1 property."
    }

    "each of the four pairs reports above its maximum" {
        val t = boundTypes()["b.Bounded"].shouldNotBeNull()
        val failures = validate(
            t,
            mapOf(
                "score" to 11, "nick" to "abcdef", "tags" to listOf("a", "b", "c"),
                "bag" to mapOf("a" to 1, "b" to 2, "c" to 3),
            ),
        )
        failures.map { it.code }.toSet() shouldBe setOf(SchFailCode.aboveMaximum)
        failures.single { it.path == "nick" }.message shouldBe "This must be at most 5 characters."
        failures.single { it.path == "bag" }.message shouldBe "This must have at most 2 properties."
    }

    "the bounds are inclusive at both ends" {
        val t = boundTypes()["b.Bounded"].shouldNotBeNull()
        validate(
            t,
            mapOf("score" to 1, "nick" to "ab", "tags" to listOf("a"), "bag" to mapOf("a" to 1)),
        ).shouldBeEmpty()
        validate(
            t,
            mapOf("score" to 10, "nick" to "abcde", "tags" to listOf("a", "b"), "bag" to mapOf("a" to 1, "b" to 2)),
        ).shouldBeEmpty()
    }

    // The bound applies to what the value BECAME. Coercion runs in both modes, so both must agree -- the same
    // discipline the emptyIsAbsent suite holds to.
    "a coerced string is bounds-checked, identically in both modes" {
        val t = boundTypes()["b.Bounded"].shouldNotBeNull()
        val data = mapOf("score" to "99", "nick" to "ab", "tags" to listOf("a"), "bag" to mapOf("a" to 1))
        validate(t, data).map { it.path to it.code } shouldBe listOf("score" to SchFailCode.aboveMaximum)
        coerceAndValidate(t, data).failures.map { it.path to it.code } shouldBe
            listOf("score" to SchFailCode.aboveMaximum)
    }

    // JSON Schema counts characters, not UTF-16 units: three astral characters are three, not six.
    "string length counts code points, not code units" {
        val t = boundTypes()["b.Bounded"].shouldNotBeNull()
        val threeEmoji = "😀😁😂"
        threeEmoji.length shouldBe 6 // what a naive check would have measured
        validate(t, mapOf("nick" to threeEmoji, "score" to 1, "tags" to listOf("a"), "bag" to mapOf("a" to 1)))
            .shouldBeEmpty()
    }

    // A standard validator ignores a keyword that does not apply to the instance type, so we do too rather
    // than rejecting a document it would accept.
    "a bound keyword belonging to another type is ignored" {
        val types = parseSchemaTypes(
            schemaDefs(cxt, "b") {
                type("Odd") {
                    type = SCT.kObject
                    // minimum means nothing on a string; minLength is what a string measures.
                    property("label", "A label") { type = SCT.string; minimum = 50 }
                }
            },
        )
        val t = types["b.Odd"].shouldNotBeNull()
        t.properties["label"]!!.valueType.minBound shouldBe null
        validate(t, mapOf("label" to "x")).shouldBeEmpty()
    }

    "a bound failure takes its message from g-errors like any other" {
        val types = parseSchemaTypes(
            schemaDefs(cxt, "b") {
                type("Custom") {
                    type = SCT.kObject
                    property("age", "An age") {
                        type = SCT.integer
                        minimum = 18
                        errors { belowMinimum("You have to be 18 or over.") }
                    }
                }
            },
        )
        val t = types["b.Custom"].shouldNotBeNull()
        val f = validate(t, mapOf("age" to 17)).single()
        f.code shouldBe SchFailCode.belowMinimum
        f.userMessage shouldBe "You have to be 18 or over."
        f.message shouldBe "This must be at least 18."
    }

    // --- g-errors: the schema's own wording for a failure (issue #202) ------------------------------------

    fun errorCopyTypes(): Map<String, SchType> = parseSchemaTypes(
        schemaDefs(cxt, "e") {
            type("Form") {
                type = SCT.kObject
                // Both a specific message and a default: the specific one wins for its code.
                property("score", "A score", required = true) {
                    type = SCT.integer
                    errors {
                        missingRequired("A score is needed.")
                        default("Scores are whole numbers.")
                    }
                }
                // Only a default: every code falls through to it.
                property("nickname", "A name") {
                    type = SCT.integer
                    errors { default("That does not look right.") }
                }
                // No block at all: the validator's own wording stands.
                property("plain", "Plain") { type = SCT.integer }
            }
        },
    )

    "the code-specific message wins over the field default" {
        val t = errorCopyTypes()["e.Form"].shouldNotBeNull()
        val f = validate(t, mapOf("nickname" to 1, "plain" to 1)).single()
        f.code shouldBe SchFailCode.missingRequired
        f.userMessage shouldBe "A score is needed."
        // Beside, not instead of: the wire wording is still there for a surface that documents the wire.
        f.message shouldBe "Required property 'score' is missing."
    }

    "a field default covers a code the block does not name" {
        val t = errorCopyTypes()["e.Form"].shouldNotBeNull()
        val f = validate(t, mapOf("score" to "x", "plain" to 1)).single { it.path == "score" }
        f.code shouldBe SchFailCode.badValue
        f.userMessage shouldBe "Scores are whole numbers."
    }

    "every code falls through to a lone default" {
        val t = errorCopyTypes()["e.Form"].shouldNotBeNull()
        val f = validate(t, mapOf("score" to 1, "nickname" to "x")).single { it.path == "nickname" }
        f.userMessage shouldBe "That does not look right."
    }

    // The third level of the chain: no schema copy at all leaves the built-in message, and userMessage null
    // rather than a copy of it -- a surface has to be able to tell "the schema said this" from "it did not".
    "a field with no block carries no user message" {
        val t = errorCopyTypes()["e.Form"].shouldNotBeNull()
        val f = validate(t, mapOf("score" to 1, "plain" to "x")).single { it.path == "plain" }
        f.userMessage shouldBe null
        f.message shouldBe "'x' is not a valid integer."
    }

    // A misspelled code is the failure mode worth catching: the schema looks like it says something, and the
    // only symptom would be the built-in wording turning up where custom copy was expected.
    "an unknown error key fails the parse, naming the offender" {
        val e = shouldThrow<KdrException> {
            parseSchemaTypes(
                mapOf(
                    "e.Bad" to mapOf(
                        SCH.type to SCT.kObject,
                        SCH.properties to mapOf(
                            "score" to mapOf(
                                SCH.type to SCT.integer,
                                SCH.errors to mapOf("typeWrong" to "nope"),
                            ),
                        ),
                    ),
                ),
            )
        }
        e.fullMessage() shouldContain "typeWrong"
        e.fullMessage() shouldContain SchFailCode.wrongType.name
    }

    // Reserved for a future markdown-fragment reference, so a document using it early has to degrade rather
    // than fail to load.
    "a non-string error value is ignored rather than rejected" {
        val types = parseSchemaTypes(
            mapOf(
                "e.Obj" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "score" to mapOf(
                            SCH.type to SCT.integer,
                            SCH.errors to mapOf("badValue" to mapOf("fragment" to "score.bad")),
                        ),
                    ),
                ),
            ),
        )
        val t = types["e.Obj"].shouldNotBeNull()
        t.properties["score"]!!.valueType.errorMessages.shouldBeEmpty()
        validate(t, mapOf("score" to "x")).single().userMessage shouldBe null
    }

    // --- const, including the non-string kinds (issue #253) -------------------

    // A regression: `const` was compared by stringifying both sides with `toOptStr`, which yields null for
    // anything that is not a CharSequence -- so two non-string values both became null and compared EQUAL,
    // and a `const` of 42 or of `true` matched absolutely anything. String constants hid it completely,
    // because a discriminator is always a string and nothing else used the keyword.
    "a non-string const actually constrains the value" {
        val types = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Fixed") {
                    type = SCT.kObject
                    property("answer", "The only accepted answer.", required = true) {
                        type = SCT.integer
                        const = 42
                    }
                    property("flag", "The only accepted flag.", required = true) {
                        type = SCT.boolean
                        const = true
                    }
                }
            },
        )
        val fixed = types.getValue("core.Fixed")

        validate(fixed, mapOf("answer" to 42, "flag" to true)).shouldBeEmpty()

        val wrongNumber = validate(fixed, mapOf("answer" to 7, "flag" to true))
        wrongNumber shouldHaveSize 1
        wrongNumber.first().path shouldBe "answer"

        val wrongFlag = validate(fixed, mapOf("answer" to 42, "flag" to false))
        wrongFlag shouldHaveSize 1
        wrongFlag.first().path shouldBe "flag"
    }

    // The tolerance that makes a constant usable on a surface that loses types: a query string carries every
    // value as text, and "42" is the same answer as 42 to the question the constant asks.
    "a const matches a value that arrived as text" {
        val types = parseSchemaTypes(
            schemaDefs(cxt, "core") {
                type("Fixed2") {
                    type = SCT.kObject
                    property("answer", "The only accepted answer.", required = true) {
                        type = SCT.integer
                        const = 42
                    }
                }
            },
        )
        coerceAndValidate(types.getValue("core.Fixed2"), mapOf("answer" to "42")).failures.shouldBeEmpty()
    }
})
