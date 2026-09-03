package com.dynamicruntime.common.schema

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * `g-visibleWhen` (issue #545): per-caller field visibility resolved at catalog render. These cover the walk in
 * isolation -- the drop, the `required` pruning, the keyword strip, and the copy-on-write identity -- with a
 * plain boolean stand-in for the cfact test the catalog supplies.
 */
class SchVisibleWhenTest : StringSpec({

    // An object schema with a gated `secret` field beside an ungated `name`, `secret` also named in `required`.
    fun schema(): Map<String, Any?> = linkedMapOf(
        SCH.type to SCT.kObject,
        SCH.properties to linkedMapOf(
            "name" to linkedMapOf(SCH.type to SCT.string),
            "secret" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "hasAdminLevel"),
        ),
        SCH.required to listOf("name", "secret"),
    )

    @Suppress("UNCHECKED_CAST")
    fun props(node: Map<String, Any?>) = node[SCH.properties] as Map<String, Any?>

    "a caller who fails the gate loses the field and its required entry" {
        val out = resolveVisibleWhen(schema()) { false }
        props(out).containsKey("secret") shouldBe false
        props(out).containsKey("name") shouldBe true
        // Pruned from required, so the schema does not demand a field it no longer offers.
        out[SCH.required] shouldBe listOf("name")
    }

    "a caller who passes the gate keeps the field, but the keyword is stripped" {
        val out = resolveVisibleWhen(schema()) { true }
        props(out).containsKey("secret") shouldBe true
        @Suppress("UNCHECKED_CAST")
        val secret = props(out)["secret"] as Map<String, Any?>
        secret.containsKey(SCH.visibleWhen) shouldBe false
        // required is untouched when nothing is dropped.
        out[SCH.required] shouldBe listOf("name", "secret")
    }

    "the expression drives the decision, so different callers get different documents" {
        // A gate naming a fact the predicate says is present keeps the field; absent drops it.
        val present = resolveVisibleWhen(schema()) { it == "hasAdminLevel" }
        val absent = resolveVisibleWhen(schema()) { it == "somethingElse" }
        props(present).containsKey("secret") shouldBe true
        props(absent).containsKey("secret") shouldBe false
    }

    "a document with no gated field comes back as the identical object (copy-on-write)" {
        val plain: Map<String, Any?> = linkedMapOf(
            SCH.type to SCT.kObject,
            SCH.properties to linkedMapOf("name" to linkedMapOf(SCH.type to SCT.string)),
            SCH.required to listOf("name"),
        )
        // Never evaluated, because nothing is gated.
        val out = resolveVisibleWhen(plain) { error("gate should not be consulted") }
        (out === plain) shouldBe true
    }

    "a passing gate still produces a new object, since the keyword was removed" {
        val original = schema()
        val out = resolveVisibleWhen(original) { true }
        (out === original) shouldBe false
    }

    "gating reaches a nested object's properties" {
        val nested: Map<String, Any?> = linkedMapOf(
            SCH.type to SCT.kObject,
            SCH.properties to linkedMapOf(
                "inner" to linkedMapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to linkedMapOf(
                        "deep" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "hasAdminLevel"),
                    ),
                    SCH.required to listOf("deep"),
                ),
            ),
        )
        val out = resolveVisibleWhen(nested) { false }
        @Suppress("UNCHECKED_CAST")
        val inner = props(out)["inner"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val innerProps = inner[SCH.properties] as Map<String, Any?>
        innerProps.containsKey("deep") shouldBe false
        inner[SCH.required] shouldBe emptyList<String>()
    }

    "boot-check helper flags an expression that does not parse" {
        val node: Map<String, Any?> = linkedMapOf(
            SCH.properties to linkedMapOf(
                "f" to linkedMapOf(SCH.type to SCT.string, SCH.visibleWhen to "a &&& b"),
            ),
        )
        // The parse lambda throws on bad syntax, exactly as CFactRegistry.parse does.
        val problems = visibleWhenProblems("here", node) {
            if (it.contains("&&&")) throw com.dynamicruntime.common.exception.KdrException.mkInput("bad syntax")
        }
        problems.size shouldBe 1
        problems.single() shouldNotBe ""
    }
})
