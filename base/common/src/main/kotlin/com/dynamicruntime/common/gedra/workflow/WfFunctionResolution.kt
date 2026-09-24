package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientDef
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
    /** The client definition a bundle is held to -- the node's, unless a trial (issue #843) supplies a candidate. */
    clientDefOf: (String) -> ClientDef? = { ClientService.get(cxt).present(it) },
    /** The cfact names a client declares -- the node's, unless a trial supplies the candidate's. */
    cfactNamesOf: (String) -> Set<String> = { SchemaService.get(cxt).cfactsFor(it).names },
    /**
     * Whether to store what resolves on each `WfDef` and `WfTask`. A trial (issue #843) passes false: it wants the
     * problems, not the functions -- and the definitions it walks may be the node's own (a client's source-code
     * bundles are shared with the running collector), which a refused write must leave as they were.
     */
    assign: Boolean = true,
) {
    val byFn: Map<String, WfFunctionCreation> = creations.associateBy { it.fn }

    for (bundle in configs.configs) {
        val client = bundle.gedraId.client
        if (onlyClient != null && client != onlyClient) continue
        val declaredCfacts = cfactNamesOf(client)
        // The labels the client suggests (issue #786), or null for a bundle with no client definition present
        // here -- a global workflow has no one client's list to be held to, so its labels are not checked.
        val suggestedLabels = clientDefOf(client)?.userLabels?.toSet()
        val bundleScope = ResolutionScope(cxt, bundle, byFn, declaredCfacts, suggestedLabels, issues)
        for (def in bundle.workflows.values) {
            val defFunctions = bundleScope.resolveList(
                def.workflowId, "the workflow", def.functionUsages, WfEventScope.global, collectedTraits = null,
            )
            if (assign) def.resolvedFunctions = defFunctions
            for (task in def.tasks) {
                val taskFunctions = bundleScope.resolveList(
                    def.workflowId, "task '${task.id}'", task.functionUsages, WfEventScope.task,
                    collectedTraits = task.traits.map { it.traitId }.toSet(),
                )
                if (assign) task.resolvedFunctions = taskFunctions
                bundleScope.checkApprovalAuthority(def, task, taskFunctions)
                bundleScope.checkSaveRuleViewerFacts(def, task, taskFunctions)
            }
            bundleScope.checkSaveRuleLocks(def)
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
private fun ResolutionScope.checkApprovalAuthority(def: WfDef, task: WfTask, resolved: List<WfFunction>) {
    task.approval ?: return
    if (!grantsReviewer(task, resolved)) {
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
 * Whether one of [task]'s own **resolved** `viewerCfacts` functions can emit [WFC.reviewer] (issues #787, #856) -- the
 * only source of that fact. Shared by the two checks that need it, so what grants review is decided in one place.
 */
private fun ResolutionScope.grantsReviewer(task: WfTask, resolved: List<WfFunction>): Boolean {
    val resolvedFns = resolved.map { it.fn }.toSet()
    return task.functionUsages.any { usage ->
        val creation = byFn[usage.fn]
        usage.fn in resolvedFns && creation?.event == WfEventType.viewerCfacts &&
            WFC.reviewer in creation.emittedCfacts(usage)
    }
}

/**
 * A task's rule for who may save it (issue #856) that names [WFC.reviewer] -- the viewer fact a task's `viewerCfacts`
 * functions grant -- needs one of the task's own resolved functions to emit it: nothing else puts it in the facts the
 * rule is judged on. Without one the rule is judged as though nobody is a reviewer, whichever way it reads, which is a
 * configuration mistake rather than an intent -- so it is reported, the way [checkApprovalAuthority] reports an
 * approval nobody could give.
 */
private fun ResolutionScope.checkSaveRuleViewerFacts(def: WfDef, task: WfTask, resolved: List<WfFunction>) {
    val rule = task.saveWhen ?: return
    val named = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(rule).map { it.value }.toSet()
    if (WFC.reviewer !in named) return
    if (!grantsReviewer(task, resolved)) {
        reportConfigProblem(
            cxt,
            bundle.issue(
                "Workflow '${def.workflowId}' in client '$client' has task '${task.id}' whose rule for who may save it " +
                    "names '${WFC.reviewer}', but no viewerCfacts function on the task emits it (a userHasLabel, say).",
                "Keeping the task; its rule reads as though nobody is a reviewer until one is added.",
                GCEL.workflow, def.workflowId,
            ),
            issues,
        )
    }
}

/**
 * A task whose rule for who may save it (issue #856) protects a trait no lock covers (issue #857) is a config problem:
 * the rule governs only the task's own save, so the raw editor and the patch endpoint would still let anyone the rule
 * refuses change the trait -- acme's follow-up shipped that way. Reported like every other definition mistake, so it is
 * caught **before it is live**: it refuses the boot (or a client reload) wherever config is checked strictly -- a
 * developer's instance and the tests -- and in production, where a node must start, it is logged and recorded on the
 * client's config issues and the operator report. The workflow is kept either way; only the lock is missing.
 */
private fun ResolutionScope.checkSaveRuleLocks(def: WfDef) {
    for ((taskId, traits) in def.unlockedSaveRuleTraits()) {
        reportConfigProblem(
            cxt,
            bundle.issue(
                "Workflow '${def.workflowId}' in client '$client' restricts who may save task '$taskId', but no lock " +
                    "covers its trait(s) ${traits.joinToString(", ") { "'$it'" }}, so anyone the rule refuses can " +
                    "still change them through the raw editor or the patch endpoint. Add a lock on each, writable " +
                    "via '$taskId'.",
                "Keeping the workflow; the trait(s) stay editable outside the task until a lock is added.",
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
