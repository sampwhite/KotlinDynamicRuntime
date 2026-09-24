package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.issue
import com.dynamicruntime.common.gedra.reportConfigProblem
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
    issues: MutableList<GedraConfigIssue>,
    /**
     * Resolve only this client's bundles (issue #842): on a reload they are the freshly reassembled ones, while
     * every other bundle already holds what the boot (or its own reload) resolved. Null resolves them all.
     */
    onlyClient: String? = null,
) {
    val byFn: Map<String, WfFunctionCreation> = creations.associateBy { it.fn }
    val schemaService = SchemaService.get(cxt)

    for (bundle in configs.configs) {
        val client = bundle.gedraId.client
        if (onlyClient != null && client != onlyClient) continue
        val declaredCfacts = schemaService.cfactsFor(client).names
        // The labels the client suggests (issue #786), or null for a bundle with no client definition present
        // here -- a global workflow has no one client's list to be held to, so its labels are not checked.
        val suggestedLabels = ClientService.get(cxt).present(client)?.userLabels?.toSet()
        val bundleScope = ResolutionScope(cxt, bundle, byFn, declaredCfacts, suggestedLabels, issues)
        for (def in bundle.workflows.values) {
            def.resolvedFunctions = bundleScope.resolveList(
                def.workflowId, "the workflow", def.functionUsages, WfEventScope.global, collectedTraits = null,
            )
            for (task in def.tasks) {
                task.resolvedFunctions = bundleScope.resolveList(
                    def.workflowId, "task '${task.id}'", task.functionUsages, WfEventScope.task,
                    collectedTraits = task.traits.map { it.traitId }.toSet(),
                )
                bundleScope.checkApprovalAuthority(def, task)
            }
        }
    }
}

/**
 * What every usage in one config bundle is checked against -- the same for each of its workflows and tasks, so
 * gathered once rather than passed down as a dozen parameters. [suggestedLabels] is the client's suggested user
 * labels (issue #786), or null when there is no client list to check a literal label against.
 */
private class ResolutionScope(
    val cxt: KdrCxt,
    /** The bundle whose workflows are resolved -- the holder every issue names (issue #839). */
    val bundle: GedraConfig,
    val byFn: Map<String, WfFunctionCreation>,
    val declaredCfacts: Set<String>,
    val suggestedLabels: Set<String>?,
    val issues: MutableList<GedraConfigIssue>,
) {
    val client: String get() = bundle.gedraId.client
}

/**
 * An approval task (issue #787) is approved only by a caller the task's `viewerCfacts` functions call a
 * [WFC.reviewer] -- the approve endpoint asks exactly that. So a task none of whose *resolved* functions can emit
 * it could never be approved by anyone, which is reported here rather than discovered by the first reviewer who
 * cannot press the button. Reported, not dropped: there is nothing to drop that would make the task work.
 */
private fun ResolutionScope.checkApprovalAuthority(def: WfDef, task: WfTask) {
    task.approval ?: return
    val resolved = task.resolvedFunctions.map { it.fn }.toSet()
    val grantsReview = task.functionUsages.any { usage ->
        val creation = byFn[usage.fn]
        usage.fn in resolved && creation?.event == WfEventType.viewerCfacts && WFC.reviewer in creation.emittedCfacts(usage)
    }
    if (!grantsReview) {
        reportConfigProblem(
            cxt,
            bundle.issue(
                "Workflow '${def.workflowId}' in client '$client' has an approval task '${task.id}' with no " +
                    "viewerCfacts function emitting '${WFC.reviewer}' (a userHasLabel, say), so nobody could approve it.",
                "Keeping the task; it cannot be approved until one is added.",
                GCEL.workflow, def.workflowId,
            ),
            issues,
        )
    }
}

/**
 * Resolves one placement's usage list, dropping (with a reported problem) any that will not build.
 * [collectedTraits] is what the placement collects, for the referenced-trait check -- null on the workflow-global
 * list, whose functions read anywhere in the form rather than one task's collected traits.
 */
private fun ResolutionScope.resolveList(
    workflowId: String,
    where: String,
    usages: List<WfFunctionUsage>,
    scope: WfEventScope,
    collectedTraits: Set<String>?,
): List<WfFunction> {
    if (usages.isEmpty()) {
        return emptyList()
    }
    val out = mutableListOf<WfFunction>()
    for (usage in usages) {
        fun drop(why: String) = reportConfigProblem(
            cxt,
            bundle.issue(
                "Workflow '$workflowId' in client '$client' declares function '${usage.fn}' on $where, which $why.",
                "Dropping that function.",
                GCEL.function, "$workflowId/${usage.fn}",
            ),
            issues,
        )
        val creation = byFn[usage.fn]
        val undeclaredCfacts = creation?.let { it.emittedCfacts(usage) - declaredCfacts } ?: emptySet()
        val uncollectedTraits =
            if (creation != null && collectedTraits != null) creation.referencedTraits(usage) - collectedTraits else emptySet()
        val unsuggestedLabels =
            if (creation != null && suggestedLabels != null) creation.referencedUserLabels(usage) - suggestedLabels else emptySet()
        when {
            creation == null ->
                drop("is not a registered workflow function")
            creation.event.scope != scope ->
                drop("is '${creation.event}' (${creation.event.scope}-scoped) and does not belong on a $scope list")
            undeclaredCfacts.isNotEmpty() ->
                drop("emits cfact(s) ${undeclaredCfacts.sorted()} the client does not declare")
            uncollectedTraits.isNotEmpty() ->
                drop("references trait(s) ${uncollectedTraits.sorted()} the task does not collect")
            unsuggestedLabels.isNotEmpty() ->
                drop("tests for user label(s) ${unsuggestedLabels.sorted()} the client does not suggest (userLabels)")
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
