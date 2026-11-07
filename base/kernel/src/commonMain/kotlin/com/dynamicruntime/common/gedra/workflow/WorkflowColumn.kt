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

    /**
     * On a workflow frozen before its relevancy window opens ([WfPhase.lifetimeOnly], issue #1598): when it opens, the
     * reason a form's engaged link to it waits.
     */
    const val opensAt = "opensAt"

    /** On a workflow frozen after its relevancy window closed (issue #1598): when it closed, as [opensAt] says when one opens. */
    const val closedAt = "closedAt"

    /** One entry of a row's `waitingReasons` (issue #1598): a workflow id, its [WfWaitsOn], and its filter's label. */
    const val waitingReasonType = "WorkflowWaitingReason"

    /** In a `waitingReasons` entry: whom the step waits on, a [WfWaitsOn] name. */
    const val waitsOn = "waitsOn"
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
        .asSequence()
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

/**
 * What a workflow asks of the person looking at a form (issue #1597), the forms list's **weight**: in the order a cell
 * draws them, strongest first. Cut by what the viewer can do, where [WfColumnCategory] is cut by the workflow's phase.
 * Not configurable: only the workflow definitions (CTA filters, keywords, windows, and eligibility) move a workflow
 * between weights, and a display setting only changes how each is drawn.
 */
@Suppress("EnumEntryName")
enum class WfWeight {
    /** The viewer can act now: engaged, and its call to action is one they may save or approve. */
    yourTurn,

    /**
     * The viewer can look, not act: engaged, with a call to action they may not act on (its CTA filter, `saveWhen`, or
     * approval rule is someone else's), or with none anyone can act on now -- a workflow outside its relevancy window,
     * frozen until a date (or past it, with its work unfinished), or one whose call to action has not been computed.
     */
    waiting,

    /** The viewer could start it: eligible, and not engaged. */
    available,

    /** Nothing more here: finished, or not eligible. */
    settled,
}

/**
 * Whom a step waiting on someone other than the viewer waits on (issue #1598), as the backend works it out from the
 * step's task and the viewer's facts: what a muted link in the forms list's workflow cell gives as its reason.
 */
@Suppress("EnumEntryName")
enum class WfWaitsOn {
    /** Whoever passes the call to action's filter, which the viewer does not: its label says who, when it has one. */
    filter,

    /** Whoever may approve it: an approval task the viewer may not approve. */
    approval,

    /** The form's owner: a save that names nobody, which waits on the owner, and the viewer is not the owner. */
    owner,

    /** Whoever the task's save names (`saveWhen`), which is not the viewer. */
    others,
}

/**
 * One workflow of a form as it asks of the viewer (issue #1597): the [workflow] its cell shows, its [weight], and its
 * CTA's [keyword].
 */
class FormWorkflowWeight(
    val workflow: FormWorkflow,
    val weight: WfWeight,
    /** The search keyword ([CTAK]) of the workflow's call to action, as stored; null when it has none or is not engaged. */
    val keyword: String?,
)

/**
 * The classification (issue #1597): the workflows of a form's cell ([formWorkflowsOf], with [phaseOf] as it takes it),
 * each with what it asks of the viewer, sorted by [WfWeight] and otherwise in the cell's order. Pure, and in the kernel,
 * so the backend (a keyword search's `mine`, and its counts) and the page drawing the cell run one rule.
 *
 * It reads the form's stored state and nothing about its data. What it cannot know from state alone is whether the
 * viewer may act on a call to action, which turns on who they are: [mayAct] answers that, for an engaged workflow
 * whose state still calculates and has a CTA task, given the workflow and its stored state entry's data. The backend
 * answers it from the viewer's facts (the request's, `kdr:isOwner`, and the task's viewer facts); the page from the
 * workflows the listing says the caller may act on.
 *
 * An eligible workflow is [WfWeight.available] whoever looks: a keyword asking the viewer to engage (a `start` keyword)
 * would make it their turn, and none is on the list yet.
 */
fun formWorkflowWeights(
    states: List<Map<String, Any?>>,
    phaseOf: (String) -> WfPhase?,
    mayAct: (workflow: FormWorkflow, state: Map<String, Any?>) -> Boolean,
): List<FormWorkflowWeight> {
    val stateById = states
        .filter { it[GE.traitId].toOptStr() == WFS.workflowState }
        .map { it[GE.data].toJsonMapOrEmpty() }
        .associateBy { it[WFD.workflowId].toOptStr() }
    return formWorkflowsOf(states, phaseOf).map { workflow ->
        val state = stateById[workflow.workflowId].orEmpty()
        val engaged = workflow.category == WfColumnCategory.engaged
        val weight = when (workflow.category) {
            WfColumnCategory.engaged ->
                if (workflow.ctaTask != null && phaseOf(workflow.workflowId)?.calculates == true && mayAct(workflow, state)) {
                    WfWeight.yourTurn
                } else {
                    WfWeight.waiting
                }
            WfColumnCategory.eligible -> WfWeight.available
            WfColumnCategory.finished, WfColumnCategory.ineligible -> WfWeight.settled
        }
        FormWorkflowWeight(workflow, weight, keyword = state[WFS.ctaKeyword].toOptStr()?.takeIf { engaged })
    }.sortedBy { it.weight.ordinal }
}

/**
 * Whether a form whose cell is [weights] matches a keyword search (issue #1597) for [keyword], asking [question]
 * ([CTKQ]): [CTKQ.mine], some workflow carrying it is the viewer's turn; [CTKQ.any], some engaged workflow's call to
 * action carries it, whoever may act. Any other question matches nothing.
 */
fun matchesKeywordSearch(weights: List<FormWorkflowWeight>, keyword: String, question: String): Boolean = when (question) {
    CTKQ.mine -> weights.any { it.keyword == keyword && it.weight == WfWeight.yourTurn }
    CTKQ.any -> weights.any { it.keyword == keyword }
    else -> false
}
