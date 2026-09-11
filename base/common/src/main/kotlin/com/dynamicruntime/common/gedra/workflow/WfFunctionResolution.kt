package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.SchemaService

/**
 * The **second pass** that resolves each workflow definition's function *usages* into runnable functions, in
 * place, once every component has registered its creations (issue #677).
 *
 * This is the two-pass initialization the design (and Cedar) use: a client may declare a function another
 * component defines, so nothing can be resolved while configs are still arriving -- only after. It fills the
 * `resolvedFunctions` store on each `WfDef` and every `WfTask`, and reports a function that will not resolve
 * as a config problem that drops **only that function**, not the workflow. Run in `WorkflowService.build`, so it
 * re-runs on a client reload the same way the registries do.
 */
fun resolveWorkflowFunctions(
    cxt: KdrCxt,
    configs: GedraConfigCollector,
    creations: List<WfFunctionCreation>,
    mode: BootCheckMode,
    issues: MutableList<GedraConfigIssue>,
) {
    val byFn: Map<String, WfFunctionCreation> = creations.associateBy { it.fn }
    val schemaService = SchemaService.get(cxt)

    for (bundle in configs.configs) {
        val client = bundle.gedraId.client
        val declaredCfacts = schemaService.cfactsFor(client).names
        for (def in bundle.workflows.values) {
            def.resolvedFunctions = resolveList(
                cxt, client, def.workflowId, "the workflow", def.functionUsages, WfEventScope.global,
                byFn, declaredCfacts, mode, issues,
            )
            for (task in def.tasks) {
                task.resolvedFunctions = resolveList(
                    cxt, client, def.workflowId, "task '${task.id}'", task.functionUsages, WfEventScope.task,
                    byFn, declaredCfacts, mode, issues,
                )
            }
        }
    }
}

/** Resolves one scope's usage list, dropping (with a reported problem) any that will not build. */
private fun resolveList(
    cxt: KdrCxt,
    client: String,
    workflowId: String,
    where: String,
    usages: List<WfFunctionUsage>,
    scope: WfEventScope,
    byFn: Map<String, WfFunctionCreation>,
    declaredCfacts: Set<String>,
    mode: BootCheckMode,
    issues: MutableList<GedraConfigIssue>,
): List<WfFunction> {
    if (usages.isEmpty()) {
        return emptyList()
    }
    val out = mutableListOf<WfFunction>()
    for (usage in usages) {
        fun drop(why: String) = reportConfigProblem(
            cxt, mode,
            GedraConfigIssue(
                "Workflow '$workflowId' in client '$client' declares function '${usage.fn}' on $where, which $why.",
                "Dropping that function.",
            ),
            issues,
        )
        val creation = byFn[usage.fn]
        val undeclaredCfacts = creation?.let { it.emittedCfacts(usage) - declaredCfacts } ?: emptySet()
        when {
            creation == null ->
                drop("is not a registered workflow function")
            creation.event.scope != scope ->
                drop("is '${creation.event}' (${creation.event.scope}-scoped) and does not belong on a $scope list")
            undeclaredCfacts.isNotEmpty() ->
                drop("emits cfact(s) ${undeclaredCfacts.sorted()} the client does not declare")
            else -> {
                // Only a KdrException means "bad initialization data" -- the create contract. Anything else is a
                // defect in the creation itself, and is left to propagate rather than mislabeled and swallowed.
                val built = try {
                    creation.create(cxt, usage)
                } catch (e: KdrException) {
                    drop("has invalid initialization data: ${e.message}")
                    null
                }
                if (built != null) out.add(built)
            }
        }
    }
    // The usages are already priority-ordered; sort the built list too, defensively, in case a creation set a
    // priority of its own.
    return out.sortedBy { it.priority }
}
