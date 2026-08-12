package com.dynamicruntime.common.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import kotlin.time.Instant

@Suppress("unused", "EnumEntryName")
private enum class Color { red, green, blue }

class ConvertUtilTest : StringSpec({

    // The JSON number grammar (RFC 8259): optional '-', an int part with no leading zeros,
    // an optional fraction with at least one digit, and an optional exponent.
    val jsonNumber = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

    // --- fmtD: exact output -------------------------------------------------

    "fmtD drops the trailing zero on whole doubles" {
        2.0.fmtD() shouldBe "2"
        (-7.0).fmtD() shouldBe "-7"
    }

    "fmtD keeps a leading zero for magnitudes below one" {
        0.5.fmtD() shouldBe "0.5"
        (-0.25).fmtD() shouldBe "-0.25"
    }

    "fmtD suppresses floating point noise within seven digits" {
        (0.1 + 0.2).fmtD() shouldBe "0.3"
    }

    "fmtD renders the smallest retained digit" {
        0.0000001.fmtD() shouldBe "0.0000001"
    }

    "fmtD rounds beyond seven fractional digits" {
        0.123456789.fmtD() shouldBe "0.1234568"
    }

    "fmtD trims interior-produced trailing zeros" {
        1.2000000.fmtD() shouldBe "1.2"
    }

    "fmtD folds negative zero to zero" {
        (-0.0).fmtD() shouldBe "0"
    }

    // --- fmtF: exact output -------------------------------------------------

    "fmtF keeps at most three fractional digits" {
        1.23456f.fmtF() shouldBe "1.235"
    }

    "fmtF drops the trailing zero on whole floats" {
        4.0f.fmtF() shouldBe "4"
    }

    // --- non-finite values render as null (valid JSON) ----------------------

    "non-finite doubles render as the JSON null literal" {
        Double.NaN.fmtD() shouldBe "null"
        Double.POSITIVE_INFINITY.fmtD() shouldBe "null"
        Double.NEGATIVE_INFINITY.fmtD() shouldBe "null"
    }

    "non-finite floats render as the JSON null literal" {
        Float.NaN.fmtF() shouldBe "null"
        Float.POSITIVE_INFINITY.fmtF() shouldBe "null"
    }

    // --- fmt() dispatch -----------------------------------------------------

    "fmt renders null, and dispatches doubles and floats to their formatters" {
        null.fmt() shouldBe "null"
        0.5.fmt() shouldBe "0.5"
        1.23456f.fmt() shouldBe "1.235"
    }

    "fmt falls back to toString for other types" {
        5L.fmt() shouldBe "5"
        true.fmt() shouldBe "true"
        "hi".fmt() shouldBe "hi"
    }

    "fmt renders an Instant as the ISO system timestamp" {
        Instant.parse("2021-06-01T08:00:00.250Z").fmt() shouldBe "2021-06-01T08:00:00.250Z"
    }

    "toJsonStr serializes an Instant value as an ISO string (via fmt)" {
        linkedMapOf("at" to Instant.parse("2021-06-01T08:00:00.000Z")).toJsonStr(compact = true) shouldBe
            """{"at":"2021-06-01T08:00:00.000Z"}"""
    }

    // --- validity: every finite output is a valid JSON number ---------------

    "fmtD output is always a valid JSON number and round-trips through the parser" {
        val values = listOf(
            0.0, 0.5, -0.25, 2.0, -7.0, 0.0000001, 0.1 + 0.2,
            123456.789, -9.99, 1.5e12, -3.0e13, 42.0,
        )
        for (v in values) {
            val s = v.fmtD()
            s shouldMatch jsonNumber
            // The project's own parser accepts it and yields the same numeric value.
            val parsed = "[$s]".jsonArray()!!.single() as Number
            parsed.toDouble() shouldBe s.toDouble()
        }
    }

    "fmtF output is always a valid JSON number" {
        val values = listOf(0.0f, 0.5f, -0.125f, 4.0f, 1.23456f, -100.0f)
        for (v in values) {
            v.fmtF() shouldMatch jsonNumber
        }
    }

    // --- toOptBool ----------------------------------------------------------

    "toOptBool reads truthy first characters case-insensitively" {
        for (s in listOf("y", "Y", "yes", "t", "TRUE", "1", "1000")) {
            s.toOptBool() shouldBe true
        }
    }

    "toOptBool reads falsy first characters case-insensitively" {
        for (s in listOf("n", "N", "no", "f", "False", "0", "0.0")) {
            s.toOptBool() shouldBe false
        }
    }

    "toOptBool skips leading whitespace and control characters" {
        "   yes".toOptBool() shouldBe true
        "\t\n f".toOptBool() shouldBe false
        // Anything with a code point <= space counts as whitespace.
        "\u0001\u0000 1".toOptBool() shouldBe true
    }

    "toOptBool returns null for an unrecognized first character" {
        "maybe".toOptBool() shouldBe null
        "-1".toOptBool() shouldBe null
    }

    "toOptBool returns null when there is no non-whitespace character" {
        "".toOptBool() shouldBe null
        "    ".toOptBool() shouldBe null
    }

    "toOptBool does not forgive exotic unicode whitespace above a space" {
        // U+00A0 (non-breaking space) is > ' ', so it is treated as the first real character -> null.
        "\u00A0true".toOptBool() shouldBe null
    }

    // --- toOptStr (issue #267) ----------------------------------------------

    "toOptStr passes a string through untouched" {
        ("hi" as Any?).toOptStr() shouldBe "hi"
        ("" as Any?).toOptStr() shouldBe ""
        (StringBuilder("built") as Any?).toOptStr() shouldBe "built"
    }

    "toOptStr converts the other primitives rather than dropping them" {
        (42 as Any?).toOptStr() shouldBe "42"
        (42L as Any?).toOptStr() shouldBe "42"
        (true as Any?).toOptStr() shouldBe "true"
        ('x' as Any?).toOptStr() shouldBe "x"
    }

    // The bug this widening exists to retire. A conversion answering null reads as "absent", and two "absents"
    // are equal -- so before #267 these two agreed, which is how a `const` of a non-string matched anything.
    "two different non-strings do not convert to the same thing" {
        (42 as Any?).toOptStr() shouldNotBe (true as Any?).toOptStr()
        (1 as Any?).toOptStr() shouldNotBe (2 as Any?).toOptStr()
    }

    // Through `fmt`, not `toString`: a bare `toString` on a Double prints "1.0" here and "1" under Kotlin/JS,
    // and both sides run this. KernelSharedTest pins the same expectations on the JS target.
    "toOptStr renders a double the way the wire does" {
        (1.0 as Any?).toOptStr() shouldBe "1"
        (1.0e10 as Any?).toOptStr() shouldBe "10000000000"
        (0.5 as Any?).toOptStr() shouldBe "0.5"
        (1.23456f as Any?).toOptStr() shouldBe "1.235"
    }

    // Held at primitives on purpose: `toString()` on a map is Kotlin's `{a=1}`, which is not JSON. A silent
    // null is bad; a plausible-looking wrong string is worse. Naming such a value is `fmt`'s job, not this one.
    "toOptStr still refuses anything that is not a primitive" {
        (null as Any?).toOptStr() shouldBe null
        (mapOf("a" to 1) as Any?).toOptStr() shouldBe null
        (listOf(1, 2) as Any?).toOptStr() shouldBe null
        (Instant.parse("2021-06-01T08:00:00.000Z") as Any?).toOptStr() shouldBe null
    }

    // --- toOptEnum ----------------------------------------------------------

    "toOptEnum resolves a member by exact name, and is null for anything else" {
        "green".toOptEnum<Color>() shouldBe Color.green
        "Green".toOptEnum<Color>() shouldBe null // matched by name, case-sensitive
        "purple".toOptEnum<Color>() shouldBe null // not a member
        (null as Any?).toOptEnum<Color>() shouldBe null
        // Converts to "5" since #267, then finds no member of that name -- null for the second reason now.
        (5 as Any?).toOptEnum<Color>() shouldBe null
    }
})
