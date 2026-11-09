package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.gedra.sandboxParentOf
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
 * What the automatic recalculation after a configuration change did (issue #1741), as a change's result carries it
 * under [FSR.auto]: the [run] in the manual recalculation's terms, whose forms they were ([client], the client's own or
 * for a draft its sandbox's), and the deployment's [autoLimit] when the client held more.
 */
class FormStatesAuto(val run: FormStatesRecalc, val client: String, val autoLimit: Long?)

/** A change's [results] automatic recalculation, or null when the change ran none. Pure, and covered under `jsTest`. */
fun formStatesAutoOf(results: Map<String, Any?>): FormStatesAuto? {
    val raw = results[FSR.auto] as? Map<*, *> ?: return null
    val auto = raw.entries.associate { it.key.toString() to it.value }
    return FormStatesAuto(parseFormStatesRecalc(auto), auto[FSR.client].toOptStr().orEmpty(), (auto[FSR.autoLimit] as? Number)?.toLong())
}

/**
 * What a save's note adds about the client's forms (issue #1741): whether the change was brought to them at once, and
 * if not, how they will be -- so a person learns the rule the moment it matters. Null when there is nothing to say: no
 * recalculation, or no forms. Pure, and covered under `jsTest`.
 */
fun formStatesAutoText(auto: FormStatesAuto?): String? {
    if (auto == null) return null
    val run = auto.run
    val whose = if (sandboxParentOf(auto.client) != null) "the sandbox's" else "this client's"
    val whoseLead = whose.replaceFirstChar { it.uppercase() }
    val total = run.total ?: (run.updated + run.current + run.failed)
    val failed = if (run.failed > 0) " The state of ${forms(run.failed)} could not be stored; the job's exceptions name them." else ""
    val later = "the nightly recalculation, or Recalculate, brings them up to date."
    return when (run.outcome) {
        FSR.noForms -> null
        FSR.completed -> when {
            total == 0L -> null
            run.updated == 0L && run.failed == 0L ->
                "$whoseLead ${if (total == 1L) "one form was" else "${forms(total)} were"} already up to date with the change."
            run.current == 0L && run.failed == 0L ->
                "Brought ${if (total == 1L) "$whose one form" else "all $total of $whose forms"} up to date with the change."
            else -> {
                // Some failed: say how many of the rest were current, rather than that all of them were.
                val rest = when {
                    run.current == 0L -> ""
                    run.failed == 0L -> "; the rest already were"
                    else -> "; ${run.current} already were"
                }
                val brought = if (run.updated == 0L) "None of $whose ${forms(total)} was brought"
                else "Brought ${run.updated} of $whose ${forms(total)}"
                "$brought up to date with the change$rest.$failed"
            }
        }
        FSR.tooMany -> "$whoseLead ${forms(total)} are more than the ${auto.autoLimit ?: "few"} brought up to date as a change is " +
            "saved: $later"
        FSR.manual -> "$whoseLead forms are brought up to date by hand, as the client's definition asks: $later"
        FSR.off -> "This deployment brings no client's forms up to date as a change is saved: $later"
        FSR.busy -> "A recalculation of $whose forms is already running; they are brought up to date when it finishes."
        else -> "Bringing $whose forms up to date stopped: ${run.reason?.trimEnd('.') ?: "no reason given"}. " +
            later.replaceFirstChar { it.uppercase() } + failed
    }
}

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

/**
 * What the page's Form states section says about `row` (issue #1412), and whether it offers to [recalculate]; and the
 * rule for bringing its forms up to date as a change is saved ([auto], issue #1741).
 */
class FormStatesOffer(val text: String, val recalculate: Boolean, val auto: String? = null)

/**
 * The rule for `row`'s forms as a change to its configuration is saved (issue #1741), said beside Recalculate so a
 * person learns it before it matters: whether this client's are brought up to date at once, and if not, why -- its
 * definition asks for it by hand, the deployment brings none, or it holds more than the deployment's limit. Null for a
 * client this node does not carry. Pure, and covered under `jsTest`.
 */
fun formStatesAutoRule(row: ClientOverview): String? {
    val limit = row.formStatesAutoLimit
    val count = row.formStatesRecalcCount
    return when {
        row.status != ClientStatus.present.name -> null
        row.manualFormStates -> "A change to this client's configuration is not brought to its forms as it is saved: its " +
            "definition asks for that to be done here, or left to the nightly recalculation."
        limit <= 0 -> "This deployment brings no client's forms up to date as a change to its configuration is saved."
        count <= limit -> "A change to this client's configuration brings its forms up to date as it is saved, while it holds " +
            "no more than ${forms(limit)}."
        else -> "A change to a client's configuration brings its forms up to date as it is saved only while it holds no more " +
            "than ${forms(limit)}; this one holds $count, so a change waits for this or the nightly recalculation."
    }
}

/**
 * The page's Form states section for `row` (issue #1412). A client this node does not carry has no forms here to
 * recalculate; one holding none has nothing to do. Within its limit it is offered; past it, the nightly recalculation
 * is the way, and the page says so rather than offering what would be refused. Judged on the count the backend holds
 * to the limit (`formStatesRecalcCount`), so the two cannot disagree. Pure, and covered under `jsTest`.
 */
fun formStatesOffer(row: ClientOverview): FormStatesOffer {
    val count = row.formStatesRecalcCount
    val offer = when {
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
    return FormStatesOffer(offer.text, offer.recalculate, formStatesAutoRule(row))
}
