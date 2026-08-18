package com.dynamicruntime.common.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * What a client's alteration of an existing type may do (issue #356).
 *
 * An altered type keeps its name, so every `$ref` resolves to the client's version -- which is what makes the
 * rule load-bearing rather than tidy. The permitted set is deliberately closed at three, so most of this file
 * is refusals; each is only reachable here, since a boot that refuses never serves a request.
 */
class SchNarrowingTest : StringSpec({

    /** A closed object: two properties, one of them required, one offering a choice list. */
    val base: Map<String, Any?> = linkedMapOf(
        SCH.type to SCT.kObject,
        SCH.properties to linkedMapOf(
            "name" to linkedMapOf(SCH.type to SCT.string, SCH.maxLength to 128),
            "colour" to linkedMapOf(
                SCH.type to SCT.string,
                SCH.options to listOf("red", "green", "blue"),
            ),
            "note" to linkedMapOf(SCH.type to SCT.string),
        ),
        SCH.required to listOf("name"),
    )

    fun problems(overlay: Map<String, Any?>): List<String> = narrowingProblems("core.Thing", base, overlay)

    /**
     * An overlay's property block, with the keys this client keeps.
     *
     * Every property to be kept has to be named, because mentioning keys **is** how a client reduces the set
     * -- so altering one property is a statement about the whole set, not an edit to one entry. Spelled out
     * in a helper here so the tests below read as what they are testing rather than as that rule.
     */
    fun props(vararg altered: Pair<String, Map<String, Any?>>): Map<String, Any?> {
        val kept = linkedMapOf<String, Any?>("name" to emptyMap<String, Any?>(),
            "colour" to emptyMap<String, Any?>(), "note" to emptyMap<String, Any?>())
        altered.forEach { (name, body) -> kept[name] = body }
        return mapOf(SCH.properties to kept)
    }

    // --- the three ways to narrow ----------------------------------------------

    "mentioning fewer properties is narrowing" {
        problems(mapOf(SCH.properties to mapOf("name" to emptyMap<String, Any?>()))).shouldBeEmpty()
    }

    "shortening a choice list is narrowing" {
        problems(props("colour" to mapOf(SCH.options to listOf("red", "green")))).shouldBeEmpty()
    }

    // The second half of that rule: an attribute that offered no choices may be given some.
    "applying a choice list where there was none is narrowing" {
        problems(props("note" to mapOf(SCH.options to listOf("a", "b")))).shouldBeEmpty()
    }

    "requiring more is narrowing" {
        problems(mapOf(SCH.required to listOf("name", "colour"))).shouldBeEmpty()
    }

    // --- what a client may change freely ---------------------------------------

    "presentation is the client's own business" {
        problems(
            props("name" to mapOf(SCH.description to "What we call it.", SCH.errors to mapOf("default" to "Nope.")))
                + (SCH.description to "Ours."),
        ).shouldBeEmpty()
    }

    // Only the value takes part in validation, which is what lets a client relabel every choice while
    // narrowing none of them.
    "relabelling a choice is presentation, not widening" {
        problems(
            props(
                "colour" to mapOf(
                    SCH.options to listOf(
                        mapOf(SCH.value to "red", SCH.label to "Crimson"),
                        mapOf(SCH.value to "green", SCH.label to "Emerald"),
                    ),
                ),
            ),
        ).shouldBeEmpty()
    }

    // --- what is refused --------------------------------------------------------

    "adding a property is refused, with the alternative named" {
        val found = problems(mapOf(SCH.properties to mapOf("name" to emptyMap<String, Any?>(), "extra" to emptyMap<String, Any?>())))
        found.size shouldBe 1
        found[0] shouldContain "'extra'"
        found[0] shouldContain "extending the type"
    }

    "adding a choice is refused" {
        val found = problems(props("colour" to mapOf(SCH.options to listOf("red", "purple"))))
        found.size shouldBe 1
        found[0] shouldContain "'purple'"
    }

    "dropping a requirement is refused" {
        val found = problems(mapOf(SCH.required to emptyList<String>()))
        found.size shouldBe 1
        found[0] shouldContain "no longer requires 'name'"
    }

    // The case that reads as narrowing and is not: removing a property rejects data that carries it, and
    // accepts data that omits it -- which the global type rejected. What this client stored would then be
    // invalid to everybody else.
    "dropping a property the type requires is refused, because it widens" {
        val found = problems(mapOf(SCH.properties to mapOf("colour" to emptyMap<String, Any?>())))
        found.size shouldBe 1
        found[0] shouldContain "drops 'name'"
        found[0] shouldContain "invalid to everybody else"
    }

    "any other validating keyword is refused" {
        problems(mapOf(SCH.additionalProperties to true)).size shouldBe 1
        problems(mapOf(SCH.properties to mapOf("name" to mapOf(SCH.maxLength to 40)))).size shouldBe 1
        problems(mapOf(SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.integer)))).size shouldBe 1
        problems(mapOf(SCH.default to "x")).size shouldBe 1
    }

    // Even a narrowing one, because the list is closed on purpose: a refusal is cheap to relax and a
    // wrongly-permitted widening reaches storage.
    "a tighter bound is refused too, and says what to do instead" {
        val found = problems(mapOf(SCH.properties to mapOf("name" to mapOf(SCH.maxLength to 40))))
        found[0] shouldContain "'${SCH.maxLength}'"
        found[0] shouldContain "Extend the type instead"
    }

    "problems are reported together rather than one per boot" {
        problems(
            mapOf(
                SCH.required to emptyList<String>(),
                SCH.properties to mapOf(
                    "name" to mapOf(SCH.maxLength to 40),
                    "extra" to emptyMap<String, Any?>(),
                ),
            ),
        ).size shouldBe 3
    }
})
