package com.dynamicruntime.common.uiblock

import com.dynamicruntime.common.cfact.parseCFactOrAlways
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Selectors in the UiBlock resolver (issue #788): an object carrying `select` stands for the first of its branches
 * whose condition the caller's cfacts satisfy -- resolved by the same walk that drops a plain conditioned object,
 * so it works anywhere in a UiBlock or a workflow view.
 */
class UiBlockSelectorTest : StringSpec({
    val names = setOf("approved", "isCta", "reviewer")
    fun resolve(node: Map<String, Any?>, present: Set<String>) =
        filterByCFacts(node, present) { parseCFactOrAlways(it, names) }

    val display = mapOf(
        UIB.select to listOf(
            mapOf(UIB.cfactExpression to "approved", "text" to "Approved."),
            mapOf(UIB.cfactExpression to "~isCta", "text" to "Not yet.", "disabled" to true),
            mapOf(UIB.cfactExpression to "reviewer", "mode" to "default"),
            mapOf("text" to "Wait for a reviewer."),
        ),
    )
    fun shown(present: Set<String>) = resolve(mapOf("display" to display), present)["display"]

    "the first branch that applies wins, in declaration order" {
        // Approved *and* not the CTA: the earlier branch wins, which is why the order matters.
        shown(setOf("approved")) shouldBe mapOf("text" to "Approved.")
        shown(emptySet()) shouldBe mapOf("text" to "Not yet.", "disabled" to true)
        shown(setOf("isCta", "reviewer")) shouldBe mapOf("mode" to "default")
    }

    "an unguarded last branch is the default, and conditions never travel" {
        shown(setOf("isCta")) shouldBe mapOf("text" to "Wait for a reviewer.")
    }

    "a selector none of whose branches applies is absent, from a field and from an array" {
        val guarded = mapOf(UIB.select to listOf(mapOf(UIB.cfactExpression to "reviewer", "text" to "Yours.")))
        resolve(mapOf("display" to guarded, "keep" to 1), emptySet()) shouldBe mapOf("keep" to 1)
        resolve(mapOf("items" to listOf(guarded, mapOf("id" to "plain"))), emptySet()) shouldBe
            mapOf("items" to listOf(mapOf("id" to "plain")))
    }

    "a chosen branch is itself filtered, and may be a selector in turn" {
        val nested = mapOf(
            UIB.select to listOf(
                mapOf(
                    UIB.cfactExpression to "isCta",
                    UIB.select to listOf(
                        mapOf(UIB.cfactExpression to "reviewer", "text" to "Approve it."),
                        mapOf("text" to "Waiting."),
                    ),
                ),
                mapOf("text" to "Later."),
            ),
        )
        resolve(mapOf("d" to nested), setOf("isCta", "reviewer"))["d"] shouldBe mapOf("text" to "Approve it.")
        resolve(mapOf("d" to nested), setOf("isCta"))["d"] shouldBe mapOf("text" to "Waiting.")
        resolve(mapOf("d" to nested), emptySet())["d"] shouldBe mapOf("text" to "Later.")
        // Inside the chosen branch, a plain conditioned child is still dropped when its condition fails.
        val withChild = mapOf(UIB.select to listOf(mapOf("hint" to mapOf(UIB.cfactExpression to "reviewer", "t" to "x"), "text" to "T")))
        resolve(mapOf("d" to withChild), emptySet())["d"] shouldBe mapOf("text" to "T")
    }

    "every branch condition is found by the boot check's walk" {
        collectExpressions(mapOf("display" to display)).toSet() shouldBe setOf("approved", "~isCta", "reviewer")
    }
})
