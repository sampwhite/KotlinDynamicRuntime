package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Covers the expression grammar inside a `${...}` block: literals, arithmetic, comparison, logic, the
 * conditional, and the `?:` default. The document-level scan (escapes, prefixes, positions) stays in
 * `ScriptUtilTest`; this is about what an expression *means*.
 *
 * The value rules are the part worth pinning down. A small dynamic language is judged by what it does when the
 * types disagree, and every one of those answers is a decision -- text never counting as a number, `+` and `~`
 * each refusing the other's job, an empty string being falsy -- so each gets a test rather than being left to
 * emerge. Several of these assert an *error*, which is the point: the mismatch is reported where it can be
 * fixed instead of being absorbed into a plausible-looking result.
 */
class ScriptExprTest : StringSpec({

    fun evl(template: String, data: Map<String, Any?> = emptyMap()) = template.evalTemplate(data)

    fun errorCode(template: String, data: Map<String, Any?> = emptyMap()): Any? =
        shouldThrow<KdrException> { template.evalTemplate(data) }.extraData[KdrException.errorCodeKey]

    // --- literals --------------------------------------------------------------

    "literal numbers and strings evaluate to themselves" {
        evl($$"""${42}/${3.5}/${"hi"}/${'yo'}""") shouldBe "42/3.5/hi/yo"
    }

    "the word literals are true, false and null" {
        evl($$"${true}|${false}") shouldBe "true|false"
        // A null contributes nothing rather than printing the word "null" into a document.
        evl($$"[${null}]") shouldBe "[]"
    }

    "a string literal keeps its escapes" {
        evl($$"""${"a\"b"}""") shouldBe "a\"b"
        evl($$"""${"tab\there"}""") shouldBe "tab\there"
    }

    // --- arithmetic ------------------------------------------------------------

    "arithmetic follows precedence, and parentheses override it" {
        evl($$"${1 + 2 * 3}") shouldBe "7"
        evl($$"${(1 + 2) * 3}") shouldBe "9"
        evl($$"${10 - 2 - 3}") shouldBe "5" // left-associative
    }

    "two integers divide as integers, and one double makes it a double" {
        evl($$"${7 / 2}") shouldBe "3"
        evl($$"${7.0 / 2}") shouldBe "3.5"
        evl($$"${7 % 2}") shouldBe "1"
    }

    /**
     * The rule that keeps this predictable: text is never a number, so the error names the real problem --
     * a value that reached the template as a string when a number was expected. Coercing it to 42, or
     * concatenating it, would both have hidden that.
     */
    "a numeric-looking string is still text, and arithmetic on it is an error" {
        errorCode($$"${n * 2}", mapOf("n" to "21")) shouldBe ScriptError.typeMismatch
        errorCode($$"${n + 1}", mapOf("n" to "3")) shouldBe ScriptError.typeMismatch
    }

    "unary minus negates" {
        evl($$"${-n + 1}", mapOf("n" to 5L)) shouldBe "-4"
    }

    "dividing by zero is an error, not an infinity that would print" {
        errorCode($$"${1 / 0}") shouldBe ScriptError.divideByZero
        errorCode($$"${1 % 0}") shouldBe ScriptError.divideByZero
    }

    // --- concatenation ---------------------------------------------------------

    "'~' joins text and '+' adds numbers; neither does the other's job" {
        evl($$"""${"n=" ~ count}""", mapOf("count" to 3L)) shouldBe "n=3"
        evl($$"""${count ~ " items"}""", mapOf("count" to 3L)) shouldBe "3 items"
        evl($$"${count + 1}", mapOf("count" to 3L)) shouldBe "4"
        // `+` on text is an error rather than a silent join -- the whole point of having two operators.
        errorCode($$"""${"n=" + count}""", mapOf("count" to 3L)) shouldBe ScriptError.typeMismatch
    }

    "'~' binds looser than arithmetic, so the sum is joined rather than the first operand" {
        evl($$"""${"total=" ~ count + 1}""", mapOf("count" to 3L)) shouldBe "total=4"
    }

    "'~' formats each side, so a number joins as it would print" {
        evl($$"""${1 ~ "-" ~ 2.5}""") shouldBe "1-2.5"
        evl($$"""${10.0 / 2 ~ "x"}""") shouldBe "5x"
    }

    // --- comparison and logic --------------------------------------------------

    "comparisons work on numbers and on strings" {
        evl($$"${1 < 2}|${2 <= 2}|${3 > 4}|${3 >= 4}") shouldBe "true|true|false|false"
        evl($$"""${"a" < "b"}""") shouldBe "true"
    }

    "equality compares within a kind, and always allows a null test" {
        evl($$"""${1 == 1.0}|${"x" == "x"}|${true == true}|${1 != 2}""") shouldBe "true|true|true|true"
        evl($$"${missing == null}") shouldBe "true"
    }

    /** Mixing kinds is refused rather than quietly false, on the same reasoning as the arithmetic rule. */
    "comparing a number with text is a type mismatch, not a silent false" {
        errorCode($$"""${"3" == 3}""") shouldBe ScriptError.typeMismatch
        errorCode($$"""${flag == "true"}""", mapOf("flag" to true)) shouldBe ScriptError.typeMismatch
    }

    "logical operators combine conditions and short-circuit" {
        evl($$"${true && false}|${true || false}|${!false}") shouldBe "false|true|true"
        // The right side is never evaluated, so a missing key there cannot fail the expression.
        evl($$"${false && missing.deep.path}") shouldBe "false"
        evl($$"${true || missing.deep.path}") shouldBe "true"
    }

    "emptiness is falsy, so a collection or string reads naturally in a condition" {
        val data = mapOf("items" to listOf(1L), "none" to emptyList<Any?>(), "blank" to "", "z" to 0L)
        evl($$"""${items ? "some" : "none"}""", data) shouldBe "some"
        evl($$"""${none ? "some" : "none"}""", data) shouldBe "none"
        evl($$"""${blank ? "y" : "n"}""", data) shouldBe "n"
        evl($$"""${z ? "y" : "n"}""", data) shouldBe "n"
    }

    // --- the conditional -------------------------------------------------------

    "a ternary picks a branch, and only evaluates the one it picks" {
        evl($$"""${admin ? "Admin" : "User"}""", mapOf("admin" to true)) shouldBe "Admin"
        evl($$"""${admin ? "Admin" : "User"}""", mapOf("admin" to false)) shouldBe "User"
        // The untaken branch would throw on its own; it is never evaluated.
        evl($$"""${true ? "ok" : missing.key}""") shouldBe "ok"
    }

    "a ternary condition tolerates an absent value, treating it as false" {
        evl($$"""${missing ? "y" : "n"}""") shouldBe "n"
        evl($$"""${a.b.c ? "y" : "n"}""") shouldBe "n"
        // Which is what makes an explicit null test work even when the parent is absent.
        evl($$"""${a.b == null ? "none" : a.b}""") shouldBe "none"
    }

    "ternaries chain to the right" {
        val data = mapOf("n" to 5L)
        evl($$"""${n > 8 ? "big" : n > 3 ? "mid" : "small"}""", data) shouldBe "mid"
    }

    // --- the default operator --------------------------------------------------

    "'?:' supplies a default for a missing or null value" {
        evl($$"""${name ?: "there"}""") shouldBe "there"
        evl($$"""${name ?: "there"}""", mapOf("name" to "Ada")) shouldBe "Ada"
        evl($$"""${name ?: "there"}""", mapOf("name" to null)) shouldBe "there"
    }

    "'?:' covers a whole missing path, not just the last segment" {
        evl($$"""${user.profile.name ?: "anonymous"}""") shouldBe "anonymous"
        evl($$"""${user.profile.name ?: "anonymous"}""", mapOf("user" to mapOf("profile" to mapOf("name" to "Ada")))) shouldBe "Ada"
    }

    "'?:' chains, and binds tighter than a ternary" {
        evl($$"""${a ?: b ?: "last"}""") shouldBe "last"
        evl($$"""${a ?: b ?: "last"}""", mapOf("b" to "B")) shouldBe "B"
        // The `?:` resolves first and becomes the condition, rather than swallowing the ternary.
        evl($$"""${(a ?: "") ? "set" : "unset"}""") shouldBe "unset"
    }

    /** A present-but-empty value is *not* absent, so `?:` keeps it. Emptiness is only falsy in a condition. */
    "'?:' defaults on absence, not on emptiness" {
        evl($$"""${name ?: "there"}""", mapOf("name" to "")) shouldBe ""
    }

    // --- staying strict where nothing was said ---------------------------------

    "a bare missing or null value is still an error" {
        errorCode($$"${nope}") shouldBe ScriptError.missingKey
        errorCode($$"${nope}", mapOf("nope" to null)) shouldBe ScriptError.nullValue
        // Including as an operand: tolerance is only where the author asked for it.
        errorCode($$"${nope + 1}") shouldBe ScriptError.missingKey
    }

    "applying an operator to a value it has no meaning for is a type mismatch" {
        errorCode($$"""${"abc" * 2}""") shouldBe ScriptError.typeMismatch
        errorCode($$"${obj * 2}", mapOf("obj" to mapOf("a" to 1L))) shouldBe ScriptError.typeMismatch
        errorCode($$"""${1 < "abc"}""") shouldBe ScriptError.typeMismatch
    }

    /** The message has to name the offending value, or a template author cannot act on it. */
    "a type mismatch says what it was given" {
        val ex = shouldThrow<KdrException> { $$"${n * 2}".evalTemplate(mapOf("n" to "21")) }
        (ex.message ?: "") shouldContain "'21'"
        (ex.message ?: "") shouldContain "*"
    }

    // --- function calls --------------------------------------------------------

    "the text functions transform their argument" {
        val data = mapOf<String, Any?>("name" to "  Ada Lovelace  ")
        evl($$"${upper(trim(name))}", data) shouldBe "ADA LOVELACE"
        evl($$"${lower(trim(name))}", data) shouldBe "ada lovelace"
        evl($$"""${trim("  x  ")}""") shouldBe "x"
    }

    "count measures whatever has a size, and lands in the numeric world" {
        val data = mapOf<String, Any?>("items" to listOf(1L, 2L, 3L), "obj" to mapOf("a" to 1L), "s" to "abcd")
        evl($$"${count(items)}|${count(obj)}|${count(s)}", data) shouldBe "3|1|4"
        // Being a real number is the point: it compares, and so it pluralises.
        evl($$"""${count(items)} ${count(items) == 1 ? "item" : "items"}""", data) shouldBe "3 items"
    }

    "abs works on both number kinds" {
        evl($$"${abs(0 - 5)}|${abs(3)}", emptyMap()) shouldBe "5|3"
        evl($$"${abs(n)}", mapOf("n" to -2.5)) shouldBe "2.5"
    }

    "the date functions accept an ISO string as well as an Instant" {
        val data = mapOf<String, Any?>("at" to "2026-08-13T14:30:00.000Z")
        evl($$"${formatDay(at)}", data) shouldBe "2026-08-13"
        evl($$"${formatDate(at)}", data) shouldBe "2026-08-13T14:30:00.000Z"
    }

    "a call composes with everything else in the grammar" {
        val data = mapOf<String, Any?>("items" to listOf(1L, 2L), "name" to "ada")
        // As an operand, in a ternary branch, and on the left of a default.
        evl($$"${count(items) * 10}", data) shouldBe "20"
        evl($$"""${count(items) > 1 ? upper(name) : name}""", data) shouldBe "ADA"
        evl($$"""${upper(missing) ?: "none"}""", data) shouldBe "none"
        // And arguments are themselves expressions.
        evl($$"""${upper(name ~ "!")}""", data) shouldBe "ADA!"
    }

    "an argument of the wrong kind names the function and the value" {
        errorCode($$"${upper(42)}") shouldBe ScriptError.typeMismatch
        errorCode($$"""${count(42)}""") shouldBe ScriptError.typeMismatch
        errorCode($$"""${formatDay("not-a-date")}""") shouldBe ScriptError.typeMismatch
        val ex = shouldThrow<KdrException> { $$"${upper(42)}".evalTemplate(emptyMap()) }
        (ex.message ?: "") shouldContain "'upper'"
        (ex.message ?: "") shouldContain "42"
    }

    /**
     * Unknown names and wrong arity are refused while **parsing**, which is what lets the fragment checker
     * catch a misspelled function with no data at hand -- unlike a bad path, which is only knowable at render.
     */
    "an unknown function or a wrong argument count is a syntax error" {
        errorCode($$"${uppr(name)}", mapOf("name" to "a")) shouldBe ScriptError.syntaxError
        errorCode($$"${upper()}") shouldBe ScriptError.syntaxError
        errorCode($$"""${upper("a", "b")}""") shouldBe ScriptError.syntaxError
        // The message lists what is available, so the fix does not need a trip to the source.
        val ex = shouldThrow<KdrException> { $$"${uppr(x)}".evalTemplate(emptyMap()) }
        (ex.message ?: "") shouldContain "upper"
    }

    "a name followed by no parenthesis is still a path, not a call" {
        evl($$"${count}", mapOf("count" to 7L)) shouldBe "7"
    }

    // --- syntax ----------------------------------------------------------------

    "a malformed expression reports a syntax error" {
        errorCode($$"${1 +}") shouldBe ScriptError.syntaxError
        errorCode($$"${(1 + 2}") shouldBe ScriptError.syntaxError
        errorCode($$"${1 2}") shouldBe ScriptError.syntaxError
        errorCode($$"""${"unterminated}""") shouldBe ScriptError.syntaxError
        errorCode($$"${a @ b}") shouldBe ScriptError.syntaxError
    }

    /**
     * The block scan has to know about string literals: a `}` inside quotes is content, and a scan that
     * stopped at the first one would hand the parser a fragment and end the block early.
     */
    "a closing brace inside a string literal does not end the block" {
        evl($$"""${ok ? "}" : "no"}""", mapOf("ok" to true)) shouldBe "}"
        evl($$"""${"a}b"} tail""") shouldBe "a}b tail"
    }

    "an expression nested beyond the depth cap is refused rather than overflowing the stack" {
        val deep = "(".repeat(80) + "1" + ")".repeat(80)
        errorCode($$"${" + deep + "}") shouldBe ScriptError.expressionTooDeep
    }

    // --- the errors keep their position ----------------------------------------

    "an expression error still points at the block, not into the expression" {
        val ex = shouldThrow<KdrException> { $$"line one\nand ${1 / 0} here".evalTemplate(emptyMap()) }
        ex.extraData[KdrException.lineKey] shouldBe 2
        ex.extraData[KdrException.lineColKey] shouldBe 5
    }
})
