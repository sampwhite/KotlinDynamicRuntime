package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.cfact.parseCFactOrAlways
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.checkMode
import com.dynamicruntime.common.gedra.gedraConfigCheckMode
import com.dynamicruntime.common.gedra.issue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.gedra.supportedTraits
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.collectExpressions
import com.dynamicruntime.common.util.analyzeTemplate
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/** One workflow as declared: the definition and the bundle it came from, which together make its [ref]. */
class WfDeclared(val bundle: GedraConfig, val def: WfDef) {
    /** How a stored gedra refers to this workflow: the bundle id (revision included) and the workflow id. */
    val ref: WfRef = WfRef(bundle.gedraId, def.workflowId)

    override fun toString(): String = ref.text
}

/**
 * The workflows one scope sees, keyed by workflow id, after shadowing (issue #533).
 *
 * There is one per client, plus the global one every client's is built from -- the shape `CFactRegistries`
 * and `SchemaService.storeFor` share, and for the same reason: a client's own bundles may add to or replace
 * what the deployment ships, and a request needs one answer for "which workflows apply here".
 */
class WorkflowRegistry(
    /** The client this is for, or null for the global registry. */
    val client: String?,
    /** Every workflow visible in this scope, by id, in arrival order. */
    val workflows: Map<String, WfDeclared>,
) {
    /** The creation workflow of this scope, or null when it has none. At most one, by check. */
    val creation: WfDeclared? = workflows.values.firstOrNull { it.def.entry == WfEntry.creation }

    /** The survey workflow of this scope, or null when it has none. At most one, by check (issue #656). */
    val survey: WfDeclared? = workflows.values.firstOrNull { it.def.entry == WfEntry.survey }

    /** The workflow named, or null. */
    fun workflow(id: String): WfDeclared? = workflows[id]

    override fun toString(): String = "${client ?: GID.globalClient}: ${workflows.keys}"
}

/** The global registry and each client's; a client absent from [byClient] sees [global]. */
class WorkflowRegistries(val global: WorkflowRegistry, val byClient: Map<String, WorkflowRegistry>) {
    /** The registry [client] sees: their own when they have one, otherwise [global]. */
    fun forClient(client: String?): WorkflowRegistry =
        if (client == null) global else byClient[client] ?: global

    companion object {
        val empty: WorkflowRegistries = WorkflowRegistries(WorkflowRegistry(null, emptyMap()), emptyMap())
    }
}

/**
 * What a label's backend fragment pull resolved to, as the check needs it: whether the file exists at all,
 * whether it is a **backend** file (a backend pull may not name a served file), and whether the key is there.
 */
class WfFragmentHit(val found: Boolean, val backend: Boolean, val present: Boolean)

/**
 * Looks a three-part fragment reference up for one client -- the seam through which the check reaches the
 * fragment service without depending on it, so the checks are testable over plain data.
 */
fun interface WfFragmentLookup {
    fun lookup(client: String?, fileId: String, namespace: String, key: String): WfFragmentHit?
}

/**
 * Builds the global workflow registry and each present client's from the collected bundles, refusing or
 * dropping what does not hold up (issue #533).
 *
 * **Shadowing.** A client sees the global workflows plus its own; its own **replaces** a global one of the
 * same id, and a client declaring a **singleton-kind** workflow (a creation or a survey, of which a scope has
 * at most one) replaces the global one of that kind for that client -- the same overlay rule every other config
 * layer follows, so a deployment can ship a default a client customizes. The at-most-one-per-singleton-kind
 * check runs *after* shadowing, on what the client actually sees.
 *
 * **What is checked**, per scope and per workflow -- each a thing that would otherwise fail silently or late:
 *
 * - The entry kind is one that is built. `creation` and `survey` are; a `normal` declared today would be
 *   accepted and inert, which is worse than a refusal.
 * - Every trait a task collects is one the scope may use: the client's supported set, or for a global
 *   workflow the traits global can see. A workflow naming a trait its client cannot store would fail at the
 *   first save, for one client, after the form had been filled in.
 * - Every label's backend fragment pull (`%{@t("file.namespace.key")}`) resolves: three parts, an existing
 *   **backend** file, a present key -- unless the pull guards its own absence. The same static check the
 *   fragment service runs over files, applied to the labels a definition carries; a frontend pull (`${...}`)
 *   binds at request time and is the author's assertion, as it is everywhere.
 * - At most one workflow of each single-instance kind (creation, survey) per scope, after shadowing.
 *
 * No cfact expressions are checked, because the model carries none yet (selectors are deferred); when it does,
 * they parse here against the scope's registry, as UiBlocks' do.
 *
 * A problem is handed to [reportConfigProblem], judged by the origin of the workflow's own bundle (issue #839):
 * for source config a refused boot everywhere but production, for stored config only in unit tests. When
 * forgiven, the workflow is **dropped from that scope** and the node carries on -- proportionate, because a
 * workflow that is not there is a state the design already defines (the client's people see no such workflow),
 * where a half-checked one would run.
 */
