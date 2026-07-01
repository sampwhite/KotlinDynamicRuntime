package com.dynamicruntime.common.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch

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
})
