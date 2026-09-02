package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigCollector
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.reportConfigProblem
import com.dynamicruntime.common.gedra.supportedTraits
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.util.analyzeTemplate

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
 * same id, and a client declaring **any** creation workflow replaces the global creation workflow for that
 * client -- the same overlay rule every other config layer follows, so a deployment can ship a default a
 * client customizes. The at-most-one-creation check runs *after* shadowing, on what the client actually sees.
 *
 * **What is checked**, per scope and per workflow -- each a thing that would otherwise fail silently or late:
 *
 * - The entry kind is one that is built. Only `creation` is; a `survey` or `normal` declared today would be
 *   accepted and inert, which is worse than a refusal.
 * - Every trait a task collects is one the scope may use: the client's supported set, or for a global
 *   workflow the traits global can see. A workflow naming a trait its client cannot store would fail at the
 *   first save, for one client, after the form had been filled in.
 * - Every label's backend fragment pull (`%{@t("file.namespace.key")}`) resolves: three parts, an existing
 *   **backend** file, a present key -- unless the pull guards its own absence. The same static check the
 *   fragment service runs over files, applied to the labels a definition carries; a frontend pull (`${...}`)
 *   binds at request time and is the author's assertion, as it is everywhere.
 * - At most one creation workflow per scope, after shadowing.
 *
 * No cfact expressions are checked, because the model carries none yet (selectors are deferred); when it does,
 * they parse here against the scope's registry, as UiBlocks' do.
 *
 * A problem is handed to [reportConfigProblem], so what happens to it is `gedraConfigCheckMode`'s answer:
 * a refused boot everywhere but production, where the workflow is **dropped from that scope** and the node
 * carries on -- proportionate, because a workflow that is not there is a state the design already defines
 * (the client's people see no such workflow), where a half-checked one would run.
 */
fun buildWorkflowRegistries(
    cxt: KdrCxt,
    configs: GedraConfigCollector,
    /** The clients present on this node, by id, with their definitions (null when a definition was dropped). */
    clients: Map<String, ClientDef?>,
    /** The qualified type names a client overlaid -- what `supportedTraits` reads as "customized". */
    overlaidTypes: (client: String) -> Set<String>,
    fragments: WfFragmentLookup,
    mode: BootCheckMode,
    issues: MutableList<GedraConfigIssue>,
): WorkflowRegistries {
    fun declaredIn(owner: String): List<WfDeclared> = configs.configs
        .filter { it.gedraId.client == owner }
        .flatMap { bundle -> bundle.workflows.values.map { WfDeclared(bundle, it) } }

    fun problem(scope: String?, w: WfDeclared, what: String) = GedraConfigIssue(
        "Workflow '${w.ref}' $what",
        "Dropping it from ${scope?.let { "client '$it'" } ?: "the global registry"}.",
    )

    // The checks that depend on nothing but the definition and the scope's own vocabulary. `off` admits
    // everything as declared -- the reading `GedraConfigCollector` gives the mode -- rather than checking and
    // then dropping: `reportConfigProblem` has no off branch, because its callers never reach it when off.
    fun admits(scope: String?, w: WfDeclared, usable: Set<String>): Boolean {
        if (mode == BootCheckMode.off) {
            return true
        }
        if (w.def.entry != WfEntry.creation) {
            reportConfigProblem(
                cxt, mode,
                problem(scope, w, "is entered by '${w.def.entry}', which is not built yet; only '${WfEntry.creation}' is."),
                issues,
            )
            return false
        }
        for (task in w.def.tasks) {
            task.traits.firstOrNull { it.traitId !in usable }?.let {
                reportConfigProblem(
                    cxt, mode,
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
            val labels = listOf("task '${task.id}'" to task.label) + task.saves.map { "save '${it.id}'" to it.label }
            for ((where, label) in labels) {
                labelProblem(scope, label, fragments)?.let {
                    reportConfigProblem(cxt, mode, problem(scope, w, "has a label on $where that $it"), issues)
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
        // A client declaring a creation workflow takes over creation for itself: the inherited one is
        // shadowed whatever its id, since "how a form is created here" has exactly one answer.
        if (admitted.any { it.def.entry == WfEntry.creation }) {
            out.entries.removeAll { it.value.def.entry == WfEntry.creation }
        }
        // Own bundles on top of the inherited ones. Inherited-then-own is shadowing; own-then-own -- two bundles
        // of one scope naming one workflow -- is a collision, refused the way two configs declaring one trait
        // are: first kept, second dropped, and said so, since a silent last-wins is the shape nobody reports.
        val ownIds = LinkedHashSet<String>()
        for (w in admitted) {
            if (!ownIds.add(w.def.workflowId)) {
                reportConfigProblem(
                    cxt, mode,
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
        // After shadowing: what this scope actually sees may still hold two, from two of its own bundles.
        val creations = out.values.filter { it.def.entry == WfEntry.creation }
        if (mode != BootCheckMode.off && creations.size > 1) {
            for (extra in creations.drop(1)) {
                reportConfigProblem(
                    cxt, mode,
                    problem(
                        scope, extra,
                        "is a second creation workflow beside '${creations.first().ref}'. A scope creates a " +
                            "form one way; keeping the first declared.",
                    ),
                    issues,
                )
                out.remove(extra.def.workflowId)
            }
        }
        return WorkflowRegistry(scope, out)
    }

    val globalUsable = configs.traitsFor(GID.globalClient).map { it.traitId }.toSet()
    val global = assemble(null, emptyMap(), declaredIn(GID.globalClient), globalUsable)
    val byClient = LinkedHashMap<String, WorkflowRegistry>()
    for ((client, def) in clients) {
        val own = declaredIn(client)
        // A client sees global's traits through `supportedTraits`, which also admits what it customized.
        val usable = supportedTraits(configs, client, def, overlaidTypes(client)).map { it.traitId }.toSet()
        // The inherited global workflows are re-checked against *this* client's usable set: a global creation
        // workflow collecting `name` is fine for a client that includes `name` and not for one that omits it.
        val inheritable = if (mode == BootCheckMode.off) {
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
