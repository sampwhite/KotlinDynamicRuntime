package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `g-outerWhitespace` (issue #541): leading/trailing whitespace on a string is either stripped (`"trim"`) or
 * refused (`"reject"`). Absent leaves it alone, as before.
 *
 * The two modes are the same rule read two ways, so the cases come in pairs: what `"trim"` cleans, `"reject"`
 * refuses. The load-bearing details are the *ordering* (the cleaned value is what `minLength`/options measure)
 * and the mode split (`"trim"` emits the cleaned value in coerce mode and checks against it in validate-only).
 * "Whitespace" here is the kernel's `<= ' '` test, which -- deliberately -- is not Kotlin's Unicode `trim`.
 */
class SchOuterWhitespaceTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    /** A `name` field carrying [mode], beside an unruled `note`, so the default is asserted next to it. */
    fun recType(mode: String, required: Boolean = true, build: SchTypeBuilder.() -> Unit = {}): SchType =
        parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A name", required = required) { outerWhitespace = mode; build() }
                    property("note", "A note, unruled")
                }
            },
        ).getValue("w.Rec")

    fun trimType() = recType(SOWS.trim)
    fun rejectType() = recType(SOWS.reject)

    fun coercedName(type: SchType, name: String): Any? =
        (coerceAndValidate(type, mapOf("name" to name)).value as Map<*, *>)["name"]

    // --- trim: strips in coerce mode, passes clean in validate-only ------------------------------------------

    "trim strips leading and trailing whitespace in coerce mode" {
        coercedName(trimType(), "  hi  ") shouldBe "hi"
        coercedName(trimType(), "\t\n hi \r\n") shouldBe "hi"
        // Interior whitespace is untouched -- only the edges.
        coercedName(trimType(), "  a b  ") shouldBe "a b"
    }

    "trim passes in validate-only, since the stored value will be clean" {
        validate(trimType(), mapOf("name" to "  hi  ")).shouldBeEmpty()
    }

    "the builder helper trimmed() sets trim mode" {
        val t = recType(SOWS.trim) // via attribute
        val h = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A name", required = true) { trimmed() }
                    property("note", "A note, unruled")
                }
            },
        ).getValue("w.Rec")
        coercedName(t, " x ") shouldBe "x"
        coercedName(h, " x ") shouldBe "x"
    }

    // --- reject: fails a value with edge whitespace, alters nothing -----------------------------------------

    "reject fails a value with leading or trailing whitespace, in both modes" {
        for (bad in listOf(" hi", "hi ", "  hi  ", "\thi", "hi\n")) {
            withClue(bad) {
                validate(rejectType(), mapOf("name" to bad)).map { it.code } shouldContainExactly
                    listOf(SchFailCode.badValue)
                coerceAndValidate(rejectType(), mapOf("name" to bad)).failures.map { it.code } shouldContainExactly
                    listOf(SchFailCode.badValue)
            }
        }
    }

    "reject passes a clean value and leaves it exactly as it arrived" {
        validate(rejectType(), mapOf("name" to "hi there")).shouldBeEmpty()
        coercedName(rejectType(), "hi there") shouldBe "hi there"
    }

    "the builder helper noOuterWhitespace() sets reject mode" {
        val h = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A code", required = true) { noOuterWhitespace() }
                }
            },
        ).getValue("w.Rec")
        validate(h, mapOf("name" to " x")).map { it.code } shouldContainExactly listOf(SchFailCode.badValue)
    }

    // --- the default: absent leaves whitespace alone --------------------------------------------------------

    "absent keeps whitespace, exactly as today" {
        val plain = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A name", required = true)
                }
            },
        ).getValue("w.Rec")
        validate(plain, mapOf("name" to "  hi  ")).shouldBeEmpty()
        (coerceAndValidate(plain, mapOf("name" to "  hi  ")).value as Map<*, *>)["name"] shouldBe "  hi  "
    }

    // --- ordering: the cleaned value is what the bounds and options measure ----------------------------------

    "trim runs before minLength, so a value that is all-but-whitespace too short fails" {
        // " a " is length 3 and would pass minLength:3 untouched; trimmed it is "a", length 1, and must fail.
        val t = recType(SOWS.trim) { minLength = 3 }
        validate(t, mapOf("name" to " a ")).map { it.code } shouldContainExactly listOf(SchFailCode.belowMinimum)
        // A genuinely long-enough value still passes after trimming.
        validate(t, mapOf("name" to "  abcd  ")).shouldBeEmpty()
    }

    "trim runs before a closed choice list, so a padded value still matches its option" {
        val chooser = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Pick") {
                    type = SCT.kObject
                    property("name", "A choice") {
                        outerWhitespace = SOWS.trim
                        option("red", "Red")
                        option("green", "Green")
                    }
                }
            },
        ).getValue("w.Pick")
        validate(chooser, mapOf("name" to "  green  ")).shouldBeEmpty()
        coercedName(chooser, "  green  ") shouldBe "green"
    }

    "reject runs before a closed choice list, so a padded copy of an option is still refused" {
        val chooser = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Pick") {
                    type = SCT.kObject
                    property("name", "A choice") {
                        outerWhitespace = SOWS.reject
                        option("red", "Red")
                    }
                }
            },
        ).getValue("w.Pick")
        // Both the edge whitespace and the now-unlisted value are reported.
        validate(chooser, mapOf("name" to " red ")).map { it.code } shouldContainExactly
            listOf(SchFailCode.badValue, SchFailCode.invalidOption)
    }

    // --- interplay with emptyIsAbsent -----------------------------------------------------------------------

    "a whitespace-only value reads as absent under emptyIsAbsent, before either mode alters it" {
        // isBlank() drops it in the container ahead of the property check, so required -> missing, not badValue,
        // under both modes; the trimmed form would be "" anyway.
        validate(trimType(), mapOf("name" to "   ")).map { it.code } shouldContainExactly
            listOf(SchFailCode.missingRequired)
        validate(rejectType(), mapOf("name" to "   ")).map { it.code } shouldContainExactly
            listOf(SchFailCode.missingRequired)
        // Optional: dropped from the coerced output entirely, no failure.
        val optional = coerceAndValidate(recType(SOWS.trim, required = false), mapOf("name" to "  ", "note" to "n"))
        optional.failures.shouldBeEmpty()
        (optional.value as Map<*, *>).keys shouldBe setOf("note")
    }

    // --- the whitespace definition is <= ' ', not Unicode -------------------------------------------------

    "a no-break space is not outer whitespace here: the rule is <= ' ', matching the rest of the kernel" {
        // U+00A0 is above ' ', so neither mode touches it -- that character is g-visibleOnly's job, not this.
        coercedName(trimType(), " hi ") shouldBe " hi "
        validate(rejectType(), mapOf("name" to " hi ")).shouldBeEmpty()
    }

    // --- refused at parse time where it would constrain nothing ---------------------------------------------

    "declared on a non-string type, it fails the parse by name" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "w") {
                    type("Bad") {
                        type = SCT.kObject
                        property("n", "A count") { type = SCT.integer; outerWhitespace = SOWS.trim }
                    }
                },
            )
        }.message.orEmpty() shouldContain SCH.outerWhitespace
    }

    "declared on a date-format string, it fails the parse: the value is parsed as a date, not read as text" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "w") {
                    type("Bad") {
                        type = SCT.kObject
                        property("d", "A day") { dayOnlyDate(); outerWhitespace = SOWS.reject }
                    }
                },
            )
        }.message.orEmpty() shouldContain SFMT.date
    }

    "an unrecognized mode fails the parse rather than silently doing nothing" {
        shouldThrow<KdrException> {
            parseSchemaTypes(mapOf("w.Raw" to mapOf(SCH.type to SCT.string, SCH.outerWhitespace to "strip")))
        }.message.orEmpty() shouldContain "'strip'"
    }

    "the resolved mode is exposed on SchType" {
        trimType().properties.getValue("name").valueType.outerWhitespace shouldBe SchOuterWhitespace.trim
        rejectType().properties.getValue("name").valueType.outerWhitespace shouldBe SchOuterWhitespace.reject
    }
})
