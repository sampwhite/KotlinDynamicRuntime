package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `g-outerWhitespace` (issues #541, #765): leading/trailing whitespace on a string is stripped (`"trim"`),
 * refused (`"reject"`), or left alone (`"keep"`).
 *
 * The declared modes are the same rule read different ways, so the cases come in pairs: what `"trim"` cleans,
 * `"reject"` refuses. The load-bearing details are the *ordering* (the cleaned value is what `minLength`/options
 * measure) and the mode split (`"trim"` emits the cleaned value in coerce mode and checks against it in
 * validate-only). "Whitespace" here is the kernel's `<= ' '` test, which -- deliberately -- is not Kotlin's
 * Unicode `trim`.
 *
 * The **input default** (issue #765) is the other axis these cases cover: a plain string with no declared mode
 * trims on the `forInput` path (so bounds/options/the handler measure the trimmed value) and is left untouched
 * everywhere else; `"keep"` is the opt-out from that default.
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

    /** The coerced `name` on the **input** path, where the #765 trim default applies. */
    fun inputCoercedName(type: SchType, name: String): Any? =
        (coerceAndValidate(type, mapOf("name" to name), SchOpts(forInput = true)).value as Map<*, *>)["name"]

    /** A plain `name` field with no declared whitespace mode, so the default is what is under test. */
    fun plainType(build: SchTypeBuilder.() -> Unit = {}): SchType =
        parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("name", "A name", required = true) { build() }
                    property("note", "A note")
                }
            },
        ).getValue("w.Rec")

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

    // --- the default: no keyword trims on input, keeps everywhere else (issue #765) -------------------------

    "with no keyword, the output/stored path keeps whitespace" {
        // forInput defaults to false, so validate/coerceAndValidate here exercise the output/stored path.
        validate(plainType(), mapOf("name" to "  hi  ")).shouldBeEmpty()
        coercedName(plainType(), "  hi  ") shouldBe "  hi  "
    }

    "with no keyword, the input path trims by default" {
        inputCoercedName(plainType(), "  hi  ") shouldBe "hi"
        inputCoercedName(plainType(), "\t hi \n") shouldBe "hi"
        // Interior whitespace is untouched -- only the edges, like an explicit trim.
        inputCoercedName(plainType(), "  a b  ") shouldBe "a b"
    }

    "the input trim default runs before minLength, closing the advertise-vs-enforce gap" {
        // The bug #765 fixes: " a " is length 3 and would pass minLength:3 untouched, then the handler stores
        // "a" (length 1). On the input path the bound now measures the trimmed value and fails.
        val t = plainType { minLength = 3 }
        coerceAndValidate(t, mapOf("name" to " a "), SchOpts(forInput = true)).failures.map { it.code } shouldContainExactly
            listOf(SchFailCode.belowMinimum)
        // A genuinely long-enough value passes after trimming, and arrives trimmed.
        inputCoercedName(t, "  abcd  ") shouldBe "abcd"
    }

    "keep opts a field out of the input trim default" {
        val kept = plainType { preserveWhitespace() }
        inputCoercedName(kept, "  hi  ") shouldBe "  hi  "
        validate(kept, mapOf("name" to "  hi  "), SchOpts(forInput = true)).shouldBeEmpty()
        // keep is resolved onto the type, and reads back on both paths (it is a no-op on output too).
        kept.properties.getValue("name").valueType.outerWhitespace shouldBe SchOuterWhitespace.keep
        coercedName(kept, "  hi  ") shouldBe "  hi  "
    }

    "an explicit trim strips on the output/stored path too, unlike the input-only default" {
        // The reason trimmed() still earns its keep: it forces the strip everywhere, not only on input.
        coercedName(trimType(), "  hi  ") shouldBe "hi"
    }

    "the input default only touches plain strings -- a non-string field is unaffected" {
        val withCount = parseSchemaTypes(
            schemaDefs(cxt, "w") {
                type("Rec") {
                    type = SCT.kObject
                    property("count", "A count", required = true) { type = SCT.integer }
                }
            },
        ).getValue("w.Rec")
        // "  5  " coerces to 5 on input; the whitespace default has no string to act on and never fires.
        (coerceAndValidate(withCount, mapOf("count" to "  5  "), SchOpts(forInput = true)).value as Map<*, *>)["count"] shouldBe 5L
    }

    "keep parses from its wire value and reads back the enum" {
        val raw = parseSchemaTypes(mapOf("w.Raw" to mapOf(SCH.type to SCT.string, SCH.outerWhitespace to SOWS.keep)))
        raw.getValue("w.Raw").outerWhitespace shouldBe SchOuterWhitespace.keep
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
