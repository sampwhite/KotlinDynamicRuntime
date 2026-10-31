package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A type's `g-onWrite` functions (issue #1501), resolved against the registered kinds and run: `computeValue` storing a
 * script's value in a derived property, every way a usage is refused for the type it is on, and the faults that clear
 * the property rather than fail the write. Pure: parsed types and a plain context, no node.
 */
class SchFunctionsTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("schFunctionsTest")
    val creations = mapOf(CVF.fn to ComputeValueCreation)

    /** A data type with two inputs, derived targets of several kinds (one its key), and [usages] as its `g-onWrite`. */
    fun dataType(vararg usages: Map<String, Any?>): SchType = parseSchemaTypes(
        schemaDefs(cxt, "t") {
            type("Data") {
                type = SCT.kObject
                property("a", "An input.") { type = SCT.number }
                property("b", "An input.") { type = SCT.number }
                property("nested", "An object input.") {
                    type = SCT.kObject
                    property("x", "A nested input.") { type = SCT.number }
                }
                property("total", "Computed.") {
                    type = SCT.number
                    derived = true
                }
                property("count", "Computed, a whole number.") {
                    type = SCT.integer
                    derived = true
                }
                property("label", "Computed text.") { derived = true }
                property("code", "Computed, but the key.") { derived = true }
                data[SCH.primaryKey] = listOf("code")
                property("gated", "Computed, but gated.") {
                    type = SCT.number
                    derived = true
                    visibleWhen = "kdr:hasAdminLevel"
                }
                onWrite(*usages)
            }
        },
    ).getValue("t.Data")

    fun functions(type: SchType) = resolveWriteFunctions(cxt, creations, type).mapNotNull { it.function }
    fun problems(type: SchType) = resolveWriteFunctions(cxt, creations, type).mapNotNull { it.problem }

    "computeValue stores the script's value, coerced to the target's type" {
        val type = dataType(computeValue("total", "a * b"), computeValue("count", "b + 1"))
        val out = runWriteFunctions(cxt, functions(type), mapOf("a" to 12.5, "b" to 4.0))
        out["total"] shouldBe 50.0
        // A whole number computed from decimals is stored as one; a fraction would be refused and cleared.
        out["count"] shouldBe 5L
        runWriteFunctions(cxt, functions(type), mapOf("b" to 4.5)).containsKey("count") shouldBe false
        // Unless the script rounds it, as it says.
        runWriteFunctions(cxt, functions(dataType(computeValue("count", "round(b * 1.5)"))), mapOf("b" to 4.5))["count"] shouldBe 7L
    }

    "functions run in priority order, each seeing what the ones before it wrote" {
        val type = dataType(computeValue("count", "total + 1", priority = 2), computeValue("total", "a * b", priority = 1))
        runWriteFunctions(cxt, functions(type), mapOf("a" to 2.0, "b" to 3.0))["count"] shouldBe 7L
    }

    "a value that cannot be computed clears the target rather than failing the write" {
        val type = dataType(computeValue("total", "a * b"), computeValue("count", "a / b"))
        // An input not filled in yet: the total is cleared, whatever was stored.
        runWriteFunctions(cxt, functions(type), mapOf("a" to 2.0, "total" to 99.0))["total"].shouldBeNull()
        // A division by zero clears too.
        runWriteFunctions(cxt, functions(type), mapOf("a" to 2.0, "b" to 0.0, "count" to 3L)).containsKey("count") shouldBe false
        // A null result clears.
        val nullable = dataType(computeValue("total", "a ?: null"))
        runWriteFunctions(cxt, functions(nullable), mapOf("total" to 1.0)).containsKey("total") shouldBe false
    }

    "a value the target's type does not take is cleared" {
        // Text does not coerce by default, so a number computed into it is refused and the target left empty.
        val type = dataType(computeValue("label", "a * 2"))
        runWriteFunctions(cxt, functions(type), mapOf("a" to 2.0, "label" to "old")).containsKey("label") shouldBe false
    }

    "a usage that cannot run on its type is refused, and the type's others still resolve" {
        fun refusal(usage: Map<String, Any?>): String {
            val type = dataType(usage, computeValue("total", "a * b"))
            functions(type) shouldHaveSize 1
            return problems(type).single()
        }
        refusal(mapOf(SFU.fn to "noSuchKind")) shouldContain "no component registered"
        refusal(mapOf(CVF.target to "count")) shouldContain "names no kind"
        refusal(computeValue("missing", "a")) shouldContain "declares no property 'missing'"
        refusal(computeValue("a", "b")) shouldContain "is not 'g-derived'"
        refusal(computeValue("gated", "a")) shouldContain "gated by 'g-visibleWhen'"
        refusal(computeValue("code", "a")) shouldContain "in the type's 'g-primaryKey'"
        refusal(computeValue("count", "a *")) shouldContain "does not parse"
        refusal(computeValue("count", """@t("x.y")""")) shouldContain "pulls copy"
        refusal(computeValue("count", "c * 2")) shouldContain "reads 'c', which the type does not declare"
        refusal(computeValue("count", "nested.y")) shouldContain "reads 'nested.y'"
        refusal(computeValue("count", "count + 1")) shouldContain "the value it computes"
        refusal(mapOf(SFU.fn to CVF.fn, CVF.target to "count")) shouldContain "settings are invalid"
    }

    "a nested property the type declares may be read" {
        functions(dataType(computeValue("count", "nested.x * 2"))).single().shouldNotBeNull()
    }

    "a script may read what an earlier function computed, but not what a later one does" {
        // Listed first, the count would read a total not yet computed: refused, and the total still runs.
        val early = dataType(computeValue("count", "total + 1"), computeValue("total", "a * b"))
        functions(early).map { it.writes } shouldBe listOf(setOf("total"))
        problems(early).single() shouldContain "which a function after it computes"
        // Two reading each other would change the data on every write; whichever runs first is refused.
        val cycle = dataType(computeValue("total", "count + 1"), computeValue("count", "total + 1"))
        functions(cycle).map { it.writes } shouldBe listOf(setOf("count"))
        problems(cycle).single() shouldContain "reads 'count', which a function after it computes"
    }

    "two functions writing one property: the later is refused" {
        val type = dataType(computeValue("total", "a"), computeValue("total", "b"))
        functions(type) shouldHaveSize 1
        problems(type).single() shouldContain "already writes"
    }
})