fun buildWorkflowRegistries(
    cxt: KdrCxt,
    configs: GedraConfigCollector,
    /** The clients present on this node, by id, with their definitions (null when a definition was dropped). */
    clients: Map<String, ClientDef?>,
    /** The qualified type names a client overlaid -- what `supportedTraits` reads as "customized". */
    overlaidTypes: (client: String) -> Set<String>,
    fragments: WfFragmentLookup,
    /**
     * The cfact names a scope's expressions may use -- its cfact registry's (null for the global scope). What an
     * eligibility test (issue #783) is parsed against, so a misspelled cfact refuses the workflow at boot rather
     * than being a test that silently never passes.
     */
    cfactNames: (scope: String?) -> Set<String>,
    issues: MutableList<GedraConfigIssue>,
    /**
     * Build only this client's registry (issue #842), inheriting from [runningGlobal] rather than re-checking the
     * global scope: a reload changes one client's workflows, and re-judging the rest would report their problems
     * again (or, on strict, refuse this reload over them). Null builds every scope, as the boot does.
     */
    onlyClient: String? = null,
    /** The global registry the node runs now; with [onlyClient], what the client inherits from. */
    runningGlobal: WorkflowRegistry? = null,
): WorkflowRegistries {
    // The entry kinds that are implemented; a workflow declaring any other is dropped rather than run
    // half-built. `normal` landed with issue #794.
    val builtEntries = setOf(WfEntry.creation, WfEntry.survey, WfEntry.normal)
    // The entry kinds a scope has at most one of: declaring one takes over that kind, and a second is refused.
    // `normal` is many-per-form, so it is deliberately not here now that it is built.
    val singletonEntries = setOf(WfEntry.creation, WfEntry.survey)

    fun declaredIn(owner: String): List<WfDeclared> = configs.configs
        .filter { it.gedraId.client == owner }
        .flatMap { bundle -> bundle.workflows.values.map { WfDeclared(bundle, it) } }

    // Held by the workflow's own bundle (issue #839): a stored workflow naming something that is not there is
    // forgiven as stored config, whichever side changed last.
    fun problem(scope: String?, w: WfDeclared, what: String) = w.bundle.issue(
        "Workflow '${w.ref}' $what",
        "Dropping it from ${scope?.let { "client '$it'" } ?: "the global registry"}.",
        GCEL.workflow, w.def.workflowId,
    )
    // `off` for the bundle's origin admits the workflow unchecked.
    fun unchecked(w: WfDeclared) = w.bundle.checkMode(cxt) == BootCheckMode.off

    // The checks that depend on nothing but the definition and the scope's own vocabulary. `off` admits
    // everything as declared -- the reading `GedraConfigCollector` gives the mode -- rather than checking and
    // then dropping: `reportConfigProblem` has no off branch, because its callers never reach it when off.
    fun admits(scope: String?, w: WfDeclared, usable: Set<String>): Boolean {
        if (unchecked(w)) {
            return true
        }
        if (w.def.entry !in builtEntries) {
            reportConfigProblem(
                cxt,
                problem(
                    scope, w,
                    "is entered by '${w.def.entry}', which is not built yet; only ${builtEntries.joinToString(" and ") { "'$it'" }} are.",
                ),
                issues,
            )
            return false
        }
        // The workflow's own label (issue #719) rides the same check as a task's: a pull that cannot resolve
        // would title the page with a broken template.
        if (w.def.label.isNotBlank()) {
            labelProblem(scope, w.def.label, fragments)?.let {
                reportConfigProblem(cxt, problem(scope, w, "has a label that $it"), issues)
                return false
            }
        }
        // Eligibility (issue #783): each test parses against this scope's cfacts, and each explanation rides the
        // label check. A global workflow is parsed against the global registry only -- a client may add cfact
        // names but never remove one, so what parses globally parses in every client that inherits it.
        if (w.def.eligibility.isNotEmpty()) {
            val allowed = cfactNames(scope)
            for (e in w.def.eligibility) {
                try {
                    parseCFactOrAlways(e.test, allowed)
                } catch (ex: KdrException) {
                    reportConfigProblem(
                        cxt,
                        problem(scope, w, "has an eligibility test '${e.id}' whose cfact test does not parse: ${ex.message}"),
                        issues,
                    )
                    return false
                }
                labelProblem(scope, e.explanation, fragments)?.let {
                    reportConfigProblem(cxt, problem(scope, w, "has an explanation on eligibility test '${e.id}' that $it"), issues)
                    return false
                }
            }
        }
        // Singleton-cfact rules (issue #784): the condition parses against the scope's cfacts, like a test. The
        // emitted name itself was held to the framework list when the definition was built.
        for (r in w.def.singletons) {
            try {
                parseCFactOrAlways(r.whenExpr, cfactNames(scope))
            } catch (ex: KdrException) {
                reportConfigProblem(
                    cxt,
                    problem(scope, w, "has a '${r.cfact}' singleton rule whose condition does not parse: ${ex.message}"),
                    issues,
                )
                return false
            }
        }
        for (task in w.def.tasks) {
            task.traits.firstOrNull { it.traitId !in usable }?.let {
                reportConfigProblem(
                    cxt,
                    problem(
                        scope, w,
                        "collects the trait '${it.traitId}' in task '${task.id}', which " +
                            (scope?.let { c -> "client '$c' does not support" } ?: "no global config declares") +
                            ". A form could be filled in and then refused at its first save.",
                    ),
                    issues,
                )
                return false
            }
            // An approval task (issue #787): the cfact an approval emits must be one this scope declares -- an
            // approval that emitted an unknown name could gate nothing -- and its copy rides the label check.
            task.approval?.let { approval ->
                if (approval.cfact !in cfactNames(scope)) {
                    reportConfigProblem(
                        cxt,
                        problem(
                            scope, w,
                            "has an approval task '${task.id}' emitting the cfact '${approval.cfact}', which this " +
                                "scope does not declare.",
                        ),
                        issues,
                    )
                    return false
                }
            }
            // A task's display (issue #788): every condition in it parses against this scope's cfacts, each branch
            // names a mode there is, and a text branch's copy rides the label check below -- a display that fails
            // any of these would mis-render for somebody, and a page is the worst place to find out.
            // Who may save the task (issue #856): its rule parses against this scope's cfacts, like a display's
            // conditions -- a rule that failed to parse would refuse every save, found by the first person refused.
            task.saveWhen?.let { rule ->
                try {
                    parseCFactOrAlways(rule, cfactNames(scope))
                } catch (ex: KdrException) {
                    reportConfigProblem(
                        cxt, problem(scope, w, "has a rule for who may save task '${task.id}' that does not parse: ${ex.message}"),
                        issues,
                    )
                    return false
                }
            }
            task.display?.let { display ->
                displayProblem(display, cfactNames(scope))?.let {
                    reportConfigProblem(cxt, problem(scope, w, "has a display on task '${task.id}' that $it"), issues)
                    return false
                }
            }
            val displayTexts = task.display?.let { displayBranches(it) }.orEmpty()
                .mapNotNull { it[WDSP.text].toOptStr() }
                .map { "display text of task '${task.id}'" to it }
            val labels = listOf("task '${task.id}'" to task.label) + task.saves.map { "save '${it.id}'" to it.label } +
                displayTexts +
                (task.approval?.let { listOf("approval prompt of task '${task.id}'" to it.prompt, "approval button of task '${task.id}'" to it.button) }
                    ?: emptyList())
            for ((where, label) in labels) {
                labelProblem(scope, label, fragments)?.let {
                    reportConfigProblem(cxt, problem(scope, w, "has a label on $where that $it"), issues)
                    return false
                }
            }
        }
        return true
    }

    // One scope: the inherited workflows (already admitted where they came from), then the scope's own on top,
    // then the creation rule over the result.
    fun assemble(scope: String?, inherited: Map<String, WfDeclared>, own: List<WfDeclared>, usable: Set<String>): WorkflowRegistry {
        val out = LinkedHashMap(inherited)
        val admitted = own.filter { admits(scope, it, usable) }
        // A client declaring a singleton-kind workflow takes that kind over for itself: the inherited one is
        // shadowed whatever its id, since "how a form is created / surveyed here" has exactly one answer.
        for (kind in singletonEntries) {
            if (admitted.any { it.def.entry == kind }) {
                out.entries.removeAll { it.value.def.entry == kind }
            }
        }
        // Own bundles on top of the inherited ones. Inherited-then-own is shadowing; own-then-own -- two bundles
        // of one scope naming one workflow -- is a collision, refused the way two configs declaring one trait
        // are: first kept, second dropped, and said so, since a silent last-wins is the shape nobody reports.
        val ownIds = LinkedHashSet<String>()
        for (w in admitted) {
            if (!ownIds.add(w.def.workflowId)) {
                reportConfigProblem(
                    cxt,
                    problem(
                        scope, w,
                        "is declared a second time in this scope, beside '${out.getValue(w.def.workflowId).ref}'. A " +
                            "workflow id identifies one definition within its client; keeping the first.",
                    ),
                    issues,
                )
                continue
            }
            out[w.def.workflowId] = w
        }
        // After shadowing: what this scope actually sees may still hold two of a singleton kind, from two of
        // its own bundles.
        for (kind in singletonEntries) {
            val ofKind = out.values.filter { it.def.entry == kind }
            for (extra in ofKind.drop(1).filterNot { unchecked(it) }) {
                reportConfigProblem(
                    cxt,
                    problem(
                        scope, extra,
                        "is a second $kind workflow beside '${ofKind.first().ref}'. A scope has one $kind " +
                            "workflow; keeping the first declared.",
                    ),
                    issues,
                )
                out.remove(extra.def.workflowId)
            }
        }
        return WorkflowRegistry(scope, out)
    }

    val globalUsable = configs.traitsFor(GID.globalClient).map { it.traitId }.toSet()
    val global = runningGlobal.takeIf { onlyClient != null }
        ?: assemble(null, emptyMap(), declaredIn(GID.globalClient), globalUsable)
    val byClient = LinkedHashMap<String, WorkflowRegistry>()
    for ((client, def) in clients) {
        if (onlyClient != null && client != onlyClient) continue
        val own = declaredIn(client)
        // A client sees global's traits through `supportedTraits`, which also admits what it customized.
        val usable = supportedTraits(configs, client, def, overlaidTypes(client)).map { it.traitId }.toSet()
        // The inherited global workflows are re-checked against *this* client's usable set: a global creation
        // workflow collecting `name` is fine for a client that includes `name` and not for one that omits it.
        // Global workflows are source-declared, so the source mode says whether they are checked at all.
        val inheritable = if (gedraConfigCheckMode(cxt) == BootCheckMode.off) {
            global.workflows
        } else {
            global.workflows.filterValues { w -> w.def.tasks.all { t -> t.traits.all { it.traitId in usable } } }
        }
        // Not a problem -- a client that omits a trait should not get the workflow collecting it -- but it is
        // the answer to "why does this client have no creation workflow?", so it is said where it happens.
        for (id in global.workflows.keys - inheritable.keys) {
            LogStartup.debug(cxt) {
                "Client '$client' does not inherit the global workflow '$id': it collects a trait the client does not support."
            }
        }
        val registry = assemble(client, inheritable, own, usable)
        if (own.isNotEmpty() || inheritable.size != global.workflows.size) {
            byClient[client] = registry
        }
    }
    return WorkflowRegistries(global, byClient)
}

