package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactParser
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientWorkflowEdit
import com.dynamicruntime.common.user.UserLabelUse
import com.dynamicruntime.common.user.UserLabelUseKind
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * What a client's workflows do with each user label (issue #1658), worked out on read from the workflows the client
 * runs -- so the Users editor can say why a person might need a label, and never states a meaning the configuration
 * does not give it.
 *
 * Two kinds of use, as a label means something in only two places:
 *  - **A task's `userHasLabel` function** ([ULH]): the fact it emits for a viewer carrying the label, classified by what
 *    the task's rules do with it -- an approval rule naming it ([UserLabelUseKind.approves]), a rule for who may save
 *    ([UserLabelUseKind.saves]), or neither ([UserLabelUseKind.tests], a display branch, say).
 *  - **A restricted workflow's visibility** naming the label ([UserLabelUseKind.sees]).
 *
 * A workflow turned off (issue #1635) is skipped: while off it asks nothing of anybody.
 */
object UserLabelUseReader {
    /** Each label [client]'s workflows use, to its uses in workflow and task order. A label none uses is absent. */
    fun usesByLabel(cxt: KdrCxt, client: String): Map<String, List<UserLabelUse>> {
        val params = WorkflowParamsService.get(cxt)
        val out = linkedMapOf<String, MutableList<UserLabelUse>>()
        for (declared in WorkflowService.get(cxt).forClient(client).workflows.values) {
            val def = declared.def
            val resolved = params.resolve(cxt, client, def.workflowId)
            if (resolved.inForce.enabled == false) continue
            // The labels as the client's page shows them, fragment pulls resolved; as written when none pulls any.
            val shown = ClientWorkflowEdit.shownLabels(cxt, client, def)
            val workflowLabel = shown?.get(WFD.label).toOptStr() ?: def.label.ifEmpty { def.workflowId }
            val taskLabels = shown?.get(WFD.tasks).toJsonMapOrEmpty()
            for (task in def.tasks) {
                // Only a function that resolved: one dropped as a config problem tests for nothing.
                if (task.resolvedFunctions.none { it.fn == ULH.fn }) continue
                for (usage in task.functionUsages) {
                    if (usage.fn != ULH.fn) continue
                    val label = usage.initData[ULH.label].toOptStr() ?: continue
                    val cfact = usage.initData[ULH.cfact].toOptStr() ?: continue
                    out.getOrPut(label) { mutableListOf() }.add(
                        UserLabelUse(
                            kind = kindOf(task, cfact),
                            workflowId = def.workflowId,
                            workflowLabel = workflowLabel,
                            taskId = task.id,
                            taskLabel = taskLabels[task.id].toOptStr() ?: task.label.ifEmpty { null },
                            cfact = cfact,
                        ),
                    )
                }
            }
            val visibility = resolved.visibility
            if (visibility.mode == WfVisibilityMode.restricted) {
                for (label in visibility.labels) {
                    out.getOrPut(label) { mutableListOf() }.add(
                        UserLabelUse(UserLabelUseKind.sees, def.workflowId, workflowLabel),
                    )
                }
            }
        }
        return out
    }

    /**
     * What [task] does with [cfact], the fact its `userHasLabel` emits: lets its holder approve when the approval's rule
     * names it (the default rule names `kdr:reviewer`), save when the rule for who may save names it, and otherwise
     * only changes what the task shows. The names as the cfact grammar reads them, so a rooted name is not split.
     */
    fun kindOf(task: WfTask, cfact: String): UserLabelUseKind {
        fun names(rule: String?) = rule != null && cfact in CFactParser.analyze(rule, emptySet()).names
        return when {
            task.approval?.let { names(it.effectiveApproveWhen) } == true -> UserLabelUseKind.approves
            names(task.saveWhen) -> UserLabelUseKind.saves
            else -> UserLabelUseKind.tests
        }
    }
}
