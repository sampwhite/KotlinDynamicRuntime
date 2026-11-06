package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactPredicate
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.filterByCFacts
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * What stands behind a form's **Needs Review** or **Finished** chip (issue #789): the engaged workflows emitting
 * that framework singleton cfact, each with its name and what its current task asks of the person looking.
 *
 * ### From stored state alone
 *
 * The design's constraint: this must not pull in the form's data. Everything comes from the form's **state** --
 * each workflow's stored entry says which singletons it emits (`singletonCfacts`), its current task (`ctaTask`) and
 * that task's status (`ctaStatus`) -- plus what can be asked of the **caller** without the form: their request
 * cfacts and the task's `viewerCfacts` functions (a reviewer is decided by the viewer's labels), and the caller's
 * relation to the form ([RCF.isOwner]), which the row it already holds says. That is enough to
 * resolve the CTA task's display for this caller, since the facts a display chooses on are exactly these: it is the
 * CTA (so `kdr:isCta`), it is not yet approved (a CTA never is), whether it is complete (stored), and the viewer's
 * own. The acting user is the whole reason the text is fetched from the backend rather than read off the listing.
 */
object SingletonWorkflows {
    /**
     * The workflows [states] say emit [cfact], as [cxt]'s caller sees them. [client] is the form's; [states] its
     * state entries, already read in the caller's scope; [rowFacts] the caller's relation to it ([RowCfacts], issue
     * #1595), which a display or a stored CTA filter may name -- worked out from the form's owner, not its data.
     */
    fun of(
        cxt: KdrCxt,
        client: String,
        states: List<Map<String, Any?>>,
        cfact: String,
        rowFacts: Set<String>,
    ): List<Map<String, Any?>> {
        val registry = WorkflowService.get(cxt).forClient(client)
        val cfacts = SchemaService.get(cxt).cfactsFor(client)
        // The request's facts and the caller's relation to this one form: per form, as the call is.
        val callerFacts = cfacts.assemble(cxt) + rowFacts
        val viewer = ViewerCfacts(cxt, client)
        val fragments = MarkdownFragmentService.get(cxt)
        fun label(text: String): String = fragments.backendPass(cxt, text)

        return states
            .filter { it[GE.traitId].toOptStr() == WFS.workflowState }
            .map { it[GE.data].toJsonMapOrEmpty() }
            .filter { data -> data[WFS.singletonCfacts].toJsonListOrEmpty().any { it.toOptStr() == cfact } }
            .mapNotNull { data ->
                val workflowId = data[WFD.workflowId].toOptStr() ?: return@mapNotNull null
                // A retired workflow contributes no singletons, so a declared one is always found; skip if not. Nor
                // does one outside its lifetime (issue #790), though its entry lingers until the form's next recompute, nor
                // one the caller does not see (issue #1384).
                val def = WorkflowPhases.liveFor(cxt, registry, workflowId)?.def ?: return@mapNotNull null
                val out = linkedMapOf<String, Any?>(
                    WFD.workflowId to workflowId,
                    WFD.label to (def.label.takeIf { it.isNotBlank() }?.let { label(it) } ?: workflowId),
                )
                // A frozen workflow (past its relevancy) still stands behind the chip, but nothing can be done in it
                // -- its saves and approvals are refused -- so it is listed without an action.
                val task = data[WFS.ctaTask].toOptStr()?.takeIf { WorkflowPhases.of(cxt, registry.client, def).calculates }?.let { def.task(it) }
                if (task != null) {
                    val viewerFacts = viewer.forTask(task)
                    // The CTA's filter rule (issue #1595) as the state stores it, evaluated for this caller: this listing
                    // reads state alone, so it shows who the step is for as of the last recompute. Permission (the approve
                    // endpoint, the judge) works the rule out again from the form's data.
                    val passes = passesCtaFilterRule(cfacts, data[WFS.ctaFilterRule].toOptStr(), callerFacts + viewerFacts)
                    val complete = data[WFS.ctaStatus].toJsonMapOrEmpty()[CDF.complete] == true
                    // The CTA's task facts, from state: available, the CTA, complete as stored, never approved.
                    val taskFacts = buildSet {
                        add(WFC.taskAvailable)
                        add(WFC.isCta)
                        if (complete) add(WFC.taskComplete)
                        if (passes) add(WFC.passesCtaFilter)
                        // The workflow's CTA keyword fact (issue #1596), as the state stores the keyword.
                        CTAK.of(data[WFS.ctaKeyword].toOptStr())?.let { add(it.workflowCfact) }
                    }
                    out[WFS.ctaTask] = task.id
                    out[SWF.actionText] = actionText(task, callerFacts + taskFacts + viewerFacts, cfacts::parse, ::label)
                    // A reviewer of the current work, as the owner-only rule asks it: the functions' reviewer, and the rule.
                    out[SWF.isReviewer] = WFC.reviewer in viewerFacts && passes
                }
                out
            }
    }

    /**
     * What [task] asks of a caller with [facts], in words: its display branch for them when it declares one -- the
     * text of a text branch, or for the task's own rendering its approval button (an approval task) or its label --
     * and its label when it declares none.
     *
     * A branch's text may carry `${…}` blocks, which the layout rule leaves for the frontend to resolve against
     * the **form's data** -- exactly what this listing never reads. Such a text cannot be finished here or in the
     * popover, so the task's own words stand in for it rather than a half-rendered template.
     */
    fun actionText(
        task: WfTask,
        facts: Set<String>,
        parse: (String) -> CFactPredicate,
        label: (String) -> String,
    ): String {
        fun own() = label(task.approval?.button ?: task.label)
        val branch = task.display?.let { filterByCFacts(mapOf(WFD.display to it), facts, parse)[WFD.display] }
            .toJsonMapOrEmpty()
        val mode = branch[WDSP.mode].toOptStr() ?: WDSP.defaultMode
        if (mode != WDSP.textMode) return own()
        val text = branch[WDSP.text].toOptStr()?.let(label) ?: return own()
        return if (needsFormData(text)) own() else text
    }

    /** Whether [text] still holds a frontend `${…}` block after the backend pass. */
    fun needsFormData(text: String): Boolean = $$"${" in text
}
