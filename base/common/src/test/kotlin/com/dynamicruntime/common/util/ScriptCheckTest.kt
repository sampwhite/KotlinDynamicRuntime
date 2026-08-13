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

    "a clean fragment file reports nothing" {
        mapOf("ns" to mapOf("a" to $$"${x}", "b" to "plain text")).checkFragmentSyntax() shouldBe emptyList()
    }

    "an issue renders as a map with its code and position" {
        val issue = $$"${1 +}".checkTemplateSyntax().first().toJsonMap()
        issue[TISS.code] shouldBe ScriptError.syntaxError.name
        issue[TISS.line] shouldBe 1
        issue[TISS.col] shouldBe 1
    }
})
