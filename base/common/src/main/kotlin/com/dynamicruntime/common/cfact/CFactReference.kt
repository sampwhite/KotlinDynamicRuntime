package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.SchemaService

/**
 * Assembles the cfact reference as one Markdown document (issue #488) -- the counterpart of
 * `renderEnvVarReference`, and the same shape of answer: the whole document, so the frontend renders text it
 * did not compose, the way it renders the README and the environment-variable reference.
 *
 * It lists the cfacts **[cxt]'s client** knows -- global's, plus any the client declared -- grouped and
 * described, and for each one **whether it is present for this caller right now**. The present set is the same
 * one the app-bar menu is resolved against ([CFactRegistry.assemble]), so a reader can see why a menu item they
 * expected is or is not offered, rather than guessing from the expression alone.
 *
 * A cfact declared but not yet computed (a client declares a name ahead of the workflow that will set it) has
 * no source, so it is never present; it is shown as absent, which is the honest reading of "declared, nothing
 * produces it yet".
 */
fun renderCFactReference(cxt: KdrCxt): String {
    val registry = SchemaService.get(cxt).cfactsFor(cxt.client)
    val present = registry.assemble(cxt)
    // Grouped, then alphabetical inside the group -- the order the discovery endpoint already sorted by, and
    // the order a page reads best in: sections rather than one alphabetical wall.
    val byGroup = registry.defs.values.groupBy { it.group }

    val sb = StringBuilder()
    sb.append("# Client facts\n\n")
    sb.append(
        "Every cfact this client knows -- a named present-or-absent fact a display condition may test -- with " +
            "**whether it is present for you right now**. This is the same set the navigation menu is resolved " +
            "against, so it says why an item is or is not offered. A cfact a client declared but nothing " +
            "produces yet is always absent.\n\n",
    )
    for (group in byGroup.keys.sorted()) {
        sb.append("## ").append(group).append("\n\n")
        for (def in byGroup.getValue(group).sortedBy { it.name }) {
            appendCFact(sb, def, def.name in present)
        }
    }
    return sb.toString()
}

/** One cfact's block: its name, whether it is present here, and the description prose. */
private fun appendCFact(sb: StringBuilder, def: CFactDef, isPresent: Boolean) {
    sb.append("### `").append(def.name).append("`\n\n")
    sb.append("**For you now:** ")
        .append(if (isPresent) "present ✓" else "absent")
        .append("\n\n")
    sb.append(prose(def.description)).append("\n\n")
}

/** Matches a KDoc `[Symbol]` reference -- a bare `[Identifier]` (dotted members allowed), not a `[text](url)` link. */
private val kdocReference = Regex("""\[([A-Za-z][\w.]*)](?!\()""")

/**
 * A def's description rendered for the document: a `[Symbol]` reference -- clickable in the source, a dangling
 * link in CommonMark -- becomes an inline-code span, so it reads as "a name" rather than a broken link. A real
 * `[text](url)` link is left alone (the negative lookahead). The same treatment `renderEnvVarReference` gives
 * its prose, and kept a copy rather than shared because the two references may want to diverge and neither is
 * the other's utility.
 */
private fun prose(text: String): String = kdocReference.replace(text) { "`${it.groupValues[1]}`" }
