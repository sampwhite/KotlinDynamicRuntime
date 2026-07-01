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

    "hex encoding should give exactly four characters" {
        'a'.toUpperHex() shouldBe "0061"
        '\n'.toUpperHex() shouldBe "000A"
        '\u2567'.toUpperHex() shouldBe "2567"
    }
})
