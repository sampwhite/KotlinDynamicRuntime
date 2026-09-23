package com.dynamicruntime.common.user

import com.dynamicruntime.common.util.trimToNull

/**
 * The one rule for a user's label list (issue #786): each label trimmed, blanks dropped, duplicates dropped with
 * the first spelling kept. Case is **kept and significant** -- a label is matched exactly as written, the way a
 * cfact name is, so `Reviewer` and `reviewer` are two labels rather than a guess at which was meant.
 *
 * In the kernel so the admin console applies the rule the backend stores by: an edit that only re-spaces or
 * repeats a label is then no change on either side, rather than a write the console thought it needed.
 */
fun normalizeUserLabels(labels: List<String>): List<String> = labels.mapNotNull { it.trimToNull() }.distinct()

/**
 * The choices a label editor offers (issue #786): the client's [suggestions] in their order, then any label the
 * user already carries that is not among them -- so an off-list label stays visible and removable rather than
 * vanishing from the control the moment it is not a suggestion.
 */
fun userLabelChoices(suggestions: List<String>, current: List<String>): List<String> =
    normalizeUserLabels(suggestions + current)
