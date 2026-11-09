package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.schema.LayoutCtx
import com.dynamicruntime.common.util.analyzeTemplate

/**
 * What a user-facing form heads entry [index] (counting from 0) of a list with (issue #1714): [template], a template over
 * `${'$'}{n}`, the entry's number counting from 1 -- the field's layout `entryLabel`, else the home copy's
 * `formEntries.label`. Words with no `${'$'}` are taken as written, unparsed, as layout copy is. A template that does not
 * evaluate falls back to the shipped one ([HFRAG.defaultEntryLabel]), said once per template in the console rather than
 * on every render, so a wording mistake costs the client its words, not the form; the boot check refuses a layout's
 * that names anything but `n`, so only a client's copy can. Pure but for that note, and covered under `jsNodeTest`.
 *
 * Theme: #TPLPS.
 */
fun entryHeading(template: String, index: Int): String {
    if ('$' !in template) return template
    val data = mapOf(LayoutCtx.n to index + 1)
    template.analyzeTemplate(evaluateWith = data).value?.let { return it }
    if (warnedEntryTemplates.add(template)) {
        console.warn("$errorLogPrefix could not resolve an entry's heading, showing the shipped one: \"$template\"")
    }
    // The shipped line is held to evaluate by its tests; the bare number is only a last word, with no wording to drift.
    return HFRAG.defaultEntryLabel.analyzeTemplate(evaluateWith = data).value ?: "${index + 1}"
}

/** The entry headings that did not evaluate and have been said in the console, so each is said once. */
private val warnedEntryTemplates = HashSet<String>()
