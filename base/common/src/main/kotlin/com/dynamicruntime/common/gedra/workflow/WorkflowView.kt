package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GedraTrait
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.collectDefClosure
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.filterByCFacts
import com.dynamicruntime.common.util.toOptStr

/**
 * Resolves a declared workflow into the **view** the creation page renders (issue #534): tasks in layout
 * order, each trait a `$ref` into *this client's* schema with the workflow's required flag beside it, saves
 * with resolved labels, and the target facts about each task -- run through the same content pipeline every
 * other rendered surface uses, so the page renders JSON and never learns what a task is.
 *
 * The resolution is the pipeline the design settled on:
 *  - **Labels** get `MarkdownFragmentService.backendPass`: a `%{...}` block resolves here -- a fragment pull
 *    with this client's overlays, a parameter, a `?:` default -- and a `${...}` block is left for the
 *    frontend, so a label can be part backend copy and part live value.
 *  - **Each task is filtered by its own cfacts** with `filterByCFacts`, the target facts about *that task*
 *    ([WfTaskFacts]) assembled beside the request's. The model carries no `cfactExpression` yet (selectors
 *    are deferred), so nothing is dropped today -- but the seam is real and per-task, which is what a
 *    single-set filter over the whole view could not be: "this shows when the task is complete" is a fact
 *    about one task, and the first row's answer must not decide the second's.
 *
 * The view is **self-contained**: each trait's `schemaRef` resolves against a `$defs` the view carries, a
 * closure of exactly the types the workflow references and their dependencies ([collectDefClosure]) -- not the
 * client's endpoint catalog. That is what lets a page render a workflow with one call rather than fetching
 * hundreds of unrelated endpoint types to resolve a few fields, and it is what a workflow that **narrows** a
 * trait's schema will need: the reachable body is then the workflow's own, with nowhere else it could live.
 *
 * [entriesByTask] supplies the entries a running workflow already holds, so a task's completeness is real; a
 * creation workflow has none, so it defaults empty. The resolver stays a pure function of the definition,
 * the client's schema, and its fragments -- no gedra is read here.
 */
fun resolveWorkflowView(
    cxt: KdrCxt,
    declared: WfDeclared,
    entriesByTask: Map<String, List<Map<String, Any?>>> = emptyMap(),
): Map<String, Any?> {
    val client: String = cxt.client
    val fragments = MarkdownFragmentService.get(cxt)
    val cfacts = SchemaService.get(cxt).cfactsFor(client)
    // Every trait an admitted workflow names is one the client can see (the boot check in #533 guaranteed it),
    // so this map is total over the workflow's traits.
    val traitsById: Map<String, GedraTrait> =
        SchemaService.get(cxt).gedraTraitsFor(client).associateBy { it.traitId }
    // The client's raw `$defs` (its variant, so a narrowed type is that client's) -- what the returned closure
    // is drawn from. The trait pointers the view hands out are resolved against this subset, not the catalog.
    val clientDefs = SchemaService.get(cxt).storeFor(client).defs
    // The trait data types the workflow references, collected as they are rendered; the seeds of the closure.
    val seedRefs = LinkedHashSet<String>()
    // The request-scoped cfacts, computed once: they are the same for every task, so only each task's own
    // target facts are unioned onto them below (each cfact source can do real work -- e.g., a section check).
    val requestFacts = cfacts.assemble(cxt)

    fun label(text: String): String = fragments.backendPass(cxt, text)

    fun traitView(ref: WfTraitRef): Map<String, Any?> {
        val trait = traitsById[ref.traitId]
            ?: throw KdrException(
                "Workflow '${declared.ref}' collects trait '${ref.traitId}', which client '$client' cannot see. " +
                    "A boot check should have refused this workflow.",
            )
        // The trait's own *data* pointer -- what a field edits, not its entry envelope: the page fills in the
        // data and the save wraps it into `{traitId, data}`. Read from the trait rather than re-derived: a
        // trait declared with a `dataType` `$ref` has no manufactured `<Name>Data` type, so re-spelling one
        // would dangle. `GedraTrait.dataSchema` is always a `$ref` -- the author's own, or the one generated
        // for inline data -- so it is the pointer in both authoring styles.
        val dataRef = trait.dataSchema[SCH.dRef].toOptStr()
            ?: throw KdrException($$"Trait '$${ref.traitId}' has no data $ref to render against.")
        refName(dataRef)?.let { seedRefs.add(it) }
        return linkedMapOf(
            WFD.traitId to ref.traitId,
            WFD.required to ref.required,
            WVF.schemaRef to dataRef,
        )
    }

    fun taskView(task: WfTask): Map<String, Any?> {
        val entries = entriesByTask[task.id] ?: emptyList()
        // Draw the traits in the page's order, each already a ref+flag; the layout named the order, and any
        // trait it left out follows in declaration order (`WfTask.displayOrder`).
        val byId = task.traits.associateBy { it.traitId }
        val orderedTraits = task.displayOrder.mapNotNull { byId[it] }.map { traitView(it) }
        val taskFacts = WfTaskFacts.of(task, entries)
        val raw = linkedMapOf<String, Any?>(
            WFD.id to task.id,
            WFD.label to label(task.label),
            WFD.traits to orderedTraits,
            WFD.saves to task.saves.map { linkedMapOf(WFD.id to it.id, WFD.label to label(it.label), WFD.kind to it.kind.name) },
            WVF.facts to taskFacts.toList(),
        )
        task.layout?.let { raw[WFD.layout] = linkedMapOf(WFD.order to it.order, WFD.edit to it.edit.name) }
        // The content pipeline, per task: the request facts (hoisted) plus this task's own, then drop anything
        // gated on a cfact they do not satisfy. A no-op on today's model (no conditions), real on tomorrow's.
        return filterByCFacts(raw, requestFacts + taskFacts, cfacts::parse)
    }

    // Tasks first: rendering them collects the trait refs the closure needs.
    val taskViews = declared.def.tasks.map { taskView(it) }
    return linkedMapOf(
        WVF.found to true,
        WFD.workflowId to declared.def.workflowId,
        WVF.ref to declared.ref.text,
        WFD.entry to declared.def.entry.name,
        WVF.showTaskList to declared.def.showTaskList,
        WFD.tasks to taskViews,
        // The self-contained schema: exactly the types the trait refs reach, and their dependencies.
        SCH.dDefs to collectDefClosure(seedRefs, clientDefs),
    )
}

/** The "no workflow" answer -- what the view returns when a client has no such (or no creation) workflow. */
fun noWorkflowView(): Map<String, Any?> = linkedMapOf(WVF.found to false)
