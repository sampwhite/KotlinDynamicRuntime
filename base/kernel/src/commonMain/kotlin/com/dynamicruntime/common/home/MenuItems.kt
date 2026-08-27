package com.dynamicruntime.common.home

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockItemsBuilder

/**
 * One menu item inside a UiBlock's items (issue #458) -- the shape a menu item has, written as parameters.
 *
 * The generic `item { set(...) }` form can express anything, which is right for a UiBlock in general and wrong
 * for a menu: a menu item has a **fixed shape**, and two things follow from saying so here rather than at each
 * call.
 *
 * It **refuses a [page] and an [action] together**, which is a rule the hardcoded menu's own builder stated
 * ("a navigation or an action, never both") and which a sequence of `set` calls cannot hold. And a misspelled
 * key stops being possible at all: a parameter name is checked by the compiler where a `set(HFLD.labl, ...)`
 * would compile and simply never be read.
 *
 * **Everything but [id] is optional, because an overlay names only what it changes.** An overlay adjusting a
 * label sets that and nothing else; the id is what says *which* item, so it is the one thing always required.
 * Both `page` and `action` absent is therefore legal — it is what a label-only overlay looks like — while both
 * *present* is the authoring mistake worth refusing.
 *
 * In the kernel so that an overlay written anywhere reaches it: a component in another module, and a client's
 * own configuration, build their items through the same call.
 */
fun UiBlockItemsBuilder.menuItem(
    id: String,
    label: String? = null,
    page: String? = null,
    action: String? = null,
    /** The cfact expression deciding whether this caller is offered the item; absent means always. */
    cfactExpression: String? = null,
    /** Where it sorts. Absent lets a base's items take their written order, and an overlay must state it. */
    displayOrder: Int? = null,
) {
    if (page != null && action != null) {
        throw KdrException.mkConv(
            "Menu item '$id' declares both a page ('$page') and an action ('$action'). An item does one thing " +
                "when it is clicked, and which one is not something the frontend should have to choose.",
        )
    }
    item {
        set(HFLD.id, id)
        if (label != null) set(HFLD.label, label)
        if (page != null) set(HFLD.page, page)
        if (action != null) set(HFLD.action, action)
        if (cfactExpression != null) set(UIB.cfactExpression, cfactExpression)
        if (displayOrder != null) set(UIB.displayOrder, displayOrder)
    }
}