/**
 * What is wrong with [label] as a backend template, or null: a syntax problem, or a literal `@t` pull that
 * does not resolve for [client]. A pull that guards its own absence (`?:`, a null test) is left to its
 * default, as the fragment service's own check leaves it.
 */
private fun labelProblem(client: String?, label: String, fragments: WfFragmentLookup): String? {
    val analysis = label.analyzeTemplate(prefix = '%')
    analysis.issues.firstOrNull()?.let { return "does not parse as a template: ${it.message}." }
    for (ref in analysis.refs) {
        val firstDot = ref.key.indexOf('.')
        val secondDot = if (firstDot < 0) -1 else ref.key.indexOf('.', firstDot + 1)
        if (firstDot <= 0 || secondDot < 0 || secondDot == ref.key.length - 1) {
            return "pulls '${ref.key}', which is not a three-part 'file.namespace.key' backend reference."
        }
        val fileId = ref.key.substring(0, firstDot)
        val namespace = ref.key.substring(firstDot + 1, secondDot)
        val key = ref.key.substring(secondDot + 1)
        val hit = fragments.lookup(client, fileId, namespace, key)
        if (hit == null || !hit.found) {
            return "pulls '${ref.key}', but no fragment file '$fileId' is declared."
        }
        if (!hit.backend) {
            return "pulls '${ref.key}' from '$fileId', which is a served (frontend) file; a backend pull names a backend file."
        }
        if (!hit.present && !ref.tolerant) {
            return "pulls '${ref.key}', but '$fileId' has no '$namespace.$key'."
        }
    }
    return null
}

