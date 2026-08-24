package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import com.dynamicruntime.common.exception.KdrException

/**
 * Sourced choice lists: the render-time resolution and the checks that refuse a bad one at boot (issue #413).
 *
 * The weight here is on **sharing**, not on the substitution. Producing the right list is the obvious half and
 * fails loudly; leaving the compiled store untouched while doing it is the half that fails silently -- a node
 * would serve one caller's clients to everybody afterwards, and the only symptom would be a correct-looking
 * dropdown. So the assertions are mostly about which objects came back, and the first thing the substitution
 * test does is check that the input was not the thing that changed.
 */
class SchOptionsSourceTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("optionsSource")

    // Two providers, so a test can tell "the right one ran" from "a provider ran". The second reports the
    // property name it was handed, which is the only way to see that the walk names a property by its key.
    val providers = mapOf<String, SchOptionsProvider>(
        "colours" to { _, _ -> listOf(SchOption("red", "Red"), SchOption("green", "Green")) },
        "echoName" to { _, name -> listOf(SchOption(name, "Field $name")) },
    )

    fun optionsAt(node: Map<String, Any?>, property: String): List<Map<String, Any?>> =
        node[SCH.properties].toJsonMapOrEmpty()[property].toJsonMapOrEmpty()[SCH.options]
            .let { it as List<*> }.map { it.toJsonMapOrEmpty() }

    "a sourced list becomes an ordinary options list, and the source key does not travel" {
        val type = linkedMapOf<String, Any?>(
            SCH.type to SCT.kObject,
            SCH.properties to linkedMapOf<String, Any?>(
                "colour" to linkedMapOf<String, Any?>(
                    SCH.description to "Pick one.",
                    SCH.optionsSource to "colours",
                    SCH.dComment to "after",
                ),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)

        optionsAt(out, "colour").map { it[SCH.value] } shouldBe listOf("red", "green")
        optionsAt(out, "colour").map { it[SCH.label] } shouldBe listOf("Red", "Green")
        // The id is consumed: a reader gets a choice list and no second way to have one.
        out[SCH.properties].toJsonMapOrEmpty()["colour"].toJsonMapOrEmpty().containsKey(SCH.optionsSource) shouldBe false
        // ...in the position the id held, so two callers differ in values and not in key order.
        out[SCH.properties].toJsonMapOrEmpty()["colour"].toJsonMapOrEmpty().keys.toList() shouldBe
            listOf(SCH.description, SCH.options, SCH.dComment)
    }

    "the source document is never touched" {
        val field = linkedMapOf<String, Any?>(SCH.optionsSource to "colours")
        val properties = linkedMapOf<String, Any?>("colour" to field)
        val type = linkedMapOf<String, Any?>(SCH.type to SCT.kObject, SCH.properties to properties)

        resolveOptionsSources(cxt, type, providers)

        // The whole argument for copy-on-write. Were this to fail, a node would answer every later caller --
        // in every client -- with whichever caller happened to arrive first, and look right doing it.
        field.keys.toList() shouldBe listOf(SCH.optionsSource)
        properties["colour"] shouldBeSameInstanceAs field
        type[SCH.properties] shouldBeSameInstanceAs properties
    }

    "a document with nothing to resolve comes back as the same object" {
        val type = linkedMapOf<String, Any?>(
            SCH.type to SCT.kObject,
            SCH.properties to linkedMapOf<String, Any?>(
                "colour" to linkedMapOf<String, Any?>(SCH.options to listOf("red")),
            ),
        )
        // Identity, not equality: the catalog resolves every response, and the overwhelming majority of nodes
        // have no sourced list. Copying them anyway would be a whole-document clone per request.
        resolveOptionsSources(cxt, type, providers) shouldBeSameInstanceAs type
    }

    "only the branch containing a source is copied" {
        val untouched = linkedMapOf<String, Any?>(SCH.type to SCT.string, SCH.description to "Plain.")
        val type = linkedMapOf<String, Any?>(
            SCH.properties to linkedMapOf<String, Any?>(
                "plain" to untouched,
                "colour" to linkedMapOf<String, Any?>(SCH.optionsSource to "colours"),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)

        out shouldNotBeSameInstanceAs type
        out[SCH.properties].toJsonMapOrEmpty()["plain"] shouldBeSameInstanceAs untouched
    }

    "a provider is told the property's own name" {
        val type = linkedMapOf<String, Any?>(
            SCH.properties to linkedMapOf<String, Any?>(
                "client" to linkedMapOf<String, Any?>(SCH.optionsSource to "echoName"),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)
        optionsAt(out, "client").single()[SCH.value] shouldBe "client"
    }

    "an array's items inherit the enclosing property's name" {
        val type = linkedMapOf<String, Any?>(
            SCH.properties to linkedMapOf<String, Any?>(
                "tags" to linkedMapOf<String, Any?>(
                    SCH.type to SCT.array,
                    SCH.items to linkedMapOf<String, Any?>(SCH.optionsSource to "echoName"),
                ),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)
        val items = out[SCH.properties].toJsonMapOrEmpty()["tags"].toJsonMapOrEmpty()[SCH.items].toJsonMapOrEmpty()
        // A multi-select's choices belong to the field, not to a nameless element, so "tags" is the answer a
        // provider needs -- there is no better name available and an empty one would be useless.
        (items[SCH.options] as List<*>).map { it.toJsonMapOrEmpty()[SCH.value] } shouldBe listOf("tags")
    }

    "an unregistered id faults rather than resolving to nothing" {
        val type = linkedMapOf<String, Any?>(
            SCH.properties to linkedMapOf<String, Any?>(
                "colour" to linkedMapOf<String, Any?>(SCH.optionsSource to "nope"),
            ),
        )
        // A booted node cannot reach this (checkInit refuses first), but a hand-built store has no such pass,
        // and an empty choice list is the failure that reads as a working page.
        shouldThrow<KdrException> { resolveOptionsSources(cxt, type, providers) }
            .fullMessage() shouldContain "nope"
    }

    // ---- the boot checks -------------------------------------------------------------------------------

    "a clean document reports no problems" {
        val type = mapOf<String, Any?>(
            SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to "colours")),
        )
        optionsSourceProblems("Type 'x'", type, providers).shouldBeEmpty()
    }

    "an unregistered id is a boot problem, named with its property" {
        val type = mapOf<String, Any?>(
            SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to "missing")),
        )
        val problems = optionsSourceProblems("Type 'core.Paint'", type, providers)
        problems shouldHaveSize 1
        problems.single() shouldContain "core.Paint"
        problems.single() shouldContain "colour"
        problems.single() shouldContain "missing"
    }

    "declaring both a list and a source is a boot problem" {
        val type = mapOf<String, Any?>(
            SCH.properties to mapOf(
                "colour" to mapOf(SCH.optionsSource to "colours", SCH.options to listOf("red")),
            ),
        )
        // Refused rather than merged: a merge would need a rule, and an answer to whether the written half
        // still binds. Neither has a use yet, and permitting it now would settle both by accident.
        optionsSourceProblems("Type 'x'", type, providers).single() shouldContain "one place or the other"
    }

    "problems are found inside nested structure, not only at the top" {
        val type = mapOf<String, Any?>(
            SCH.properties to mapOf(
                "addresses" to mapOf(
                    SCH.type to SCT.array,
                    SCH.items to mapOf(
                        SCH.properties to mapOf("country" to mapOf(SCH.optionsSource to "missing")),
                    ),
                ),
            ),
        )
        // Through an array's `items`: a choice list on the element of a repeating group is an ordinary thing
        // to declare, and a check that stopped at the top would pass it.
        optionsSourceProblems("Type 'x'", type, providers).single() shouldContain "country"
    }

    "problems are found inside a union's branches" {
        val type = mapOf<String, Any?>(
            SCH.oneOf to listOf(
                mapOf(SCH.properties to mapOf("kind" to mapOf(SCH.type to SCT.string))),
                mapOf(SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to "missing"))),
            ),
        )
        // A **list** of schemas, which is a shape the walk meets nowhere else -- and the shape every gedra
        // entry type is, so a check blind to it would be blind to most of the document that matters.
        optionsSourceProblems("Type 'x'", type, providers).single() shouldContain "colour"
    }

    "a union branch resolves like anything else" {
        val branch = linkedMapOf<String, Any?>(SCH.properties to linkedMapOf<String, Any?>("k" to linkedMapOf<String, Any?>()))
        val type = linkedMapOf<String, Any?>(
            SCH.oneOf to listOf(
                branch,
                linkedMapOf<String, Any?>(
                    SCH.properties to linkedMapOf<String, Any?>(
                        "colour" to linkedMapOf<String, Any?>(SCH.optionsSource to "colours"),
                    ),
                ),
            ),
        )
        val out = resolveOptionsSources(cxt, type, providers)
        val branches = out[SCH.oneOf] as List<*>
        // The untouched branch is shared; only the one holding the source was rebuilt.
        branches[0] shouldBeSameInstanceAs branch
        optionsAt(branches[1].toJsonMapOrEmpty(), "colour").map { it[SCH.value] } shouldBe listOf("red", "green")
    }

    "a non-string source is refused rather than ignored" {
        val type = mapOf<String, Any?>(SCH.properties to mapOf("colour" to mapOf(SCH.optionsSource to 7)))
        optionsSourceProblems("Type 'x'", type, providers).single() shouldContain "not an id"
    }
})
