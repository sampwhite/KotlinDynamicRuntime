package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The field names of the forms listing's **workflow summary** (issue #791): the workflows a caller should see
 * across every form they may see, delivered once beside the listing's items so each row can be drawn from its own
 * state entries alone.
 */
@Suppress("ConstPropertyName")
object WCOL {
    /** The summary's type name, under the gedra namespace. */
    const val summaryType = "FormWorkflowSummary"

    /** One workflow the summary names; see [workflows]. */
    const val summaryWorkflowType = "FormWorkflowSummaryEntry"

    /** The summary's list of workflows, each keyed by [client] and `workflowId`. */
    const val workflows = "workflows"

    /** The client whose workflow this is -- one summary can span clients for a cross-client administrator. */
    const val client = "client"

    /** The workflow's [WfPhase] name when the summary was made. */
    const val phase = "phase"

    /** Eligibility test id to its explanation, resolved -- what the ineligible dialog lists. */
    const val explanations = "explanations"

    /** The workflow's last task -- where a finished workflow's link lands, having no current task. */
    const val lastTask = "lastTask"
}

/**
 * The field names of the workflow pages' **aggregate** (issue #792) -- one entry per workflow, with its [WCOL.client],
 * `workflowId`, `label` and [WCOL.phase], and a count of the forms in each of these states -- and of the forms
 * listing's drill-down filter onto one of those counts.
 */
@Suppress("ConstPropertyName")
object WAGG {
    /** One aggregate entry's type name, under the gedra namespace. */
    const val entryType = "WorkflowAggregateEntry"

    /** How many forms the caller may see are eligible for the workflow and not in it. */
    const val eligible = "eligible"

    /** How many are engaged with it and not finished -- work under way. */
    const val engaged = "engaged"

    /** How many are engaged with it and finished. */
    const val finished = "finished"

    /**
     * The forms listing's filter (issue #792): only forms whose cell has this workflow, in [workflowState] when
     * given. Together with the listing's own client, the workflow a count on the workflow pages stands for.
     */
    const val workflowId = "workflowId"

    /** With [workflowId]: the state the forms must be in -- [eligible], [engaged] or [finished]. */
    const val workflowState = "workflowState"

    /** The states a drill-down may ask for: the three the aggregate counts. */
    val drillStates: List<WfColumnCategory> = listOf(WfColumnCategory.eligible, WfColumnCategory.engaged, WfColumnCategory.finished)
}

/**
 * Where a workflow sits in a form's workflow cell (issue #791), in the order the cell lists them: work under way
 * first, then what the form could start, then what is done, then what it cannot start.
 */
@Suppress("EnumEntryName")
enum class WfColumnCategory {
    /** Engaged and not finished: its link goes to the current task. */
    engaged,

    /** Not engaged, and the form passes every eligibility test: its link goes to the workflow, to engage. */
    eligible,

    /** Engaged, and it emits the framework's `finished` cfact or has no task left to do. */
    finished,

    /** Not engaged, and some eligibility test fails: its link opens the reasons. */
    ineligible,
}

/** One workflow in a form's cell: which [category], its current task when engaged, and failing test ids when not eligible. */
class FormWorkflow(
    val workflowId: String,
    val category: WfColumnCategory,
    val ctaTask: String?,
    val failureIds: List<String>,
)

/**
 * The workflows one form's cell shows (issue #791), from its state entries, sorted by [WfColumnCategory] and
 * otherwise in the order the entries hold them. Pure, so the page and a test read one rule.
 *
 * [phaseOf] answers for a workflow id the phase the summary recorded, or null when the summary does not name it
 * (unknown to the caller, retired, or outside its lifetime) -- and such a workflow is not shown. Nor is one the
 * form is not engaged with while it is outside its engagement window: [WfPhase.isShown].
 */
fun formWorkflowsOf(states: List<Map<String, Any?>>, phaseOf: (String) -> WfPhase?): List<FormWorkflow> {
    val engaged = states
        .filter { it[GE.traitId].toOptStr() == WFS.workflowEngagement }
        .map { it[GE.data].toJsonMapOrEmpty() }
        .filter { it[WFS.engaged] == true }
        .mapNotNull { it[WFD.workflowId].toOptStr() }
        .toSet()
    return states
        .filter { it[GE.traitId].toOptStr() == WFS.workflowState }
        .map { it[GE.data].toJsonMapOrEmpty() }
        .mapNotNull { data ->
            val id = data[WFD.workflowId].toOptStr() ?: return@mapNotNull null
            val phase = phaseOf(id) ?: return@mapNotNull null
            val isEngaged = id in engaged
            if (!phase.isShown(isEngaged)) return@mapNotNull null
            val failures = data[WFS.eligibilityFailures].toJsonListOrEmpty()
                .mapNotNull { it.toJsonMapOrEmpty()[WFD.id].toOptStr() }
            val category = when {
                isEngaged && (WSC.finished in data[WFS.singletonCfacts].toJsonListOrEmpty().map { it.toOptStr() } ||
                    data[WFS.tasksDone] == true) -> WfColumnCategory.finished
                isEngaged -> WfColumnCategory.engaged
                data[WFS.eligible] == true -> WfColumnCategory.eligible
                else -> WfColumnCategory.ineligible
            }
            FormWorkflow(id, category, data[WFS.ctaTask].toOptStr().takeIf { isEngaged }, failures.takeIf { !isEngaged }.orEmpty())
        }
        .sortedBy { it.category.ordinal }
}
