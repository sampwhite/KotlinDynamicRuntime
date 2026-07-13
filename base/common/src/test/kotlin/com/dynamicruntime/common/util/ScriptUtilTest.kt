package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers the `${...}` template evaluator (issue #50): variable substitution, value formatting, the doubled-prefix
 * escape, a configurable prefix (with pass-through of other prefixes for multi-pass evaluation), the
 * missing/null error policy, and -- the payoff of a home-grown engine -- exact character/line/column error
 * positions with structured error codes.
 */
class ScriptUtilTest : StringSpec({

    "substitutes a single variable" {
        $$"Your code is ${verifyCode}.".evalTemplate(mapOf("verifyCode" to "1234")) shouldBe "Your code is 1234."
    }

    "substitutes multiple and adjacent variables" {
        $$"${a}${b} then ${a}".evalTemplate(mapOf("a" to "X", "b" to "Y")) shouldBe "XY then X"
    }

    "tolerates whitespace around the variable name" {
        $$"${ a }".evalTemplate(mapOf("a" to "X")) shouldBe "X"
    }

    "formats non-string values via fmt()" {
        $$"${n}/${d}/${b}".evalTemplate(mapOf("n" to 42L, "d" to 3.5, "b" to true)) shouldBe "42/3.5/true"
    }

    "a doubled prefix is an escaped literal prefix" {
        // These strings contain a literal `$$`, which a normal multi-dollar ($$"...") literal would read as
        // interpolation -- so they use a triple-dollar ($$$"...") literal, where only `$$$` interpolates.
        $$$"cost is $$5".evalTemplate(emptyMap()) shouldBe $$"cost is $5"
        $$$"$${a}".evalTemplate(mapOf("a" to "X")) shouldBe $$"${a}"
    }

    "a lone prefix is emitted literally" {
        "100$ and done".evalTemplate(emptyMap()) shouldBe "100$ and done"
        "trailing $".evalTemplate(emptyMap()) shouldBe "trailing $"
    }

    "a custom prefix works and leaves the default prefix untouched" {
        // With '#' as the prefix, '#{...}' substitutes while '${...}' passes through for a later pass.
        $$"#{x} and ${y}".evalTemplate(mapOf("x" to "X"), prefix = '#') shouldBe $$"X and ${y}"
    }

    "a missing key throws with the missingKey code" {
        val ex = shouldThrow<KdrException> { $$"hi ${who}".evalTemplate(emptyMap()) }
        // Pin the literal wire key here so the shared constant's value stays a stable contract.
        ex.extraData["errorCode"] shouldBe ScriptError.missingKey
    }

    "a null value throws with the nullValue code" {
        val ex = shouldThrow<KdrException> { $$"hi ${who}".evalTemplate(mapOf("who" to null)) }
        ex.extraData[KdrException.errorCodeKey] shouldBe ScriptError.nullValue
    }

    "an unterminated expression throws with the unterminatedExpression code" {
        val ex = shouldThrow<KdrException> { $$"hi ${who".evalTemplate(mapOf("who" to "x")) }
        ex.extraData[KdrException.errorCodeKey] shouldBe ScriptError.unterminatedExpression
    }

    "an empty expression throws with the emptyExpression code" {
        val ex = shouldThrow<KdrException> { $$"hi ${}".evalTemplate(emptyMap()) }
        ex.extraData[KdrException.errorCodeKey] shouldBe ScriptError.emptyExpression
    }

    "reports the exact character offset, line, and column of an error" {
        // Line 1 is "Line one." (offsets 0-8) then '\n' (9). Line 2 is "Value: ${missing} here.",
        // so the '$' opening the bad block sits at offset 17 -- line 2, column 8 (1-based).
        val template = $$"Line one.\nValue: ${missing} here."
        val ex = shouldThrow<KdrException> { template.evalTemplate(emptyMap()) }

        ex.extraData[KdrException.errorCodeKey] shouldBe ScriptError.missingKey
        ex.extraData[KdrException.offsetKey] shouldBe 17
        ex.extraData[KdrException.lineKey] shouldBe 2
        ex.extraData[KdrException.lineColKey] shouldBe 8
    }

    "a dotted path resolves through nested maps (issue #59)" {
        // The natural pairing: a two-tier fragment map resolved as ${namespace.key}.
        val fragments = mapOf("email" to mapOf("code" to "1234"))
        $$"Your code is ${email.code}.".evalTemplate(fragments) shouldBe "Your code is 1234."
    }

    "a deeper dotted path drills multiple levels" {
        val data = mapOf("a" to mapOf("b" to mapOf("c" to "deep")))
        $$"${a.b.c}".evalTemplate(data) shouldBe "deep"
    }

    "a missing dotted segment throws missingKey" {
        val ex = shouldThrow<KdrException> {
            $$"${a.missing}".evalTemplate(mapOf("a" to mapOf("b" to "x")))
        }
        ex.extraData[KdrException.errorCodeKey] shouldBe ScriptError.missingKey
    }

    "drilling into a non-map throws notAnObject" {
        val ex = shouldThrow<KdrException> {
            $$"${a.b}".evalTemplate(mapOf("a" to "not a map"))
        }
        ex.extraData[KdrException.errorCodeKey] shouldBe ScriptError.notAnObject
    }
})
