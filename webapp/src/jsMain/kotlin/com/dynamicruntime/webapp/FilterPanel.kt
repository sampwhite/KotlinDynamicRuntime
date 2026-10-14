package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * The collapsible filter shell the listing pages share (issues #562, #683): a toggle that opens a well of
 * filter controls, and -- while the well is closed -- a row of chips saying what the list is narrowed by, so a
 * narrowed list never looks like the whole one. What goes *inside* the well is each page's own (the forms
 * list's schema-declared search groups, the users console's spec-driven fields); this holds the arrangement,
 * the per-field wrapper, and the words a chip says, so the two pages cannot drift into two versions of the
 * same idea.
 *
 * Small pieces rather than one component, because the pages compose their toolbars differently -- the forms
 * list puts a free-text box and its Search button before the toggle, the users console its Create button --
 * and one component would have to take the toolbar's leading content as a prop to fit both. The pieces are
 * what is actually shared.
 */

/**
 * What the toggle says: the count of filters in force while closed, so the button itself reports that the
 * list is narrowed (`Filters (2)`); a plain `Filters` when nothing is; `Hide filters` while open. Pure, and
 * covered under `jsNodeTest`.
 */
fun filterToggleLabel(open: Boolean, filterCount: Int): String = when {
    open -> "Hide filters"
    filterCount == 0 -> "Filters"
    else -> "Filters ($filterCount)"
}

/**
 * The toggle button, labelled by [filterToggleLabel]. [filterCount] is the filters in force -- not every chip:
 * a sort chip narrows nothing and is not counted. States its state for assistive tech, and names the well it
 * controls ([wellId]) while that well exists -- the well is rendered only when open, and an `aria-controls`
 * naming an absent element is worse than none.
 */
fun ChildrenBuilder.filterToggle(open: Boolean, filterCount: Int, wellId: String, onToggle: () -> Unit) {
    Button {
        asDynamic()["aria-expanded"] = open
        if (open) asDynamic()["aria-controls"] = wellId
        onClick = { onToggle() }
        +filterToggleLabel(open, filterCount)
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

/** The inset well the filter controls sit in, rendered only while [open]; [wellId] is what the toggle names. */
fun ChildrenBuilder.filterWell(open: Boolean, wellId: String, content: ChildrenBuilder.() -> Unit) {
    if (!open) return
    div {
        // Via asDynamic, as the app bar sets its own ids: the wrapper types `id` as an ElementId, not a String.
        asDynamic()["id"] = wellId
        className = ClassName("filter-well")
        content()
    }
}

/** The narrowest a well's track may be, its column gap and its padding -- `.filter-well` in `app.css`; change them together. */
const val filterTrackMinPx = 300.0
const val filterColumnGapPx = 24.0
const val filterWellPaddingPx = 12.0

/**
 * How many columns a well [wellWidth] wide lays out (its `clientWidth`, padding included): what the grid's
 * `repeat(auto-fill, minmax(300px, 1fr))` fits, at least one. Pure, covered under `jsNodeTest`.
 */
fun filterWellColumns(wellWidth: Double): Int {
    val inner = wellWidth - 2 * filterWellPaddingPx
    return maxOf(1, kotlin.math.floor((inner + filterColumnGapPx) / (filterTrackMinPx + filterColumnGapPx)).toInt())
}

/**
 * Whether the first of [tallCount] tall groups -- the Users page's date ranges -- should begin a new row after
 * [shortCount] short ones, in a well of [columns]. A row is as tall as its tallest cell, so a tall group sharing a row
 * with short ones stretches them all; breaking costs one short row, and pays when the short ones' last, partial row
 * would otherwise push the tall ones into an extra row of their own. Three text boxes in four columns: break (one short
 * row, one tall). Four in three: do not -- the fourth shares a tall row that is there anyway. Pure, covered under
 * `jsNodeTest`.
 */
fun breakBeforeTall(shortCount: Int, tallCount: Int, columns: Int): Boolean {
    if (tallCount == 0 || columns < 1) return false
    val partial = shortCount % columns
    fun rows(n: Int) = (n + columns - 1) / columns
    return partial != 0 && rows(partial + tallCount) > rows(tallCount)
}

/** A `ResizeObserver` calling [changed] whenever what it observes changes size. */
internal fun resizeObserver(changed: () -> Unit): dynamic = js("new ResizeObserver(function () { changed(); })")

/** One labelled control in the well: the label above, the control ([content]) beneath. */
fun ChildrenBuilder.filterGroup(
    label: String,
    /**
     * Whether this group begins a new row of the well rather than continuing the last. For the first of a run of tall
     * groups -- a from-to date pair and its hint -- so the short ones before it are not stretched to its height.
     */
    startsRow: Boolean = false,
    content: ChildrenBuilder.() -> Unit,
) {
    div {
        className = ClassName(if (startsRow) "filter-group filter-row-start" else "filter-group")
        span {
            className = ClassName("filter-label")
            +label
        }
        content()
    }
}

// --- the words a chip says, one vocabulary for every page's chips ---------------------------------------

/**
 * A text filter as a chip: `Name contains "plan"` or `Site is "North"`. A blank [value] is no filter and so
 * no chip -- the one place that rule is written, so a chips builder cannot forget it. Pure, and covered under
 * `jsNodeTest`.
 */
fun textChip(label: String, value: String?, contains: Boolean): String? {
    val v = value?.trim()?.ifEmpty { null } ?: return null
    return if (contains) "$label contains \"$v\"" else "$label is \"$v\""
}

/**
 * A range filter as a chip: `Year 2020 – 2025`, `Year ≥ 2020`, `Due ≤ 2026-01-01`. A blank bound is an open
 * end; two blank bounds are no filter and so no chip. Pure, and covered under `jsNodeTest`.
 */
fun rangeChip(label: String, lo: String?, hi: String?): String? {
    val l = lo?.trim()?.ifEmpty { null }
    val h = hi?.trim()?.ifEmpty { null }
    return when {
        l != null && h != null -> "$label $l – $h"
        l != null -> "$label ≥ $l"
        h != null -> "$label ≤ $h"
        else -> null
    }
}
