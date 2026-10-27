package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.util.toOptStr

/**
 * What recalculating a client's form states did (issue #1412), as `/clientAdmin/client/formStates/recalculate`
 * answers it: its [outcome] ([FSR.completed], [FSR.stopped], or [FSR.busy]), the forms it [updated], those already
 * [current], those whose state could not be stored ([failed]), how many it was to look at ([total]), and the
 * [reason] it stopped or did nothing. The counts are absent, and zero here, when nothing ran.
 */
class FormStatesRecalc(
    val outcome: String,
    val updated: Long = 0,
    val current: Long = 0,
    val failed: Long = 0,
    val total: Long? = null,
    val reason: String? = null,
)

/** The recalculation's answer as [FormStatesRecalc]. Pure, and covered under `jsTest`. */
fun parseFormStatesRecalc(results: Map<String, Any?>): FormStatesRecalc {
    fun count(key: String): Long = (results[key] as? Number)?.toLong() ?: 0
    return FormStatesRecalc(
        outcome = results[FSR.outcome].toOptStr() ?: FSR.completed,
        updated = count(FSR.updated),
        current = count(FSR.current),
        failed = count(FSR.failed),
        total = (results[FSR.total] as? Number)?.toLong(),
        reason = results[FSR.reason].toOptStr(),
    )
}

/** [n] forms, with the noun agreeing: "1 form", "3 forms". */
private fun forms(n: Number): String = if (n.toLong() == 1L) "1 form" else "$n forms"

/**
 * What the page says a recalculation did. A run that changed nothing says so, so that an administrator who expected a
 * change knows to look elsewhere -- at whether the configuration is published, say. Pure, and covered under `jsTest`.
 */
fun formStatesRecalcText(result: FormStatesRecalc): String {
    val total = result.total ?: (result.updated + result.current + result.failed)
    val failed = if (result.failed > 0) {
        " The state of ${forms(result.failed)} could not be stored; the job's exceptions name them."
    } else {
        ""
    }
    return when (result.outcome) {
        FSR.busy -> result.reason ?: "Another recalculation of this client's forms is running. Try again once it has finished."
        // The reason is the job's own sentence, already ending in its period.
        FSR.stopped -> "Stopped after updating ${result.updated} of ${forms(total)}: ${result.reason?.trimEnd('.') ?: "no reason given"}.$failed"
        else -> when {
            total == 0L -> "There were no forms to recalculate."
            result.updated == 0L && result.failed == 0L ->
                if (total == 1L) "Its one form was already current." else "All ${forms(total)} were already current."
            result.current == 0L && result.failed == 0L -> if (total == 1L) "Updated its one form." else "Updated all ${forms(total)}."
            else -> "Updated ${result.updated} of ${forms(total)}; the rest were already current.$failed"
        }
    }
}

/** What the page's Form states section says about `row` (issue #1412), and whether it offers to [recalculate]. */
class FormStatesOffer(val text: String, val recalculate: Boolean)

/**
 * The page's Form states section for `row` (issue #1412). A client this node does not carry has no forms here to
 * recalculate; one holding none has nothing to do. Within its limit it is offered; past it, the nightly recalculation
 * is the way, and the page says so rather than offering what would be refused. Judged on the count the backend holds
 * to the limit (`formStatesRecalcCount`), so the two cannot disagree. Pure, and covered under `jsTest`.
 */
fun formStatesOffer(row: ClientOverview): FormStatesOffer {
    val count = row.formStatesRecalcCount
    return when {
        row.status != ClientStatus.present.name -> FormStatesOffer("Not loaded on this node, so its forms cannot be recalculated here.", false)
        count == 0 -> FormStatesOffer("This client holds no forms yet.", false)
        count > row.formStatesRecalcLimit -> FormStatesOffer(
            "This client holds ${forms(count)}, more than the ${row.formStatesRecalcLimit} a recalculation from this page " +
                "may take. The nightly recalculation brings them up to date.",
            false,
        )
        else -> FormStatesOffer(
            "Brings the stored state of this client's ${forms(count)} up to date with its configuration now, rather " +
                "than at the nightly recalculation.",
            true,
        )
    }
}
