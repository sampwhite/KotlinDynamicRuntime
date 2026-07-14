package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Covers reference resolution in [parseSchemaTypes] — in particular a `$ref` sitting directly on an array's
 * `items`, which is bound in the resolution pass (like a property `$ref`) rather than expanded during parsing.
 * This is the shape every list endpoint's output uses (`items { ref(...) }`), so without it array elements are
 * never validated.
 */
class SchParserTest : StringSpec({

    val cxt = KdrCxt.mkSimpleCxt("test")

    // A Holder with an array of Bar refs, plus a self-referential Node (its `children` array refs Node).
    fun types(): Map<String, SchType> = parseSchemaTypes(
        schemaDefs(cxt, "core") {
            type("Bar") {
                type = SCT.kObject
                property("n", "A number", required = true) { type = SCT.integer }
            }
            type("Holder") {
                type = SCT.kObject
                property("bars", "A list of bars") {
                    type = SCT.array
                    items { ref("Bar") }
                }
            }
            type("Node") {
                type = SCT.kObject
                property("value", "The value", required = true) { type = SCT.string }
                property("children", "Child nodes") {
                    type = SCT.array
                    items { ref("Node") } // self-reference via items
                }
            }
        },
    )

    "an array's items \$ref is resolved to the element type" {
        val holder = types()["core.Holder"].shouldNotBeNull()
        val bars = holder.properties["bars"].shouldNotBeNull().valueType
        bars.jsonType shouldBe SCT.array
        val bar = bars.itemType.shouldNotBeNull()
        bar.name shouldBe "core.Bar"
        bar.jsonType shouldBe SCT.kObject
        bar.required shouldBe setOf("n")
    }

    "array element contents are validated against the resolved element type" {
        val holder = types()["core.Holder"].shouldNotBeNull()
        // A well-formed element passes.
        validate(holder, mapOf("bars" to listOf(mapOf("n" to 1)))).shouldBeEmpty()
        // A second element missing the required field fails, at the indexed + nested path.
        validate(holder, mapOf("bars" to listOf(mapOf("n" to 1), emptyMap<String, Any?>())))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder
            listOf("bars[1].n" to SchFailCode.missingRequired)
    }

    "coercion reaches into array elements" {
        val holder = types()["core.Holder"].shouldNotBeNull()
        val result = coerceAndValidate(holder, mapOf("bars" to listOf(mapOf("n" to "5"))))
        result.failures.shouldBeEmpty()
        val bars = result.value.shouldNotBeNull().toJsonMap()["bars"] as List<*>
        (bars[0] as Map<*, *>)["n"] shouldBe 5L // the string "5" coerced to the integer element type
    }

    "a self-referential array (items refs its own type) resolves without expanding" {
        val node = types()["core.Node"].shouldNotBeNull()
        val children = node.properties["children"].shouldNotBeNull().valueType
        // The element type is bound back to the very same Node instance -- a safe object-graph cycle.
        children.itemType shouldBe node
        // Validation still descends into the recursive elements.
        validate(
            node,
            mapOf("value" to "root", "children" to listOf(mapOf("value" to "kid", "children" to emptyList<Any?>()))),
        ).shouldBeEmpty()
        validate(node, mapOf("value" to "root", "children" to listOf(emptyMap<String, Any?>())))
            .map { it.path to it.code } shouldContainExactlyInAnyOrder
            listOf("children[0].value" to SchFailCode.missingRequired)
    }

    $$"an array items $ref to an unknown type throws KdrException" {
        shouldThrow<KdrException> {
            parseSchemaTypes(
                schemaDefs(cxt, "core") {
                    type("Holder") {
                        type = SCT.kObject
                        property("xs", "Items") {
                            type = SCT.array
                            items { ref("Nope") }
                        }
                    }
                },
            )
        }
    }
})
