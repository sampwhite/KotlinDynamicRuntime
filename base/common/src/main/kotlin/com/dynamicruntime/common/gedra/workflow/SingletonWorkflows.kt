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
 * cfacts and the task's `viewerCfacts` functions (a reviewer is decided by the viewer's labels). That is enough to
 * resolve the CTA task's display for this caller, since the facts a display chooses on are exactly these: it is the
 * CTA (so `wfIsCta`), it is not yet approved (a CTA never is), whether it is complete (stored), and the viewer's
 * own. The acting user is the whole reason the text is fetched from the backend rather than read off the listing.
 */
object SingletonWorkflows {
    /**
     * The workflows [states] say emit [cfact], as [cxt]'s caller sees them. [client] is the form's; [states] its
     * state entries, already read in the caller's scope.
     */
    fun of(cxt: KdrCxt, client: String, states: List<Map<String, Any?>>, cfact: String): List<Map<String, Any?>> {
        val registry = WorkflowService.get(cxt).forClient(client)
        val cfacts = SchemaService.get(cxt).cfactsFor(client)
        val requestFacts = cfacts.assemble(cxt)
        val viewer = ViewerCfacts(cxt, client)
        val fragments = MarkdownFragmentService.get(cxt)
        fun label(text: String): String = fragments.backendPass(cxt, text)

        return states
            .filter { it[GE.traitId].toOptStr() == WFS.workflowState }
            .map { it[GE.data].toJsonMapOrEmpty() }
            .filter { data -> data[WFS.singletonCfacts].toJsonListOrEmpty().any { it.toOptStr() == cfact } }
            .mapNotNull { data ->
                val workflowId = data[WFD.workflowId].toOptStr() ?: return@mapNotNull null
                // A retired workflow contributes no singletons, so a declared one is always found; skip if not.
                val def = registry.workflow(workflowId)?.def ?: return@mapNotNull null
                val out = linkedMapOf<String, Any?>(
                    WFD.workflowId to workflowId,
                    WFD.label to (def.label.takeIf { it.isNotBlank() }?.let { label(it) } ?: workflowId),
                )
                val task = data[WFS.ctaTask].toOptStr()?.let { def.task(it) }
                if (task != null) {
                    val viewerFacts = viewer.forTask(task)
                    val complete = data[WFS.ctaStatus].toJsonMapOrEmpty()[SVY.complete] == true
                    // The CTA's task facts, from state: available, the CTA, complete as stored, never approved.
                    val taskFacts = buildSet {
                        add(WFC.taskAvailable)
                        add(WFC.isCta)
                        if (complete) add(WFC.taskComplete)
                    }
                    out[WFS.ctaTask] = task.id
                    out[SWF.actionText] = actionText(task, requestFacts + taskFacts + viewerFacts, cfacts::parse, ::label)
                    out[SWF.isReviewer] = WFC.reviewer in viewerFacts
                }
                out
            }
    }

    /**
     * What [task] asks of a caller with [facts], in words: its display branch for them when it declares one -- the
     * text of a text branch, or for the task's own rendering its approval button (an approval task) or its label --
     * and its label when it declares none.
     */
    private fun actionText(
        task: WfTask,
        facts: Set<String>,
        parse: (String) -> CFactPredicate,
        label: (String) -> String,
    ): String {
        val own = task.approval?.button ?: task.label
        val branch = task.display?.let { filterByCFacts(mapOf(WFD.display to it), facts, parse)[WFD.display] }
            .toJsonMapOrEmpty()
        val mode = branch[WDSP.mode].toOptStr() ?: WDSP.defaultMode
        return label(if (mode == WDSP.textMode) branch[WDSP.text].toOptStr() ?: own else own)
    }
}
