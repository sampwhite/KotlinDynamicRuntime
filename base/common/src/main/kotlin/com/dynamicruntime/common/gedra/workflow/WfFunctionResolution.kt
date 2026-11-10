package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.CFactParser
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
        // The traits the client can see -- its own and the global ones -- for a function reading anywhere in the form.
        val visibleTraits = configs.traitsFor(client).map { it.traitId }.toSet()
        val bundleScope = ResolutionScope(cxt, bundle, byFn, declaredCfacts, suggestedLabels, visibleTraits, issues)
        for (def in bundle.workflows.values) {
            val defFunctions = bundleScope.resolveList(
                def.workflowId, "the workflow", def.functionUsages, WfEventScope.global, collectedTraits = null,
            )
            if (assign) def.resolvedFunctions = defFunctions
            for (task in def.tasks) {
                val taskFunctions = bundleScope.checkCtaFilters(
                    def, task,
                    bundleScope.resolveList(
                        def.workflowId, "task '${task.id}'", task.functionUsages, WfEventScope.task,
                        collectedTraits = task.traits.map { it.traitId }.toSet(),
                    ),
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
    /** The trait ids the client can see (issue #1594): what a function reading anywhere in the form may name. */
    val visibleTraits: Set<String>,
    val issues: MutableList<GedraConfigIssue>,
) {
    val client: String get() = bundle.gedraId.client
}

/**
 * An approval task (issue #787) whose rule for who may approve names [WFC.reviewer] -- as the default rule does
 * (`WfApproval.defaultApproveWhen`, issue #1595) -- is approved only by a caller the task's `viewerCfacts` functions call
 * one. So a task none of whose *resolved* functions can emit it could never be approved by anyone, which is reported
 * here rather than discovered by the first reviewer who cannot press the button. Reported, not dropped: there is
 * nothing to drop that would make the task work. A rule naming no reviewer (`kdr:isOwner`, the owner's own step)
 * needs no such function.
 *
 * An approval with steps (issue #1702) also has each step's rule stored as its CTA filter while the step is current, so
 * each must name only facts about the person, as a `ctaFilter` function's filter must ([ctaFilterProblem]). Reported, not
 * dropped, for the same reason.
 */
private fun ResolutionScope.checkApprovalAuthority(def: WfDef, task: WfTask, resolved: List<WfFunction>) {
    val approval = task.approval ?: return
    if (approval.steps.isNotEmpty()) {
        val personFacts = personFacts(resolved)
        approval.steps.firstNotNullOfOrNull { step ->
            ctaFilterProblem(declaredCfacts, personFacts, step.effectiveApproveWhen)
                ?.let { "step '${step.id}''s rule for who may give it $it" }
        }?.let { why ->
            reportConfigProblem(
                cxt,
                bundle.issue(
                    "Workflow '${def.workflowId}' in client '$client' has an approval task '${task.id}' whose $why.",
                    "Keeping the task; a listing cannot tell whose turn the step is.",
                    GCEL.workflow, def.workflowId,
                ),
                issues,
            )
        }
    }
    if (approval.approveWhens.none { WFC.reviewer in CFactParser.analyze(it, emptySet()).names }) return
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
 * The facts about the person a task's rules may name where a listing evaluates them (issues #1594, #1702): the request's,
 * the row's (`kdr:isOwner`), and what the task's resolved `viewerCfacts` functions emit -- from [resolved], the list just
 * resolved, not the task's stored one, since a trial resolves without assigning. What a CTA filter and an approval step's
 * rule are both held to.
 */
private fun ResolutionScope.personFacts(resolved: List<WfFunction>): Set<String> =
    SchemaService.get(cxt).cfactsFor(client).requestNames + RCF.all +
        resolved.filter { it.event == WfEventType.viewerCfacts }.flatMap { ViewerCfactsFn.of(it).emittedCfacts }

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
 * A task's `ctaFilter` functions (issue #1594), held to what a CTA filter is for: each sits on a normal workflow's
 * task, since only that has a call to action; reads only traits the client has, since a misspelled one would never
 * match and quietly route every form the same way; and every filter it can set parses and names only facts about the
 * person -- the request's, and what the task's own resolved `viewerCfacts` functions emit -- since a listing evaluates
 * the filter against those alone, without the form. A function failing any of these is dropped and reported, so a
 * filter a listing could not evaluate is never stored. Returns [resolved] without the dropped ones.
 */
private fun ResolutionScope.checkCtaFilters(def: WfDef, task: WfTask, resolved: List<WfFunction>): List<WfFunction> {
    if (resolved.none { it.event == WfEventType.ctaFilter }) return resolved
    val personFacts = personFacts(resolved)
    return resolved.filter { fn ->
        if (fn.event != WfEventType.ctaFilter) return@filter true
        val ctaFn = CtaFilterFn.of(fn)
        val unknownTraits = ctaFn.readTraits - visibleTraits
        val why = when {
            def.entry != WfEntry.normal -> "sets a call to action's filter, which only a normal workflow's task has"
            unknownTraits.isNotEmpty() -> "reads trait(s) ${unknownTraits.sorted()}, which the client does not have"
            else -> ctaFn.possibleFilters.firstNotNullOfOrNull { filter ->
                ctaFilterProblem(declaredCfacts, personFacts, filter)?.let { "can set the CTA filter '$filter', which $it" }
            }
        } ?: return@filter true
        reportConfigProblem(
            cxt,
            bundle.issue(
                "Workflow '${def.workflowId}' in client '$client' declares function '${fn.fn}' on task '${task.id}', which $why.",
                "Dropping that function.",
                GCEL.function, "${def.workflowId}/${fn.fn}",
            ),
            issues,
        )
        false
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
    // The names as the cfact grammar reads them, registered or not -- not a word scan, which would split a rooted
    // name like `kdr:reviewer` at its colon (issue #952).
    if (WFC.reviewer !in CFactParser.analyze(rule, emptySet()).names) return
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
