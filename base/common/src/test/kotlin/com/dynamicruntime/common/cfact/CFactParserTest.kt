package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The cfact expression language (issue #454): grammar, refusals, and evaluation.
 *
 * Exhaustive because it is cheap to be -- the whole thing is pure, so every case is a string in and a boolean
 * out. It is also the layer where a mistake is least visible later: an expression that parses but means
 * something else produces a UI that is merely *wrong*, with nothing failing.
 */
class CFactParserTest : StringSpec({

    val allowed = setOf("app", "edge", "loggedIn", "isAdmin", "ab", "cd", "ef", "gh")
    fun parse(expr: String) = CFactParser.parse(expr, allowed)
    fun matches(expr: String, vararg present: String) = parse(expr).matches(present.toSet())

    "a single cfact matches when present" {
        matches("app", "app") shouldBe true
        matches("app", "edge") shouldBe false
        matches("app") shouldBe false
    }

    "commas mean every one" {
        matches("app,loggedIn", "app", "loggedIn") shouldBe true
        matches("app,loggedIn", "app") shouldBe false
        matches("app,loggedIn,isAdmin", "app", "loggedIn", "isAdmin") shouldBe true
    }

    "pipes mean any one" {
        matches("app|edge", "edge") shouldBe true
        matches("app|edge", "app") shouldBe true
        matches("app|edge", "loggedIn") shouldBe false
    }

    "a tilde means absent" {
        matches("~edge", "app") shouldBe true
        matches("~edge", "edge") shouldBe false
        // Still atomic, so it joins without parentheses of its own.
        matches("~edge,loggedIn", "app", "loggedIn") shouldBe true
        matches("~edge,loggedIn", "edge", "loggedIn") shouldBe false
    }

    "parentheses nest, and a group can be negated whole" {
        matches("(ab,cd)|(~ef,gh)", "ab", "cd") shouldBe true
        matches("(ab,cd)|(~ef,gh)", "gh") shouldBe true
        matches("(ab,cd)|(~ef,gh)", "ef", "gh") shouldBe false
        matches("(ab,cd)|(~ef,gh)", "ab") shouldBe false

        matches("~((ab,cd)|(~ef,gh))", "ab", "cd") shouldBe false
        matches("~((ab,cd)|(~ef,gh))", "ab") shouldBe true
    }

    /**
     * The rule that keeps precedence out of folklore. `a,b|c` has two defensible readings and a reader cannot
     * tell which was meant, so it is refused rather than guessed -- and the message says how to write either.
     */
    "mixing operators at one level is refused, and the message says how to fix it" {
        val e = shouldThrow<KdrException> { parse("ab,cd|ef") }
        e.fullMessage() shouldContain "mixed without parentheses"
        e.fullMessage() shouldContain "("
        // Both parenthesized forms are accepted, and they mean different things.
        matches("(ab,cd)|ef", "ef") shouldBe true
        matches("ab,(cd|ef)", "ef") shouldBe false
        matches("ab,(cd|ef)", "ab", "ef") shouldBe true
    }

    /**
     * The check the whole registry exists for. An unknown name evaluates to "absent", so a mistyped negation
     * would be silently **always true** and the guarded item would show to everyone. Refusing at parse turns
     * that into a startup failure.
     */
    "an unregistered name is refused, and the message names it" {
        val e = shouldThrow<KdrException> { parse("admn") }
        e.fullMessage() shouldContain "admn"
        e.fullMessage() shouldContain "not a registered cfact"
        // Under negation especially: this is the one that fails open when unchecked.
        val neg = shouldThrow<KdrException> { parse("~admn") }
        neg.fullMessage() shouldContain "admn"
    }

    "the registered names are listed, since the usual cause is a typo" {
        shouldThrow<KdrException> { parse("isAdmim") }.fullMessage() shouldContain "isAdmin"
    }

    "malformed expressions are refused rather than half-parsed" {
        shouldThrow<KdrException> { parse("(app") }.fullMessage() shouldContain "missing"
        shouldThrow<KdrException> { parse("app)") }.fullMessage() shouldContain "unexpected"
        shouldThrow<KdrException> { parse("app,") }.fullMessage() shouldContain "operand is missing"
        shouldThrow<KdrException> { parse(",app") }.fullMessage() shouldContain "cfact name was expected"
        shouldThrow<KdrException> { parse("~") }.fullMessage() shouldContain "operand is missing"
    }

    /**
     * The literals, marked with `#` so a reader can see they are not names anyone registered -- and so the
     * cfact namespace keeps no reserved words: a real cfact may still be called `never`.
     */
    "#never matches nothing and #always matches everything" {
        matches("#never") shouldBe false
        matches("#never", "app", "edge", "loggedIn") shouldBe false
        matches("#always") shouldBe true
        matches("#always", "app") shouldBe true
    }

    /**
     * `#never` is how an overlay removes an item, and `#always` is how a later overlay puts it back -- which
     * is why both are spelled rather than only omittable. Merging never has to learn to delete anything.
     */
    "the literals compose like any other operand" {
        matches("app,#never", "app") shouldBe false
        matches("app|#always") shouldBe true
        matches("~#never") shouldBe true
        matches("(app,#never)|loggedIn", "loggedIn") shouldBe true
    }

    "a name is still legal where a literal would be, since the sigil separates them" {
        // `never` without the sigil is an ordinary (here unregistered) name, not the literal.
        shouldThrow<KdrException> { parse("never") }.fullMessage() shouldContain "not a registered cfact"
        CFactParser.parse("never", allowed + "never").matches(setOf("never")) shouldBe true
    }

    // Reported as a bad literal rather than a missing registration: the sigil already said which it is, so
    // pointing at the registry would send the reader looking for the wrong thing.
    "a mistyped literal says so, rather than blaming the registry" {
        val e = shouldThrow<KdrException> { parse("#nevr") }
        e.fullMessage() shouldContain "not a known literal"
        e.fullMessage() shouldContain "#never"
    }

    /**
     * Absence means always; blankness is refused. A missing field, a typo, or an emptied overlay value all
     * produce an empty string, and reading those as "show to everyone" fails in the permissive direction.
     */
    "an omitted expression matches everything, but a blank one is refused" {
        parseCFactOrAlways(null, allowed).matches(emptySet()) shouldBe true
        val e = shouldThrow<KdrException> { parse("") }
        e.fullMessage() shouldContain "blank"
        e.fullMessage() shouldContain CFACT.alwaysName
        shouldThrow<KdrException> { parse("   ") }
        CFACT.always.matches(emptySet()) shouldBe true
        CFACT.never.matches(setOf("app")) shouldBe false
    }

    "spaces around operators are tolerated" {
        matches("app , loggedIn", "app", "loggedIn") shouldBe true
        matches(" ( ab , cd ) | ef ", "ef") shouldBe true
    }

    // Rendering exists for diagnostics; a round trip is the cheapest check that the tree is the shape meant.
    "a parsed expression renders back to an equivalent form" {
        parse("app").render() shouldBe "app"
        parse("~edge").render() shouldBe "~edge"
        parse("app,loggedIn").render() shouldBe "app,loggedIn"
        parse("(ab,cd)|(~ef,gh)").render() shouldBe "(ab,cd)|(~ef,gh)"
        parse("#never").render() shouldBe "#never"
        parse("#always").render() shouldBe "#always"
        parse("ab,(cd|ef)").render() shouldBe "ab,(cd|ef)"
    }

    // referencedNames feeds the g-visibleWhen boot check (issue #564): every atom, through the operators,
    // and nothing for the literals, which name no cfact.
    "referencedNames lists the atoms an expression names, unfolding operators and ignoring literals" {
        parse("app").referencedNames() shouldBe setOf("app")
        parse("~edge").referencedNames() shouldBe setOf("edge")
        parse("(ab,cd)|(~ef,gh)").referencedNames() shouldBe setOf("ab", "cd", "ef", "gh")
        parse("#always").referencedNames() shouldBe emptySet()
        parse("#never").referencedNames() shouldBe emptySet()
        CFACT.always.referencedNames() shouldBe emptySet()
    }
})