/**
 * A task display's **leaf** branches (issue #788) -- what can actually be shown: the display itself when it is a
 * single branch, and otherwise every branch of its selector, following a branch that is itself a selector down to
 * its own. Nested selectors are allowed by the resolver, so the boot check has to reach every branch they could
 * choose. [depth] bounds the walk over what is, for stored config, external data.
 */
fun displayBranches(display: Map<String, Any?>, depth: Int = 0): List<Map<String, Any?>> {
    if (depth > maxDisplayDepth) {
        throw KdrException.mkConv("A task display nests selectors more than $maxDisplayDepth deep.")
    }
    if (!display.containsKey(UIB.select)) {
        return listOf(display)
    }
    return (display[UIB.select] as? List<*>).orEmpty()
        .filterIsInstance<Map<*, *>>()
        .flatMap { displayBranches(it.toJsonMapOrEmpty(), depth + 1) }
}

/** How deep a task display's selectors may nest -- far beyond any real use, a guard on bad stored config. */
private const val maxDisplayDepth = 10

/**
 * The first thing wrong with a task [display] (issue #788), or null: a selector, at any depth, that is not a
 * non-empty list of branches; a leaf branch naming a mode there is not, or a text branch with no text; or a
 * condition anywhere in it that does not parse against [allowed] -- the scope's cfact names, which include the task
 * facts (`wfIsCta`, `wfReviewer`, an approval's cfact) since those are declared too.
 */
