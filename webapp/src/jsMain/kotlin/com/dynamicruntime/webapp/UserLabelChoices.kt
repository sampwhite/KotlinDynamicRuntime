package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.UserLabelDetail
import com.dynamicruntime.common.user.joinedWithOr
import com.dynamicruntime.common.user.mergeLabelSuggestions
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.util.toJsonListOfStrings
import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import web.cssom.ClassName

/**
 * What the label-suggestions read says for one client (issues #786, #1481, #1658): the labels a label control offers
 * ([offered]: the client's suggestions, then the labels its users carry), and each suggested label with what the
 * client's workflows do with it ([details]).
 */
class LabelSuggestions(val offered: List<String>, val details: List<UserLabelDetail>) {
    companion object {
        val none = LabelSuggestions(emptyList(), emptyList())
    }
}

/** A label-suggestions answer's `results` as [LabelSuggestions]. Pure, covered under `jsNodeTest`. */
fun labelSuggestionsFrom(results: Map<String, Any?>): LabelSuggestions = LabelSuggestions(
    offered = mergeLabelSuggestions(results[ADF.labels].toJsonListOfStrings(), results[ADF.labelsInUse].toJsonListOfStrings()),
    details = UserLabelDetail.listFrom(results),
)

/**
 * One row of the Users editor's Labels group (issue #1659): a label the client suggests, whether the draft holds it,
 * and what to say under it -- the client's note, then each thing its workflows do with the label, then who carries it.
 */
class LabelRow(
    val label: String,
    val checked: Boolean,
    val note: String?,
    val uses: List<String>,
    val carriers: String,
)

/** A row per label in [details], in the client's order, checked where [draft] holds it. Pure, covered under `jsNodeTest`. */
fun labelRows(details: List<UserLabelDetail>, draft: List<String>): List<LabelRow> {
    val held = normalizeUserLabels(draft).toSet()
    return details.map { d ->
        LabelRow(d.label, d.label in held, d.note, d.uses.map { it.sentence }, labelCarriersText(d.carriers))
    }
}

/** How many users carry a label, as a row says it. */
fun labelCarriersText(carriers: Int): String = when (carriers) {
    0 -> "Nobody carries it yet."
    1 -> "1 user carries it."
    else -> "$carriers users carry it."
}

/** The labels in [draft] the client does not suggest: what the free-entry box under the rows holds. */
fun otherLabels(details: List<UserLabelDetail>, draft: List<String>): List<String> {
    val suggested = details.map { it.label }.toSet()
    return normalizeUserLabels(draft).filter { it !in suggested }
}

/** [draft] with [label] added at its end, or removed. */
fun withLabel(draft: List<String>, label: String, on: Boolean): List<String> {
    val labels = normalizeUserLabels(draft)
    return if (on) normalizeUserLabels(labels + label) else labels.filter { it != label }
}

/** [draft]'s suggested labels as they are, with its other labels replaced by [others]. */
fun withOtherLabels(details: List<UserLabelDetail>, draft: List<String>, others: List<String>): List<String> {
    val suggested = details.map { it.label }.toSet()
    return normalizeUserLabels(normalizeUserLabels(draft).filter { it in suggested } + others)
}

/**
 * What a user with no labels cannot do here (issue #1659), a sentence per label the client's workflows depend on:
 * "Without `reviewer`, this user can't approve “Approve the plan” in “Plan the logistics”." Empty when [draft] holds
 * any label -- whoever chose one has seen the rows -- or when no workflow withholds anything for want of one. A note,
 * never a refusal. Pure, covered under `jsNodeTest`.
 */
fun missingLabelsPrompt(details: List<UserLabelDetail>, draft: List<String>): List<String> {
    if (normalizeUserLabels(draft).isNotEmpty()) return emptyList()
    return details.mapNotNull { d ->
        val withheld = d.uses.mapNotNull { it.withheld }.distinct().ifEmpty { return@mapNotNull null }
        "Without “${d.label}”, this user can't ${joinedWithOr(withheld)}."
    }
}

/**
 * The notice that label-gated work has nobody to do it (issue #1661), a line per label from `nobodyCarriesWarnings`,
 * and [advice] saying what to do. Drawn on the Users list and on a client's Workflows section.
 */
fun ChildrenBuilder.labelWarnings(warnings: List<String>, advice: String) {
    div {
        className = ClassName("user-labels-prompt")
        for (line in warnings) p { +line }
        p {
            className = ClassName("type-hint")
            +advice
        }
    }
}
