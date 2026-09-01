package com.dynamicruntime.common.uiblock

import com.dynamicruntime.common.cfact.CFACT
import com.dynamicruntime.common.home.HACT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * What the boot refuses to start over (issue #457).
 *
 * Both cases fail **silently** without the check, which is why they are worth a boot refusal: an overlay of a
 * block nobody registered merges onto nothing and is simply never served, and a mistyped cfact name would be
 * caught at parse -- except that nothing parses a block's expressions until somebody renders it, which is on a
 * page, in front of a person.
 */
class UiBlockCheckTest : StringSpec({

    val menu = "menu"
    fun everywhere(names: Set<String>): (String?) -> Set<String> = { names }

    fun block(expression: String?) = uiBlock(menu, origin = "core", arrayKeys = mapOf("items" to "id")) {
        items("items") {
            item {
                set("id", "home")
                if (expression != null) set(UIB.cfactExpression, expression)
            }
        }
    }

    "a clean set of blocks reports nothing" {
        uiBlockProblems(listOf(block("isAdmin")), everywhere(setOf("isAdmin"))).shouldBeEmpty()
        uiBlockProblems(listOf(block(null)), everywhere(emptySet())).shouldBeEmpty()
    }

    "an overlay of a block nothing registers is reported" {
        val orphan = uiBlockOverlay("noSuchBlock", origin = "acmeConfig", client = "acme") { set("title", "x") }
        val problems = uiBlockProblems(listOf(block(null), orphan), everywhere(emptySet()))
        problems.single() shouldContain "names a block nothing registers"
        problems.single() shouldContain "acmeConfig"
    }

    "an unregistered cfact name is reported" {
        uiBlockProblems(listOf(block("isAdmn")), everywhere(setOf("isAdmin")))
            .single() shouldContain "not a registered cfact"
    }

    "the literals are legal without being registered" {
        // `#never` is how an overlay retires an item, so it has to pass a check that refuses unknown names.
        uiBlockProblems(listOf(block(CFACT.neverName)), everywhere(emptySet())).shouldBeEmpty()
        uiBlockProblems(listOf(block(CFACT.alwaysName)), everywhere(emptySet())).shouldBeEmpty()
    }

    "an expression is checked against each client's own vocabulary, not just the shared one" {
        // The failure this catches: a name every other client has, missing at one customer -- who would
        // otherwise be the one to find out.
        val acmeOverlay = uiBlockOverlay(menu, origin = "acmeConfig", client = "acme") {
            items("items") { item { set("id", "audit"); set(UIB.cfactExpression, "acmeOnly") } }
        }
        val allowed = { client: String? -> if (client == "acme") setOf("acmeOnly") else emptySet() }
        uiBlockProblems(listOf(block(null), acmeOverlay), allowed).shouldBeEmpty()
        // ...and the reverse: the same block where acme does *not* have the name.
        uiBlockProblems(listOf(block(null), acmeOverlay), everywhere(emptySet()))
            .single() shouldContain "client 'acme'"
    }


    // --- calls into the frontend -------------------------------------------------------------------------

    fun blockCalling(action: Any?) = uiBlock(menu, origin = "core", arrayKeys = mapOf("items" to "id")) {
        items("items") { item { set("id", "home"); if (action != null) set(UIB.action, action) } }
    }

    "a call to a function nothing declares is reported" {
        // The silent failure this catches: a name no frontend function implements is a click that does
        // nothing, and neither side can see the other to notice.
        uiBlockProblems(listOf(blockCalling(listOf("noSuchFunction"))), everywhere(emptySet()))
            .single() shouldContain "which no frontend function declares"
    }

    "a call with the wrong number of parameters is reported" {
        uiBlockProblems(listOf(blockCalling(listOf(HACT.logout.name, "extra"))), everywhere(emptySet()))
            .single() shouldContain "with 1 parameter(s); it takes 0"
    }

    "a declared call with the right arity, and a route, are both fine" {
        uiBlockProblems(listOf(blockCalling(listOf(HACT.logout.name))), everywhere(emptySet())).shouldBeEmpty()
        // A route is a string and is not a call at all, so nothing looks it up in the registry.
        uiBlockProblems(listOf(blockCalling("/forms/123")), everywhere(emptySet())).shouldBeEmpty()
    }

    "every problem is reported in one pass" {
        val orphan = uiBlockOverlay("noSuchBlock", origin = "a", client = null) { set("title", "x") }
        uiBlockProblems(listOf(block("isAdmn"), orphan), everywhere(emptySet())).size shouldBe 2
    }

    // --- parenting / drill-down (issue #517) ------------------------------------------------------------

    // A block whose items may name a [UIB.parentId] and/or carry an action -- (id, parentId, action).
    fun parented(vararg specs: Triple<String, String?, Any?>) =
        uiBlock(menu, origin = "core", arrayKeys = mapOf("items" to "id")) {
            items("items") {
                for ((id, parentId, action) in specs) item {
                    set("id", id)
                    if (parentId != null) set(UIB.parentId, parentId)
                    if (action != null) set(UIB.action, action)
                }
            }
        }

    "a top-level parent with action-bearing children reports nothing" {
        uiBlockProblems(
            listOf(
                parented(
                    Triple("debug", null, null),
                    Triple("pages", "debug", "debugPage"),
                    Triple("off", "debug", listOf(HACT.logout.name)),
                ),
            ),
            everywhere(emptySet()),
        ).shouldBeEmpty()
    }

    "a child naming a parent no item declares is reported" {
        uiBlockProblems(listOf(parented(Triple("pages", "missing", "debugPage"))), everywhere(emptySet()))
            .single() shouldContain "which no item in that array declares"
    }

    "an item naming itself as its parent is reported" {
        uiBlockProblems(listOf(parented(Triple("loop", "loop", null))), everywhere(emptySet()))
            .single() shouldContain "names itself as its parent"
    }

    "a second nesting level is refused -- nesting is one level" {
        uiBlockProblems(
            listOf(parented(Triple("a", null, null), Triple("b", "a", null), Triple("c", "b", null))),
            everywhere(emptySet()),
        ).single() shouldContain "Nesting is one level"
    }

    "a parent that also carries its own action is refused" {
        // The silent failure this catches (the render draws a parent as a header): its route would vanish.
        uiBlockProblems(
            listOf(parented(Triple("reports", null, "reportsPage"), Triple("weekly", "reports", "weeklyPage"))),
            everywhere(emptySet()),
        ).single() shouldContain "has children and its own action"
    }
})
