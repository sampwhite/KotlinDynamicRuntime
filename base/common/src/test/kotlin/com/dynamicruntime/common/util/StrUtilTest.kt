package com.dynamicruntime.common.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class StrUtilTest : StringSpec({

    "splitComma splits on commas" {
        "a,b,c".splitComma() shouldBe listOf("a", "b", "c")
    }

    "splitComma trims surrounding whitespace on each element" {
        "a , b ,  c".splitComma() shouldBe listOf("a", "b", "c")
    }

    "splitComma on a string with no comma returns the single trimmed element" {
        "  abc  ".splitComma() shouldBe listOf("abc")
    }

    "splitComma keeps empty elements from leading, trailing and repeated commas" {
        "a,,b,".splitComma() shouldBe listOf("a", "", "b", "")
    }

    "splitComma on an empty string returns an empty list" {
        "".splitComma() shouldBe emptyList()
    }

    "splitComma on a blank (whitespace-only) string returns an empty list" {
        "   ".splitComma() shouldBe emptyList()
    }

    "isVariableName accepts letters, digits and underscores starting with a letter or underscore" {
        "name".isVariableName() shouldBe true
        "_debug".isVariableName() shouldBe true
        "explainInput".isVariableName() shouldBe true
        "a1_b2".isVariableName() shouldBe true
        "_".isVariableName() shouldBe true
    }

    "isVariableName rejects empty, leading digits, and non-word characters" {
        "".isVariableName() shouldBe false
        "1abc".isVariableName() shouldBe false
        "a-b".isVariableName() shouldBe false
        "a b".isVariableName() shouldBe false
        "a.b".isVariableName() shouldBe false
        $$"$note".isVariableName() shouldBe false
    }

    "hex encoding should give exactly four characters" {
        'a'.toUpperHex() shouldBe "0061"
        '\n'.toUpperHex() shouldBe "000A"
        '\u2567'.toUpperHex() shouldBe "2567"
    }

    "sanitizeForDisplay leaves an ordinary value untouched" {
        "first_last@example.com".sanitizeForDisplay() shouldBe "first_last@example.com"
    }

    "sanitizeForDisplay collapses whitespace and newlines to single spaces" {
        "a\n\nb\tc   d".sanitizeForDisplay() shouldBe "a b c d"
        "  trim me  ".sanitizeForDisplay() shouldBe "trim me"
    }

    "sanitizeForDisplay strips the characters that build Markdown links/images/code" {
        "[click](http://evil.com)".sanitizeForDisplay() shouldBe "clickhttp://evil.com"
        "![img](http://x)".sanitizeForDisplay() shouldBe "!imghttp://x"
        "<http://auto>".sanitizeForDisplay() shouldBe "http://auto"
        "`code`".sanitizeForDisplay() shouldBe "code"
        // Emphasis is intentionally kept -- it does not link, and `_` is common in real values.
        "a_b*c".sanitizeForDisplay() shouldBe "a_b*c"
    }

    "sanitizeForDisplay clips over-long input with an ellipsis" {
        val long = "x".repeat(200)
        val out = long.sanitizeForDisplay(maxLen = 10)
        out.length shouldBe 10
        out shouldBe "xxxxxxxxx\u2026"
    }
})
