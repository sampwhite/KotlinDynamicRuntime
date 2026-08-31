package com.dynamicruntime.common.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Covers the parse-only template checker: what it finds, where it says it is, and -- the part that makes it
 * usable -- that it reports every broken block in a file rather than stopping at the first.
 *
 * The boundary of the check is itself worth pinning: it validates the *template*, not the data, so a
 * reference to a key nobody supplies is deliberately **not** an issue here. That only becomes knowable when a
 * caller hands over a data map, which is a later, separate check.
 */
class ScriptCheckTest : StringSpec({

    "a clean template reports nothing" {
        $$"""Hello ${user.name ?: "there"}, you have ${count} messages.""".checkTemplateSyntax() shouldBe emptyList()
    }

    "a malformed expression is reported with its position" {
        val issues = $$"line one\nand ${1 +} here".checkTemplateSyntax()
        issues.size shouldBe 1
        issues[0].code shouldBe ScriptError.syntaxError
        issues[0].line shouldBe 2
        issues[0].col shouldBe 5
    }

    /** One error per run would make fixing a file a series of round trips. */
    "every broken block in a template is reported, not just the first" {
        val issues = $$"${1 +} then ${a @ b} then ${ok}".checkTemplateSyntax()
        issues.size shouldBe 2
        issues.all { it.code == ScriptError.syntaxError } shouldBe true
    }

    "an empty expression is reported" {
        val issues = $$"a ${} b".checkTemplateSyntax()
        issues.size shouldBe 1
        issues[0].code shouldBe ScriptError.emptyExpression
    }

    /**
     * An unterminated block swallows the rest of the document, so nothing after it can be trusted to be text.
     * It is reported and the scan stops, rather than inventing issues out of what is really string content.
     */
    "an unterminated block is the last thing reported" {
        // No closing brace at all. (A `${` whose brace is closed later *is* a terminated block, however odd
        // the contents -- that reports a syntax error instead, which the case above covers.)
        val issues = $$"ok ${a} then ${oops".checkTemplateSyntax()
        issues.size shouldBe 1
        issues[0].code shouldBe ScriptError.unterminatedExpression
    }

    "an unterminated string literal is named as such" {
        val issues = $$"""${"oops}""".checkTemplateSyntax()
        issues.size shouldBe 1
        issues[0].code shouldBe ScriptError.syntaxError
    }

    /**
     * The gain a call brings to a parse-only check: a misspelled *function* is knowable from the text, while a
     * misspelled *path* is not. `${uppr(name)}` is caught here, with no data at hand; `${user.nmae}` cannot be.
     */
    "a misspelled function is caught, though a misspelled path cannot be" {
        val issues = $$"${uppr(name)}".checkTemplateSyntax()
        issues.size shouldBe 1
        issues[0].code shouldBe ScriptError.syntaxError

        // Wrong arity likewise.
        $$"${upper()}".checkTemplateSyntax().size shouldBe 1

        // A path typo is data, not text, so it stays invisible to this check.
        $$"${user.nmae}".checkTemplateSyntax() shouldBe emptyList()
    }

    /** The checker knows nothing about data, so a key nobody provides is not its business. */
    "a reference to data is not an issue, however absent that data would be" {
        $$"${nobody.supplies.this}".checkTemplateSyntax() shouldBe emptyList()
    }

    "an escaped prefix is not a block" {
        $$$"cost is $$5 and $${a}".checkTemplateSyntax() shouldBe emptyList()
    }

    // --- what a template asks of its data --------------------------------------

    "a template reports the paths it will read" {
        val paths = $$"Hi ${user.name}, you have ${count} messages.".analyzeTemplate().paths
        paths.required shouldBe setOf("user.name", "count")
        paths.optional shouldBe emptySet()
    }

    /**
     * The rule that makes this usable rather than noisy: a path the template already handles the absence of is
     * **not** required. Reported as stricter than the evaluator, every sensible default would read as a break.
     */
    "a path the template already guards is optional, not required" {
        $$"""${user.name ?: "there"}""".analyzeTemplate().paths.required shouldBe emptySet()
        $$"""${user.name ?: "there"}""".analyzeTemplate().paths.optional shouldBe setOf("user.name")
        // A ternary condition, and a null test, are the other two guarded positions.
        $$"""${admin ? "yes" : "no"}""".analyzeTemplate().paths.required shouldBe emptySet()
        $$"""${a.b == null ? "none" : "some"}""".analyzeTemplate().paths.required shouldBe emptySet()
    }

    "an unguarded read of the same path wins over a guarded one" {
        val paths = $$"""${a ?: "x"} and ${a}""".analyzeTemplate().paths
        paths.required shouldBe setOf("a")
        paths.optional shouldBe emptySet()
    }

    /** "Required" means referenced: both arms of a ternary count, though only one will run. */
    "both arms of a conditional are required, because either could run" {
        val paths = $$"""${c ? x.one : x.two}""".analyzeTemplate().paths
        paths.required shouldBe setOf("x.one", "x.two")
        paths.optional shouldBe setOf("c")
    }

    "function arguments are read like any other operand" {
        $$"${upper(user.name)}".analyzeTemplate().paths.required shouldBe setOf("user.name")
        $$"""${upper(user.name) ?: "anon"}""".analyzeTemplate().paths.optional shouldBe setOf("user.name")
    }

    "a template that reads nothing requires nothing" {
        $$"""Plain copy with ${"a literal"} and ${1 + 2}.""".analyzeTemplate().paths.required shouldBe emptySet()
    }

    // --- checking requirements against a data map -------------------------------

    "the missing paths are the required ones the data does not supply" {
        val paths = $$"${user.name} and ${count}".analyzeTemplate().paths
        paths.missingFrom(mapOf("user" to mapOf("name" to "Ada"), "count" to 3L)) shouldBe emptyList()
        paths.missingFrom(mapOf("user" to mapOf("name" to "Ada"))) shouldBe listOf("count")
        // A typo'd path is exactly the failure this is for.
        $$"${user.nmae}".analyzeTemplate().paths
            .missingFrom(mapOf("user" to mapOf("name" to "Ada"))) shouldBe listOf("user.nmae")
    }

    "a null value counts as unsupplied, because that is what would throw" {
        val paths = $$"${a}".analyzeTemplate().paths
        paths.missingFrom(mapOf("a" to null)) shouldBe listOf("a")
        paths.missingFrom(mapOf("a" to "")) shouldBe emptyList() // present and empty is supplied
    }

    "drilling into a non-object counts as unsupplied" {
        $$"${a.b}".analyzeTemplate().paths.missingFrom(mapOf("a" to "text")) shouldBe listOf("a.b")
    }

    "a guarded path is never reported missing, however absent it is" {
        $$"""${a.b ?: "x"}""".analyzeTemplate().paths.missingFrom(emptyMap()) shouldBe emptyList()
    }

    // --- whole fragment files --------------------------------------------------

    "a fragment file check names the namespace and key of each problem" {
        val parsed = mapOf(
            "email" to mapOf("good" to $$"Hi ${name}", "bad" to $$"Broken ${1 +}"),
            "other" to mapOf("alsoBad" to $$"${}"),
        )
        val issues = parsed.checkFragmentSyntax()
        issues.size shouldBe 2
        issues.any { it.message.startsWith("email.bad:") } shouldBe true
        issues.any { it.message.startsWith("other.alsoBad:") } shouldBe true
    }

    "fragment paths are reported per entry, and copy that reads nothing is left out" {
        val parsed = mapOf(
            "email" to mapOf("body" to $$"Code ${code}", "static" to "No substitutions here."),
            "greet" to mapOf("hi" to $$"""Hi ${user.name ?: "there"}"""),
        )
        val entries = parsed.fragmentPaths().associateBy { it.entry }
        entries.keys shouldBe setOf("email.body", "greet.hi")
        entries.getValue("email.body").paths.required shouldBe setOf("code")
        entries.getValue("greet.hi").paths.optional shouldBe setOf("user.name")
    }

    "a clean fragment file reports nothing" {
        mapOf("ns" to mapOf("a" to $$"${x}", "b" to "plain text")).checkFragmentSyntax() shouldBe emptyList()
    }

    "an issue renders as a map with its code and position" {
        val issue = $$"${1 +}".checkTemplateSyntax().first().toJsonMap()
        issue[TISS.code] shouldBe ScriptError.syntaxError.name
        issue[TISS.line] shouldBe 1
        issue[TISS.col] shouldBe 1
    }

    // --- block count (issue #514): "does this text use this prefix's pass at all?" ---------------------

    "blockCount counts a prefix's blocks and ignores its escapes and literals" {
        $$"plain text".analyzeTemplate().blockCount shouldBe 0
        $$"${a} and ${b}".analyzeTemplate().blockCount shouldBe 2
        // A doubled prefix is an escape and a lone one is literal -- neither is a block.
        "100%% off, 50% more".analyzeTemplate('%').blockCount shouldBe 0
        // Counted against the chosen prefix only: a ${...} is plain text to the % pass, and vice versa.
        $$"${a} but %{b}".analyzeTemplate('%').blockCount shouldBe 1
        $$"${a} but %{b}".analyzeTemplate().blockCount shouldBe 1
    }

    "a malformed or unterminated block still counts -- the opener is what is counted" {
        // The point of the count is presence, so a block that opens and then goes wrong is still a block: an
        // audience check that missed it would let exactly the broken cases through.
        $$"${1 +} rest".analyzeTemplate().blockCount shouldBe 1   // opens, expression is malformed
        $$"trailing ${unterminated".analyzeTemplate().blockCount shouldBe 1 // opens, never closes
    }
})
