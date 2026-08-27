package com.dynamicruntime.common.uiblock

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * How UiBlock layers merge (issue #457): precedence, which arrays merge and which are replaced, and the
 * ordering.
 *
 * Pure -- layers in, one map out. The assertions worth reading are the ones about **arrays**, because that is
 * where a merge can be wrong without failing: an item silently replaced, an item silently dropped, or two
 * items in an order that differs between machines.
 */
class UiBlockMergeTest : StringSpec({

    val menu = "menu"
    val byId = mapOf("items" to "id")

    fun base(vararg items: Pair<String, Int>) = uiBlock(menu, origin = "core", arrayKeys = byId) {
        set("title", "Menu")
        items("items", startOrder = 0, step = 0) {
            for ((id, order) in items) item { set("id", id); set(UIB.displayOrder, order) }
        }
    }

    fun ids(block: MergedUiBlock): List<String?> =
        (block.content["items"] as List<*>).map { (it as Map<*, *>)["id"] as String? }

    fun overlay(client: String? = null, build: UiBlockBuilder.() -> Unit) =
        uiBlockOverlay(menu, origin = "test", client = client, build = build)

    // --- what merges, and what replaces ---------------------------------------------------------------

    "an overlay changes one item, and renumbers nothing" {
        val over = overlay { items("items") { item { set("id", "home"); set("label", "Start") } } }
        val merged = mergeUiBlock(menu, listOf(base("home" to 100, "forms" to 200), over), client = null)
        ids(merged) shouldBe listOf("home", "forms")
        (merged.content["items"] as List<*>).first().let { (it as Map<*, *>)["label"] } shouldBe "Start"
        // The key it did not mention survives -- an overlay names a change, not a replacement.
        (merged.content["items"] as List<*>).first().let { (it as Map<*, *>)[UIB.displayOrder] } shouldBe 100
        merged.content["title"] shouldBe "Menu"
    }

    "an overlay adds an item the base never had" {
        val over = overlay {
            items("items") { item { set("id", "extra"); set(UIB.displayOrder, 150) } }
        }
        ids(mergeUiBlock(menu, listOf(base("home" to 100, "forms" to 200), over), client = null)) shouldBe
            listOf("home", "extra", "forms")
    }

    "an array with no declared key is replaced whole, not merged" {
        // Without a key, position would have to identify an element -- true until somebody inserts one. A
        // predictable replacement beats a merge that is right by luck.
        val plainBase = uiBlock(menu, origin = "core") { set("tags", listOf("a", "b")) }
        val over = overlay { set("tags", listOf("c")) }
        mergeUiBlock(menu, listOf(plainBase, over), client = null).content["tags"] shouldBe listOf("c")
    }

    "nested objects merge per key rather than being replaced" {
        val nested = uiBlock(menu, origin = "core") { obj("layout") { set("dense", false); set("columns", 2) } }
        val over = overlay { obj("layout") { set("dense", true) } }
        mergeUiBlock(menu, listOf(nested, over), client = null).content["layout"] shouldBe
            mapOf("dense" to true, "columns" to 2)
    }

    // --- precedence -----------------------------------------------------------------------------------

    "a client's overlay wins over a component's, whatever order they were declared in" {
        val component = overlay {
            items("items") { item { set("id", "home"); set("label", "Component") } }
        }
        val acme = overlay(client = "acme") {
            items("items") { item { set("id", "home"); set("label", "Acme") } }
        }
        // Declared client-first, so declaration order alone would give the wrong answer.
        val merged = mergeUiBlock(menu, listOf(base("home" to 100), acme, component), client = "acme")
        (merged.content["items"] as List<*>).first().let { (it as Map<*, *>)["label"] } shouldBe "Acme"
    }

    "another client's overlay is not applied" {
        val acme = overlay(client = "acme") {
            items("items") { item { set("id", "home"); set("label", "Acme") } }
        }
        val merged = mergeUiBlock(menu, listOf(base("home" to 100), acme), client = "globex")
        (merged.content["items"] as List<*>).first().let { (it as Map<*, *>)["label"] } shouldBe null
    }

    // --- ordering -------------------------------------------------------------------------------------

    "items sort by displayOrder, not by the order they were contributed" {
        val over = overlay {
            items("items") { item { set("id", "middle"); set(UIB.displayOrder, 150) } }
        }
        ids(mergeUiBlock(menu, listOf(base("z" to 100, "a" to 200), over), client = null)) shouldBe
            listOf("z", "middle", "a")
    }

    "a tie is broken by the primary key, so the order is the same on every machine" {
        // Contribution order is not a usable fallback: components default to one load priority, so two of them
        // are ordered by ServiceLoader discovery -- jar order, which differs between machines. Two items at
        // one displayOrder would then render differently in different environments.
        val over = overlay {
            items("items") { item { set("id", "aaa"); set(UIB.displayOrder, 100) } }
        }
        ids(mergeUiBlock(menu, listOf(base("zzz" to 100), over), client = null)) shouldBe listOf("aaa", "zzz")
    }

    "an item with no displayOrder sorts last, rather than implying a position" {
        // An overlay stamps nothing, so this item genuinely has no order -- which is the case under test.
        val over = overlay { items("items") { item { set("id", "unplaced") } } }
        val merged = mergeUiBlock(menu, listOf(base("home" to 100), over), client = null)
        (merged.content["items"] as List<*>).last().let { (it as Map<*, *>)[UIB.displayOrder] } shouldBe null
        ids(merged) shouldBe listOf("home", "unplaced")
    }

    // --- what a base owns ------------------------------------------------------------------------------

    "only a base declares merge rules" {
        // An overlay that could change how arrays merge would be changing how it is itself folded in.
        shouldThrow<KdrException> {
            UiBlockSource(menu, isOverlay = true, client = null, origin = "test", content = emptyMap(),
                arrayKeys = mapOf("items" to "id"))
        }.fullMessage() shouldBe
            "The overlay of UiBlock 'menu' from test declares merge rules. Only the base declares them: an " +
            "overlay that could change how arrays merge would be changing how it is itself folded in."
    }

    "an overlay alone does not make a block found" {
        // An overlay changes a block; it does not supply one. A block whose base never registered is a
        // contributor referring to something that is not there, and hiding that would be the worst outcome.
        mergeUiBlock(menu, listOf(overlay { set("title", "Only me") }), client = null).found shouldBe false
        mergeUiBlock(menu, listOf(base("home" to 100)), client = null).found shouldBe true
    }

    "the builder refuses a key set twice" {
        shouldThrow<KdrException> { uiBlock(menu, origin = "t") { set("title", "One"); set("title", "Two") } }
    }

    "the builder spaces displayOrder so a later contributor can land between two items" {
        val block = uiBlock(menu, origin = "core", arrayKeys = byId) {
            items("items") { item { set("id", "a") }; item { set("id", "b") } }
        }
        (block.content["items"] as List<*>).map { (it as Map<*, *>)[UIB.displayOrder] } shouldBe
            listOf(UIB.orderStep, UIB.orderStep * 2)
    }
})