fun displayProblem(display: Map<String, Any?>, allowed: Set<String>): String? {
    selectorShapeProblem(display, 0)?.let { return it }
    val branches = try {
        displayBranches(display)
    } catch (e: KdrException) {
        return "is malformed: ${e.message}"
    }
    for (branch in branches) {
        val mode = branch[WDSP.mode].toOptStr() ?: WDSP.defaultMode
        if (mode !in WDSP.modes) {
            return "has a branch with mode '$mode'; a branch is one of ${WDSP.modes.sorted()}."
        }
        if (mode == WDSP.textMode && branch[WDSP.text].toOptStr().isNullOrBlank()) {
            return "has a '${WDSP.textMode}' branch with no ${WDSP.text}."
        }
    }
    for (expression in collectExpressions(display)) {
        try {
            parseCFactOrAlways(expression, allowed)
        } catch (e: KdrException) {
            return "has a condition that does not parse: ${e.message}"
        }
    }
    return null
}

/** A selector in [node], at any depth down its branches, that is not a non-empty list of objects; or null. */
private fun selectorShapeProblem(node: Map<String, Any?>, depth: Int): String? {
    if (!node.containsKey(UIB.select) || depth > maxDisplayDepth) {
        return null
    }
    val raw = node[UIB.select] as? List<*>
    if (raw.isNullOrEmpty() || raw.any { it !is Map<*, *> }) {
        return "has a '${UIB.select}' that is not a non-empty list of branches."
    }
    return raw.firstNotNullOfOrNull { selectorShapeProblem((it as Map<*, *>).toJsonMapOrEmpty(), depth + 1) }
}
