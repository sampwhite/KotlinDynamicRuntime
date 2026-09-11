package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * The collapsible filter shell the listing pages share (issues #562, #683): a toggle that opens a well of
 * filter controls, and -- while the well is closed -- a row of chips saying what the list is narrowed by, so a
 * narrowed list never looks like the whole one. What goes *inside* the well is each page's own (the forms
 * list's schema-declared search groups, the users console's spec-driven fields); this holds only the
 * arrangement and the words, so the two pages cannot drift into two versions of the same idea.
 *
 * Three small pieces rather than one component, because the pages compose their toolbars differently -- the
 * forms list puts a free-text box and its Search button before the toggle, the users console its Create
 * button -- and one component would have to take the toolbar's leading content as a prop to fit both. The
 * pieces are what is actually shared.
 */

/**
 * What the toggle says: the count while closed with filters in force, so the button itself reports that the
 * list is narrowed (`Filters (2)`); a plain `Filters` when nothing is; `Hide filters` while open. Pure, and
 * covered under `jsNodeTest`.
 */
fun filterToggleLabel(open: Boolean, chipCount: Int): String = when {
    open -> "Hide filters"
    chipCount == 0 -> "Filters"
    else -> "Filters ($chipCount)"
}

/** The toggle button, labelled by [filterToggleLabel] and stating its state for assistive tech. */
fun ChildrenBuilder.filterToggle(open: Boolean, chipCount: Int, onToggle: () -> Unit) {
    Button {
        asDynamic()["aria-expanded"] = open
        onClick = { onToggle() }
        +filterToggleLabel(open, chipCount)
    }
}

/**
 * The filters in force, one chip each, shown only while the well is closed -- while it is open the controls
 * themselves say what is set, and a second copy beside them would be noise. Renders nothing for no chips.
 */
fun ChildrenBuilder.filterChips(chips: List<String>, open: Boolean) {
    if (open || chips.isEmpty()) return
    div {
        className = ClassName("filter-chips")
        chips.forEach { chip ->
            span {
                className = ClassName("filter-chip")
                +chip
            }
        }
    }
}

/**
 * The inset well the filter controls sit in, rendered only while [open]. [wide] asks for wider columns, for
 * controls that do not fit the default track -- a from-to pair of date-time pickers, say.
 */
fun ChildrenBuilder.filterWell(open: Boolean, wide: Boolean = false, content: ChildrenBuilder.() -> Unit) {
    if (!open) return
    div {
        className = ClassName(if (wide) "filter-well wide" else "filter-well")
        content()
    }
}
