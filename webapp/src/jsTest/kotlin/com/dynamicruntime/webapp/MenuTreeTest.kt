package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #161) for [menuTree], the app-bar's one-level drill-down (issue #517): a flat menu
 * grouped into top-level items each paired with the children that name it as their parent.
 */
class MenuTreeTest {

    private fun item(id: String, parentId: String? = null) = MenuItem(id, id, action = null, parentId = parentId)

    @Test
    fun topLevelItemsHaveNoChildren() {
        val tree = menuTree(listOf(item("a"), item("b")))
        assertEquals(listOf("a", "b"), tree.map { it.item.id })
        assertEquals(listOf(emptyList(), emptyList()), tree.map { it.children.map { c -> c.id } })
    }

    @Test
    fun childrenNestUnderTheirParentInOrder() {
        // "debug" parent with two children, interleaved with a plain top-level item.
        val tree = menuTree(
            listOf(
                item("catalog"),
                item("debug"),
                item("debugPages", parentId = "debug"),
                item("debugOff", parentId = "debug"),
            ),
        )
        assertEquals(listOf("catalog", "debug"), tree.map { it.item.id }) // children are not top-level
        assertEquals(emptyList(), tree.first { it.item.id == "catalog" }.children.map { it.id })
        assertEquals(listOf("debugPages", "debugOff"), tree.first { it.item.id == "debug" }.children.map { it.id })
    }

    @Test
    fun anOrphanWhoseParentIsAbsentDoesNotRenderParentless() {
        // The parent's cfact dropped it, so only the child arrived: it must not appear at the top level.
        val tree = menuTree(listOf(item("catalog"), item("debugPages", parentId = "debug")))
        assertEquals(listOf("catalog"), tree.map { it.item.id })
    }
}
